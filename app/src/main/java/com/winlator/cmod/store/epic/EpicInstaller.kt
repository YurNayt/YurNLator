package com.winlator.cmod.store.epic

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.store.StoreNet
import com.winlator.cmod.store.StoreShortcuts
import com.winlator.cmod.store.steam.SteamDownloadService
import com.winlator.cmod.store.steam.SteamInstaller.Job
import com.winlator.cmod.store.steam.SteamInstaller.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Installs owned Epic games into Download/YurNLator/Games/Epic/<title>: the build manifest from Epic's
 * launcher service, then its chunks from the CDN (several at once), each file written in order and
 * checked against its SHA-1. Finished files are listed in .yurn_done, so pausing / a lost connection /
 * closing the app continues where it stopped. After Bannerlator's EpicDownloadManager (GPL-3.0).
 */
object EpicInstaller {
    private const val TAG = "YurnEpicInstall"
    private const val MANIFEST_API = "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/namespace"
    private const val PARALLEL = 8
    /** Chunks fetched ahead of the file being written, in bytes. */
    private const val AHEAD = 96L * 1024 * 1024

    data class Installed(val appName: String, val title: String, val dir: String, val exe: String, val args: String) {
        val exeFile: File get() = File(dir, exe)
        fun toJson(): JSONObject = JSONObject().put("title", title).put("dir", dir).put("exe", exe).put("args", args)
    }

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs
    private val _installed = MutableStateFlow<Map<String, Installed>>(emptyMap())
    val installed: StateFlow<Map<String, Installed>> = _installed

    private val queue = Executors.newSingleThreadExecutor { Thread(it, "EpicInstall").apply { isDaemon = true } }
    private val stop = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private var loaded = false

    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator/Games/Epic")

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trimEnd('.').ifEmpty { "game" }

    fun dirFor(g: EpicGame) = File(root(), safe(g.title))

    private fun registry(c: Context) = File(c.filesDir, "epic/installed.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val map = HashMap<String, Installed>()
        runCatching {
            val o = JSONObject(registry(context).readText())
            o.keys().forEach { k ->
                val v = o.getJSONObject(k)
                val i = Installed(k, v.optString("title"), v.optString("dir"), v.optString("exe"), v.optString("args"))
                if (File(i.dir).isDirectory) map[k] = i
            }
        }
        _installed.value = map
    }

    @Synchronized
    private fun save(context: Context) {
        val o = JSONObject()
        _installed.value.values.forEach { o.put(it.appName, it.toJson()) }
        runCatching { registry(context).apply { parentFile?.mkdirs() }.writeText(o.toString()) }
    }

    // Jobs are keyed by the app name; the shared Job carries an Int id, so it's the name's hash.
    private fun setJob(app: String, j: Job) { _jobs.value = _jobs.value + (app to j); SteamDownloadService.update() }
    private fun show(app: String, j: Job) { if (app !in stop && _jobs.value.containsKey(app)) setJob(app, j) }
    fun anyActive() = _jobs.value.values.any { it.active }

    fun install(context: Context, g: EpicGame) {
        val ctx = context.applicationContext
        load(ctx)
        if (_jobs.value[g.appName]?.active == true) return
        stop.remove(g.appName)
        setJob(g.appName, Job(g.appName.hashCode(), g.title, Phase.QUEUED, done = _jobs.value[g.appName]?.done ?: 0, total = _jobs.value[g.appName]?.total ?: 0))
        SteamDownloadService.start(ctx)
        queue.execute { run(ctx, g) }
    }

    fun pause(app: String) {
        stop += app
        _jobs.value[app]?.takeIf { it.active }?.let { setJob(app, it.copy(phase = Phase.PAUSED, speed = 0)) }
    }

    fun cancel(g: EpicGame) {
        pause(g.appName)
        _jobs.value = _jobs.value - g.appName
        SteamDownloadService.update()
        Thread {
            val end = System.currentTimeMillis() + 15_000
            while (g.appName in running && System.currentTimeMillis() < end) Thread.sleep(200)
            if (_installed.value[g.appName] == null) StoreShortcuts.deleteTree(dirFor(g))
        }.start()
    }

    private fun get(url: String, token: String?): ByteArray? = runCatching {
        val rb = Request.Builder().url(url).header("User-Agent", EpicAccount.USER_AGENT)
        if (token != null) rb.header("Authorization", "Bearer $token")
        StoreNet.client.newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else { Log.w(TAG, "GET ${r.code}"); null } }
    }.getOrNull()

    private class Cdn(val base: String, val dir: String, val query: String)

    /** The game's current build: its manifest and the servers its chunks come from. */
    private fun latestBuild(token: String, g: EpicGame): Pair<EpicManifest, List<Cdn>> {
        val api = get("$MANIFEST_API/${g.namespace}/catalogItem/${g.catalogItemId}/app/${g.appName}/label/Live", token)
            ?: throw IllegalStateException("Epic: no build info")
        val element = JSONObject(String(api)).getJSONArray("elements").getJSONObject(0)
        val manifests = element.getJSONArray("manifests")
        val cdns = ArrayList<Cdn>()
        var manifestName = ""
        for (i in 0 until manifests.length()) {
            val m = manifests.getJSONObject(i)
            val uri = m.optString("uri")
            val at = uri.indexOf("/Builds")
            if (at < 0 || !uri.startsWith("http") || uri.contains("cloudflare.epicgamescdn.com")) continue
            val path = uri.substring(at).substringBefore('?')
            manifestName = path.substringAfterLast('/')
            val q = m.optJSONArray("queryParams")?.let { qp ->
                (0 until qp.length()).map { qp.getJSONObject(it) }.filter { it.optString("name").isNotEmpty() }
                    .joinToString("&") { it.optString("name") + "=" + it.optString("value") }
            }.orEmpty()
            cdns += Cdn(uri.substring(0, at), path.substringBeforeLast('/'), if (q.isEmpty()) "" else "?$q")
        }
        if (cdns.isEmpty()) throw IllegalStateException("Epic: no download servers")
        val manifestBytes = cdns.firstNotNullOfOrNull { get(it.base + it.dir + "/" + manifestName + it.query, null) }
            ?: throw IllegalStateException("Epic: manifest unavailable")
        return EpicManifest.parse(manifestBytes) to cdns
    }

    /** (size on disk, download size) of the game's current build; cached a day. Blocking. */
    fun sizes(context: Context, g: EpicGame): Pair<Long, Long>? {
        val cache = File(context.filesDir, "epic/sizes.json")
        val all = runCatching { JSONObject(cache.readText()) }.getOrDefault(JSONObject())
        all.optJSONObject(g.appName)?.let { o ->
            if (System.currentTimeMillis() - o.optLong("t") < 24L * 3600 * 1000) return o.optLong("disk") to o.optLong("dl")
        }
        return runCatching {
            val token = EpicAccount.token(context) ?: return null
            val (m, _) = latestBuild(token, g)
            val disk = m.installSize
            val dl = m.chunks.values.sumOf { it.fileSize }.takeIf { it > 0 } ?: disk
            synchronized(this) {
                all.put(g.appName, JSONObject().put("t", System.currentTimeMillis()).put("disk", disk).put("dl", dl))
                cache.parentFile?.mkdirs(); cache.writeText(all.toString())
            }
            disk to dl
        }.onFailure { Log.w(TAG, "${g.appName}: size", it) }.getOrNull()
    }

    private fun run(context: Context, g: EpicGame) {
        val app = g.appName
        running += app
        var j = Job(app.hashCode(), g.title, Phase.PREPARING, done = _jobs.value[app]?.done ?: 0, total = _jobs.value[app]?.total ?: 0)
        show(app, j)
        try {
            val token = EpicAccount.token(context) ?: throw IllegalStateException("Epic: sign in again")
            val (manifest, cdns) = latestBuild(token, g)
            if (manifest.encrypted) throw IllegalStateException("Epic: this game's files are encrypted, not supported")

            val dir = dirFor(g).apply { mkdirs() }
            val doneFile = File(dir, ".yurn_done")
            val doneSet = runCatching { doneFile.readLines().toHashSet() }.getOrDefault(HashSet())
            val files = manifest.files.filter { f ->
                val p = f.name.replace('\\', '/')
                p.isNotBlank() && !p.startsWith("/") && !(p.length > 1 && p[1] == ':') && p.split('/').none { it == ".." }
            }
            val total = files.sumOf { it.size }
            val todo = files.filter { f -> !(f.name in doneSet && File(dir, f.name).length() == f.size) }
            val already = total - todo.sumOf { it.size }
            j = j.copy(phase = Phase.DOWNLOADING, done = already, total = total)
            show(app, j)

            // How many times each chunk is still needed: one kept in memory only while it is.
            val refs = HashMap<String, Int>()
            todo.forEach { f -> f.parts.forEach { refs[it.guid] = (refs[it.guid] ?: 0) + 1 } }
            val pool = Executors.newFixedThreadPool(PARALLEL)
            val pending = HashMap<String, Future<ByteArray>>()
            val spill = File(dir, ".yurn_chunks")
            val written = AtomicLong(already)
            var lastT = System.currentTimeMillis(); var lastB = already; var speed = 0.0

            fun fetch(c: EpicManifest.Chunk): ByteArray {
                var err: Throwable? = null
                for (attempt in 0 until 6) {
                    if (app in stop) throw InterruptedException("paused")
                    for (cdn in cdns) {
                        try {
                            val raw = get(cdn.base + cdn.dir + "/" + c.path(manifest.chunkDir), null) ?: continue
                            return EpicManifest.decodeChunk(raw)
                        } catch (t: Throwable) { err = t }
                    }
                    Thread.sleep(minOf(2_000L shl attempt, 30_000L)) // network gone or switched: wait and retry
                }
                throw IllegalStateException("Epic: chunk download failed", err)
            }

            // Queue the chunks of the next files, up to AHEAD bytes past the file being written.
            var cursor = 0
            fun prefetch(fromFile: Int) {
                var budget = 0L
                var i = fromFile
                while (i < todo.size && budget < AHEAD) {
                    for (p in todo[i].parts) {
                        if (pending.containsKey(p.guid) || File(spill, p.guid).isFile) continue
                        val c = manifest.chunks[p.guid] ?: throw IllegalStateException("Epic: manifest misses a chunk")
                        pending[p.guid] = pool.submit<ByteArray> { fetch(c) }
                        budget += c.windowSize
                    }
                    i++
                }
                cursor = maxOf(cursor, i)
            }

            try {
                for ((index, f) in todo.withIndex()) {
                    if (app in stop) break
                    prefetch(index)
                    val target = File(dir, f.name)
                    target.parentFile?.mkdirs()
                    val part = File(target.parentFile, target.name + ".yurn_part")
                    val sha = MessageDigest.getInstance("SHA-1")
                    RandomAccessFile(part, "rw").use { out ->
                        out.setLength(0)
                        for (p in f.parts) {
                            if (app in stop) throw InterruptedException("paused")
                            val spilled = File(spill, p.guid)
                            val data = pending.remove(p.guid)?.get(10, TimeUnit.MINUTES)
                                ?: if (spilled.isFile) spilled.readBytes() else fetch(manifest.chunks[p.guid]!!)
                            out.write(data, p.offset, p.size)
                            sha.update(data, p.offset, p.size)
                            val left = (refs[p.guid] ?: 1) - 1
                            refs[p.guid] = left
                            if (left > 0 && !spilled.isFile) { spill.mkdirs(); spilled.writeBytes(data) } // needed again later
                            if (left <= 0) spilled.delete()
                            val now = System.currentTimeMillis()
                            val done = written.addAndGet(p.size.toLong() and 0xFFFFFFFFL)
                            if (now - lastT >= 700) {
                                val inst = (done - lastB) * 1000.0 / (now - lastT)
                                speed = if (speed <= 0) inst else speed * 0.7 + inst * 0.3
                                lastT = now; lastB = done
                                j = j.copy(phase = Phase.DOWNLOADING, done = done, speed = speed.toLong().coerceAtLeast(0))
                                show(app, j)
                            }
                        }
                    }
                    if (f.sha1 != null && f.sha1.any { it != 0.toByte() } && !sha.digest().contentEquals(f.sha1))
                        throw IllegalStateException("Epic: ${f.name} is damaged, try again")
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) throw IllegalStateException("Epic: can't write ${f.name}")
                    doneFile.appendText(f.name + "\n")
                }
            } finally {
                pool.shutdownNow()
            }

            if (app in stop) {
                setJob(app, j.copy(phase = Phase.PAUSED, speed = 0, done = written.get()))
                return
            }
            StoreShortcuts.deleteTree(spill)
            doneFile.delete()
            val exe = manifest.launchExe.replace('\\', '/').trimStart('/')
            _installed.value = _installed.value + (app to Installed(app, g.title, dir.absolutePath, exe, manifest.launchCommand.trim()))
            save(context)
            setJob(app, j.copy(phase = Phase.DONE, done = total, speed = 0))
        } catch (t: Throwable) {
            if (app in stop || _jobs.value[app] == null) {
                _jobs.value[app]?.let { setJob(app, it.copy(phase = Phase.PAUSED, speed = 0)) }
            } else {
                Log.w(TAG, "install $app failed", t)
                setJob(app, j.copy(phase = Phase.FAILED, speed = 0, error = (t.cause?.takeIf { t is java.util.concurrent.ExecutionException } ?: t).message ?: t.javaClass.simpleName))
            }
        } finally {
            running -= app
            SteamDownloadService.update()
        }
    }

    fun uninstall(context: Context, app: String) {
        load(context)
        val i = _installed.value[app] ?: return
        val dir = File(i.dir)
        if (!dir.canonicalPath.startsWith(root().canonicalPath + File.separator)) return
        StoreShortcuts.of(context, dir.absolutePath).forEach { it.delete() }
        StoreShortcuts.deleteTree(dir)
        _installed.value = _installed.value - app
        _jobs.value = _jobs.value - app
        save(context)
    }

    fun shortcutCount(context: Context, app: String) = _installed.value[app]?.let { StoreShortcuts.of(context, it.dir).size } ?: 0

    fun addShortcut(context: Context, container: Container, app: String): File? {
        val i = _installed.value[app] ?: return null
        val exe = i.exeFile.takeIf { it.isFile } ?: return null
        return StoreShortcuts.add(context, container, i.title, exe, i.args, mapOf("epicApp" to app))
    }
}
