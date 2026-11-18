package com.winlator.cmod.store.steam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.store.StoreNet
import com.winlator.cmod.ui.uiStr
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * An in-game card when a Steam-library game earns an achievement. Goldberg (the game's Steam API
 * here) records it in GSE Saves/<app>/achievements.json; that file is watched while the game runs
 * and every newly earned one is shown, one after another: its picture, name and description in the
 * account's language when Steam answered, the plain names from the emulator's list otherwise.
 * Games whose achievements don't go through Steam's API (Ubisoft's, set by Ubisoft's servers)
 * never write the file and show nothing.
 */
object AchievementWatcher {
    private const val TAG = "YurnAchievements"
    private const val POLL_MS = 1_500L
    private const val SHOWN_MS = 5_000L
    @Volatile private var thread: Thread? = null

    private class Info(val title: String, val description: String, val icon: String?)

    @JvmStatic
    fun start(context: Context, root: FrameLayout?, container: Container?, shortcut: Shortcut?) {
        val appId = SteamSync.appIdOf(shortcut)
        if (appId <= 0 || container == null || root == null) return
        stop()
        val app = context.applicationContext
        val file = SteamSync.gseAchievements(container, appId)
        val t = Thread({
            try {
                var known = earned(file)
                var stamp = file.lastModified()
                var names: Map<String, Info>? = null
                val queue = java.util.concurrent.LinkedBlockingQueue<String>()
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(POLL_MS)
                    val m = file.lastModified()
                    if (m != stamp) {
                        stamp = m
                        val now = earned(file)
                        (now - known).forEach { queue.add(it) }
                        known = known + now
                    }
                    val name = queue.poll() ?: continue
                    // Names in the account's language from Steam, once (only when something was earned).
                    if (names == null) names = runCatching { localized(app, appId) }.getOrNull() ?: emptyMap()
                    val info = names[name] ?: fromSchema(shortcut, appId, name)
                    val picture = info.icon?.let { runCatching { download(it) }.getOrNull() }
                    Log.i(TAG, "$appId: earned $name")
                    val done = java.util.concurrent.CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post {
                        // In Steam's own look: a game from the Steam library.
                        com.winlator.cmod.ui.ingame.AchievementToast.show(root, com.winlator.cmod.ui.ingame.AchievementToast.Look.STEAM,
                            uiStr(R.string.l_ach_unlocked), info.title, info.description.takeIf { it.isNotBlank() && it != info.title },
                            picture, SHOWN_MS) { done.countDown() }
                    }
                    // The next one once this card is gone (it may be held by a finger for a while).
                    done.await(SHOWN_MS * 4, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Log.w(TAG, "watcher", t)
            }
        }, "AchievementWatcher")
        t.isDaemon = true
        thread = t
        t.start()
    }

    @JvmStatic
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun earned(file: File): Set<String> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().filter { k ->
            val e = o.optJSONObject(k)
            e != null && (e.optBoolean("earned", false) || e.optInt("earned", 0) != 0)
        }.toSet()
    }.getOrDefault(emptySet())

    private fun localized(context: Context, appId: Int): Map<String, Info> =
        SteamSync.achievements(context, appId).orEmpty().associate { a ->
            a.name to Info(a.title, a.description, a.iconEarned ?: a.icon)
        }

    /** From the list given to Goldberg before the launch (english names). */
    private fun fromSchema(shortcut: Shortcut?, appId: Int, name: String): Info {
        val inst = runCatching { SteamInstaller.installed.value[appId] }.getOrNull()
        val dirs = inst?.let { Goldberg.settingsDirs(it) }.orEmpty()
        for (d in dirs) {
            val arr = runCatching { org.json.JSONArray(File(d, "achievements.json").readText()) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name") != name) continue
                val title = o.optJSONObject("displayName")?.optString("english").orEmpty().ifBlank { name }
                val desc = o.optJSONObject("description")?.optString("english").orEmpty()
                val icon = o.optString("icon").removePrefix("img/").takeIf { it.isNotBlank() && !it.startsWith("steam_default") }
                return Info(title, desc, icon?.let { "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$appId/$it" })
            }
        }
        return Info(name, "", null)
    }

    private fun download(url: String): Bitmap? =
        StoreNet.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
}
