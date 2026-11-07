package com.winlator.cmod.core

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

    private fun marker(context: Context) = File(context.filesDir, "renderer-driver-pending")

    @JvmStatic
    fun blocked(context: Context, driverId: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val set = prefs.getStringSet(PREF, emptySet())!!.toMutableSet()
        val m = marker(context)
        if (m.isFile) {
            runCatching { m.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { set += it }
            m.delete()
            prefs.edit().putStringSet(PREF, set).apply()
        }
        return driverId in set
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
