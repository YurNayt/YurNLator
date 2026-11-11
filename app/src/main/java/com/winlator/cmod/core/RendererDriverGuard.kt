package com.winlator.cmod.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import java.io.File

/**
 * The X11 screen renderer loads the container's custom Vulkan driver into the app's own process.
 * When such a driver crashes there (a Turnip build that doesn't know this Adreno), the app dies before
 * the game shows anything. [arm] leaves a marker while the renderer starts; if the app survives
 * [SAFE_MS] it's removed. A marker still there on the next game start means that driver crashed
 * the app: it's [blocked] for the renderer (the system driver draws the screen; the game keeps it).
 */
object RendererDriverGuard {
    private const val SAFE_MS = 20_000L
    private const val PREF = "renderer_driver_blocked"
    private const val PREF_V2 = "renderer_driver_guard_v2"

    private fun marker(context: Context) = File(context.filesDir, "renderer-driver-pending")

    @JvmStatic
    fun blocked(context: Context, driverId: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // v2: blocks from before it checked where the crash was (any crash in the first 20 s, a
        // sound server crash on exit included, blamed the driver) are forgotten once.
        if (!prefs.getBoolean(PREF_V2, false)) prefs.edit().remove(PREF).putBoolean(PREF_V2, true).apply()
        val set = prefs.getStringSet(PREF, emptySet())!!.toMutableSet()
        val m = marker(context)
        if (m.isFile) {
            val id = runCatching { m.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            m.delete()
            if (id != null && lastCrashWasInDriver(context)) {
                set += id
                prefs.edit().putStringSet(PREF, set).apply()
            }
        }
        return driverId in set
    }

    /**
     * The app's last death was a native crash with a Vulkan driver in the crashing thread. Unknown
     * (Android before 11, no record) counts as yes, as before.
     */
    private fun lastCrashWasInDriver(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return true
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return true
            if (info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return false
            val raw = info.traceInputStream?.use { it.readBytes() } ?: return true
            val files = Tombstone.crashingFiles(raw) ?: return true
            files.any { f ->
                val l = f.lowercase()
                l.contains("vulkan") || l.contains("freedreno") || l.contains("adreno") || l.contains("turnip")
                    || l.contains("/contents/") || l.contains("libgsl") || l.contains("notgsl")
            }
        }.getOrDefault(true)
    }

    @JvmStatic
    fun arm(context: Context, driverId: String) {
        val m = marker(context)
        runCatching { m.writeText(driverId) }
        Handler(Looper.getMainLooper()).postDelayed({ m.delete() }, SAFE_MS)
    }

    /** The session ended normally: the driver didn't crash the app. */
    @JvmStatic
    fun disarm(context: Context) { marker(context).delete() }

    /** Forget the crashes (e.g. after a driver update). */
    @JvmStatic
    fun reset(context: Context) {
        marker(context).delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF).apply()
    }
}
