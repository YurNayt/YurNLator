package com.winlator.cmod.store.steam

import android.content.Context
import android.os.Build
import android.util.Log
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import `in`.dragonbra.javasteam.steam.authentication.SteamAuthentication
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.ISteamConfigurationBuilder
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.util.compat.Consumer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The app's one Steam connection (JavaSteam): connects to a Steam server, signs in with the saved
 * refresh token, and keeps the account's licenses (what it owns). Sign-in is by QR code (approved in
 * the Steam mobile app) or by account name + password with a Steam Guard code / confirmation.
 *
 * Only a refresh token and the account name are kept (private app preferences), never the password.
 * Written for YurNLator after Bannerlator's JavaSteam path (GPL-3.0), much reduced.
 */
object SteamSession {
    private const val TAG = "YurnSteam"
    private const val PREFS = "yurn_steam"

    enum class State { OFFLINE, CONNECTING, CONNECTED, ONLINE }

    data class Status(val state: State, val error: String? = null)

    /** What the sign-in screen shows. */
    sealed class Auth {
        object Idle : Auth()
        object Working : Auth()
        data class Qr(val url: String) : Auth()
        data class Code(val email: String?, val wrong: Boolean) : Auth()
        object Confirm : Auth()
        data class Failed(val message: String) : Auth()
    }

    private val _status = MutableStateFlow(Status(State.OFFLINE))
    val status: StateFlow<Status> = _status
    private val _auth = MutableStateFlow<Auth>(Auth.Idle)
    val auth: StateFlow<Auth> = _auth
    private val _licenses = MutableStateFlow<List<License>>(emptyList())
    val licenses: StateFlow<List<License>> = _licenses

    private lateinit var app: Context
    @Volatile var client: SteamClient? = null
        private set
    @Volatile var apps: SteamApps? = null
        private set
    private var user: SteamUser? = null
    private var manager: CallbackManager? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "SteamIO").apply { isDaemon = true } }
    @Volatile private var wanted = false
    @Volatile private var retryDelay = 5_000L
    @Volatile private var pendingCode: CompletableFuture<String>? = null
    @Volatile private var authCancelled = false

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val account: String get() = if (::app.isInitialized) prefs().getString("account", "") ?: "" else ""
    val steamId: Long get() = if (::app.isInitialized) prefs().getLong("steam_id", 0L) else 0L

    fun signedIn(context: Context): Boolean {
        init(context)
        return !prefs().getString("refresh_token", null).isNullOrEmpty()
    }

    private fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    private class Sub<T>(val f: (T) -> Unit) : Consumer<T> {
        override fun accept(t: T) = f(t)
    }

    /** Connects (and signs in when a token is saved). Safe to call again. */
    @Synchronized
    fun start(context: Context) {
        init(context)
        wanted = true
        if (client == null) {
            val config = SteamConfiguration.create(Sub<ISteamConfigurationBuilder> { b ->
                b.withProtocolTypes(EnumSet.of(ProtocolTypes.TCP))
                b.withConnectionTimeout(30_000L)
                b.withDirectoryFetch(true)
            })
            val c = SteamClient(config)
            val m = CallbackManager(c)
            user = c.getHandler(SteamUser::class.java)
            apps = c.getHandler(SteamApps::class.java)
            m.subscribe(ConnectedCallback::class.java, Sub<ConnectedCallback> { onConnected() })
            m.subscribe(DisconnectedCallback::class.java, Sub<DisconnectedCallback> { onDisconnected(it) })
            m.subscribe(LoggedOnCallback::class.java, Sub<LoggedOnCallback> { onLoggedOn(it) })
            m.subscribe(LoggedOffCallback::class.java, Sub<LoggedOffCallback> { onLoggedOff(it) })
            m.subscribe(LicenseListCallback::class.java, Sub<LicenseListCallback> { cb ->
                if (cb.result == EResult.OK) _licenses.value = cb.licenseList.toList()
            })
            client = c
            manager = m
            Thread({
                while (true) {
                    try { m.runWaitCallbacks(1000L) } catch (t: Throwable) { Log.w(TAG, "callbacks", t) }
                }
            }, "SteamCallbacks").apply { isDaemon = true; start() }
        }
        if (_status.value.state == State.OFFLINE) connect()
    }

    private fun connect() {
        val c = client ?: return
        _status.value = Status(State.CONNECTING)
        io.execute {
            try { c.connect() } catch (t: Throwable) {
                Log.w(TAG, "connect", t)
                _status.value = Status(State.OFFLINE, t.message)
            }
        }
    }

    private fun onConnected() {
        retryDelay = 5_000L
        _status.value = Status(State.CONNECTED)
        if (signedIn(app)) logOn()
    }

    private fun onDisconnected(cb: DisconnectedCallback) {
        val was = _status.value
        _status.value = Status(State.OFFLINE, was.error)
        _licenses.value = emptyList()
        if (!cb.isUserInitiated && wanted) {
            val delay = retryDelay
            retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
            io.execute {
                try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                if (wanted && _status.value.state == State.OFFLINE) connect()
            }
        }
    }

    private fun logOn() {
        val token = prefs().getString("refresh_token", null) ?: return
        val u = user ?: return
        io.execute {
            try {
                val details = LogOnDetails()
                details.username = account
                details.accessToken = token
                details.shouldRememberPassword = true
                details.loginID = loginId()
                u.logOn(details)
            } catch (t: Throwable) {
                Log.w(TAG, "logOn", t)
            }
        }
    }

    /** A fixed number per install, so this session doesn't replace another one of the same account. */
    private fun loginId(): Int {
        var id = prefs().getInt("login_id", 0)
        if (id == 0) {
            id = (0x10000000..0x7FFFFFF0).random()
            prefs().edit().putInt("login_id", id).apply()
        }
        return id
    }

    private fun onLoggedOn(cb: LoggedOnCallback) {
        if (cb.result == EResult.OK) {
            cb.clientSteamID?.let { prefs().edit().putLong("steam_id", it.convertToUInt64()).apply() }
            _status.value = Status(State.ONLINE)
            return
        }
        val name = cb.result.name
        Log.w(TAG, "logon failed: $name")
        val rejected = listOf("Password", "Expired", "Denied", "Revoked", "Invalid").any { name.contains(it) }
        if (rejected) prefs().edit().remove("refresh_token").apply()
        _status.value = Status(State.CONNECTED, name)
    }

    private fun onLoggedOff(cb: LoggedOffCallback) {
        Log.i(TAG, "logged off: ${cb.result}")
        _licenses.value = emptyList()
        if (_status.value.state == State.ONLINE) _status.value = Status(State.CONNECTED, cb.result.name)
        // Another sign-in of this account took over, or Steam restarted: sign in again shortly.
        if (wanted && signedIn(app)) io.execute {
            try { Thread.sleep(10_000L) } catch (_: InterruptedException) {}
            if (wanted && _status.value.state == State.CONNECTED) logOn()
        }
    }

    /** Waits (blocking) until signed in and the licenses arrived. */
    fun awaitOnline(context: Context, timeoutMs: Long = 60_000L): Boolean {
        start(context)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (_status.value.state == State.ONLINE && _licenses.value.isNotEmpty()) return true
            Thread.sleep(250L)
        }
        return false
    }

    private fun awaitConnected(timeoutMs: Long = 30_000L): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val s = _status.value.state
            if (s == State.CONNECTED || s == State.ONLINE) return true
            Thread.sleep(200L)
        }
        return false
    }

    private fun details(): AuthSessionDetails = AuthSessionDetails().apply {
        deviceFriendlyName = "YurNLator (${Build.MODEL})"
        clientOSType = EOSType.AndroidUnknown
        persistentSession = true
    }

    /** Sign-in by QR code: [auth] becomes [Auth.Qr] (the code changes now and then), then Idle when done. */
    fun startQr(context: Context) {
        start(context)
        authCancelled = false
        _auth.value = Auth.Working
        Thread({
            try {
                if (!awaitConnected()) throw IllegalStateException("no connection to Steam")
                val session: QrAuthSession = SteamAuthentication(client!!).beginAuthSessionViaQR(details()).get()
                _auth.value = Auth.Qr(session.challengeUrl)
                session.challengeUrlChanged = object : IChallengeUrlChanged {
                    override fun onChanged(qrAuthSession: QrAuthSession) {
                        if (!authCancelled) _auth.value = Auth.Qr(qrAuthSession.challengeUrl)
                    }
                }
                finish(session.pollingWaitForResult().get(), "")
            } catch (t: Throwable) {
                fail(t)
            }
        }, "SteamQr").start()
    }

    /** Sign-in by account name and password; Steam Guard asks via [Auth.Code] / [Auth.Confirm]. */
    fun startPassword(context: Context, username: String, password: String) {
        start(context)
        authCancelled = false
        _auth.value = Auth.Working
        Thread({
            try {
                if (!awaitConnected()) throw IllegalStateException("no connection to Steam")
                val d = details().apply {
                    this.username = username
                    this.password = password
                    authenticator = object : IAuthenticator {
                        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
                            askCode(null, previousCodeWasIncorrect)

                        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
                            askCode(email ?: "", previousCodeWasIncorrect)

                        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                            _auth.value = Auth.Confirm
                            return CompletableFuture.completedFuture(true)
                        }
                    }
                }
                val session = SteamAuthentication(client!!).beginAuthSessionViaCredentials(d).get()
                finish(session.pollingWaitForResult().get(), username)
            } catch (t: Throwable) {
                fail(t)
            }
        }, "SteamLogin").start()
    }

    private fun askCode(email: String?, wrong: Boolean): CompletableFuture<String> {
        val f = CompletableFuture<String>()
        pendingCode = f
        _auth.value = Auth.Code(email, wrong)
        return f
    }

    fun submitCode(code: String) {
        _auth.value = Auth.Working
        pendingCode?.complete(code.trim())
    }

    fun cancelAuth() {
        authCancelled = true
        pendingCode?.completeExceptionally(InterruptedException("cancelled"))
        pendingCode = null
        _auth.value = Auth.Idle
    }

    private fun finish(r: AuthPollResult, typed: String) {
        if (authCancelled) return
        val name = r.accountName?.takeIf { it.isNotBlank() } ?: typed
        prefs().edit().putString("account", name).putString("refresh_token", r.refreshToken).apply()
        pendingCode = null
        _auth.value = Auth.Idle
        if (_status.value.state == State.CONNECTED) logOn() else start(app)
    }

    private fun fail(t: Throwable) {
        if (authCancelled) return
        val cause = if (t is ExecutionException && t.cause != null) t.cause!! else t
        Log.w(TAG, "sign-in failed", cause)
        pendingCode = null
        _auth.value = Auth.Failed(cause.message ?: cause.javaClass.simpleName)
    }

    /** Signs out and forgets the token (installed games stay). */
    fun signOut() {
        if (!::app.isInitialized) return
        prefs().edit().remove("refresh_token").remove("steam_id").apply()
        _licenses.value = emptyList()
        io.execute {
            try { user?.logOff() } catch (_: Throwable) {}
        }
        if (_status.value.state == State.ONLINE) _status.value = Status(State.CONNECTED)
    }
}
