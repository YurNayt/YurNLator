package com.winlator.cmod.contents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.LocalInstalls
import com.winlator.cmod.core.RemoteFetch
import com.winlator.cmod.core.TarCompressorUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Vulkan wrappers (the ICD games talk to: libvulkan_wrapper + its icd json).
 *
 * - "builtin": the wrapper shipped in the APK (graphics_driver/wrapper.tzst).
 * - Others: .tzst archives in files/wrappers/, downloaded from the catalog (Bannerlator's
 *   wrappers.json, credited per author) or installed from the user's own file.
 * - A container / shortcut picks one with "wrapper=<id>" in its graphics driver config.
 *   All wrappers install as the same ICD, so the selected one is extracted over the
 *   image at launch, but only when it differs from the last one used.
 */
object WrapperManager {
    const val TYPE = "Wrapper"
    const val BUILTIN_ID = "builtin"
    const val CATALOG_URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/wrappers.json"
    private const val TAG = "WrapperManager"
    private const val MARKER = "usr/lib/.yurn-wrapper"

    /** Bumped on install / remove so open screens reload. */
    val revision = mutableIntStateOf(0)

    data class Installed(val id: String, val name: String, val author: String?, val targets: List<String>, val fromFile: Boolean)

    data class CatalogEntry(
        val id: String,
        val title: String,
        val author: String,
        val description: String,
        val url: String,
        val size: Long,
        val md5: String,
        val targets: List<String>
    )

    private fun dir(context: Context) = File(context.filesDir, "wrappers").apply { mkdirs() }
    private fun archive(context: Context, id: String) = File(dir(context), "$id.tzst")
    private fun meta(context: Context, id: String) = File(dir(context), "$id.json")

    @JvmStatic
    fun installed(context: Context): List<Installed> {
        val list = arrayListOf(Installed(BUILTIN_ID, "Built-in wrapper", "Ludashi", emptyList(), false))
        dir(context).listFiles { f -> f.name.endsWith(".tzst") }?.sortedBy { it.name }?.forEach { f ->
            val id = f.name.removeSuffix(".tzst")
            val m = runCatching { JSONObject(meta(context, id).readText()) }.getOrNull()
            list += Installed(
                id = id,
                name = m?.optString("name")?.takeIf { it.isNotBlank() } ?: id,
                author = m?.optString("author")?.takeIf { it.isNotBlank() },
                targets = m?.optJSONArray("targets")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                fromFile = m?.optBoolean("fromFile") == true
            )
        }
        return list
    }

    @JvmStatic
    fun isInstalled(context: Context, id: String) = id == BUILTIN_ID || archive(context, id).isFile

    @JvmStatic
    fun displayName(context: Context, id: String?): String {
        val clean = id?.takeIf { it.isNotBlank() } ?: BUILTIN_ID
        return installed(context).firstOrNull { it.id == clean }?.name ?: "Built-in wrapper"
    }

    /** Catalog entries. allowNetwork=false: saved copy only. BCn layers are not wrappers and are left out. */
    @JvmStatic
    fun catalog(context: Context, allowNetwork: Boolean = true): List<CatalogEntry> {
        val body = if (allowNetwork) RemoteFetch.get(context, CATALOG_URL) else RemoteFetch.cached(context, CATALOG_URL)
        body ?: return emptyList()
        return runCatching {
            val root = JSONObject(body)
            val arr = root.optJSONArray("wrappers") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank() || id.contains("bcn-")) return@mapNotNull null
                val name = o.optString("name")
                // "WinlatorMali — leegao" -> author "WinlatorMali", title "leegao"
                val dash = name.indexOf(" — ")
                val author = if (dash > 0) name.substring(0, dash) else o.optString("author", "Other")
                val title = if (dash > 0) name.substring(dash + 3) else name
                val targets = o.optJSONArray("gpuTargets")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                CatalogEntry(
                    id = id, title = title, author = author,
                    description = o.optString("description"),
                    url = o.optString("url"),
                    size = o.optString("file_size").toLongOrNull() ?: 0L,
                    md5 = o.optString("file_checksum").uppercase(Locale.ENGLISH),
                    targets = targets.filter { it != "all" }
                )
            }
        }.getOrDefault(emptyList())
    }

    /** "mali,exynos" -> "Mali / Exynos only"; empty for wrappers that work everywhere. */
    @JvmStatic
    fun targetsLabel(targets: List<String>): String? =
        if (targets.isEmpty()) null
        else targets.joinToString(" / ") { it.replaceFirstChar { c -> c.uppercase() } } + " only"

    /** Downloads, checks size and MD5, installs. Returns an error message or null. */
    fun download(context: Context, entry: CatalogEntry, progress: (Int) -> Unit = {}): String? {
        val tmp = File(context.cacheDir, "wrapper-${System.nanoTime()}.tzst")
        try {
            val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(entry.url).build()).execute().use { response ->
                if (!response.isSuccessful) return "Download failed (${response.code})"
                val body = response.body ?: return "Download failed"
                val total = body.contentLength().takeIf { it > 0 } ?: entry.size
                val md = MessageDigest.getInstance("MD5")
                var done = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                if (entry.size > 0 && done != entry.size) return "Downloaded file has the wrong size"
                val hex = md.digest().joinToString("") { "%02X".format(it) }
                if (entry.md5.length == 32 && hex != entry.md5) return "Checksum mismatch, the file is damaged"
            }
            if (!looksLikeZstd(tmp)) return "Not a .tzst wrapper archive"
            install(context, entry.id, tmp, entry.title, entry.author, entry.targets, fromFile = false)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            return "Download failed: ${e.message ?: "network error"}"
        } finally {
            tmp.delete()
        }
    }

    /** A .tzst picked from storage. Works offline. Returns the new id, or null if not a wrapper archive. */
    fun installFromFile(context: Context, uri: Uri): String? {
        val name = displayName(context, uri) ?: "wrapper"
        val base = name.removeSuffix(".tzst").removeSuffix(".tar.zst")
        val id = "file-" + base.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        val tmp = File(context.cacheDir, "wrapper-import-${System.nanoTime()}.tzst")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: return null
            if (!looksLikeZstd(tmp)) return null
            install(context, id, tmp, base, null, emptyList(), fromFile = true)
            LocalInstalls.mark(context, TYPE, id)
            return id
        } catch (e: Exception) {
            Log.w(TAG, "Import failed", e)
            return null
        } finally {
            tmp.delete()
        }
    }

    private fun install(context: Context, id: String, file: File, name: String, author: String?, targets: List<String>, fromFile: Boolean) {
        val dst = archive(context, id)
        file.copyTo(dst, overwrite = true)
        val m = JSONObject().put("name", name).put("fromFile", fromFile).put("targets", JSONArray(targets))
        if (author != null) m.put("author", author)
        meta(context, id).writeText(m.toString())
        revision.intValue++
    }

    @JvmStatic
    fun remove(context: Context, id: String) {
        if (id == BUILTIN_ID) return
        archive(context, id).delete()
        meta(context, id).delete()
        LocalInstalls.forget(context, LocalInstalls.key(TYPE, id))
        revision.intValue++
    }

    /**
     * Called at launch, after the image's graphics runtime is in place. Extracts the
     * selected wrapper over the image when it differs from the one used last time.
     * [builtinJustExtracted]: the launch already unpacked the built-in wrapper.
     */
    @JvmStatic
    fun applySelected(context: Context, rootDir: File, selectedId: String?, builtinJustExtracted: Boolean) {
        var id = selectedId?.takeIf { it.isNotBlank() } ?: BUILTIN_ID
        if (id != BUILTIN_ID && !archive(context, id).isFile) {
            Log.w(TAG, "Wrapper $id is not installed, using the built-in one")
            id = BUILTIN_ID
        }
        val marker = File(rootDir, MARKER)
        val current = if (builtinJustExtracted) BUILTIN_ID else runCatching { marker.readText().trim() }.getOrDefault(BUILTIN_ID)
        if (current == id) {
            if (builtinJustExtracted) FileUtils.writeString(marker, id)
            return
        }
        Log.d(TAG, "Switching wrapper $current -> $id")
        // Archives packed on macOS may carry ._* / .DS_Store files: keep them out of the image.
        val skipMacMetadata = com.winlator.cmod.core.OnExtractFileListener { f, _ ->
            if (f.name.startsWith("._") || f.name == ".DS_Store") null else f
        }
        val ok = if (id == BUILTIN_ID) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/wrapper.tzst", rootDir, skipMacMetadata)
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive(context, id), rootDir, skipMacMetadata)
        }
        if (ok) FileUtils.writeString(marker, id) else Log.w(TAG, "Extracting wrapper $id failed")
    }

    private fun looksLikeZstd(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x28.toByte() && b[1] == 0xB5.toByte() && b[2] == 0x2F.toByte() && b[3] == 0xFD.toByte()
        }
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
