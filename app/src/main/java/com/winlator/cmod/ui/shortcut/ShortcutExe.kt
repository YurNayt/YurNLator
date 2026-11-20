package com.winlator.cmod.ui.shortcut

import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import java.io.File
import java.util.Locale

/** Which program a library entry starts, and changing it. */
object ShortcutExe {
    /** The drives a game can be on: C: (the container's own) and the container's mapped drives. */
    fun drives(container: Container): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        out += "C:" to File(container.rootDir, ".wine/drive_c")
        for (d in container.drivesIterator()) {
            val letter = d[0]?.trim()?.uppercase(Locale.ROOT) ?: continue
            val path = d[1]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (letter == "C") continue
            out += "$letter:" to File(path)
        }
        return out
    }

    /** The program the entry starts now, as a file on the phone; null when it can't be found. */
    fun current(shortcut: Shortcut): File? {
        val p = shortcut.path?.trim()?.trim('"')?.trim() ?: return null
        if (p.startsWith("/")) return File(p).takeIf { it.exists() }
        if (p.length < 3 || p[1] != ':') return null
        val letter = p.substring(0, 1).uppercase(Locale.ROOT)
        val rest = p.substring(2).replace('\\', '/').trimStart('/')
        val root = drives(shortcut.container).firstOrNull { it.first == "$letter:" }?.second ?: return null
        return File(root, rest).takeIf { it.exists() }
    }

    /** The Windows path of [file], "D:\Games\x.exe", or its phone path when no drive holds it. */
    fun windowsPath(container: Container, file: File): String {
        val abs = file.absolutePath
        for ((letter, root) in drives(container)) {
            val r = root.absolutePath.trimEnd('/')
            if (abs == r || abs.startsWith("$r/")) return letter + "\\" + abs.substring(r.length).trimStart('/').replace('/', '\\')
        }
        return abs
    }

    /**
     * Points the entry at [exe]: its Exec line starts [exe] (same Wine prefix), and a fixed
     * working folder is dropped so the game starts in [exe]'s own folder. Its settings stay.
     */
    fun change(shortcut: Shortcut, exe: File): Boolean {
        val lines = shortcut.file.readLines()
        var changed = false
        val out = ArrayList<String>(lines.size)
        var section = ""
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("[")) section = t
            if (section == "[Desktop Entry]" && t.startsWith("Exec=")) {
                val at = t.lastIndexOf("wine ")
                val head = if (at >= 0) t.substring(0, at + 5) else "Exec=wine "
                out += head + "\"" + exe.absolutePath + "\""
                changed = true
                continue
            }
            if (section == "[Desktop Entry]" && t.startsWith("Path=")) continue
            out += line
        }
        if (!changed) return false
        shortcut.file.writeText(out.joinToString("\n", postfix = "\n"))
        return true
    }
}
