package com.winlator.cmod.core

import android.os.Environment
import android.util.Log
import com.winlator.cmod.container.Container
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copies of a container's game saves outside the app: Download/YurNLator/Saves/<container>/ (drive
 * D: in Windows), so they survive a Proton switch, removing the container, another APK variant
 * (each one is its own app with its own containers) or uninstalling.
 *
 * Saved: the Windows user's data folders (Documents incl. My Games, Saved Games, AppData Roaming /
 * Local / LocalLow, Public Documents), without caches (shader / browser caches, temp files, crash
 * dumps) and files over [MAX_FILE]. Symlinks are never followed (a linked folder could be the whole
 * phone storage). A backup equal to the newest one is skipped; the newest [KEEP] stay.
 */
object SaveBackup {
    private const val TAG = "SaveBackup"
    private const val KEEP = 5
    private const val MAX_FILE = 256L * 1024 * 1024
    private const val STAMP = "yyyy-MM-dd_HH-mm-ss"

    /** Folders under drive_c/users/<user>/ that hold saves. */
    private val USER_FOLDERS = listOf("Documents", "Saved Games", "AppData/Roaming", "AppData/Local", "AppData/LocalLow")

    /** Folder names left out anywhere (lower case). */
    private val SKIP_DIRS = setOf(
        "temp", "tmp", "cache", "caches", "cache2", "gpucache", "shadercache", "shader_cache", "shaders_cache",
        "d3dscache", "nvidia", "nvidia corporation", "amd", "crashdumps", "crashes", "crashreports", "inetcache",
        "webcache", "code cache", "dawncache", "grshadercache", "mesa_shader_cache", "dxvk-cache", "vkd3d-proton",
        "packages", "logs", "microsoft", "wine_gecko", "wine-mono", "fontconfig", ".cache",
    )
    private val SKIP_EXT = setOf("log", "dmp", "tmp", "dxvk-cache", "cache")

    enum class Reason(val tag: String) { SESSION("session"), PROTON("proton"), MANUAL("manual"), REMOVE("remove"), RESTORE("before-restore") }

    data class Backup(val file: File, val date: Date, val reason: String) {
        val size: Long get() = file.length()
    }

    /** Download/YurNLator/Saves. */
    @JvmStatic
    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator/Saves")

    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().ifEmpty { "container" }

    /** One folder per container: "<name> (<id>)". */
    @JvmStatic
    fun dirFor(container: Container): File = File(root(), "${safe(container.name)} (${container.id})")

    private fun usersDir(container: Container) = File(container.rootDir, ".wine/drive_c/users")

    /** Every save file of the container: (path inside the zip, file). */
    private fun collect(container: Container): List<Pair<String, File>> {
        val users = usersDir(container)
        val out = ArrayList<Pair<String, File>>()
        val roots = ArrayList<Pair<String, File>>()
        users.listFiles()?.forEach { u ->
            if (!u.isDirectory || Files.isSymbolicLink(u.toPath())) return@forEach
            if (u.name.equals("Public", true)) roots += "Public/Documents" to File(u, "Documents")
            else USER_FOLDERS.forEach { f -> roots += "${u.name}/$f" to File(u, f) }
        }
        fun walk(prefix: String, dir: File) {
            if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return
            dir.listFiles()?.forEach { f ->
                if (Files.isSymbolicLink(f.toPath())) return@forEach
                val path = "$prefix/${f.name}"
                if (f.isDirectory) {
                    if (f.name.lowercase(Locale.ROOT) !in SKIP_DIRS) walk(path, f)
                } else if (f.isFile && f.length() <= MAX_FILE && f.extension.lowercase(Locale.ROOT) !in SKIP_EXT) {
                    out += path to f
                }
            }
        }
        roots.forEach { (p, d) -> walk(p, d) }
        return out
    }

    /** What the files are now (to skip a backup equal to the last one). */
    private fun fingerprint(files: List<Pair<String, File>>): String =
        files.sortedBy { it.first }.joinToString("\n") { (p, f) -> "$p|${f.length()}|${f.lastModified() / 1000}" }.hashCode().toString(16)

    /** The container's backups, newest first. */
    @JvmStatic
    fun list(container: Container): List<Backup> {
        val fmt = SimpleDateFormat(STAMP, Locale.US)
        return dirFor(container).listFiles { f -> f.isFile && f.name.endsWith(".zip") }.orEmpty().mapNotNull { f ->
            val stamp = f.name.take(STAMP.length)
            val date = runCatching { fmt.parse(stamp) }.getOrNull() ?: return@mapNotNull null
            Backup(f, date, f.name.removeSuffix(".zip").drop(STAMP.length).trim('_'))
        }.sortedByDescending { it.date }
    }

    /**
     * Backs up the container's saves (blocking: call off the main thread). Null when there was
     * nothing to save, the saves didn't change since the newest backup, or writing failed.
     */
    @JvmStatic
    @Synchronized
    fun backup(container: Container, reason: Reason): File? = runCatching {
        val files = collect(container)
        if (files.isEmpty()) return null
        val dir = dirFor(container).apply { mkdirs() }
        val print = fingerprint(files)
        val printFile = File(dir, ".last")
        if (reason != Reason.MANUAL && printFile.isFile && printFile.readText() == print && list(container).isNotEmpty()) return null
        val name = SimpleDateFormat(STAMP, Locale.US).format(Date()) + "_" + reason.tag + ".zip"
        val tmp = File(dir, "$name.part")
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
            val buf = ByteArray(64 * 1024)
            for ((path, f) in files) {
                val entry = ZipEntry(path).apply { time = f.lastModified() }
                runCatching {
                    FileInputStream(f).use { input ->
                        zip.putNextEntry(entry)
                        while (true) { val n = input.read(buf); if (n < 0) break; zip.write(buf, 0, n) }
                        zip.closeEntry()
                    }
                }.onFailure { Log.w(TAG, "skipped $path: ${it.message}") }
            }
        }
        val out = File(dir, name)
        if (!tmp.renameTo(out)) { tmp.delete(); return null }
        printFile.writeText(print)
        list(container).drop(KEEP).forEach { it.file.delete() }
        Log.i(TAG, "${container.name}: ${files.size} files -> ${out.path}")
        out
    }.onFailure { Log.w(TAG, "backup of ${container.name} failed", it) }.getOrNull()

    /** Background backup (after a game session, before removing a container…). */
    @JvmStatic
    fun backupAsync(container: Container, reason: Reason) {
        Thread({ backup(container, reason) }, "SaveBackup").apply { isDaemon = false; start() }
    }

    /**
     * Puts a backup's files back into the container (files that exist are overwritten, others
     * stay). The current saves are backed up first. Blocking. Returns the number of files restored.
     */
    @JvmStatic
    fun restore(container: Container, backup: Backup): Int {
        backup(container, Reason.RESTORE)
        val users = usersDir(container).canonicalFile
        var count = 0
        ZipInputStream(FileInputStream(backup.file).buffered()).use { zip ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(users, e.name).canonicalFile
                if (!target.path.startsWith(users.path + File.separator)) continue // stays inside drive_c/users
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> while (true) { val n = zip.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
                if (e.time > 0) target.setLastModified(e.time)
                count++
            }
        }
        return count
    }
}
