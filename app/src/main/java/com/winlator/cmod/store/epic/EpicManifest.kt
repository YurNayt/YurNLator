package com.winlator.cmod.store.epic

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Inflater

/**
 * An Epic build manifest (which files make up the game and from which chunks), binary or the older
 * JSON form. Port of Bannerlator's EpicDownloadManager parser (GPL-3.0), itself after Legendary's
 * models/manifest.py.
 */
class EpicManifest(
    val chunkDir: String,
    val encrypted: Boolean,
    val chunks: Map<String, Chunk>,
    val files: List<FileEntry>,
    val launchExe: String,
    val launchCommand: String,
) {
    class Chunk(val guid: IntArray, val hash: Long, val group: Int, val windowSize: Int, val fileSize: Long, val secret: IntArray?) {
        val key: String get() = guidKey(guid)

        /** Path under the CDN build folder. */
        fun path(dir: String): String {
            val sub = "%02d".format(group)
            if (dir == "ChunksV5") {
                val plain = secret == null || (secret[0] or secret[1] or secret[2] or secret[3]) == 0
                val h = b64(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(hash).array())
                return "$dir/${if (plain) "plain" else b64(ints(secret!!))}/$sub/${h}_${b64(ints(guid))}.chunk"
            }
            return "$dir/$sub/%016X_%s.chunk".format(hash, guidKey(guid))
        }
    }

    class Part(val guid: String, val offset: Int, val size: Int)

    class FileEntry(val name: String, val sha1: ByteArray?, val parts: List<Part>) {
        val size: Long get() = parts.sumOf { it.size.toLong() and 0xFFFFFFFFL }
    }

    val installSize: Long get() = files.sumOf { it.size }

    companion object {
        fun guidKey(g: IntArray) = "%08X%08X%08X%08X".format(g[0], g[1], g[2], g[3])

        private fun ints(v: IntArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putInt(it) } }.array()
        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        private fun dirFor(version: Int) = when {
            version >= 22 -> "ChunksV5"; version >= 15 -> "ChunksV4"; version >= 6 -> "ChunksV3"; version >= 3 -> "ChunksV2"; else -> "Chunks"
        }

        private fun fstring(b: ByteBuffer): String {
            val len = b.int
            if (len == 0) return ""
            return if (len < 0) {
                val bytes = ByteArray((-len - 1) * 2); b.get(bytes); b.short
                String(bytes, Charsets.UTF_16LE)
            } else {
                val bytes = ByteArray(len - 1); b.get(bytes); b.get()
                String(bytes, Charsets.US_ASCII)
            }
        }

        fun parse(bytes: ByteArray): EpicManifest {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 4 || buf.int != 0x44BEC00C) return parseJson(bytes)
            val headerSize = buf.int
            val sizeUncompressed = buf.int
            buf.int // compressed size
            buf.position(buf.position() + 20)
            val storedAs = buf.get().toInt() and 0xFF
            val version = buf.int
            buf.position(headerSize)
            var body = ByteArray(buf.remaining()).also { buf.get(it) }
            if (storedAs and 1 != 0) {
                val out = ByteArray(sizeUncompressed)
                val inf = Inflater()
                inf.setInput(body)
                val got = inf.inflate(out)
                inf.end()
                require(got == sizeUncompressed) { "manifest: bad size" }
                body = out
            }
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            // Meta
            val metaStart = b.position()
            val metaSize = b.int
            b.get() // data version
            val featureLevel = b.int
            b.get() // is file data
            b.int // app id
            fstring(b) // app name
            fstring(b) // build version
            val launchExe = fstring(b)
            val launchCommand = fstring(b)
            val chunkVersion = if (featureLevel > 0) featureLevel else version
            val dir = dirFor(chunkVersion)
            b.position(metaStart + metaSize)
            // Chunks
            val cdlStart = b.position()
            val cdlSize = b.int
            b.get()
            val n = b.int
            val guids = Array(n) { IntArray(4) { b.int } }
            val hashes = LongArray(n) { b.long }
            repeat(n) { b.position(b.position() + 20) } // chunk sha1s
            val groups = IntArray(n) { b.get().toInt() and 0xFF }
            val windows = IntArray(n) { b.int }
            val sizes = LongArray(n) { b.long }
            val secrets: Array<IntArray>? = if (chunkVersion >= 22 && b.position() + n.toLong() * 16 <= cdlStart + cdlSize)
                Array(n) { IntArray(4) { b.int } } else null
            b.position(cdlStart + cdlSize)
            val chunks = LinkedHashMap<String, Chunk>(n * 2)
            for (i in 0 until n) Chunk(guids[i], hashes[i], groups[i], windows[i], sizes[i], secrets?.get(i)).let { chunks[it.key] = it }
            // Files
            val fmlStart = b.position()
            val fmlSize = b.int
            b.get()
            val fc = b.int
            val names = Array(fc) { fstring(b) }
            repeat(fc) { fstring(b) } // symlink targets
            val shas = Array(fc) { ByteArray(20).also { b.get(it) } }
            b.position(b.position() + fc) // flags
            repeat(fc) { val t = b.int; repeat(t) { fstring(b) } } // install tags
            val files = ArrayList<FileEntry>(fc)
            for (i in 0 until fc) {
                val pc = b.int
                val parts = ArrayList<Part>(pc)
                repeat(pc) {
                    val start = b.position()
                    val structSize = b.int
                    val g = IntArray(4) { b.int }
                    val off = b.int
                    val sz = b.int
                    parts += Part(guidKey(g), off, sz)
                    b.position(start + structSize)
                }
                files += FileEntry(names[i], shas[i], parts)
            }
            b.position(fmlStart + fmlSize)
            return EpicManifest(dir, storedAs and 2 != 0, chunks, files, launchExe, launchCommand)
        }

        /** Number in the JSON manifests' "blob" form: three decimal digits per byte, least significant first. */
        private fun blob(s: String?): Long {
            if (s == null) return 0
            var num = 0L
            var shift = 0
            var i = 0
            while (i < s.length && shift < 64) {
                val v = s.substring(i, minOf(i + 3, s.length)).toIntOrNull() ?: 0
                num = num or ((v.toLong() and 0xFF) shl shift)
                i += 3; shift += 8
            }
            return num
        }

        private fun guidOf(hex: String) = IntArray(4) { java.lang.Long.parseLong(hex.substring(it * 8, it * 8 + 8), 16).toInt() }

        private fun parseJson(bytes: ByteArray): EpicManifest {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val dir = dirFor(blob(root.optString("ManifestFileVersion", "013000000000")).toInt())
            val hashes = root.getJSONObject("ChunkHashList")
            val groups = root.optJSONObject("DataGroupList")
            val sizes = root.optJSONObject("ChunkFilesizeList")
            val chunks = LinkedHashMap<String, Chunk>()
            hashes.keys().forEach { g ->
                if (g.length < 32) return@forEach
                val c = Chunk(guidOf(g), blob(hashes.getString(g)), blob(groups?.optString(g, "0")).toInt(), 1 shl 20,
                    blob(sizes?.optString(g, "0")), null)
                chunks[c.key] = c
            }
            val list = root.getJSONArray("FileManifestList")
            val files = (0 until list.length()).map { i ->
                val f = list.getJSONObject(i)
                val parts = (f.optJSONArray("FileChunkParts") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { j ->
                        val p = a.getJSONObject(j)
                        Part(p.optString("Guid").uppercase(), blob(p.optString("Offset", "0")).toInt(), blob(p.optString("Size", "0")).toInt())
                    }
                }
                val hash = f.optString("FileHash")
                val sha = if (hash.length >= 60) ByteArray(20) { k -> (hash.substring(k * 3, k * 3 + 3).toIntOrNull() ?: 0).toByte() } else null
                FileEntry(f.optString("Filename"), sha, parts)
            }
            return EpicManifest(dir, false, chunks, files, root.optString("LaunchExeString"), root.optString("LaunchCommand"))
        }

        /** A chunk file from the CDN -> its data (zlib when compressed). */
        fun decodeChunk(raw: ByteArray): ByteArray {
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == 0xB1FE3AA2.toInt()) { "bad chunk" }
            b.int // header version
            val headerSize = b.int
            val compressed = b.int
            b.position(b.position() + 16 + 8)
            val storedAs = b.get().toInt() and 0xFF
            require(storedAs and 2 == 0) { "encrypted chunk" }
            val data = raw.copyOfRange(headerSize, headerSize + compressed)
            if (storedAs and 1 == 0) return data
            val inf = Inflater()
            inf.setInput(data)
            val out = java.io.ByteArrayOutputStream(1 shl 20)
            val tmp = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val k = inf.inflate(tmp)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(tmp, 0, k)
            }
            inf.end()
            return out.toByteArray()
        }
    }
}
