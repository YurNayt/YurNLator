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
import java.util.concurrent.Executors

/**
 * Installs the account's Steam games with JavaSteam's depot downloader (GameNative's fork, as in
 * Bannerlator) into Download/YurNLator/Games/Steam/<folder>: outside the containers, so a game
 * survives removing a container and can be added to any of them. One download at a time; pausing
 * stops it and keeps the files, installing again continues (finished files are checked, not fetched).
 */
object SteamInstaller {
    private const val TAG = "YurnSteamInstall"

    enum class Phase { QUEUED, PREPARING, DOWNLOADING, PAUSED, FAILED, DONE }

    data class Job(val appId: Int, val name: String, val phase: Phase, val done: Long = 0, val total: Long = 0,
                   val speed: Long = 0, val error: String? = null) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val active: Boolean get() = phase == Phase.QUEUED || phase == Phase.PREPARING || phase == Phase.DOWNLOADING
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

    fun anyActive() = _jobs.value.values.any { it.active }

    fun install(context: Context, app: SteamApp) {
        val ctx = context.applicationContext
        load(ctx)
        if (job(app.id)?.active == true) return
        pauseRequested.remove(app.id)
        setJob(Job(app.id, app.name, Phase.QUEUED, total = app.sizeBytes))
        SteamDownloadService.start(ctx)
        queue.execute { run(ctx, app) }
    }

    fun pause(appId: Int) {
        pauseRequested += appId
        val j = job(appId) ?: return
        if (j.phase == Phase.QUEUED) setJob(j.copy(phase = Phase.PAUSED, speed = 0))
        running[appId]?.let { d -> Thread { runCatching { d.close() } }.start() }
    }

    /** Stops the download and deletes what was downloaded. */
    fun cancel(context: Context, app: SteamApp) {
        pause(app.id)
        Thread {
            while (running.containsKey(app.id)) Thread.sleep(200)
            if (_installed.value[app.id] == null) deleteTree(dirFor(app))
            _jobs.value = _jobs.value - app.id
            SteamDownloadService.update()
        }.start()
    }

    private fun run(context: Context, app: SteamApp) {
        if (app.id in pauseRequested) return
        var j = Job(app.id, app.name, Phase.PREPARING, total = app.sizeBytes)
        setJob(j)
        val dir = dirFor(app)
        var downloader: DepotDownloader? = null
        var failure: Throwable? = null
        try {
            if (!SteamSession.awaitOnline(context, 90_000L)) throw IllegalStateException("Steam: no connection")
            val client = SteamSession.client ?: throw IllegalStateException("Steam: no connection")
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
            var lastT = System.currentTimeMillis()
            var lastBytes = 0L
            var speed = 0.0
            val lock = Any()
            d.addListener(object : IDownloadListener {
                override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                    perDepot.merge(depotId, uncompressedBytes) { a, b -> maxOf(a, b) }
                    pctDepot.merge(depotId, depotPercentComplete) { a, b -> maxOf(a, b) }
                    val done = perDepot.values.sum()
                    synchronized(lock) {
                        val now = System.currentTimeMillis()
                        if (now - lastT < 500) return
                        val inst = (done - lastBytes) * 1000.0 / (now - lastT)
                        speed = if (speed <= 0) inst else speed * 0.7 + inst * 0.3
                        lastT = now; lastBytes = done
                        // No size from Steam: estimate it from how far the depots are.
                        val avgPct = pctDepot.values.average().toFloat()
                        val total = if (app.sizeBytes > 0) maxOf(app.sizeBytes, done)
                            else if (avgPct > 0.02f) (done / avgPct).toLong() else 0L
                        j = j.copy(phase = Phase.DOWNLOADING, done = done, total = total, speed = speed.toLong().coerceAtLeast(0))
                        setJob(j)
                    }
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
            setJob(j.copy(phase = Phase.DOWNLOADING))
            d.getCompletion().get()
        } catch (t: Throwable) {
            if (failure == null) failure = t
        } finally {
            running.remove(app.id)
            runCatching { downloader?.close() }
        }
        when {
            app.id in pauseRequested -> setJob(j.copy(phase = Phase.PAUSED, speed = 0))
            failure != null -> {
                val f = failure!!
                Log.w(TAG, "install ${app.id} failed", f)
                setJob(j.copy(phase = Phase.FAILED, speed = 0, error = (f.cause ?: f).message ?: f.javaClass.simpleName))
            }
            else -> {
                val (exe, args) = findExe(dir, app)
                if (exe.isNotEmpty()) runCatching { File(File(dir, exe).parentFile, "steam_appid.txt").takeIf { !it.exists() }?.writeText(app.id.toString()) }
                _installed.value = _installed.value + (app.id to Installed(app.id, app.name, dir.absolutePath, exe, args))
                saveRegistry(context)
                setJob(j.copy(phase = Phase.DONE, done = maxOf(j.done, j.total), speed = 0))
            }
        }
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
