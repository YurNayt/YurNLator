package com.winlator.cmod.community

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The public preset library (github.com/YurNayt/YurNLator, branch "presets").
 *
 * Reading needs no account: the index and the preset files are plain files on that branch.
 * Publishing goes one of two ways:
 *  - through the upload service named in the branch's config.json ("upload_url"), when one is
 *    set up: anonymous, from inside the app;
 *  - otherwise as a GitHub issue the user submits in the browser (a GitHub account is needed).
 * Either way a GitHub Action checks the preset, adds it to the library and keeps the issue as the
 * preset's page: 👍 on it are the preset's likes, comments are the reviews.
 *
 * Every call here is blocking: run it on Dispatchers.IO.
 */
object PresetHub {
    const val REPO = "YurNayt/YurNLator"
    private const val RAW = "https://raw.githubusercontent.com/$REPO/presets/"
    private const val PREFS_MINE = "community_presets_mine"
    private const val KEY_AUTHOR = "community_presets_author"

    data class IndexEntry(
        val id: String,
        val game: String,
        val gameKey: String,
        val exe: String,
        val title: String,
        val author: String,
        val device: String,
        val soc: String,
        val gpu: String,
        val created: Long,
        val issue: Int,
        val likes: Int,
        val comments: Int,
        val appVersion: String,
    ) {
        val hardware: String get() = listOf(gpu, soc).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun cacheDir(context: Context) = File(context.filesDir, "community_presets").apply { mkdirs() }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    /** The library index; a fresh copy when [refresh] or the saved one is older than 10 minutes. */
    fun index(context: Context, refresh: Boolean = false): List<IndexEntry>? {
        val file = File(cacheDir(context), "index.json")
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 10 * 60_000L
        var body: String? = null
        if (refresh || !fresh) {
            body = get(RAW + "index.json?t=" + System.currentTimeMillis() / 60_000)
            if (body != null && runCatching { JSONObject(body) }.isSuccess) runCatching { file.writeText(body) }
            else body = null
        }
        if (body == null && file.isFile) body = runCatching { file.readText() }.getOrNull()
        body ?: return null
        return runCatching { parseIndex(JSONObject(body)) }.getOrNull()
    }

    private fun parseIndex(root: JSONObject): List<IndexEntry> {
        val arr = root.optJSONArray("presets") ?: JSONArray()
        val out = ArrayList<IndexEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").filter { it.isLetterOrDigit() }
            if (id.isEmpty()) continue
            out += IndexEntry(
                id = id,
                game = o.optString("game"),
                gameKey = o.optString("game_key").ifEmpty { CommunityPreset.gameKey(o.optString("game")) },
                exe = o.optString("exe").lowercase(),
                title = o.optString("title"),
                author = o.optString("author"),
                device = o.optString("device"),
                soc = o.optString("soc"),
                gpu = o.optString("gpu"),
                created = o.optLong("created"),
                issue = o.optInt("issue"),
                likes = o.optInt("likes"),
                comments = o.optInt("comments"),
                appVersion = o.optString("app_version"),
            )
        }
        return out
    }

    /** Presets for this game: same exe, or the same name once punctuation is ignored. */
    fun forGame(all: List<IndexEntry>, name: String, exe: String): List<IndexEntry> {
        val key = CommunityPreset.gameKey(name)
        val exeKey = exe.lowercase()
        return all.filter {
            (exeKey.isNotBlank() && it.exe == exeKey) || (key.isNotBlank() && it.gameKey == key)
        }
    }

    /** Same SoC first, then the same GPU, then likes, then newest. */
    fun rank(list: List<IndexEntry>, soc: String, gpu: String): List<IndexEntry> = list.sortedWith(
        compareByDescending<IndexEntry> { soc.isNotBlank() && it.soc.equals(soc, true) }
            .thenByDescending { gpu.isNotBlank() && it.gpu.equals(gpu, true) }
            .thenByDescending { it.likes }
            .thenByDescending { it.created }
    )

    fun fetch(context: Context, entry: IndexEntry): CommunityPreset? {
        val file = File(cacheDir(context), "p_${entry.id}.json")
        val body = get(RAW + "p/${entry.id}.json") ?: file.takeIf { it.isFile }?.readText() ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        runCatching { file.writeText(body) }
        return CommunityPreset.parse(json, entry.issue, entry.likes, entry.comments)
    }

    fun issueUrl(issue: Int) = "https://github.com/$REPO/issues/$issue"

    // ── publishing ──────────────────────────────────────────────────────────────────────────

    private fun uploadUrl(context: Context): String? {
        val body = get(RAW + "config.json?t=" + System.currentTimeMillis() / 600_000)
            ?: File(cacheDir(context), "config.json").takeIf { it.isFile }?.readText()
        val url = runCatching { JSONObject(body ?: return null).optString("upload_url") }.getOrNull()
        if (body != null) runCatching { File(cacheDir(context), "config.json").writeText(body) }
        return url?.trim()?.takeIf { it.startsWith("https://") }
    }

    fun savedAuthor(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_AUTHOR, "").orEmpty()

    fun saveAuthor(context: Context, name: String) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY_AUTHOR, name.trim()).apply()

    /** The delete token of a preset published from this phone, or null. */
    fun myToken(context: Context, id: String): String? =
        context.getSharedPreferences(PREFS_MINE, Context.MODE_PRIVATE).getString(id, null)

    fun rememberMine(context: Context, id: String, token: String) =
        context.getSharedPreferences(PREFS_MINE, Context.MODE_PRIVATE).edit().putString(id, token).apply()

    fun forgetMine(context: Context, id: String) =
        context.getSharedPreferences(PREFS_MINE, Context.MODE_PRIVATE).edit().remove(id).apply()

    fun myIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_MINE, Context.MODE_PRIVATE).all.keys

    /** The preset packed for an issue body: gzip + base64, so it survives copy / paste and fits in a link. */
    fun pack(preset: CommunityPreset): String {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(preset.toJson().toString().toByteArray()) }
        }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(76).joinToString("\n")
    }

    private fun issueBody(preset: CommunityPreset): String = buildString {
        appendLine("<!-- yurnlator-preset v1 · do not edit the block below -->")
        appendLine("**Game:** ${preset.game}" + if (preset.exe.isNotBlank()) " (`${preset.exe}`)" else "")
        appendLine("**Preset:** ${preset.title.ifBlank { "—" }}")
        appendLine("**Author:** ${preset.author.ifBlank { "anonymous" }}")
        appendLine("**Device:** ${preset.deviceModel} · ${preset.hardware}")
        val r = preset.requires
        appendLine("**Uses:** " + listOf(r.wineLabel.ifBlank { r.wine }, r.dxvk.takeIf { it.isNotBlank() }?.let { "DXVK $it" },
            r.vkd3d.takeIf { it.isNotBlank() }?.let { "VKD3D $it" }, r.driverLabel.takeIf { it.isNotBlank() })
            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
        if (preset.notes.isNotBlank()) { appendLine(); appendLine(preset.notes) }
        appendLine()
        appendLine("```yurnlator-preset")
        appendLine(pack(preset))
        appendLine("```")
    }

    private fun issueTitle(preset: CommunityPreset) =
        "[preset] ${preset.game.take(60)}" + (preset.title.takeIf { it.isNotBlank() }?.let { " — ${it.take(60)}" } ?: "")

    sealed class Publish {
        /** Sent to the upload service; the library has it within a few minutes. */
        data class Sent(val issue: Int) : Publish()
        /** Open this link: the user submits the issue on GitHub. */
        data class OpenBrowser(val url: String) : Publish()
        data class Failed(val reason: String) : Publish()
    }

    /** Publishes [preset]; [token] (kept on this phone) lets its author delete it later. */
    fun publish(context: Context, preset: CommunityPreset, token: String): Publish {
        rememberMine(context, preset.id, token)
        val service = uploadUrl(context)
        if (service != null) {
            val payload = JSONObject().put("title", issueTitle(preset)).put("body", issueBody(preset)).toString()
            val resp = runCatching {
                http.newCall(
                    Request.Builder().url(service.trimEnd('/') + "/upload")
                        .post(payload.toRequestBody("application/json".toMediaType())).build()
                ).execute().use { r -> r.body?.string()?.let { JSONObject(it) }?.takeIf { r.isSuccessful } }
            }.getOrNull()
            if (resp != null && resp.optBoolean("ok")) return Publish.Sent(resp.optInt("issue"))
            // Service down: GitHub still works.
        }
        val url = "https://github.com/$REPO/issues/new?title=" + Uri.encode(issueTitle(preset)) +
            "&body=" + Uri.encode(issueBody(preset))
        return if (url.length > 7800) Publish.Failed("This preset is too large for a link; save it to a file and share that.")
        else Publish.OpenBrowser(url)
    }

    /** Asks the library to remove a preset published from this phone. */
    fun delete(context: Context, id: String): Publish {
        val token = myToken(context, id) ?: return Publish.Failed("This preset wasn't published from this phone.")
        val title = "[preset-delete] $id"
        val body = "<!-- yurnlator-preset-delete -->\nid: $id\ntoken: $token\n"
        val service = uploadUrl(context)
        if (service != null) {
            val payload = JSONObject().put("title", title).put("body", body).toString()
            val ok = runCatching {
                http.newCall(
                    Request.Builder().url(service.trimEnd('/') + "/upload")
                        .post(payload.toRequestBody("application/json".toMediaType())).build()
                ).execute().use { r -> r.isSuccessful && r.body?.string()?.let { JSONObject(it).optBoolean("ok") } == true }
            }.getOrDefault(false)
            if (ok) { forgetMine(context, id); return Publish.Sent(0) }
        }
        return Publish.OpenBrowser("https://github.com/$REPO/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body))
    }

    /** A preset as a file / text anyone can import (also offline: Telegram, Discord…). */
    fun exportText(preset: CommunityPreset): String = preset.toJson().toString(2)

    /** Reads a shared preset: the JSON file, or an issue body with the packed block. */
    fun importText(text: String): CommunityPreset? {
        runCatching { CommunityPreset.parse(JSONObject(text.trim())) }.getOrNull()?.let { return it }
        val block = Regex("```yurnlator-preset\\s+([A-Za-z0-9+/=\\s]+)```").find(text)?.groupValues?.get(1) ?: return null
        return runCatching {
            val raw = Base64.decode(block.replace(Regex("\\s"), ""), Base64.DEFAULT)
            val json = java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            CommunityPreset.parse(JSONObject(json))
        }.getOrNull()
    }
}
