package com.winlator.cmod.perf

import android.util.Log
import java.io.File

/**
 * Own CPU / GPU frequency limits for a game (root): a lowest and a highest clock per CPU cluster
 * (cpufreq policy) and for the GPU, picked from the steps the kernel offers.
 *
 * Why: under a vendor boost (a benchmark package name, a game profile) the GPU can be kept high
 * while the shared power / heat budget pulls the big CPU cores down; the game then waits on the CPU,
 * the GPU idles at a high clock and frame times get worse. A lower GPU ceiling and a CPU floor
 * give that budget back to the CPU.
 *
 * Writes go through [PerfRevertRegistry] (put back when the game closes, after a crash at the next
 * start). Restored values are the hardware range, not what was read at the time: a read taken while
 * thermal-engine held a cap would otherwise be written back as a permanent limit.
 *
 * Holding the values: vendor services rewrite scaling_min/max_freq. A guard thread puts our values
 * back once a second. It never fights the thermal limits (they lower the effective maximum through
 * the kernel's own requests, which a write can't lift) and doesn't chmod the nodes (a thermal module
 * may keep them writable on purpose).
 */
object FreqTuner {
    private const val TAG = "FreqTuner"
    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"

    /** One cpufreq policy: its cores and its clock steps (kHz, ascending). */
    data class Cluster(val policy: Int, val cpus: List<Int>, val steps: List<Int>) {
        val dir get() = "$CPUFREQ/policy$policy"
        val minNode get() = "$dir/scaling_min_freq"
        val maxNode get() = "$dir/scaling_max_freq"
        /** "0–1", "7". */
        val cpuLabel: String get() = if (cpus.size <= 1) cpus.joinToString() else "${cpus.first()}–${cpus.last()}"
    }

    /** The GPU's clock steps (MHz, ascending) and the nodes that take them. */
    data class Gpu(
        val steps: List<Int>,
        /** MHz nodes (KGSL min_clock_mhz / max_clock_mhz). */
        val minMhzNode: String?, val maxMhzNode: String?,
        /** Hz nodes (devfreq min_freq / max_freq). */
        val minHzNode: String?, val maxHzNode: String?,
        /** Current clock: KGSL gpuclk (Hz) or devfreq cur_freq (Hz). */
        val curHzNode: String?,
    ) {
        val nodes get() = listOfNotNull(minMhzNode, maxMhzNode, minHzNode, maxHzNode)
    }

    /** Lowest / highest clock, 0 = left to the system. CPU in kHz, GPU in MHz. */
    data class Range(val min: Int = 0, val max: Int = 0) {
        val isSet get() = min > 0 || max > 0
    }

    /** CPU ranges by policy number, and the GPU range. */
    data class Profile(val cpu: Map<Int, Range> = emptyMap(), val gpu: Range = Range()) {
        val isEmpty get() = cpu.values.none { it.isSet } && !gpu.isSet

        /** "c0=0-2035200;c7=2400000-0;g=0-680" (empty when nothing is set). */
        fun encode(): String = (cpu.filterValues { it.isSet }.toSortedMap().map { (p, r) -> "c$p=${r.min}-${r.max}" } +
            listOfNotNull(gpu.takeIf { it.isSet }?.let { "g=${it.min}-${it.max}" })).joinToString(";")

        companion object {
            fun decode(s: String?): Profile {
                if (s.isNullOrBlank()) return Profile()
                val cpu = HashMap<Int, Range>(); var gpu = Range()
                for (part in s.split(';')) {
                    val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    val (a, b) = v.split('-', limit = 2).mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 } ?: continue
                    val r = Range(a.coerceAtLeast(0), b.coerceAtLeast(0))
                    if (k == "g") gpu = r else k.removePrefix("c").toIntOrNull()?.let { cpu[it] = r }
                }
                return Profile(cpu, gpu)
            }
        }
    }

    // ---- what the device has ------------------------------------------------------------------

    private var clustersCache: List<Cluster>? = null
    private var gpuCache: Gpu? = null
    private var gpuProbed = false

    private fun read(path: String): String? =
        runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: if (RootManager.isGranted) RootManager.readNode(path)?.trim() else null

    private fun ints(s: String?): List<Int> = s?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull()?.toInt() } ?: emptyList()

    /** CPU clusters, slowest first. */
    @Synchronized
    fun clusters(): List<Cluster> {
        clustersCache?.let { return it }
        // policyN by number (listing the directory can be refused to apps; reading works, or root reads).
        val dirs = (0 until 32).map { File("$CPUFREQ/policy$it") }
            .filter { it.isDirectory || read("${it.path}/related_cpus") != null }
        val out = dirs.mapNotNull { d ->
            val policy = d.name.removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
            val cpus = parseCpuList(read("${d.path}/related_cpus") ?: read("${d.path}/affected_cpus") ?: "$policy")
            var steps = ints(read("${d.path}/scaling_available_frequencies"))
            if (steps.size < 2) steps = read("${d.path}/stats/time_in_state")?.lines()
                ?.mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() } ?: emptyList()
            if (steps.size < 2) steps = listOfNotNull(read("${d.path}/cpuinfo_min_freq")?.toIntOrNull(), read("${d.path}/cpuinfo_max_freq")?.toIntOrNull())
            steps = steps.filter { it > 0 }.distinct().sorted()
            if (steps.size < 2) null else Cluster(policy, cpus, steps)
        }.sortedWith(compareBy({ it.steps.last() }, { it.policy }))
        return out.also { if (it.isNotEmpty()) clustersCache = it }
    }

    private fun parseCpuList(s: String): List<Int> = s.split(Regex("[\\s,]+")).flatMap { t ->
        val ab = t.split('-').mapNotNull { it.toIntOrNull() }
        when (ab.size) { 1 -> listOf(ab[0]); 2 -> (ab[0]..ab[1]).toList(); else -> emptyList() }
    }.distinct().sorted()

    /** The GPU's steps and nodes (Adreno KGSL, or a devfreq GPU such as Mali), or null. */
    @Synchronized
    fun gpu(): Gpu? {
        if (gpuProbed) return gpuCache
        gpuProbed = true
        val kgslDevfreq = "$KGSL/devfreq"
        val exists = { p: String -> File(p).exists() || read(p) != null }
        val g = if (exists(KGSL)) {
            val hz = ints(read("$KGSL/gpu_available_frequencies")).ifEmpty { ints(read("$kgslDevfreq/available_frequencies")) }
            val steps = hz.map { it / 1_000_000 }.filter { it > 0 }.distinct().sorted()
            if (steps.size < 2) null else Gpu(
                steps,
                "$KGSL/min_clock_mhz".takeIf(exists), "$KGSL/max_clock_mhz".takeIf(exists),
                "$kgslDevfreq/min_freq".takeIf(exists), "$kgslDevfreq/max_freq".takeIf(exists),
                "$KGSL/gpuclk".takeIf(exists) ?: "$kgslDevfreq/cur_freq".takeIf(exists)
            )
        } else {
            val dir = devfreqGpuDir()
            val hz = dir?.let { ints(read("$it/available_frequencies")) }.orEmpty()
            val steps = hz.map { it / 1_000_000 }.filter { it > 0 }.distinct().sorted()
            if (dir == null || steps.size < 2) null else Gpu(
                steps, null, null,
                "$dir/min_freq".takeIf(exists), "$dir/max_freq".takeIf(exists), "$dir/cur_freq".takeIf(exists)
            )
        }
        gpuCache = g?.takeIf { it.nodes.isNotEmpty() }
        // Without root some nodes may not be readable yet: look again once root is there.
        if (gpuCache == null && !RootManager.isGranted) gpuProbed = false
        return gpuCache
    }

    private fun devfreqGpuDir(): String? {
        val tokens = listOf("gpu", "mali", "g3d", "sgpu", "xclipse", "pvr")
        for (root in listOf("/sys/class/devfreq", "/sys/devices/virtual/devfreq")) {
            File(root).listFiles().orEmpty().forEach { d ->
                val n = d.name.lowercase()
                if (tokens.any { n.contains(it) } && File(d, "available_frequencies").exists()) return d.path
            }
        }
        return null
    }

    /** Root granted and there is something to set. */
    fun isAvailable(): Boolean = RootManager.isGranted && (clusters().isNotEmpty() || gpu() != null)

    // ---- live readings ----------------------------------------------------------------------------

    data class Live(
        /** Current clock per policy (kHz). */
        val cpuCur: Map<Int, Int>,
        /** Effective highest clock per policy (kHz): below the set one = held down by heat / another limiter. */
        val cpuMax: Map<Int, Int>,
        val gpuCurMhz: Int,
    )

    fun live(): Live {
        val cl = clusters()
        val cur = cl.associate { it.policy to (read("${it.dir}/scaling_cur_freq")?.toIntOrNull() ?: 0) }
        val max = cl.associate { it.policy to (read(it.maxNode)?.toIntOrNull() ?: 0) }
        val g = gpu()
        val gpuHz = g?.curHzNode?.let { read(it)?.toLongOrNull() } ?: 0L
        return Live(cur, max, (if (gpuHz > 100_000) gpuHz / 1_000_000 else gpuHz).toInt())
    }

    // ---- applying ---------------------------------------------------------------------------------

    @Volatile private var target: Profile = Profile()
    @Volatile private var guard: Thread? = null

    /** Every node this tuner may write. */
    fun allNodes(): List<String> = clusters().flatMap { listOf(it.minNode, it.maxNode) } + (gpu()?.nodes ?: emptyList())

    private fun nearest(steps: List<Int>, v: Int): Int = steps.minByOrNull { kotlin.math.abs(it - v) } ?: v

    /**
     * Applies [p] (root only). Sides left at 0 aren't touched; values snap to the nearest real step.
     * Starts the guard that keeps them.
     */
    @Synchronized
    fun apply(p: Profile) {
        if (!RootManager.isGranted) return
        target = p
        for (c in clusters()) {
            val r = p.cpu[c.policy] ?: continue
            if (!r.isSet) continue
            val lo = c.steps.first().toString(); val hi = c.steps.last().toString()
            val min = r.min.takeIf { it > 0 }?.let { nearest(c.steps, it) }
            val max = r.max.takeIf { it > 0 }?.let { nearest(c.steps, it) }
            // Order: a floor above the current ceiling only sticks once the ceiling is up.
            val curMax = read(c.maxNode)?.toIntOrNull() ?: c.steps.last()
            fun writeMin() { if (min != null) PerfRevertRegistry.applyWriteRestoring(c.minNode, "$min", lo) }
            fun writeMax() { if (max != null) PerfRevertRegistry.applyWriteRestoring(c.maxNode, "$max", hi) }
            // A ceiling under a floor left by another switch ("CPU at max clock"): the ceiling wins.
            val curMin = read(c.minNode)?.toIntOrNull() ?: 0
            if (min == null && max != null && curMin > max) PerfRevertRegistry.applyWriteRestoring(c.minNode, lo, lo)
            if (min != null && min > curMax) { writeMax(); writeMin() } else { writeMin(); writeMax() }
        }
        gpu()?.let { g -> if (p.gpu.isSet) applyGpu(g, p.gpu) }
        Log.i(TAG, "applied ${p.encode()}")
        startGuard()
    }

    private fun applyGpu(g: Gpu, r: Range) {
        val min = r.min.takeIf { it > 0 }?.let { nearest(g.steps, it) }
        val max = r.max.takeIf { it > 0 }?.let { nearest(g.steps, it) }
        val lo = g.steps.first(); val hi = g.steps.last()
        // A floor above the old ceiling (or a ceiling below the old floor) is refused, so with both
        // set the ceiling is written again after the floor.
        fun writeMax() {
            if (max == null) return
            g.maxMhzNode?.let { PerfRevertRegistry.applyWriteRestoring(it, "$max", "$hi") }
            g.maxHzNode?.let { PerfRevertRegistry.applyWriteRestoring(it, "${max.toLong() * 1_000_000}", "${hi.toLong() * 1_000_000}") }
        }
        fun writeMin() {
            if (min == null) return
            g.minMhzNode?.let { PerfRevertRegistry.applyWriteRestoring(it, "$min", "$lo") }
            g.minHzNode?.let { PerfRevertRegistry.applyWriteRestoring(it, "${min.toLong() * 1_000_000}", "${lo.toLong() * 1_000_000}") }
        }
        if (max != null && min == null) {
            // A floor above the new ceiling ("GPU at max clock"): the ceiling wins, the floor goes to the bottom.
            val floorMhz = g.minMhzNode?.let { read(it)?.toIntOrNull() }
                ?: g.minHzNode?.let { read(it)?.toLongOrNull()?.let { hz -> (hz / 1_000_000).toInt() } } ?: 0
            if (floorMhz > max) {
                g.minMhzNode?.let { PerfRevertRegistry.applyWriteRestoring(it, "$lo", "$lo") }
                g.minHzNode?.let { PerfRevertRegistry.applyWriteRestoring(it, "${lo.toLong() * 1_000_000}", "${lo.toLong() * 1_000_000}") }
            }
            writeMax()
        }
        else if (min != null && max == null) writeMin()
        else { writeMax(); writeMin(); writeMax() } // both: whichever order the node accepts, the second pass settles it
    }

    /** Stops the guard and puts the tuner's nodes back (other switches' nodes stay). */
    @Synchronized
    fun clear() {
        stopGuard()
        target = Profile()
        PerfRevertRegistry.revertNodes(allNodes())
    }

    fun stopGuard() {
        guard?.interrupt()
        guard = null
    }

    private fun startGuard() {
        if (guard?.isAlive == true) return
        guard = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    hold(target)
                }
            } catch (_: InterruptedException) {
            }
        }, "FreqTuner-guard").apply { isDaemon = true; start() }
    }

    /** Puts back what a vendor service changed: a ceiling raised above ours, a floor dropped below ours. */
    private fun hold(p: Profile) {
        if (p.isEmpty || !RootManager.isGranted) return
        for (c in clusters()) {
            val r = p.cpu[c.policy] ?: continue
            val curMax = read(c.maxNode)?.toIntOrNull() ?: continue
            val curMin = read(c.minNode)?.toIntOrNull() ?: continue
            if (r.max > 0) {
                val want = nearest(c.steps, r.max)
                if (curMax > want) RootManager.writeNode(c.maxNode, "$want")
            }
            if (r.min > 0) {
                val want = nearest(c.steps, r.min)
                // Below our floor while the ceiling is above it: someone lowered it (not the heat).
                if (curMin < want && curMax >= want) RootManager.writeNode(c.minNode, "$want")
            }
        }
        val g = gpu() ?: return
        if (p.gpu.max > 0) {
            val want = nearest(g.steps, p.gpu.max)
            g.maxMhzNode?.let { n -> if ((read(n)?.toIntOrNull() ?: 0) > want) RootManager.writeNode(n, "$want") }
            g.maxHzNode?.let { n -> if ((read(n)?.toLongOrNull() ?: 0) > want.toLong() * 1_000_000) RootManager.writeNode(n, "${want.toLong() * 1_000_000}") }
        }
        if (p.gpu.min > 0) {
            val want = nearest(g.steps, p.gpu.min)
            g.minMhzNode?.let { n -> if ((read(n)?.toIntOrNull() ?: Int.MAX_VALUE) < want) RootManager.writeNode(n, "$want") }
            g.minHzNode?.let { n -> if ((read(n)?.toLongOrNull() ?: Long.MAX_VALUE) < want.toLong() * 1_000_000) RootManager.writeNode(n, "${want.toLong() * 1_000_000}") }
        }
    }
}
