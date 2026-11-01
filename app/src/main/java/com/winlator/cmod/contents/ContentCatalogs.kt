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
        // Daily FEX builds from upstream; only the newest are listed.
        Source("FEX nightly", "https://raw.githubusercontent.com/merkezekre2026/fexcore-wcp/main/contents.json", maxPerType = 10),
    )

    data class Entry(val type: String, val verName: String, val verCode: Int, val remoteUrl: String, val source: String)

    /** Download URL -> source name, for "where is this from" labels. */
    private val sourceByUrl = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun sourceOf(remoteUrl: String?): String? = remoteUrl?.let { sourceByUrl[it] }

    /** Network refresh of the extra catalogs. Call from a background thread. */
    @JvmStatic
    fun refresh(context: Context) {
        EXTRA_SOURCES.forEach { runCatching { RemoteFetch.get(context, it.url) } }
    }

    /** Saved copies only (no network). */
    @JvmStatic
    fun extraEntries(context: Context): List<Entry> = EXTRA_SOURCES.flatMap { source ->
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
