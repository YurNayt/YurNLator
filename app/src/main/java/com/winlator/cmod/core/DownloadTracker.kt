package com.winlator.cmod.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Downloads with visible progress, pause / resume and cancel, shared by every install
 * (Proton / Wine, DXVK, VKD3D, FEX, Box64, GPU drivers). Lists read [states] by the same key
 * they already use for "installing" ("DXVK:2.3.1", "wine:<id>", "driver:<url>").
 *
 * Pausing closes the connection; resuming continues from the bytes already on disk
 * (HTTP Range), so a long pause or a dropped connection doesn't start over.
 */
object DownloadTracker {
    data class State(
        val done: Long = 0,
        val total: Long = 0,
        val paused: Boolean = false,
        /** Download finished, the package is being unpacked / installed. */
        val installing: Boolean = false
    ) {
        /** 0…100, or -1 while the size is unknown. */
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
    }

    /** Observable from Compose. Changed on the main thread only. */
    @JvmField val states = mutableStateMapOf<String, State>()

    private val main = Handler(Looper.getMainLooper())
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val live = ConcurrentHashMap<String, State>()
    private val lastPost = ConcurrentHashMap<String, Long>()
    private val lock = Object()
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    class CancelledException : Exception("Download cancelled")

    private fun post(key: String, s: State?, force: Boolean = true) {
        if (s == null) live.remove(key) else live[key] = s
        val now = System.currentTimeMillis()
        if (!force && now - (lastPost[key] ?: 0L) < 150) return
        lastPost[key] = now
        main.post { val cur = live[key]; if (cur == null) states.remove(key) else states[key] = cur }
    }

    @JvmStatic fun state(key: String): State? = states[key]

    @JvmStatic fun pause(key: String) {
        paused += key
        live[key]?.let { post(key, it.copy(paused = true)) }
    }

    @JvmStatic fun resume(key: String) {
        paused -= key
        live[key]?.let { post(key, it.copy(paused = false)) }
        synchronized(lock) { lock.notifyAll() }
    }

    @JvmStatic fun cancel(key: String) {
        cancelled += key
        paused -= key
        synchronized(lock) { lock.notifyAll() }
    }

    /** Marks the start of an install (shows 0 % at once, before the first byte). */
    @JvmStatic fun begin(key: String) {
        cancelled -= key
        paused -= key
        post(key, State())
    }

    /** Download done, unpacking / installing now. */
    @JvmStatic fun installing(key: String) {
        post(key, (live[key] ?: State()).copy(installing = true, paused = false))
    }

    @JvmStatic fun finish(key: String) {
        paused -= key
        cancelled -= key
        post(key, null)
    }

    @JvmStatic fun isCancelled(key: String) = key in cancelled

    /**
     * Downloads [url] into [file]; with [resumeExisting] continues a partial file.
     * [base] / [overallTotal] place this file inside a multi-part download (bytes before it, all bytes).
     * Blocks while paused. Returns false on HTTP / IO failure; throws [CancelledException] on cancel.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(CancelledException::class)
    fun download(
        key: String, url: String, file: File,
        resumeExisting: Boolean = false, base: Long = 0, overallTotal: Long = 0, expectedSize: Long = 0
    ): Boolean {
        if (!resumeExisting) file.delete()
        var attempts = 0
        var size = expectedSize
        while (true) {
            waitIfPaused(key)
            val have = if (file.isFile) file.length() else 0L
            if (size > 0 && have > size) { file.delete(); continue } // broken partial file: start over
            if (size > 0 && have == size) return true
            val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            try {
                client.newCall(req).execute().use { response ->
                    val body = response.body ?: return false
                    if (response.code == 416 && have > 0) { // stale partial file: start over
                        file.delete()
                        if (++attempts > 4) return false
                        return@use
                    }
                    val append = have > 0 && response.code == 206
                    if (!response.isSuccessful) return false
                    if (have > 0 && !append) file.delete() // server ignored Range: start over
                    val start = if (append) have else 0L
                    if (size <= 0) size = body.contentLength().let { if (it > 0) it + start else 0 }
                    val total = if (overallTotal > 0) overallTotal else size
                    var done = start
                    attempts = 0 // the server answered: count failures again from here
                    body.byteStream().use { input ->
                        FileOutputStream(file, append).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (key in cancelled) throw CancelledException()
                                if (key in paused) break // close the connection, resume later with Range
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                post(key, State(base + done, total, false), force = false)
                            }
                        }
                    }
                    if (key in paused) return@use
                    if (size > 0 && file.length() < size) throw java.io.IOException("Connection closed early")
                    return true
                }
            } catch (e: CancelledException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                if (key in cancelled) { file.delete(); throw CancelledException() }
                // Dropped connection: retry a few times, continuing where it stopped.
                if (++attempts > 4) return false
                Thread.sleep(1500L * attempts)
            }
        }
    }

    private fun waitIfPaused(key: String) {
        synchronized(lock) {
            while (key in paused && key !in cancelled) lock.wait(1000)
        }
        if (key in cancelled) throw CancelledException()
    }
}
