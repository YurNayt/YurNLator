package com.winlator.cmod.store.epic

import android.content.Context
import android.util.Base64
import android.util.Log
import com.winlator.cmod.store.StoreNet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Epic Games account: sign-in on Epic's own page (in a WebView), whose redirect page hands an
 * authorization code; that is traded for an access + refresh token with the Epic launcher's public
 * client id (the one Legendary uses). Only the tokens and the display name are kept (private app
 * preferences), never the password. After Bannerlator's EpicAuthClient (GPL-3.0).
 */
object EpicAccount {
    private const val TAG = "YurnEpic"
    private const val PREFS = "yurn_epic"
    const val CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a"
    private const val CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf"
    private const val TOKEN_URL = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token"
    const val USER_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit"

    /** Epic's sign-in page; after signing in it goes to [REDIRECT] which shows the code as JSON. */
    const val LOGIN_URL = "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Fid%2Fapi%2Fredirect" +
        "%3FclientId%3D$CLIENT_ID%26responseType%3Dcode"
    const val REDIRECT = "https://www.epicgames.com/id/api/redirect"

    private val _name = MutableStateFlow<String?>(null)
    /** Display name when signed in, null otherwise. */
    val name: StateFlow<String?> = _name

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        _name.value = if (p.getString("refresh", null).isNullOrEmpty()) null else p.getString("name", "Epic")
    }

    fun signedIn(context: Context) = !prefs(context).getString("refresh", null).isNullOrEmpty()

    fun accountId(context: Context) = prefs(context).getString("account", "") ?: ""

    private fun post(form: FormBody): JSONObject? = runCatching {
        val auth = Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder().url(TOKEN_URL).header("Authorization", "Basic $auth").header("User-Agent", USER_AGENT).post(form).build()
        StoreNet.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "token HTTP ${r.code}"); null } else JSONObject(r.body?.string() ?: "")
        }
    }.onFailure { Log.w(TAG, "token", it) }.getOrNull()

    private fun save(context: Context, j: JSONObject): Boolean {
        val access = j.optString("access_token")
        if (access.isEmpty()) return false
        val expires = parseTime(j.optString("expires_at")) ?: (System.currentTimeMillis() + j.optLong("expires_in", 7200L) * 1000)
        prefs(context).edit()
            .putString("access", access)
            .putLong("expires", expires)
            .putString("refresh", j.optString("refresh_token"))
            .putString("account", j.optString("account_id"))
            .putString("name", j.optString("displayName").ifBlank { "Epic" })
            .apply()
        _name.value = j.optString("displayName").ifBlank { "Epic" }
        return true
    }

    private fun parseTime(s: String): Long? = runCatching {
        if (s.isBlank()) null else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time
    }.getOrNull()

    /** Trades the code from the redirect page for tokens (blocking). */
    fun signIn(context: Context, code: String): Boolean {
        val j = post(FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("token_type", "eg1").build()) ?: return false
        return save(context, j)
    }

    /** A valid access token, refreshed when it ran out (blocking). Null when signed out or Epic refused. */
    fun token(context: Context): String? {
        val p = prefs(context)
        val access = p.getString("access", null)
        if (access != null && p.getLong("expires", 0) > System.currentTimeMillis() + 60_000) return access
        val refresh = p.getString("refresh", null) ?: return null
        val j = post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("token_type", "eg1").build())
        if (j == null) return null
        return if (save(context, j)) p.getString("access", null) else null
    }

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
        _name.value = null
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
        }
    }
}
