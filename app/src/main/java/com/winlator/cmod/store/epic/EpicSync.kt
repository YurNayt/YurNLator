package com.winlator.cmod.store.epic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.SaveBackup
import com.winlator.cmod.store.StoreNet
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.xenvironment.ImageFs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Epic cloud saves for games installed from the Epic library (their shortcut has epicApp), around
 * each session, as Legendary's sync-saves:
 *
 *  - before the game starts: when the cloud has a newer save than the container, it comes down
 *    (the container's saves are backed up first);
 *  - after it ends: when the game wrote newer saves than the cloud's, they go up as a new save.
 *
 * Where a game keeps its saves comes from its store record (CloudSaveFolder, with {AppData},
 * {UserDir}, {InstallDir}… placeholders); games without it have no Epic cloud saves. In the cloud a
 * save is a small build manifest ("manifests/<date>.manifest") with its data in 1 MiB chunks, the
 * same formats as game downloads. After Legendary's core / savegame_helper (GPL-3.0).
 */
object EpicSync {
    private const val TAG = "YurnEpicSync"
    private const val STORAGE = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com/api/v1/access/egstore/savesync"
    private const val CATALOG = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace"
    private const val CHUNK = 1 shl 20
    private const val MAX_UPLOAD = 200L * 1024 * 1024
    private const val SAME_S = 60L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("yurn_epic_sync", Context.MODE_PRIVATE)

    private fun appOf(shortcut: Shortcut?) = shortcut?.getExtra("epicApp", "").orEmpty()

    // ── Where the game keeps its saves ──────────────────────────────────────────

    private class CloudCfg(val folder: String, val include: List<String>?, val exclude: List<String>?)

    /** From the game's store record, cached a few days (also "no cloud saves"). */
    private fun config(context: Context, app: String, token: String): CloudCfg? {
        val cache = File(context.filesDir, "epic/cloud/$app.json")
        var o = runCatching { JSONObject(cache.readText()) }.getOrNull()
        if (o == null || System.currentTimeMillis() - o.optLong("t") > 3L * 24 * 3600 * 1000) {
            EpicLibrary.loadCached(context)
            val g = EpicLibrary.games.value?.firstOrNull { it.appName == app }
            if (g != null) {
                val body = get("$CATALOG/${g.namespace}/bulk/items?id=${g.catalogItemId}&includeDLCDetails=true&includeMainGameDetails=true&country=US&locale=en", token)
                val root = body?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
                val item = root?.optJSONObject(g.catalogItemId)
                if (item != null) {
                    val attrs = item.optJSONObject("customAttributes")
                    fun attr(k: String) = attrs?.optJSONObject(k)?.optString("value")?.takeIf { it.isNotBlank() }
                    o = JSONObject().put("t", System.currentTimeMillis()).put("folder", attr("CloudSaveFolder") ?: "")
                        .put("include", attr("CloudIncludeList") ?: "").put("exclude", attr("CloudExcludeList") ?: "")
                    runCatching { cache.parentFile?.mkdirs(); cache.writeText(o.toString()) }
                }
            }
        }
        val folder = o?.optString("folder").orEmpty()
        if (folder.isEmpty()) return null
        fun list(k: String) = o!!.optString(k).split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
        return CloudCfg(folder, list("include"), list("exclude"))
    }

    /** The game's cloud save folder as Epic names it, "" when it has none, null when unknown (blocking). */
    @JvmStatic
    fun cloudFolder(context: Context, app: String): String? = runCatching {
        val token = EpicAccount.token(context) ?: return null
        config(context, app, token)?.folder ?: ""
    }.getOrNull()

    /** CloudSaveFolder in this container ("{AppData}" is Local AppData, as in Epic's launcher). */
    private fun saveDir(container: Container, installDir: File, folder: String, accountId: String): File? {
        val driveC = File(container.rootDir, ".wine/drive_c")
        val profile = File(driveC, "users/${ImageFs.USER}").takeIf { it.isDirectory }
            ?: File(driveC, "users").listFiles()?.firstOrNull { it.isDirectory && !it.name.equals("Public", true) }
            ?: File(driveC, "users/${ImageFs.USER}")
        val vars = mapOf(
            "{installdir}" to installDir.path,
            "{epicid}" to accountId,
            "{appdata}" to File(profile, "AppData/Local").path,
            "{userdir}" to File(profile, "Documents").path,
            "{userprofile}" to profile.path,
            "{usersavedgames}" to File(profile, "Saved Games").path,
        )
        val parts = ArrayList<String>()
        for (p in folder.replace('\\', '/').split('/')) {
            val v = vars[p.lowercase(Locale.ROOT)]
            when {
                v != null -> { parts.clear(); parts += v.trimEnd('/') }
                p.isEmpty() || p == "." -> {}
                p == ".." -> parts += ".."
                p.matches(Regex("[A-Za-z]:")) -> { parts.clear(); parts += driveC.path }
                else -> parts += p
            }
        }
        if (parts.isEmpty() || !parts[0].startsWith("/")) return null
        // "a/../b" inside a placeholder's path: resolve without following links.
        return File(File(parts.joinToString("/")).toPath().normalize().toString())
    }

    private fun matches(name: String, patterns: List<String>) = patterns.any { p ->
        (p.endsWith("/") && name.startsWith(p)) || name.endsWith(p) ||
            Regex(p.split('*').joinToString(".*") { Regex.escape(it).replace("\\?", ".") }).matches(name)
    }

    /** The save files (relative name -> file), with the game's include / exclude lists. */
    private fun localFiles(dir: File, cfg: CloudCfg): Map<String, File> {
        if (!dir.isDirectory) return emptyMap()
        val out = sortedMapOf<String, File>(String.CASE_INSENSITIVE_ORDER)
        dir.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (cfg.include != null && !matches(rel, cfg.include)) return@forEach
            if (cfg.exclude != null && matches(rel, cfg.exclude)) return@forEach
            out[rel] = f
        }
        return out
    }

    // ── Cloud ───────────────────────────────────────────────────────────────────

    private fun get(url: String, token: String?): ByteArray? = runCatching {
        val rb = Request.Builder().url(url).header("User-Agent", EpicAccount.USER_AGENT)
        if (token != null) rb.header("Authorization", "Bearer $token")
        StoreNet.client.newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else { Log.w(TAG, "GET ${r.code}"); null } }
    }.getOrNull()

    private class Remote(val key: String, val readLink: String)

    /** All the account's cloud files of [app]: full name -> read link. */
    private fun listCloud(account: String, app: String, token: String): Map<String, Remote>? {
        val body = get("$STORAGE/$account/$app/", token) ?: return null
        val files = JSONObject(String(body, Charsets.UTF_8)).optJSONObject("files") ?: return emptyMap()
        val out = HashMap<String, Remote>()
        files.keys().forEach { k -> files.optJSONObject(k)?.optString("readLink")?.takeIf { it.isNotEmpty() }?.let { out[k] = Remote(k, it) } }
        return out
    }

    private val nameFormat get() = SimpleDateFormat("yyyy.MM.dd-HH.mm.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** The time of a save from its manifest's name ("2026.11.14-18.30.05.manifest", UTC). */
    private fun timeOf(key: String): Long =
        runCatching { nameFormat.parse(key.substringAfterLast('/').removeSuffix(".manifest"))!!.time }.getOrDefault(0L)

    private fun latest(files: Map<String, Remote>): Remote? =
        files.values.filter { it.key.contains("/manifests/") && it.key.endsWith(".manifest") }.maxByOrNull { timeOf(it.key) }

    // ── Before the game ──────────────────────────────────────────────────────────

    /** Blocking, bounded; on the launch's worker thread before the game process starts. */
    @JvmStatic
    fun beforeLaunch(context: Context, container: Container?, shortcut: Shortcut?) {
        val app = appOf(shortcut)
        if (app.isEmpty() || container == null) return
        if (!com.winlator.cmod.store.StoreLaunchPrefs.enabled(context, com.winlator.cmod.store.StoreLaunchPrefs.epic(app))) return
        runCatching { down(context, container, app) }.onFailure { Log.w(TAG, "$app: cloud down", it) }
    }

    private fun down(context: Context, container: Container, app: String) {
        EpicInstaller.load(context)
        val inst = EpicInstaller.installed.value[app] ?: return
        val token = EpicAccount.token(context) ?: return
        val account = EpicAccount.accountId(context).ifEmpty { return }
        val cfg = config(context, app, token) ?: run { Log.i(TAG, "$app: no Epic cloud saves"); return }
        val dir = saveDir(container, File(inst.dir), cfg.folder, account) ?: return
        val files = listCloud(account, app, token) ?: return
        val save = latest(files) ?: run { Log.i(TAG, "$app: nothing in the cloud yet"); return }
        val remoteTime = timeOf(save.key)
        val local = localFiles(dir, cfg)
        val localTime = local.values.maxOfOrNull { it.lastModified() } ?: 0L
        if (localTime > 0 && localTime >= remoteTime - SAME_S * 1000) {
            Log.i(TAG, "$app: local saves are as new as the cloud's (${cfg.folder})")
            return
        }
        val m = EpicManifest.parse(get(save.readLink, null) ?: return)
        val base = save.key.substringBeforeLast("manifests/")
        val data = HashMap<String, ByteArray>()
        for (c in m.chunks.values) {
            val r = files[base + c.path(m.chunkDir)] ?: run { Log.w(TAG, "$app: chunk missing"); return }
            data[c.key] = EpicManifest.decodeChunk(get(r.readLink, null) ?: return)
        }
        if (local.isNotEmpty()) SaveBackup.backup(container, SaveBackup.Reason.EPIC_CLOUD)
        var n = 0
        for (f in m.files) {
            if (f.name.split('/').any { it == ".." }) continue
            val out = File(dir, f.name)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".yurn_dl")
            tmp.outputStream().use { o ->
                for (p in f.parts) {
                    val c = data[p.guid] ?: throw IllegalStateException("chunk of ${f.name} missing")
                    o.write(c, p.offset, p.size)
                }
            }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            out.setLastModified(remoteTime)
            n++
        }
        prefs(context).edit().putLong("$app:synced", remoteTime).apply()
        Log.i(TAG, "$app: $n save files from the cloud (${save.key.substringAfterLast('/')})")
        if (n > 0) toast(context, uiStr(R.string.l_ste_cloud_down, n))
    }

    // ── After the game ───────────────────────────────────────────────────────────

    /** In the background, after Wine stopped (its files are written). */
    @JvmStatic
    fun afterExitAsync(context: Context, container: Container?, shortcut: Shortcut?) {
        val app = appOf(shortcut)
        if (app.isEmpty() || container == null) return
        if (!com.winlator.cmod.store.StoreLaunchPrefs.enabled(context, com.winlator.cmod.store.StoreLaunchPrefs.epic(app))) return
        val ctx = context.applicationContext
        Thread({ runCatching { up(ctx, container, app) }.onFailure { Log.w(TAG, "$app: cloud up", it) } }, "EpicSync").start()
    }

    private fun up(context: Context, container: Container, app: String) {
        EpicInstaller.load(context)
        val inst = EpicInstaller.installed.value[app] ?: return
        val token = EpicAccount.token(context) ?: return
        val account = EpicAccount.accountId(context).ifEmpty { return }
        val cfg = config(context, app, token) ?: return
        val dir = saveDir(container, File(inst.dir), cfg.folder, account) ?: return
        val local = localFiles(dir, cfg)
        if (local.isEmpty()) return
        val localTime = local.values.maxOf { it.lastModified() }
        if (localTime <= prefs(context).getLong("$app:synced", 0L) + 2_000) return // nothing changed since the last sync
        if (local.values.sumOf { it.length() } > MAX_UPLOAD) { Log.w(TAG, "$app: saves too big to upload"); return }
        val remote = listCloud(account, app, token) ?: return
        val remoteTime = latest(remote)?.let { timeOf(it.key) } ?: 0L
        if (remoteTime > localTime + SAME_S * 1000) { Log.i(TAG, "$app: the cloud has a newer save, not uploading"); return }

        val packed = pack(local, "$app$account", cfg.folder, localTime)
        val req = JSONObject().put("files", JSONArray(packed.keys.toList())).toString()
        val resp = StoreNet.client.newCall(Request.Builder().url("$STORAGE/$account/$app/")
            .header("User-Agent", EpicAccount.USER_AGENT).header("Authorization", "Bearer $token")
            .post(req.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else throw IllegalStateException("savesync HTTP ${r.code}")
        } ?: return
        val links = JSONObject(resp).optJSONObject("files") ?: return
        // Chunks first, the manifest last (so a half-done upload never shows as a save).
        val order = packed.keys.sortedBy { it.startsWith("manifests/") }
        for (name in order) {
            val key = links.keys().asSequence().firstOrNull { it == name || it.endsWith("/$name") } ?: throw IllegalStateException("no link for $name")
            val link = links.getJSONObject(key).optString("writeLink")
            StoreNet.client.newCall(Request.Builder().url(link).put(packed.getValue(name).toRequestBody(null)).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("upload HTTP ${r.code}")
            }
        }
        prefs(context).edit().putLong("$app:synced", localTime).apply()
        Log.i(TAG, "$app: ${local.size} save files to the cloud")
        toast(context, uiStr(R.string.l_ste_cloud_up, local.size))
    }

    // ── Packing (Legendary's SaveGameHelper.package_savegame) ───────────────────

    private class ChunkOut(val guid: IntArray, val hash: Long, val sha: ByteArray, val group: Int, val size: Long)
    private class PartOut(val guid: IntArray, val offset: Int, val size: Int)
    private class FileOut(val name: String, val sha: ByteArray, val parts: List<PartOut>)

    /** Upload name ("manifests/…", "ChunksV4/…") -> bytes. */
    private fun pack(files: Map<String, File>, metaApp: String, folder: String, time: Long): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val chunks = ArrayList<ChunkOut>()
        val fileList = ArrayList<FileOut>()
        val rnd = SecureRandom()
        var guid = IntArray(4) { rnd.nextInt() }
        val buf = ByteArrayOutputStream(CHUNK)

        fun finish() {
            val data = buf.toByteArray().copyOf(CHUNK) // padded to 1 MiB
            val sha = MessageDigest.getInstance("SHA-1").digest(data)
            val hash = rollingHash(data)
            val bytes = chunkFile(guid, hash, sha, data)
            val c = ChunkOut(guid, hash, sha, groupOf(guid), bytes.size.toLong())
            chunks += c
            out["ChunksV4/%02d/%016X_%s.chunk".format(c.group, hash, EpicManifest.guidKey(guid))] = bytes
            buf.reset()
            guid = IntArray(4) { rnd.nextInt() }
        }

        for ((name, f) in files) {
            val md = MessageDigest.getInstance("SHA-1")
            val parts = ArrayList<PartOut>()
            f.inputStream().use { i ->
                val tmp = ByteArray(64 * 1024)
                while (true) {
                    val room = CHUNK - buf.size()
                    val n = i.read(tmp, 0, minOf(tmp.size, room))
                    if (n <= 0) break
                    val last = parts.lastOrNull()
                    if (last != null && last.guid === guid && last.offset + last.size == buf.size()) {
                        parts[parts.size - 1] = PartOut(guid, last.offset, last.size + n)
                    } else parts += PartOut(guid, buf.size(), n)
                    buf.write(tmp, 0, n)
                    md.update(tmp, 0, n)
                    if (buf.size() >= CHUNK) finish()
                }
            }
            fileList += FileOut(name, md.digest(), parts)
        }
        if (buf.size() > 0) finish()

        val build = nameFormat.format(Date(time))
        out["manifests/$build.manifest"] = manifestFile(metaApp, build, folder, chunks, fileList)
        return out
    }

    private val hashTable: LongArray by lazy {
        LongArray(256) { i ->
            var v = i.toLong()
            repeat(8) { v = if (v and 1L != 0L) (v ushr 1) xor -0x3693a86a2878f0beL else v ushr 1 }
            v
        }
    }

    /** Epic's chunk rolling hash (a CRC-64-ECMA variant). */
    private fun rollingHash(data: ByteArray): Long {
        val t = hashTable
        var h = 0L
        for (b in data) h = ((h shl 1) or (h ushr 63)) xor t[b.toInt() and 0xFF]
        return h
    }

    private fun groupOf(guid: IntArray): Int {
        val crc = CRC32()
        crc.update(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { guid.forEach { putInt(it) } }.array())
        return (crc.value % 100).toInt()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        val tmp = ByteArray(64 * 1024)
        while (!d.finished()) { val n = d.deflate(tmp); out.write(tmp, 0, n) }
        d.end()
        return out.toByteArray()
    }

    /** Chunk file, header version 3 (66 bytes), zlib data. */
    private fun chunkFile(guid: IntArray, hash: Long, sha: ByteArray, data: ByteArray): ByteArray {
        val z = deflate(data)
        val b = ByteBuffer.allocate(66 + z.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0xB1FE3AA2.toInt()).putInt(3).putInt(66).putInt(z.size)
        guid.forEach { b.putInt(it) }
        b.putLong(hash).put(1.toByte()).put(sha).put(3.toByte()).putInt(data.size).put(z)
        return b.array()
    }

    private class Le {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = apply { o.write(v) }
        fun u32(v: Int) = apply { o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun u64(v: Long) = apply { o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()) }
        fun raw(b: ByteArray) = apply { o.write(b) }
        fun str(s: String) = apply {
            if (s.isEmpty()) { u32(0); return@apply }
            if (s.all { it.code < 128 }) { u32(s.length + 1); raw(s.toByteArray(Charsets.US_ASCII)); u8(0) }
            else { u32(-(s.length + 1)); raw(s.toByteArray(Charsets.UTF_16LE)); u8(0); u8(0) }
        }
        /** A section: its size (including the size field) first. */
        fun section(body: Le) = apply { u32(body.o.size() + 4); raw(body.o.toByteArray()) }
        fun bytes(): ByteArray = o.toByteArray()
    }

    /** Binary manifest, version 18 (as Legendary writes saves). */
    private fun manifestFile(app: String, build: String, folder: String, chunks: List<ChunkOut>, files: List<FileOut>): ByteArray {
        val meta = Le().u8(0).u32(18).u8(0).u32(0).str(app).str(build).str("").str("").u32(0).str("").str("").str("")
        val cdl = Le().u8(0).u32(chunks.size)
        chunks.forEach { c -> c.guid.forEach { cdl.u32(it) } }
        chunks.forEach { cdl.u64(it.hash) }
        chunks.forEach { cdl.raw(it.sha) }
        chunks.forEach { cdl.u8(it.group) }
        chunks.forEach { cdl.u32(CHUNK) }
        chunks.forEach { cdl.u64(it.size) }
        val fml = Le().u8(0).u32(files.size)
        files.forEach { fml.str(it.name) }
        files.forEach { fml.str("") }
        files.forEach { fml.raw(it.sha) }
        files.forEach { fml.u8(0) }
        files.forEach { fml.u32(0) }
        files.forEach { f ->
            fml.u32(f.parts.size)
            f.parts.forEach { p -> fml.u32(28); p.guid.forEach { fml.u32(it) }; fml.u32(p.offset); fml.u32(p.size) }
        }
        val cf = Le().u8(0).u32(1).str("CloudSaveFolder").str(folder)
        val body = Le().section(meta).section(cdl).section(fml).section(cf).bytes()
        val z = deflate(body)
        return Le().u32(0x44BEC00C).u32(41).u32(body.size).u32(z.size)
            .raw(MessageDigest.getInstance("SHA-1").digest(body)).u8(1).u32(18).raw(z).bytes()
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() } }
    }
}
