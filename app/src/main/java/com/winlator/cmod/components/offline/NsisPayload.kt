package com.winlator.cmod.components.offline

import java.io.File
import java.util.zip.Inflater

/**
 * The files packed in an NSIS (Nullsoft) installer, read without running it.
 *
 * Only non-solid installers compressed with deflate are read (VulkanRT's): after the "first header"
 * (…, 0xDEADBEEF, "NullsoftInst", header size, data size) come blocks, each a 4-byte length (high
 * bit: compressed) and its raw-deflate data. The first block is the installer's script; the others
 * are the files, in the order the script extracts them. Names are not taken from the script: the
 * caller recognises its files by what they are ([Pe]).
 */
object NsisPayload {
    class Pe(val bytes: ByteArray, val machine: Int, val dll: Boolean) {
        val x64: Boolean get() = machine == 0x8664
        val x86: Boolean get() = machine == 0x14c
    }

    /** The PE files in [exe]'s payload, in order. Throws when [exe] is not an installer this reads. */
    fun peFiles(exe: File): List<Pe> = blocks(exe).drop(1).mapNotNull { pe(it) }

    fun blocks(exe: File): List<ByteArray> {
        val data = exe.readBytes()
        val sig = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte()) + "NullsoftInst".toByteArray()
        val at = indexOf(data, sig)
        require(at >= 4) { "not an NSIS installer" }
        val total = le32(data, at + 20)
        val base = at + 24
        require(total > 0 && base + total <= data.size) { "NSIS data is cut short" }
        val out = ArrayList<ByteArray>()
        var p = base
        while (p + 4 <= base + total) {
            val raw = le32(data, p)
            val compressed = raw and 0x80000000.toInt() != 0
            val n = raw and 0x7fffffff
            if (n <= 0 || p + 4 + n > base + total) break
            val chunk = data.copyOfRange(p + 4, p + 4 + n)
            out += if (compressed) inflate(chunk) ?: break else chunk
            p += 4 + n
        }
        require(out.size > 1) { "NSIS installer with solid or non-deflate compression is not supported" }
        return out
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        val inf = Inflater(true)
        return try {
            inf.setInput(raw)
            val out = java.io.ByteArrayOutputStream(raw.size * 3)
            val buf = ByteArray(1 shl 16)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            if (out.size() == 0) null else out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    private fun pe(b: ByteArray): Pe? {
        if (b.size < 0x40 || b[0] != 'M'.code.toByte() || b[1] != 'Z'.code.toByte()) return null
        val pe = le32(b, 0x3c)
        if (pe < 0 || pe + 24 > b.size || le32(b, pe) != 0x4550) return null
        val machine = le16(b, pe + 4)
        val characteristics = le16(b, pe + 22)
        return Pe(b, machine, characteristics and 0x2000 != 0)
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun indexOf(data: ByteArray, sig: ByteArray): Int {
        outer@ for (i in 0..data.size - sig.size) {
            for (k in sig.indices) if (data[i + k] != sig[k]) continue@outer
            return i
        }
        return -1
    }
}
