package com.winlator.cmod.contents

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** Where a driver author's list comes from. */
sealed class DriverFeed {
    /** GitHub releases of owner/repo: every .zip asset is a driver. */
    data class Github(val owner: String, val repo: String) : DriverFeed() {
        val apiUrl get() = "https://api.github.com/repos/$owner/$repo/releases"
        override fun describe() = "github.com/$owner/$repo"
    }

    /** A JSON array of {"verName": "...", "remoteUrl": "...zip"} (Bannerlator / Winlator contents format). */
    data class Json(val url: String) : DriverFeed() {
        override fun describe() = url
    }

    abstract fun describe(): String
}

/** A driver author: one or more feeds under one name, shown as one group. */
data class DriverSource(
    val name: String,
    val feeds: List<DriverFeed>,
    val note: String = "",
    val builtIn: Boolean = false
)

/**
 * Driver authors, like Bannerlator's driver sources: built-in authors can be switched
 * off, custom ones (GitHub owner/repo or a JSON link) can be added and removed.
 * Only enabled authors' downloads are listed in the driver picker.
 */
object DriverSources {
    private const val KEY_DISABLED = "yurn_driver_sources_disabled"
    private const val KEY_CUSTOM = "yurn_driver_sources_custom"
    private const val NIGHTLIES = "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main"

    @JvmField
    val BUILT_IN: List<DriverSource> = listOf(
        DriverSource("StevenMXZ", listOf(DriverFeed.Github("StevenMXZ", "freedreno_turnip-CI")),
            "Turnip builds, Ludashi's default", true),
        DriverSource("whitebelyash", listOf(DriverFeed.Github("whitebelyash", "AdrenoToolsDrivers")),
            "Mainline Turnip sync builds", true),
        DriverSource("Weab-Chan", listOf(DriverFeed.Github("Weab-chan", "freedreno_turnip-CI")),
            "Turnip CI builds", true),
        DriverSource("K11MCH1 (Kimchi)", listOf(DriverFeed.Json("$NIGHTLIES/kimchi_drivers.json")),
            "Qualcomm proprietary and Turnip builds", true),
        DriverSource("MTR / WinNative", listOf(
            DriverFeed.Json("$NIGHTLIES/mtr_drivers.json"),
            DriverFeed.Github("WinNative-Emu", "Drivers")
        ), "Turnip MTR builds", true),
        DriverSource("Banners-Turnip", listOf(DriverFeed.Json("$NIGHTLIES/banners-turnip_drivers.json")),
            "Bannerlator's Turnip builds", true),
        DriverSource("Rutabaga", listOf(DriverFeed.Json("$NIGHTLIES/rutabaga_drivers.json")),
            "Rutabaga builds", true),
    )

    /** Bumped on every change, so open screens reload their driver lists. */
    val revision = mutableIntStateOf(0)

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun disabled(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED, emptySet()).orEmpty()

    @JvmStatic
    fun isEnabled(context: Context, source: DriverSource) = source.name !in disabled(context)

    @JvmStatic
    fun setEnabled(context: Context, source: DriverSource, enabled: Boolean) {
        val set = disabled(context).toMutableSet()
        if (enabled) set.remove(source.name) else set.add(source.name)
        prefs(context).edit().putStringSet(KEY_DISABLED, set).apply()
        revision.intValue++
    }

    @JvmStatic
    fun custom(context: Context): List<DriverSource> {
        val raw = prefs(context).getString(KEY_CUSTOM, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                val feed = when (o.optString("type")) {
                    "github" -> DriverFeed.Github(o.optString("owner").trim(), o.optString("repo").trim())
                    "json" -> DriverFeed.Json(o.optString("url").trim())
                    else -> null
                }
                if (name.isEmpty() || feed == null) null else DriverSource(name, listOf(feed))
            }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun all(context: Context): List<DriverSource> = BUILT_IN + custom(context)

    @JvmStatic
    fun enabled(context: Context): List<DriverSource> = all(context).filter { isEnabled(context, it) }

    /** Adds a custom author. Returns an error message, or null on success. */
    fun addCustom(context: Context, name: String, feed: DriverFeed): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "Enter a name"
        if (all(context).any { it.name.equals(clean, ignoreCase = true) }) return "This name is already used"
        val list = custom(context) + DriverSource(clean, listOf(feed))
        save(context, list)
        return null
    }

    fun removeCustom(context: Context, source: DriverSource) {
        save(context, custom(context).filterNot { it.name == source.name })
        setEnabled(context, source, true) // forget its "disabled" flag
    }

    private fun save(context: Context, list: List<DriverSource>) {
        val array = JSONArray()
        list.forEach { s ->
            val o = JSONObject().put("name", s.name)
            when (val f = s.feeds.first()) {
                is DriverFeed.Github -> o.put("type", "github").put("owner", f.owner).put("repo", f.repo)
                is DriverFeed.Json -> o.put("type", "json").put("url", f.url)
            }
            array.put(o)
        }
        prefs(context).edit().putString(KEY_CUSTOM, array.toString()).apply()
        revision.intValue++
    }

    /**
     * Understands what people paste: "owner/repo", "github.com/owner/repo",
     * "https://github.com/owner/repo/releases" or a JSON link.
     */
    fun parseFeed(input: String): DriverFeed? {
        val t = input.trim().removeSuffix("/")
        if (t.isEmpty()) return null
        if (t.startsWith("http") && !t.contains("github.com/", ignoreCase = true)) return DriverFeed.Json(t)
        if (t.endsWith(".json", ignoreCase = true)) return DriverFeed.Json(t)
        val path = t.substringAfter("github.com/", t)
            .removePrefix("repos/")
            .split('/')
            .filter { it.isNotBlank() }
        return if (path.size >= 2) DriverFeed.Github(path[0], path[1]) else null
    }
}
