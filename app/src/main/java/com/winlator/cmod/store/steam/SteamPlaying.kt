package com.winlator.cmod.store.steam

import android.content.Context
import android.util.Log
import com.winlator.cmod.container.Shortcut
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo

/**
 * "In game" on the Steam profile while a game from the Steam library runs: Steam counts its
 * playtime and friends see it (the account is set online for that: a session that only says
 * "playing" while offline gets the playtime but shows nothing to friends). Said again after every new sign-in (a reconnect mid-game clears
 * it on Steam's side), cleared when the game ends. Like a real Steam client, it takes the
 * account's "playing" slot: a game running on a PC with the same account is told it's playing
 * elsewhere.
 */
object SteamPlaying {
    private const val TAG = "YurnSteamPlaying"
    @Volatile private var thread: Thread? = null

    @JvmStatic
    fun start(context: Context, shortcut: Shortcut?) {
        val appId = SteamSync.appIdOf(shortcut)
        if (appId <= 0 || !SteamSync.showPlaying(context)) return
        if (!com.winlator.cmod.store.StoreLaunchPrefs.enabled(context, com.winlator.cmod.store.StoreLaunchPrefs.steam(appId))) return
        if (!SteamSession.signedIn(context)) return
        stop()
        SteamSession.start(context)
        val t = Thread({
            var said: SteamSession.Status? = null
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val s = SteamSession.status.value
                    val apps = SteamSession.apps
                    // A new Status object = a new sign-in: say it again.
                    if (s.state == SteamSession.State.ONLINE && apps != null && s !== said) {
                        // Friends only see a game on an account that is online: this session
                        // signs in "offline" (as a store, not a client) until it says so.
                        runCatching {
                            SteamSession.client?.getHandler(`in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends::class.java)
                                ?.setPersonaState(`in`.dragonbra.javasteam.enums.EPersonaState.Online)
                        }.onFailure { Log.w(TAG, "online", it) }
                        runCatching { apps.notifyGamesPlayed(listOf(info(appId)), EOSType.Windows10) }
                            .onSuccess { said = s; Log.i(TAG, "$appId: in game") }
                            .onFailure { Log.w(TAG, "$appId: in game", it) }
                    }
                    Thread.sleep(2_000L)
                }
            } catch (_: InterruptedException) {
            }
        }, "SteamPlaying")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Clears "In game" (idempotent). */
    @JvmStatic
    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        // Off the UI thread (called from onDestroy too): the message goes out over the network.
        Thread({
            val apps = SteamSession.apps
            if (SteamSession.status.value.state == SteamSession.State.ONLINE && apps != null)
                runCatching { apps.notifyGamesPlayed(emptyList(), EOSType.Windows10) }
            Log.i(TAG, "not in game")
        }, "SteamPlayingStop").start()
    }

    private fun info(appId: Int) = GamePlayedInfo(gameId = appId.toLong(), processId = 0,
        ownerId = SteamSession.steamId.and(0xFFFFFFFFL).toInt(), gameBuildId = 0)
}
