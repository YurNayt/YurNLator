package com.winlator.cmod.contents

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * Which download a GPU driver was installed from (download URL -> installed driver id).
 * Lets the download screen keep a driver under its author as "Installed" instead of
 * offering the same download again.
 */
object DriverOrigins {
    private const val KEY = "yurn_driver_origins"

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    @JvmStatic
    fun record(context: Context, url: String, driverId: String?) {
        if (driverId.isNullOrBlank()) return
        val o = read(context).put(url, driverId)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, o.toString()).apply()
    }

    /** The installed driver id for this download, or null when it isn't installed (any more). */
    @JvmStatic
    fun installedIdFor(context: Context, url: String, installedIds: Collection<String>): String? {
        val id = read(context).optString(url, "")
        return id.takeIf { it.isNotEmpty() && installedIds.any { i -> i.equals(it, ignoreCase = true) } }
    }
}
