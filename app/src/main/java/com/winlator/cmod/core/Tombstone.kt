package com.winlator.cmod.core

/**
 * Reads Android's native crash tombstone (the protobuf ApplicationExitInfo.traceInputStream gives,
 * system/core/debuggerd/proto/tombstone.proto) into text: signal, fault address, abort message,
 * the crashing thread's backtrace with function names, and short stacks of threads in our libraries.
 */
object Tombstone {
    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (p < end) {
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl s)
                if (x < 0x80) break
                s += 7
            }
            return r
        }
        /** Calls [f] with (field, wireType, sub-reader for length-delimited / value for the rest). */
        fun fields(f: (Int, Long, Reader?) -> Unit) {
            while (more()) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> f(field, varint(), null)
                    1 -> { var v = 0L; for (i in 0 until 8) v = v or ((b[p + i].toLong() and 0xff) shl (8 * i)); p += 8; f(field, v, null) }
                    2 -> { val n = varint().toInt(); val sub = Reader(b, p, p + n); p += n; f(field, 0, sub) }
                    5 -> { var v = 0L; for (i in 0 until 4) v = v or ((b[p + i].toLong() and 0xff) shl (8 * i)); p += 4; f(field, v, null) }
                    else -> return
                }
            }
        }
        fun str() = String(b, p, end - p, Charsets.UTF_8)
    }

    private class Frame(var relPc: Long = 0, var fn: String = "", var off: Long = 0, var file: String = "")
    private class Thread(var id: Int = 0, var name: String = "", val frames: ArrayList<Frame> = ArrayList(), val notes: ArrayList<String> = ArrayList())

    private fun frame(r: Reader) = Frame().also { f ->
        r.fields { n, v, s -> when (n) { 1 -> f.relPc = v; 4 -> f.fn = s!!.str(); 5 -> f.off = v; 6 -> f.file = s!!.str() } }
    }

    private fun thread(r: Reader) = Thread().also { t ->
        r.fields { n, v, s -> when (n) { 1 -> t.id = v.toInt(); 2 -> t.name = s!!.str(); 4 -> t.frames += frame(s!!); 7 -> t.notes += s!!.str() } }
    }

    private fun line(i: Int, f: Frame) = "#%02d pc %08x  %s%s".format(i, f.relPc, f.file.substringAfterLast("/").ifEmpty { "?" },
        if (f.fn.isNotEmpty()) " (${f.fn}+${f.off})" else "")

    private fun ours(f: Frame) = f.file.contains("/data/app/") || f.file.contains("com.yurnayt")

    /** Library paths in the crashing thread's backtrace (null when [raw] isn't a tombstone). */
    fun crashingFiles(raw: ByteArray): List<String>? = runCatching {
        var tid = 0
        val threads = ArrayList<Thread>()
        Reader(raw).fields { n, v, s ->
            when (n) {
                6 -> tid = v.toInt()
                16 -> { var t: Thread? = null; s!!.fields { k, _, ss -> if (k == 2) t = thread(ss!!) }; t?.let { threads += it } }
            }
        }
        val crash = threads.firstOrNull { it.id == tid } ?: return null
        crash.frames.map { it.file }
    }.getOrNull()

    /** Null when [raw] isn't a tombstone protobuf. */
    fun format(raw: ByteArray): String? = runCatching {
        var tid = 0; var signal = ""; var fault = ""; var abort = ""
        val causes = ArrayList<String>(); val threads = ArrayList<Thread>()
        Reader(raw).fields { n, v, s ->
            when (n) {
                // system/core/debuggerd/proto/tombstone.proto: tid 6, signal_info 10, abort_message 14,
                // causes 15, threads 16 (a map: entry key 1, Thread 2).
                6 -> tid = v.toInt()
                10 -> { var name = ""; var code = ""; var has = false; var addr = 0L
                    s!!.fields { k, x, ss -> when (k) { 2 -> name = ss!!.str(); 4 -> code = ss!!.str(); 8 -> has = x != 0L; 9 -> addr = x } }
                    signal = "$name ($code)"; if (has) fault = "0x" + java.lang.Long.toHexString(addr) }
                14 -> abort = s!!.str()
                15 -> s!!.fields { k, _, ss -> if (k == 1) causes += ss!!.str() }
                16 -> { var t: Thread? = null; s!!.fields { k, _, ss -> if (k == 2) t = thread(ss!!) }; t?.let { threads += it } }
            }
        }
        if (threads.isEmpty() && signal.isEmpty()) return null
        val sb = StringBuilder("\n\n--- native crash ---\nSignal: $signal" + (if (fault.isNotEmpty()) ", fault address $fault" else "") + "\n")
        if (abort.isNotEmpty()) sb.append("Abort: $abort\n")
        causes.forEach { sb.append("Cause: $it\n") }
        val crash = threads.firstOrNull { it.id == tid }
        if (crash != null) {
            sb.append("\nCrashing thread: ${crash.name} ($tid)\n")
            crash.frames.take(40).forEachIndexed { i, f -> sb.append(line(i, f)).append('\n') }
            crash.notes.forEach { sb.append("note: $it\n") }
        }
        threads.filter { it !== crash && it.frames.any(::ours) }.take(6).forEach { t ->
            sb.append("\nThread ${t.name} (${t.id}):\n")
            t.frames.take(12).forEachIndexed { i, f -> sb.append(line(i, f)).append('\n') }
        }
        sb.toString()
    }.getOrNull()
}
