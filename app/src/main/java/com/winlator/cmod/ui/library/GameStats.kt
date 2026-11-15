package com.winlator.cmod.ui.library

import android.content.Context
import android.os.Environment
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.session.LaunchConfig
import com.winlator.cmod.ui.uiStr
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What the game page shows as tiles: time played, launches, last launch (session/Playtime), the
 * game folder's size and the store it came from. Layout after Winlator Ludashi 5.0's game page
 * (itself after teldommm/WinLite).
 */
data class GameStats(
    val playtimeMillis: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMillis: Long = 0L,
    val sizeText: String? = null,
    /** "steam", "epic" or "". */
    val store: String = "",
)

object GameStatsLoader {
    private val BINARY_DIRS = setOf("win64", "win32", "x64", "x86", "bin", "bin32", "bin64", "binaries", "shipping", "retail")
    private const val MAX_ENTRIES = 250_000
    private const val MAX_SCAN_MS = 4_000L
    private const val STALE_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "game_size_cache"

    /** Cheap part (preferences): off the main thread. */
    fun load(context: Context, shortcut: Shortcut): GameStats {
        val p = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
        return GameStats(
            playtimeMillis = p.getLong(shortcut.name + "_playtime", 0L),
            playCount = p.getInt(shortcut.name + "_play_count", 0),
            lastPlayedMillis = p.getLong(shortcut.name + "_last_played", 0L),
            store = com.winlator.cmod.store.StoreOrigin.of(shortcut),
        )
    }

    /** The game folder's size, measured at most once a day (or when the folder changed). Off the main thread. */
    fun size(context: Context, shortcut: Shortcut): String? {
        val dir = gameFolder(context, shortcut) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(dir.path, null)?.split(';')?.takeIf { it.size >= 3 }?.let { parts ->
            val bytes = parts[0].toLongOrNull()
            val at = parts[2].toLongOrNull()
            if (bytes != null && at != null && System.currentTimeMillis() - at < STALE_MS && dir.lastModified() <= at)
                return textOf(bytes, parts[1] == "1")
        }
        val (bytes, truncated) = folderSize(dir)
        prefs.edit().putString(dir.path, "$bytes;${if (truncated) "1" else "0"};${System.currentTimeMillis()}").apply()
        return textOf(bytes, truncated)
    }

    private fun textOf(bytes: Long, truncated: Boolean): String? =
        if (bytes > 0) StringUtils.formatBytes(bytes) + if (truncated) "+" else "" else null

    /** The store's install folder, or the exe's folder (above Binaries/Win64…) when it's the game's own. */
    private fun gameFolder(context: Context, shortcut: Shortcut): File? {
        shortcut.getExtra("steamAppId").toIntOrNull()?.let { id ->
            com.winlator.cmod.store.steam.SteamInstaller.load(context)
            com.winlator.cmod.store.steam.SteamInstaller.installed.value[id]?.let { return File(it.dir) }
        }
        shortcut.getExtra("epicApp").takeIf { it.isNotEmpty() }?.let { app ->
            com.winlator.cmod.store.epic.EpicInstaller.load(context)
            com.winlator.cmod.store.epic.EpicInstaller.installed.value[app]?.let { return File(it.dir) }
        }
        val exe = LaunchConfig.exeFile(shortcut.path) ?: return null
        var dir = exe.parentFile ?: return null
        var up = 0
        while (up < 4 && dir.name.lowercase(Locale.ROOT) in BINARY_DIRS) { dir = dir.parentFile ?: break; up++ }
        // A folder that holds more than the game (Download, a drive, drive_c) is not measured.
        val broad = mutableListOf(Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        shortcut.container?.let { c ->
            broad += File(c.rootDir, ".wine/drive_c")
            for (d in c.drivesIterator()) if (d.size >= 2 && d[1] != null) broad += File(d[1])
        }
        val target = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        if (broad.any { runCatching { it.canonicalPath }.getOrDefault(it.path) == target }) return null
        return dir.takeIf { it.isDirectory }
    }

    private fun folderSize(root: File): Pair<Long, Boolean> {
        var total = 0L
        var entries = 0
        var truncated = false
        val deadline = System.currentTimeMillis() + MAX_SCAN_MS
        fun tick(): FileVisitResult {
            if (++entries > MAX_ENTRIES || ((entries and 255) == 0 && System.currentTimeMillis() > deadline)) {
                truncated = true
                return FileVisitResult.TERMINATE
            }
            return FileVisitResult.CONTINUE
        }
        runCatching {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = tick()
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return tick()
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return total to truncated
    }
}

internal fun formatPlaytime(ms: Long): String {
    val minutes = (ms / 60000L).coerceAtLeast(1L)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> uiStr(R.string.l_gd_hm, h.toInt(), m.toInt())
        h > 0 -> uiStr(R.string.l_gd_h, h.toInt())
        else -> uiStr(R.string.l_gd_m, m.toInt())
    }
}

internal fun formatLastPlayed(ms: Long): String {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val played = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)
    return SimpleDateFormat(if (played == year) "d MMM" else "d MMM yyyy", Locale.getDefault()).format(Date(ms))
}
