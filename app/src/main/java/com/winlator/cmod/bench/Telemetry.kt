package com.winlator.cmod.bench

import com.winlator.cmod.core.CPUStatus
import org.json.JSONObject
import java.io.File

/**
 * What the phone did during a run, per YurnBench phase: CPU and GPU clocks against their maximum
 * (did the system throttle?), CPU cycles spent (core clocks × time), GPU load and temperature.
 * Sampled every 0.5 s by the bench watchdog. Readings the system doesn't allow stay at -1.
 */
class Telemetry(private val root: RootShell? = null) {
    class PhaseStats {
        var samples = 0
        var cpuMeanMhz = 0.0      // sum of the per-sample mean core clock
        var cpuTopMhz = 0.0       // sum of the per-sample fastest core clock
        var cpuMeanRatio = 0.0    // sum of mean(cur/max) over cores
        var cpuTopRatio = 0.0     // sum of the fastest core's cur/max
        var cpuCycles = 0.0       // core-MHz × seconds summed over cores → megacycles
        var gpuSamples = 0
        var gpuMhz = 0.0
        var gpuRatio = 0.0
        var busySamples = 0
        var gpuBusy = 0.0
        var maxCpuTemp = -1
        var seconds = 0.0
        var procCpu = 0.0          // CPU seconds the game's processes used (all their threads)
        var busyMcycles = 0.0      // procCpu × the mean core clock: cycles actually spent, estimated
        var hwCycles = 0.0         // root: hardware counters (simpleperf), whole system
        var hwInstructions = 0.0

        fun toJson(): JSONObject = JSONObject().apply {
            put("samples", samples)
            if (samples > 0) {
                put("cpu_mhz", cpuMeanMhz / samples); put("cpu_top_mhz", cpuTopMhz / samples)
                put("cpu_ratio", cpuMeanRatio / samples); put("cpu_top_ratio", cpuTopRatio / samples)
                put("cpu_mcycles", cpuCycles)
            }
            if (gpuSamples > 0) { put("gpu_mhz", gpuMhz / gpuSamples); put("gpu_ratio", gpuRatio / gpuSamples) }
            if (busySamples > 0) put("gpu_busy", gpuBusy / busySamples)
            put("cpu_temp", maxCpuTemp)
            put("seconds", seconds)
            if (procCpu > 0) {
                put("proc_cpu_s", procCpu)
                put("cpu_load", if (seconds > 0) procCpu / (seconds * CORES) else 0.0)
                put("busy_mcycles", busyMcycles)
            }
            if (hwCycles > 0) { put("hw_mcycles", hwCycles / 1e6); put("hw_minstr", hwInstructions / 1e6); put("ipc", hwInstructions / hwCycles) }
        }
    }

    private val phases = LinkedHashMap<String, PhaseStats>()
    private val cpuMax: IntArray = IntArray(Runtime.getRuntime().availableProcessors()) { CPUStatus.getMaxClockSpeed(it).toInt() }
    // Files apps may read directly; with root, the rest through the root shell.
    private val gpuCur: String? = first(GPU_CUR)
    private val busyPath: String? = first(GPU_BUSY)
    private val rootGpuCur: String? = if (gpuCur == null) firstRoot(GPU_CUR) else null
    private val rootBusy: String? = if (busyPath == null) firstRoot(GPU_BUSY) else null
    private val gpuMaxMhz: Double = first(GPU_MAX)?.let { mhz(it) } ?: firstRoot(GPU_MAX)?.let { p -> root?.read(listOf(p))?.firstOrNull()?.let(::mhzOf) } ?: -1.0
    private val procTime = ProcTime()
    private var last = 0L

    /** Where root mode got its readings from (for the result card). */
    val sources: String get() = listOfNotNull(
        (gpuCur ?: rootGpuCur)?.let { "gpu clock" + if (gpuCur == null) " (root)" else "" },
        (busyPath ?: rootBusy)?.let { "gpu load" + if (busyPath == null) " (root)" else "" },
    ).joinToString(", ")

    private fun firstRoot(paths: List<String>): String? {
        val r = root ?: return null
        val values = r.read(paths)
        return paths.indices.firstOrNull { values.getOrNull(it) != null }?.let { paths[it] }
    }

    /** Adds the hardware counters simpleperf counted per phase. */
    fun addCounters(perPhase: Map<String, PerfStat.Counts>) {
        perPhase.forEach { (name, c) ->
            val p = phases.getOrPut(name) { PhaseStats() }
            p.hwCycles += c.cycles; p.hwInstructions += c.instructions
        }
    }

    fun sample(phase: String) {
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 0.5 else ((now - last) / 1000.0).coerceIn(0.1, 2.0)
        last = now
        val p = phases.getOrPut(phase) { PhaseStats() }
        p.seconds += dt
        val used = runCatching { procTime.delta() }.getOrDefault(0.0)
        p.procCpu += used
        val clocks = CPUStatus.getCurrentClockSpeeds()
        var sum = 0.0; var top = 0.0; var ratioSum = 0.0; var topRatio = 0.0; var n = 0
        for (i in clocks.indices) {
            val cur = clocks[i].toInt().let { if (it < 0) it + 65536 else it }.toDouble()
            if (cur <= 0) continue
            val max = cpuMax.getOrElse(i) { 0 }.toDouble()
            sum += cur; n++
            if (cur > top) top = cur
            if (max > 0) { val r = (cur / max).coerceAtMost(1.0); ratioSum += r; if (r > topRatio) topRatio = r }
            p.cpuCycles += cur * dt
        }
        if (n > 0) {
            p.busyMcycles += used * (sum / n)
            p.samples++
            p.cpuMeanMhz += sum / n; p.cpuTopMhz += top
            p.cpuMeanRatio += ratioSum / n; p.cpuTopRatio += topRatio
        }
        val rootValues = if (rootGpuCur != null || rootBusy != null) root?.read(listOfNotNull(rootGpuCur, rootBusy)) else null
        val cur = gpuCur?.let { mhz(it) } ?: rootGpuCur?.let { rootValues?.getOrNull(0)?.let(::mhzOf) } ?: -1.0
        if (cur > 0) { p.gpuSamples++; p.gpuMhz += cur; if (gpuMaxMhz > 0) p.gpuRatio += (cur / gpuMaxMhz).coerceAtMost(1.0) }
        val b = busyPath?.let { busy(it) }
            ?: rootBusy?.let { path -> rootValues?.getOrNull(if (rootGpuCur != null) 1 else 0)?.let { busyOf(it, path) } }
        if (b != null) { p.busySamples++; p.gpuBusy += b }
        val t = CPUStatus.getCpuTempC()
        if (t > p.maxCpuTemp) p.maxCpuTemp = t
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("gpu_max_mhz", gpuMaxMhz)
        put("cpu_max_mhz", cpuMax.maxOrNull() ?: 0)
        put("phases", JSONObject().apply { phases.forEach { (k, v) -> put(k, v.toJson()) } })
    }

    companion object {
        private val GPU_CUR = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz", "/sys/kernel/gpu/gpu_clock",
            "/sys/class/devfreq/gpufreq/cur_freq", "/sys/kernel/ged/hal/current_freqency",
        )
        private val GPU_MAX = listOf(
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/max_clock_mhz", "/sys/kernel/gpu/gpu_max_clock",
            "/sys/class/devfreq/gpufreq/max_freq",
        )
        private val GPU_BUSY = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy", "/sys/class/misc/mali0/device/utilisation", "/sys/kernel/ged/hal/gpu_utilization",
        )

        private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

        private fun first(paths: List<String>): String? = paths.firstOrNull { read(it) != null }

        private val CORES = Runtime.getRuntime().availableProcessors()

        /** A clock file in Hz, kHz or MHz (or "123 MHz"), as MHz; -1 when unreadable. */
        private fun mhz(path: String): Double = read(path)?.let(::mhzOf) ?: -1.0

        private fun mhzOf(text: String): Double {
            val v = text.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: return -1.0
            return when {
                v > 10_000_000 -> v / 1_000_000   // Hz
                v > 10_000 -> v / 1000            // kHz
                else -> v                         // MHz
            }
        }

        /** "45", "45 %", or kgsl's "busy total" pair → percent. */
        private fun busy(path: String): Double? = read(path)?.let { busyOf(it, path) }

        private fun busyOf(text: String, path: String): Double? {
            val parts = text.replace("%", "").trim().split(Regex("\\s+"))
            val a = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val b = parts.getOrNull(1)?.toDoubleOrNull()
            return if (b != null && b > 0 && parts.size == 2 && path.endsWith("gpubusy")) 100 * a / b else a.coerceIn(0.0, 100.0)
        }
    }
}
