package com.winlator.cmod.core

import android.util.Log
import com.winlator.cmod.container.Container
import java.io.File
import java.util.Locale

/**
 * Games on a memory card or USB drive: Wine reaches a file only through a drive of the container.
 * A game added from a card (or moved to another container) whose container had no drive for that
 * card failed with "path not found". [ensureMapped] gives the card a free letter (E..Y) when no
 * drive covers it, or points a drive that names the same card by its other path (/storage/X vs
 * /mnt/media_rw/X) at the path the game uses.
 */
object ExternalDrives {
    private const val TAG = "ExternalDrives"

    private fun norm(p: String) = p.trimEnd('/').ifEmpty { "/" }

    /** "/storage/88F8-5DB7" or "/mnt/media_rw/88F8-5DB7" for a file on a removable volume, else null. */
    @JvmStatic
    fun volumeRoot(file: File): File? {
        val path = norm(file.absolutePath)
        for (base in listOf("/storage/", "/mnt/media_rw/")) {
            if (!path.startsWith(base)) continue
            val volume = path.substring(base.length).substringBefore('/')
            if (volume.isEmpty() || volume == "emulated" || volume == "self") return null
            return File(base + volume)
        }
        return null
    }

    /** True when [file] is (now) on one of [container]'s drives; false when no letter is free. */
    @JvmStatic
    fun ensureMapped(container: Container, file: File): Boolean {
        val path = norm(file.absolutePath)
        val driveC = norm(File(container.rootDir, ".wine/drive_c").absolutePath)
        if (path == driveC || path.startsWith("$driveC/")) return true
        val volume = volumeRoot(file) ?: return true
        val volumeName = volume.name
        val used = HashSet<Char>()
        val drives = ArrayList<Pair<Char, String>>()
        for (d in container.drivesIterator()) {
            val letter = d[0]?.trim()?.uppercase(Locale.ROOT)?.firstOrNull() ?: continue
            val target = d[1]?.let { norm(it) } ?: continue
            drives += letter to target
            used += letter
        }
        if (drives.any { (_, t) -> path == t || path.startsWith("$t/") }) return true

        // A drive already on this card under its other path: point it at the path the game uses.
        val sameCard = drives.firstOrNull { (_, t) -> volumeRoot(File(t))?.name == volumeName }
        val letter = sameCard?.first ?: ('E'..'Y').firstOrNull { it !in used && it != 'Z' } ?: return false
        val rebuilt = StringBuilder()
        for ((l, t) in drives) if (l != letter) rebuilt.append(l).append(':').append(t)
        rebuilt.append(letter).append(':').append(norm(volume.absolutePath))
        container.drives = rebuilt.toString()
        container.saveData()
        WineUtils.createDosdevicesSymlinks(container)
        Log.i(TAG, "${container.name}: $letter: -> ${volume.absolutePath} for $path")
        return true
    }

    /** For a library entry about to start: its program's path as written in the entry. */
    @JvmStatic
    fun ensureMappedFor(container: Container, shortcutPath: String?): Boolean {
        val p = shortcutPath?.trim()?.trim('"')?.trim() ?: return true
        if (!p.startsWith("/")) return true
        return ensureMapped(container, File(p))
    }
}
