package com.winlator.cmod.store.steam

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.ExeIconExtractor
import com.winlator.cmod.xenvironment.ImageFs
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Installs the account's Steam games with JavaSteam's depot downloader (GameNative's fork, as in
 * Bannerlator) into Download/YurNLator/Games/Steam/<folder>: outside the containers, so a game
 * survives removing a container and can be added to any of them. One download at a time; pausing
 * stops it and keeps the files, installing again continues (finished files are checked, not fetched).
 */
object SteamInstaller {
    private const val TAG = "YurnSteamInstall"

    enum class Phase { QUEUED, PREPARING, DOWNLOADING, RECONNECTING, PAUSED, FAILED, DONE }

    data class Job(val appId: Int, val name: String, val phase: Phase, val done: Long = 0, val total: Long = 0,
                   val speed: Long = 0, val error: String? = null) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val active: Boolean get() = phase == Phase.QUEUED || phase == Phase.PREPARING || phase == Phase.DOWNLOADING || phase == Phase.RECONNECTING
    }

    data class Installed(val appId: Int, val name: String, val dir: String, val exe: String, val args: String) {
        val exeFile: File get() = File(dir, exe)
        fun toJson(): JSONObject = JSONObject().put("name", name).put("dir", dir).put("exe", exe).put("args", args)
    }

    private val _jobs = MutableStateFlow<Map<Int, Job>>(emptyMap())
    val jobs: StateFlow<Map<Int, Job>> = _jobs
    private val _installed = MutableStateFlow<Map<Int, Installed>>(emptyMap())
    val installed: StateFlow<Map<Int, Installed>> = _installed

    private val queue = Executors.newSingleThreadExecutor { Thread(it, "SteamInstall").apply { isDaemon = true } }
    private val running = ConcurrentHashMap<Int, DepotDownloader>()
    private val pauseRequested = ConcurrentHashMap.newKeySet<Int>()
    private var loaded = false

    /** Download/YurNLator/Games/Steam (drive D: in the containers). */
    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator/Games/Steam")

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trimEnd('.').ifEmpty { "game" }

    fun dirFor(app: SteamApp) = File(root(), safe(app.installDir))

    private fun registry(context: Context) = File(context.filesDir, "steam/installed.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val map = HashMap<Int, Installed>()
        runCatching {
            val o = JSONObject(registry(context).readText())
            o.keys().forEach { k ->
                val v = o.getJSONObject(k)
                val i = Installed(k.toInt(), v.optString("name"), v.optString("dir"), v.optString("exe"), v.optString("args"))
                if (File(i.dir).isDirectory) map[i.appId] = i
            }
        }
        _installed.value = map
    }

    @Synchronized
    private fun saveRegistry(context: Context) {
        val o = JSONObject()
        _installed.value.values.forEach { o.put(it.appId.toString(), it.toJson()) }
        runCatching { registry(context).apply { parentFile?.mkdirs() }.writeText(o.toString()) }
    }

    private fun setJob(job: Job) {
        _jobs.value = _jobs.value + (job.appId to job)
        SteamDownloadService.update()
    }

    private fun job(appId: Int) = _jobs.value[appId]

    /** Progress of a running download: never over a pause or a cancel the user just made. */
    private fun show(j: Job) {
        if (j.appId !in pauseRequested && job(j.appId) != null) setJob(j)
    }

    fun anyActive() = _jobs.value.values.any { it.active }

    fun install(context: Context, app: SteamApp) {
        val ctx = context.applicationContext
        load(ctx)
        if (job(app.id)?.active == true) return
        pauseRequested.remove(app.id)
        setJob(Job(app.id, app.name, Phase.QUEUED, done = job(app.id)?.done ?: 0L, total = app.sizeBytes))
        SteamDownloadService.start(ctx)
        queue.execute { run(ctx, app) }
    }

    fun pause(appId: Int) {
        pauseRequested += appId
        val j = job(appId) ?: return
        // Shown at once; the download thread lets go within a second.
        if (j.active) setJob(j.copy(phase = Phase.PAUSED, speed = 0))
    }

    /** Stops the download and deletes what was downloaded. */
    fun cancel(context: Context, app: SteamApp) {
        pause(app.id)
        _jobs.value = _jobs.value - app.id
        SteamDownloadService.update()
        Thread {
            val end = System.currentTimeMillis() + 15_000
            while (running.containsKey(app.id) && System.currentTimeMillis() < end) Thread.sleep(200)
            if (_installed.value[app.id] == null) {
                deleteTree(dirFor(app))
                Thread.sleep(3_000) // a write of the stopped download that was still in flight
                if (_installed.value[app.id] == null && job(app.id) == null) deleteTree(dirFor(app))
            }
        }.start()
    }

    private const val MAX_ATTEMPTS = 8
    /** No new data for this long: the connection is dead (e.g. Wi-Fi -> mobile), start over from where it was. */
    private const val STALL_MS = 60_000L
    /** Before the first data Steam still sends the manifests; give big games more time. */
    private const val FIRST_DATA_MS = 180_000L

    private enum class End { DONE, PAUSED, STALLED, FAILED }

    private fun run(context: Context, app: SteamApp) {
        if (app.id in pauseRequested) return
        val dir = dirFor(app)
        var j = Job(app.id, app.name, Phase.PREPARING, done = job(app.id)?.done ?: 0L, total = app.sizeBytes)
        show(j)
        var failure: Throwable? = null
        var end = End.FAILED
        for (n in 0 until MAX_ATTEMPTS) {
            if (app.id in pauseRequested) { end = End.PAUSED; break }
            if (n > 0) {
                j = j.copy(phase = Phase.RECONNECTING, speed = 0)
                show(j)
                // Let the new network settle and Steam reconnect.
                Thread.sleep(minOf(5_000L * n, 30_000L))
            }
            val r = runOnce(context, app, dir, j) { j = it }
            end = r.first
            failure = r.second
            if (end != End.STALLED) break
            Log.i(TAG, "download of ${app.id} stalled, resuming (attempt ${n + 1})")
        }
        if (app.id in pauseRequested) end = End.PAUSED
        if (job(app.id) == null) return // cancelled
        when (end) {
            End.PAUSED -> setJob(j.copy(phase = Phase.PAUSED, speed = 0))
            End.FAILED, End.STALLED -> {
                val f = failure
                Log.w(TAG, "install ${app.id} failed", f)
                setJob(j.copy(phase = Phase.FAILED, speed = 0,
                    error = f?.let { (it.cause ?: it).message ?: it.javaClass.simpleName } ?: "no connection"))
            }
            End.DONE -> {
                val (exe, args) = findExe(dir, app)
                if (exe.isNotEmpty()) runCatching { File(File(dir, exe).parentFile, "steam_appid.txt").takeIf { !it.exists() }?.writeText(app.id.toString()) }
                _installed.value = _installed.value + (app.id to Installed(app.id, app.name, dir.absolutePath, exe, args))
                saveRegistry(context)
                // Games that need a running Steam start with the emulator (switchable in the game's card).
                runCatching {
                    Goldberg.forget(dir.absolutePath)
                    val inst = _installed.value[app.id]
                    if (inst != null && Goldberg.usesSteam(inst) && Goldberg.ensure(context)) Goldberg.apply(context, inst)
                }.onFailure { Log.w(TAG, "goldberg", it) }
                setJob(j.copy(phase = Phase.DONE, done = maxOf(j.done, j.total), speed = 0))
            }
        }
    }

    /**
     * One go of the depot downloader. Never blocks for good: it is watched every second and let go
     * when paused, when no data comes for a while, or when the phone switches networks (the old
     * connections are dead then). Files already written are kept; the next go checks and continues.
     */
    private fun runOnce(context: Context, app: SteamApp, dir: File, start: Job, publish: (Job) -> Unit): Pair<End, Throwable?> {
        var j = start
        val base = start.done
        var downloader: DepotDownloader? = null
        var failure: Throwable? = null
        var end = End.FAILED
        try {
            if (!SteamSession.awaitOnline(context, 120_000L)) return End.STALLED to IllegalStateException("Steam: no connection")
            if (app.id in pauseRequested) return End.PAUSED to null
            val client = SteamSession.client ?: return End.STALLED to null
            val network = SteamSession.networkChanges.value
            dir.mkdirs()
            val cores = Runtime.getRuntime().availableProcessors()
            val d = DepotDownloader(
                steamClient = client,
                licenses = SteamSession.licenses.value,
                debug = false,
                useLanCache = false,
                maxDownloads = (cores * 1.5).toInt().coerceAtLeast(1),
                maxDecompress = (cores * 0.5).toInt().coerceAtLeast(1),
                androidEmulation = true,
                parentJob = null,
                skipLargeFileAllocation = true,
            )
            downloader = d
            running[app.id] = d
            val perDepot = ConcurrentHashMap<Int, Long>()
            val pctDepot = ConcurrentHashMap<Int, Float>()
            val startedAt = System.currentTimeMillis()
            val lastData = AtomicLong(0L)
            var lastT = startedAt
            var lastBytes = 0L
            var speed = 0.0
            val lock = Any()
            d.addListener(object : IDownloadListener {
                override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                    lastData.set(System.currentTimeMillis())
                    perDepot.merge(depotId, uncompressedBytes) { a, b -> maxOf(a, b) }
                    pctDepot.merge(depotId, depotPercentComplete) { a, b -> maxOf(a, b) }
                    if (app.id in pauseRequested) return
                    val got = perDepot.values.sum()
                    synchronized(lock) {
                        val now = System.currentTimeMillis()
                        if (now - lastT < 500) return
                        val inst = (got - lastBytes) * 1000.0 / (now - lastT)
                        speed = if (speed <= 0) inst else speed * 0.7 + inst * 0.3
                        lastT = now; lastBytes = got
                        // No size from Steam: estimate it from how far the depots are.
                        val avgPct = pctDepot.values.average().toFloat()
                        val total = if (app.sizeBytes > 0) app.sizeBytes
                            else if (avgPct > 0.02f) (got / avgPct).toLong() else 0L
                        val done = if (total > 0) minOf(base + got, total) else base + got
                        j = j.copy(phase = Phase.DOWNLOADING, done = done, total = maxOf(total, done), speed = speed.toLong().coerceAtLeast(0))
                        publish(j)
                        show(j)
                    }
                }

                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    lastData.set(System.currentTimeMillis())
                }

                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    failure = error
                }
            })
            d.add(AppItem(
                appId = app.id,
                installDirectory = dir.absolutePath,
                branch = "public",
                os = "windows",
                downloadAllArchs = true,
            ))
            d.finishAdding()
            j = j.copy(phase = Phase.DOWNLOADING)
            publish(j)
            show(j)
            val completion = d.getCompletion()
            while (true) {
                try {
                    completion.get(1, TimeUnit.SECONDS)
                    end = if (failure == null) End.DONE else End.FAILED
                    break
                } catch (_: TimeoutException) {
                } catch (e: ExecutionException) {
                    if (failure == null) failure = e.cause ?: e
                    end = End.STALLED
                    break
                }
                if (app.id in pauseRequested || job(app.id) == null) { end = End.PAUSED; break }
                val now = System.currentTimeMillis()
                val last = lastData.get()
                val quiet = if (last == 0L) now - startedAt > FIRST_DATA_MS else now - last > STALL_MS
                if (quiet || SteamSession.networkChanges.value != network) { end = End.STALLED; break }
            }
        } catch (t: Throwable) {
            if (failure == null) failure = t
            end = End.STALLED
        } finally {
            // close() can hang on a dead connection: do it aside, wait a little for its writes to stop.
            downloader?.let { d ->
                val closer = Thread { runCatching { d.close() } }.apply { isDaemon = true; start() }
                runCatching { closer.join(10_000L) }
            }
            running.remove(app.id)
        }
        return end to failure
    }

    /** Resolves [rel] ("bin/Game.exe") inside [dir] ignoring letter case. */
    private fun resolve(dir: File, rel: String): File? {
        var cur = dir
        for (part in rel.split('/').filter { it.isNotEmpty() && it != "." }) {
            val exact = File(cur, part)
            cur = if (exact.exists()) exact else cur.listFiles()?.firstOrNull { it.name.equals(part, true) } ?: return null
        }
        return cur.takeIf { it.isFile }
    }

    private val NOT_GAME = listOf("unins", "crash", "redist", "setup", "install", "dxsetup", "vcredist", "vc_redist",
        "dotnet", "directx", "launcherhelper", "cefprocess", "helper", "reporter", "easyanticheat", "be_service")

    /** The game's exe (relative to [dir]) and its arguments: Steam's launch entry, else the biggest exe near the top. */
    private fun findExe(dir: File, app: SteamApp): Pair<String, String> {
        for (l in app.launches) {
            val f = resolve(dir, l.exe) ?: continue
            if (f.extension.equals("exe", true)) return f.relativeTo(dir).path to l.args
        }
        val exes = ArrayList<File>()
        fun scan(d: File, depth: Int) {
            d.listFiles()?.forEach { f ->
                if (Files.isSymbolicLink(f.toPath())) return@forEach
                if (f.isDirectory && depth < 3 && !f.name.startsWith("_")) scan(f, depth + 1)
                else if (f.isFile && f.extension.equals("exe", true) && NOT_GAME.none { f.name.lowercase(Locale.ROOT).contains(it) }) exes += f
            }
        }
        scan(dir, 0)
        val best = exes.maxByOrNull { it.length() } ?: return "" to ""
        return best.relativeTo(dir).path to ""
    }

    /** Deletes a folder tree without ever following a link out of it. */
    private fun deleteTree(f: File) {
        if (Files.isSymbolicLink(f.toPath())) { f.delete(); return }
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    private fun shortcutName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Game" }

    /** Shortcuts (any container) that start this game. */
    private fun shortcutsOf(context: Context, dir: String): List<File> =
        ContainerManager(context).containers.flatMap { c ->
            c.desktopDir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty().filter { f ->
                runCatching { f.readText().contains(dir) }.getOrDefault(false)
            }
        }

    fun shortcutCount(context: Context, appId: Int): Int =
        _installed.value[appId]?.let { shortcutsOf(context, it.dir).size } ?: 0

    /** Removes the game's files and its shortcuts. */
    fun uninstall(context: Context, appId: Int, fallbackDir: File? = null) {
        load(context)
        val i = _installed.value[appId]
        val dir = i?.dir?.let(::File) ?: fallbackDir ?: return
        if (!dir.canonicalPath.startsWith(root().canonicalPath + File.separator)) return // only ever inside Games/Steam
        shortcutsOf(context, dir.absolutePath).forEach { it.delete() }
        deleteTree(dir)
        Goldberg.forget(dir.absolutePath)
        _installed.value = _installed.value - appId
        _jobs.value = _jobs.value - appId
        saveRegistry(context)
    }

    private fun wineHome(context: Context, container: Container): String {
        val imagefs = File(context.filesDir, "imagefs").absolutePath.trimEnd('/')
        val root = container.rootDir.absolutePath.trimEnd('/')
        return if (root.startsWith(imagefs)) root.substring(imagefs.length) else "/home/" + ImageFs.USER
    }

    /** Adds the game to [container]'s library; returns the exe (for the components check). */
    fun addShortcut(context: Context, container: Container, appId: Int): File? {
        val i = _installed.value[appId] ?: return null
        val exe = i.exeFile.takeIf { it.isFile } ?: return null
        val name = shortcutName(i.name)
        val desktopDir = container.desktopDir.apply { mkdirs() }
        val desktop = File(desktopDir, "$name.desktop")
        val prefix = wineHome(context, container) + "/.wine"
        val text = buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(name).append('\n')
            append("Exec=env WINEPREFIX=\"").append(prefix).append("\" wine \"").append(exe.absolutePath).append("\"\n")
            append("Type=Application\n")
            append("Icon=").append(name).append('\n')
            append("container_id:").append(container.id).append('\n')
            append("\n[Extra Data]\n")
            append("steamAppId=").append(appId).append('\n')
            if (i.args.isNotBlank()) append("execArgs=").append(i.args).append('\n')
        }
        desktop.writeText(text)
        runCatching {
            val icons = container.getIconsDir(64).apply { mkdirs() }
            ExeIconExtractor.extractIcon(exe, File(icons, "$name.png"))
        }
        return exe
    }

    /** Saves the game's Steam library picture as its cover (YurNLator/covers/<name>.png). */
    fun saveCover(appId: Int, bytes: ByteArray) {
        val i = _installed.value[appId] ?: return
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "YurNLator/covers").apply { mkdirs() }
            File(dir, shortcutName(i.name) + ".png").writeBytes(bytes)
        }
    }

    fun containers(context: Context): List<Container> = ContainerManager(context).containers
}
