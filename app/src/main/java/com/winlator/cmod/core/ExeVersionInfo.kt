package com.winlator.cmod.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the game's own name from a Windows .exe: the ProductName (or FileDescription) of its
 * version resource, e.g. hl2.exe -> "Half-Life 2", TombRaider.exe -> "Tomb Raider".
 * Never throws; null when the file has no usable version info.
 */
object ExeVersionInfo {
    private const val RT_VERSION = 16

    /** A name fit for a shortcut, or null (then the caller falls back to the file / folder name). */
    @JvmStatic
    fun gameName(exe: File): String? {
        val strings = runCatching { read(exe) }.getOrNull() ?: return null
        for (key in listOf("ProductName", "FileDescription")) {
            val v = strings[key]?.let(::clean) ?: continue
            if (usable(v)) return v
        }
        return null
    }

    private val shown = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Library name of a shortcut: a shortcut still named after its exe ("hl2", "TombRaider")
     * shows the game's own name instead; any other (user-chosen) name stays.
     */
    @JvmStatic
    fun displayName(name: String, execPath: String?): String {
        val path = execPath?.trim()?.removePrefix("\"")?.let { p ->
            val end = p.lowercase().indexOf(".exe")
            if (end < 0) null else p.substring(0, end + 4)
        } ?: return name
        if (!path.startsWith("/")) return name
        val exe = File(path)
        if (!exe.nameWithoutExtension.equals(name, ignoreCase = true)) return name
        return shown.getOrPut(path) { gameName(exe) ?: name }
    }

    private fun clean(s: String): String = s
        .replace(Regex("[™®©]"), "")
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // Engine / tool names and generic descriptions say nothing about the game.
    private val generic = Regex(
        "(?i)^(launcher|game|application|setup|installer|bootstrapper|unity player|unityplayer|" +
            "unreal engine|ue4|ue5|ue4 game|ue5 game|source engine|directx|microsoft.*|windows.*|" +
            ".*\\.exe|.*shipping.*|crash ?report.*|easy ?anti.?cheat.*|battleye.*|redist.*)$"
    )

    private fun usable(s: String): Boolean = s.length in 2..60 && !generic.matches(s) && s.any { it.isLetter() }

    /** StringFileInfo key -> value of the first language block. */
    private fun read(exe: File): Map<String, String>? {
        RandomAccessFile(exe, "r").use { f ->
            if (f.length() < 0x40) return null
            fun u16(at: Long): Int { f.seek(at); return f.read() or (f.read() shl 8) }
            fun u32(at: Long): Long { f.seek(at); val b = ByteArray(4); f.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL }
            if (u16(0) != 0x5A4D) return null
            val pe = u32(0x3C)
            if (pe <= 0 || pe > f.length() - 24 || u32(pe) != 0x4550L) return null
            val sections = u16(pe + 6)
            val optSize = u16(pe + 20)
            val opt = pe + 24
            val magic = u16(opt)
            val dirBase = opt + if (magic == 0x20B) 112 else 96
            val resRva = u32(dirBase + 2 * 8)
            if (resRva == 0L) return null
            val secTable = opt + optSize
            data class Sec(val va: Long, val size: Long, val raw: Long)
            val secs = (0 until sections).map { i ->
                val s = secTable + i * 40L
                Sec(u32(s + 12), maxOf(u32(s + 8), u32(s + 16)), u32(s + 20))
            }
            fun off(rva: Long): Long? = secs.firstOrNull { rva >= it.va && rva < it.va + it.size }?.let { it.raw + rva - it.va }
            val root = off(resRva) ?: return null

            // Resource directory: entry (id or name, offset with the high bit = subdirectory).
            fun entries(dir: Long): List<Pair<Long, Long>> {
                val n = u16(dir + 12) + u16(dir + 14)
                return (0 until minOf(n, 256)).map { i -> u32(dir + 16 + i * 8L) to u32(dir + 20 + i * 8L) }
            }
            val type = entries(root).firstOrNull { it.first == RT_VERSION.toLong() } ?: return null
            if (type.second and 0x80000000L == 0L) return null
            val nameDir = root + (type.second and 0x7FFFFFFF)
            val name = entries(nameDir).firstOrNull() ?: return null
            var node = name.second
            var dir = nameDir
            if (node and 0x80000000L != 0L) {
                dir = root + (node and 0x7FFFFFFF)
                node = entries(dir).firstOrNull()?.second ?: return null
            }
            val dataEntry = root + (node and 0x7FFFFFFF)
            val dataRva = u32(dataEntry)
            val dataSize = u32(dataEntry + 4).toInt()
            if (dataSize <= 0 || dataSize > 1 shl 20) return null
            val at = off(dataRva) ?: return null
            val blob = ByteArray(dataSize)
            f.seek(at); f.readFully(blob)
            return strings(blob)
        }
    }

    /** Finds "Key\0" (UTF-16LE) entries of the String table and their values. */
    private fun strings(b: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in listOf("ProductName", "FileDescription", "InternalName", "OriginalFilename")) {
            val pattern = ByteArray((key.length + 1) * 2).also { p -> key.forEachIndexed { i, c -> p[i * 2] = c.code.toByte() } }
            var i = indexOf(b, pattern, 0)
            while (i >= 6) {
                // String struct: wLength, wValueLength, wType, szKey, padding to 4, Value.
                val start = i - 6
                if (start % 4 != 0) { i = indexOf(b, pattern, i + 1); continue }
                val valueLen = (b[start + 2].toInt() and 0xFF) or ((b[start + 3].toInt() and 0xFF) shl 8)
                var v = i + pattern.size
                while ((v - start) % 4 != 0) v++
                if (valueLen > 0 && v < b.size) {
                    val sb = StringBuilder()
                    var p = v
                    while (p + 1 < b.size) {
                        val ch = ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)).toChar()
                        if (ch == '\u0000') break
                        sb.append(ch); p += 2
                    }
                    if (sb.isNotBlank()) { out[key] = sb.toString(); break }
                }
                i = indexOf(b, pattern, i + 2)
            }
        }
        return out
    }

    private fun indexOf(b: ByteArray, p: ByteArray, from: Int): Int {
        var i = from
        outer@ while (i <= b.size - p.size) {
            for (j in p.indices) if (b[i + j] != p[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
