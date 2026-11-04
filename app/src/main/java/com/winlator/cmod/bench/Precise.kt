package com.winlator.cmod.bench

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Root mode of the bundle finder's measurements (optional): GPU clock / load read through `su`
 * (the files apps can't open), and the real hardware CPU counters (cycles, instructions) from
 * Android's own `simpleperf`, system-wide, every 0.5 s.
 */
object RootMode {
    private const val KEY = "yurn_bench_root"
    @Volatile private var checked: Boolean? = null

    fun enabled(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)
    fun setEnabled(context: Context, on: Boolean) = PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()

    /** True when `su` grants root (asks the root manager the first time). Blocking: call off the main thread. */
    fun available(): Boolean {
        checked?.let { return it }
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(8, TimeUnit.SECONDS) && out.contains("uid=0")
        }.getOrDefault(false)
        checked = ok
        return ok
    }
}

/** A long-lived `su` shell for reading files apps aren't allowed to read. */
class RootShell private constructor(private val proc: java.lang.Process) {
    private val writer = OutputStreamWriter(proc.outputStream)
    private val reader = BufferedReader(InputStreamReader(proc.inputStream))

    /** First line of each file (null when unreadable even as root). */
    @Synchronized
    fun read(paths: List<String>): List<String?> = runCatching {
        val cmd = StringBuilder()
        paths.forEach { cmd.append("cat '").append(it).append("' 2>/dev/null | head -n 1 || true; echo '@@'\n") }
        cmd.append("echo '@@END'\n")
        writer.write(cmd.toString()); writer.flush()
        val out = ArrayList<String?>()
        var current: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            when (line) {
                "@@END" -> break
                "@@" -> { out += current?.trim()?.takeIf { it.isNotEmpty() }; current = null }
                else -> if (current == null) current = line
            }
        }
        out
    }.getOrDefault(paths.map { null })

    fun close() { runCatching { writer.write("exit\n"); writer.flush(); proc.destroy() } }

    companion object {
        fun open(): RootShell? = runCatching { RootShell(ProcessBuilder("su").redirectErrorStream(true).start()) }.getOrNull()
    }
}

/**
 * Hardware CPU counters for the whole system through `simpleperf stat -a --interval 500`
 * (root). Each interval's cycles and instructions are added to the phase YurnBench is in.
 */
class PerfStat(private val phase: () -> String) {
    class Counts { var cycles = 0.0; var instructions = 0.0 }
    val perPhase = LinkedHashMap<String, Counts>()
    @Volatile var error: String? = null
    private var proc: java.lang.Process? = null

    /** Older simpleperf prints running totals per interval; newer can print the interval alone. */
    private var cumulative = true
    private var lastCycles = -1.0
    private var lastInstr = -1.0

    fun start() {
        val help = runCatching {
            val h = ProcessBuilder("su", "-c", "simpleperf stat --help").redirectErrorStream(true).start()
            val text = h.inputStream.bufferedReader().readText()
            h.waitFor(5, TimeUnit.SECONDS)
            text
        }.getOrDefault("")
        if (!help.contains("--interval")) { error = "simpleperf isn't available on this system"; return }
        cumulative = !help.contains("interval-only-values")
        val flags = if (cumulative) "" else " --interval-only-values"
        val p = runCatching {
            ProcessBuilder("su", "-c", "simpleperf stat -a -e cpu-cycles,instructions --interval 500 --duration 600$flags")
                .redirectErrorStream(true).start()
        }.getOrElse { error = "simpleperf: ${it.message}"; return }
        proc = p
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line -> parse(line) }
            }
        }, "PerfStat").apply { isDaemon = true; start() }
    }

    private fun parse(line: String) {
        val l = line.trim()
        if (l.contains("not found", true) || l.contains("permission", true) || l.contains("error", true)) {
            if (error == null) error = l.take(120)
            return
        }
        val event = when {
            l.contains("cpu-cycles") -> "c"
            l.contains("instructions") -> "i"
            else -> return
        }
        var count = l.split(Regex("\\s+")).firstOrNull()?.replace(",", "")?.toDoubleOrNull() ?: return
        if (cumulative) {
            val prev = if (event == "c") lastCycles else lastInstr
            if (event == "c") lastCycles = count else lastInstr = count
            if (prev < 0 || count < prev) return
            count -= prev
        }
        synchronized(perPhase) {
            val c = perPhase.getOrPut(phase()) { Counts() }
            if (event == "c") c.cycles += count else c.instructions += count
        }
    }

    fun stop() {
        runCatching { proc?.destroy() }
        // simpleperf runs as root: its own process outlives destroy() of the su wrapper.
        runCatching { ProcessBuilder("su", "-c", "pkill -INT simpleperf").start().waitFor(3, TimeUnit.SECONDS) }
    }
}

/**
 * CPU time used by the game's processes (Wine, FEX / Box64, wineserver: they run as this app's
 * user, so their /proc entries are readable without root), the app's own process left out.
 */
class ProcTime {
    private val uid = Process.myUid()
    private val self = Process.myPid()
    private val tick = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble() }.getOrDefault(100.0).takeIf { it > 0 } ?: 100.0
    private val last = HashMap<Int, Long>()

    /** CPU seconds the game's processes used since the previous call. */
    fun delta(): Double {
        var total = 0L
        val seen = HashSet<Int>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            val ticks = runCatching {
                if (Os.stat("/proc/$pid").st_uid != uid) return@forEach
                val stat = File("/proc/$pid/stat").readText()
                val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                // After the command: state(0) … utime(11) stime(12) cutime(13) cstime(14)
                f[11].toLong() + f[12].toLong()
            }.getOrNull() ?: return@forEach
            seen += pid
            val prev = last[pid]
            if (prev != null && ticks >= prev) total += ticks - prev
            else if (prev == null && last.isNotEmpty()) total += 0   // a new process: counted from its next sample
            last[pid] = ticks
        }
        last.keys.retainAll(seen)
        return total / tick
    }

    companion object { const val TAG = "ProcTime" }
}

internal fun logW(msg: String, e: Throwable? = null) = Log.w("BundleFinder", msg, e)
