package com.winlator.cmod.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Small, offline-friendly fetch for catalog JSON (component lists, driver repos).
 *
 * - Every successful download is saved to app storage; offline, the saved copy is used,
 *   so the app still knows the names of downloadable items.
 * - The network is used at most once per [TTL_MS] per URL, with short timeouts, and
 *   never when the device reports no internet.
 * - [cached] never touches the network: screens use it to render instantly first.
 */
object RemoteFetch {
    private const val TTL_MS = 10 * 60 * 1000L

    private const val RETRY_AFTER_FAILURE_MS = 2 * 60 * 1000L

    private val memory = ConcurrentHashMap<String, Pair<Long, String>>()
    private val lastFailure = ConcurrentHashMap<String, Long>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    @JvmStatic
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.filesDir, "remote_cache").apply { mkdirs() }
        return File(dir, Integer.toHexString(url.hashCode()) + ".json")
    }

    /** Last saved copy only. No network. */
    @JvmStatic
    fun cached(context: Context, url: String): String? {
        memory[url]?.let { return it.second }
        val file = cacheFile(context, url)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /**
     * Fresh copy if online and not fetched recently, otherwise the saved copy.
     * Returns null only when there is nothing saved and the download failed.
     */
    @JvmStatic
    fun get(context: Context, url: String): String? {
        val now = System.currentTimeMillis()
        memory[url]?.let { (time, body) -> if (now - time < TTL_MS) return body }
        val recentlyFailed = lastFailure[url]?.let { now - it < RETRY_AFTER_FAILURE_MS } == true
        if (!recentlyFailed && isOnline(context)) {
            val body = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()
            if (body != null) {
                memory[url] = now to body
                lastFailure.remove(url)
                runCatching { cacheFile(context, url).writeText(body) }
                return body
            }
            // Slow or broken network: don't retry on every screen refresh.
            lastFailure[url] = now
        }
        return cached(context, url)
    }
}
