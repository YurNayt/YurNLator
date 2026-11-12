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
    private const val PREF_V3 = "renderer_driver_guard_v3"
    private const val PREF_BUILD = "renderer_driver_guard_build"
    private const val PREF_STRIKES = "renderer_driver_strikes_"

    private fun marker(context: Context) = File(context.filesDir, "renderer-driver-pending")

    @JvmStatic
    fun blocked(context: Context, driverId: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // v3: blocks from before it needed the custom driver's own library in the crash (a crash in
        // our renderer or the system Vulkan loader was blamed on the driver) are forgotten once.
        // Blocks also end with an app update: the renderer or the driver handling may be fixed.
        val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        if (!prefs.getBoolean(PREF_V3, false) || prefs.getString(PREF_BUILD, "") != build) {
            prefs.edit().remove(PREF).remove(PREF_STRIKES).putBoolean(PREF_V2, true).putBoolean(PREF_V3, true)
                .putString(PREF_BUILD, build).apply()
        }
        val set = prefs.getStringSet(PREF, emptySet())!!.toMutableSet()
        val m = marker(context)
        if (m.isFile) {
            val id = runCatching { m.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            m.delete()
            if (id != null && lastCrashWasInDriver(context)) {
                // Blocked on the second crash in a row: one crash can be anything.
                val strikes = prefs.getInt(PREF_STRIKES + id, 0) + 1
                if (strikes >= 2) set += id
                prefs.edit().putInt(PREF_STRIKES + id, strikes).putStringSet(PREF, set).apply()
            }
        }
        return driverId in set
    }

    /**
     * The app's last death was a native crash inside the custom driver's own library (a Turnip /
     * Freedreno build from contents). A crash in the system Vulkan loader or our renderer doesn't
     * count, nor does an unknown cause.
     */
    private fun lastCrashWasInDriver(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return false
            if (info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return false
            val raw = info.traceInputStream?.use { it.readBytes() } ?: return false
            val files = Tombstone.crashingFiles(raw) ?: return false
            files.any { f ->
                val l = f.lowercase()
                l.contains("/contents/adrenotools/") || l.contains("freedreno") || l.contains("turnip")
            }
        }.getOrDefault(false)
    }

    @JvmStatic
    fun arm(context: Context, driverId: String) {
        val m = marker(context)
        runCatching { m.writeText(driverId) }
        Handler(Looper.getMainLooper()).postDelayed({
            m.delete()
            PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_STRIKES + driverId).apply()
        }, SAFE_MS)
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
