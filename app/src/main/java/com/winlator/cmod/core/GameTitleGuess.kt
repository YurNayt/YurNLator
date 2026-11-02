package com.winlator.cmod.core

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Works out what game a shortcut is, for cover art lookups: the exe name alone is often
 * cryptic ("speed2.exe" is Need for Speed Underground 2, not Speedball 2).
 *
 * Candidates, best first:
 *  1. ProductName / FileDescription from the exe's version info ("Need for Speed Underground 2")
 *  2. the game folder ("…/Need for Speed Underground 2/speed2.exe"), skipping bin / x64 / Binaries…
 *  3. the shortcut name
 * A search result is accepted only if its name shares enough words with the query.
 */
object GameTitleGuess {
    private val genericFolders = setOf(
        "bin", "bin32", "bin64", "binaries", "binary", "win32", "win64", "x86", "x64", "x86_64", "amd64",
        "game", "games", "release", "retail", "shipping", "system", "system32", "common", "launcher",
        "program files", "program files (x86)", "drive_c", "steamapps", "steam", "gog games", "gog",
        "epic games", "data", "exe", "run", "app", "client", "windows", "retail_x64", "final",
        "download", "downloads", "winlator", "storage", "emulated", "0", "sdcard", "home", "users", "public"
    )
    private val genericProduct = Regex(
        "^(microsoft|windows|directx|setup|install|installer|launcher|unity|unreal|game|application|" +
            "microsoft® windows® operating system|nvidia|amd|steam|ubisoft connect|ea app|origin)\\b.*",
        RegexOption.IGNORE_CASE
    )

    @JvmStatic
    fun candidates(shortcutName: String?, exe: File?): List<String> {
        val out = linkedSetOf<String>()
        if (exe != null && exe.isFile) {
            val info = runCatching { versionStrings(exe) }.getOrDefault(emptyMap())
            listOf("ProductName", "FileDescription").mapNotNull { info[it] }.forEach { addClean(out, it, true) }
            var dir = exe.parentFile
            var depth = 0
            while (dir != null && depth < 4) {
                val n = dir.name
                if (n.lowercase(Locale.ENGLISH) !in genericFolders && n.length > 1) {
                    addClean(out, n, false)
                    break
                }
                dir = dir.parentFile
                depth++
            }
        }
        shortcutName?.let { addClean(out, it, false) }
        return out.toList()
    }

    private fun addClean(out: MutableSet<String>, raw: String, product: Boolean) {
        var s = raw.replace('_', ' ').replace('.', ' ')
            .replace(Regex("[™®©]"), "")
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
            .replace(Regex("(?i)\\b(goty|repack|fitgirl|dodi|portable|x64|x86|win64|win32|v\\d+(\\.\\d+)*)\\b"), " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("\\s+"), " ").trim()
        if (s.length < 3) return
        if (product && genericProduct.matches(s)) return
        if (s.lowercase(Locale.ENGLISH).endsWith(" exe")) s = s.dropLast(4)
        out += s
    }

    private fun words(s: String): List<String> {
        val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")
        val stop = setOf("the", "of", "a", "an", "and", "edition", "game", "for")
        return s.lowercase(Locale.ENGLISH)
            .replace("&", " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .map { roman[it] ?: it }
            .filter { it !in stop }
    }

    /** 0…1: how well a search result name matches the query (all query words found -> 1). */
    @JvmStatic
    fun score(query: String, result: String): Double {
        val q = words(query).toSet()
        val r = words(result).toSet()
        if (q.isEmpty() || r.isEmpty()) return 0.0
        val common = q.count { it in r }
        // Numbers (sequel numbers) must agree: "Underground 2" is not "Underground".
        val qNum = q.filter { it.all(Char::isDigit) }.toSet()
        val rNum = r.filter { it.all(Char::isDigit) }.toSet()
        if (qNum != rNum && (qNum.isNotEmpty() || rNum.isNotEmpty())) return common.toDouble() / (q.size + r.size)
        return common.toDouble() / maxOf(q.size, r.size)
    }

    /** Index of the best result for [query] (score ≥ 0.6), or -1. */
    @JvmStatic
    fun best(query: String, results: List<String>): Int {
        var bestIdx = -1
        var bestScore = 0.6
        results.forEachIndexed { i, name ->
            val s = score(query, name)
            if (s > bestScore || (bestIdx == -1 && s >= bestScore)) { bestIdx = i; bestScore = s }
        }
        return bestIdx
    }

    // --- PE version info (RT_VERSION / StringFileInfo) ---

    private fun versionStrings(exe: File): Map<String, String> = RandomAccessFile(exe, "r").use { f ->
        fun u16(o: Long): Int { f.seek(o); return f.read() or (f.read() shl 8) }
        fun u32(o: Long): Long { f.seek(o); val b = ByteArray(4); f.readFully(b)
            return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24) }
        if (u16(0) != 0x5A4D) return emptyMap()
        val pe = u32(0x3C)
        if (u32(pe) != 0x4550L) return emptyMap()
        val sections = u16(pe + 6)
        val optSize = u16(pe + 20)
        val opt = pe + 24
        val magic = u16(opt)
        val dirBase = opt + if (magic == 0x20B) 112 else 96
        val rsrcRva = u32(dirBase + 2 * 8)
        if (rsrcRva == 0L) return emptyMap()
        val secTable = opt + optSize
        fun rvaToOff(rva: Long): Long {
            for (i in 0 until sections) {
                val s = secTable + i * 40L
                val va = u32(s + 12); val size = maxOf(u32(s + 8), u32(s + 16)); val raw = u32(s + 20)
                if (rva >= va && rva < va + size) return rva - va + raw
            }
            return -1
        }
        val root = rvaToOff(rsrcRva)
        if (root < 0) return emptyMap()
        // Directory: 16-byte header, then (named + id) entries of 8 bytes.
        fun entries(dir: Long): List<Pair<Long, Long>> {
            val n = u16(dir + 12) + u16(dir + 14)
            return (0 until minOf(n, 4096)).map { i -> u32(dir + 16 + i * 8L) to u32(dir + 20 + i * 8L) }
        }
        val type = entries(root).firstOrNull { it.first == 16L } ?: return emptyMap()
        if (type.second and 0x80000000L == 0L) return emptyMap()
        val names = root + (type.second and 0x7fffffffL)
        val name = entries(names).firstOrNull() ?: return emptyMap()
        var node = name.second
        var level = names
        if (node and 0x80000000L != 0L) {
            level = root + (node and 0x7fffffffL)
            node = entries(level).firstOrNull()?.second ?: return emptyMap()
        }
        if (node and 0x80000000L != 0L) return emptyMap()
        val dataEntry = root + node
        val off = rvaToOff(u32(dataEntry))
        val size = u32(dataEntry + 4).toInt()
        if (off < 0 || size <= 0 || size > 1 shl 20) return emptyMap()
        val buf = ByteArray(size)
        f.seek(off); f.readFully(buf)
        val text = String(buf, Charsets.UTF_16LE)
        val out = HashMap<String, String>()
        for (key in listOf("ProductName", "FileDescription")) {
            val i = text.indexOf(key + "\u0000")
            if (i < 0) continue
            var j = i + key.length
            while (j < text.length && text[j] == '\u0000') j++
            val end = text.indexOf('\u0000', j).let { if (it < 0) text.length else it }
            val v = text.substring(j, end).trim()
            if (v.length >= 3 && v.all { it >= ' ' }) out[key] = v
        }
        out
    }
}
