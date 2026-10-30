package com.winlator.cmod.core

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.preference.PreferenceManager

/**
 * Remembers which components the user installed from their own files, so the
 * download screen can show a "My files" group (only when there is something in it).
 *
 * Key: "<type>|<name>", where type is the component type ("AdrenoTools", "DXVK", …)
 * and name is the driver id or the content version name.
 */
object LocalInstalls {
    private const val KEY = "yurn_local_installs"

    /** Bumped on changes so open screens recompose. */
    val revision = mutableIntStateOf(0)

    @JvmStatic
    fun key(type: String, name: String) = "$type|$name"

    private fun read(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context).getStringSet(KEY, emptySet()).orEmpty()

    @JvmStatic
    fun mark(context: Context, type: String, name: String?) {
        if (name.isNullOrBlank()) return
        val set = read(context).toMutableSet()
        if (set.add(key(type, name))) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putStringSet(KEY, set).apply()
            revision.intValue++
        }
    }

    @JvmStatic
    fun forget(context: Context, key: String) {
        val set = read(context).toMutableSet()
        if (set.remove(key)) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putStringSet(KEY, set).apply()
            revision.intValue++
        }
    }

    @JvmStatic
    fun contains(context: Context, key: String?) = key != null && key in read(context)
}
