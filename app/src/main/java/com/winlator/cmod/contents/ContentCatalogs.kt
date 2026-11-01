package com.winlator.cmod.contents

import android.content.Context
import com.winlator.cmod.core.RemoteFetch
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Extra component catalogs (DXVK, VKD3D, FEXCore, Box64, WOWBox64, Wine / Proton) merged into
 * the main one (StevenMXZ's Winlator-Contents or the URL set in Settings), like Bannerlator does.
 * All use the Winlator contents.json format: [{type, verName, verCode, remoteUrl}].
 *
 * [refresh] downloads them (off the main thread); [extraEntries] only reads the saved copies,
 * so merging never waits for the network and works offline.
 */
object ContentCatalogs {
    data class Source(val name: String, val url: String, val maxPerType: Int = Int.MAX_VALUE)

    const val MAIN_SOURCE = "Winlator Contents"

    val EXTRA_SOURCES = listOf(
        // Bannerlator's catalog: Nightlies components, Proton / GE-Proton / CachyOS layers.
        Source("Bannerlator", "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/contents.json"),
        // Arihany's WCP hub: recent DXVK (incl. gplasync), VKD3D, FEXCore.
        Source("WCP Hub", "https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json"),
        // Daily FEX builds from upstream; about a month of the newest (grouped under one row).
        Source("FEX nightly", "https://raw.githubusercontent.com/merkezekre2026/fexcore-wcp/main/contents.json", maxPerType = 31),
    )

    /** Releases of the FEX nightly repo: their publish date names each daily build ("20261030"). */
    private const val FEX_NIGHTLY_RELEASES = "https://api.github.com/repos/merkezekre2026/fexcore-wcp/releases?per_page=100"

    /** Commit hash -> build day "yyyyMMdd". */
    private val nightlyDates = ConcurrentHashMap<String, String>()
    @Volatile private var nightlyDatesBody: String? = null

    /** Build day of a daily FEX build by its commit hash (full or short), or null if unknown. */
    @JvmStatic
    fun nightlyDate(hash: String): String? {
        val h = hash.lowercase(Locale.ENGLISH)
        nightlyDates[h]?.let { return it }
        if (h.length < 7) return null
        return nightlyDates.entries.firstOrNull { it.key.startsWith(h) || h.startsWith(it.key) }?.value
    }

    private fun loadNightlyDates(context: Context) {
        val body = RemoteFetch.cached(context, FEX_NIGHTLY_RELEASES) ?: return
        if (body == nightlyDatesBody) return
        runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Tags look like "fex-nightly-2610-d321f49fb".
                val hash = o.optString("tag_name").substringAfterLast('-').lowercase(Locale.ENGLISH)
                val day = o.optString("published_at").take(10).replace("-", "")
                if (hash.length >= 7 && day.length == 8) nightlyDates[hash] = day
            }
        }
        nightlyDatesBody = body
    }

    data class Entry(val type: String, val verName: String, val verCode: Int, val remoteUrl: String, val source: String)

    /** Download URL -> source name, for "where is this from" labels. */
    private val sourceByUrl = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun sourceOf(remoteUrl: String?): String? = remoteUrl?.let { sourceByUrl[it] }

    /** Network refresh of the extra catalogs. Call from a background thread. */
    @JvmStatic
    fun refresh(context: Context) {
        EXTRA_SOURCES.forEach { runCatching { RemoteFetch.get(context, it.url) } }
        runCatching { RemoteFetch.get(context, FEX_NIGHTLY_RELEASES) }
        loadNightlyDates(context)
    }

    /** Saved copies only (no network). */
    @JvmStatic
    fun extraEntries(context: Context): List<Entry> {
        loadNightlyDates(context)
        return extraEntriesOf(context)
    }

    private fun extraEntriesOf(context: Context): List<Entry> = EXTRA_SOURCES.flatMap { source ->
        val body = RemoteFetch.cached(context, source.url) ?: return@flatMap emptyList()
        val parsed = runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("remoteUrl")
                val name = o.optString("verName").trim()
                if (url.isBlank() || name.isBlank()) null
                else Entry(o.optString("type"), name, o.optString("verCode", "0").toIntOrNull() ?: 0, url, source.name)
            }
        }.getOrDefault(emptyList())
        parsed.groupBy { it.type.lowercase(Locale.ENGLISH) }.values.flatMap { it.take(source.maxPerType) }
    }.onEach { sourceByUrl[it.remoteUrl] = it.source }

    @JvmStatic
    fun rememberMainSource(remoteUrl: String) {
        sourceByUrl.putIfAbsent(remoteUrl, MAIN_SOURCE)
    }
}
