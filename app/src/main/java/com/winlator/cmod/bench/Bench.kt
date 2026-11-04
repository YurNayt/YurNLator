package com.winlator.cmod.bench

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bundle finder, stage 1 (hidden developer option): runs YurnBench in one container with every
 * chosen combination of GPU driver, DXVK and FEXCore / Box64, and keeps the results.
 *
 * Wine / Proton stays the container's for the whole series: changing it means another runtime and
 * prefix, so it's chosen once by picking the container.
 *
 * One run = a temporary shortcut (files/bench/run.desktop) whose settings override the container's
 * (driver, DXVK, emulator), pointing at YurnBench in the container's drive C:. The game screen in
 * bench mode watches YurnBench's heartbeat and result files and closes the session itself
 * ([Watchdog]); back on the bundle finder tab, [onReturn] reads the outcome and starts the next run.
 * The whole series lives in files/bench/state.json, so it survives the app being closed between runs.
 */
data class Combo(
    val driverId: String,
    val driverLabel: String,
    /** For DX12 runs: the container's DXVK (its dxgi.dll presents for VKD3D); not compared. */
    val dxvk: String,
    /** "FEXCore" or "Box64" (WOWBox64 on arm64ec is stored as Box64, like the editors do). */
    val emulator: String,
    val emulatorVersion: String,
    /** "d3d11" (DXVK) or "d3d12" (VKD3D). */
    val api: String = API11,
    val vkd3d: String = "",
) {
    val dx12: Boolean get() = api == API12
    /** DXVK for DX11 runs, VKD3D for DX12 runs: the translation layer the run compares. */
    val layer: String get() = if (dx12) "VKD3D $vkd3d" else "DXVK $dxvk"
    val label: String get() = "$driverLabel · $layer · ${if (emulator == "FEXCore") "FEX" else "Box64"} $emulatorVersion"
    /** Same bundle (for averaging repeated runs). */
    val key: String get() = listOf(api, driverId, if (dx12) vkd3d else dxvk, emulator, emulatorVersion).joinToString("|")

    fun toJson(): JSONObject = JSONObject().put("driver", driverId).put("driverLabel", driverLabel).put("dxvk", dxvk)
        .put("emulator", emulator).put("emulatorVersion", emulatorVersion).put("api", api).put("vkd3d", vkd3d)

    companion object {
        const val API11 = "d3d11"
        const val API12 = "d3d12"
        fun from(o: JSONObject) = Combo(o.optString("driver"), o.optString("driverLabel"), o.optString("dxvk"),
            o.optString("emulator"), o.optString("emulatorVersion"), o.optString("api").ifEmpty { API11 }, o.optString("vkd3d"))
    }
}

data class Phase(val avg: Double, val low1: Double, val frames: Int, val gpuMs: Double = 0.0, val gpuLow1: Double = 0.0) {
    /** Frames per second the GPU alone could do (from its own timestamps); 0 when not measured. */
    val gpuFps: Double get() = if (gpuMs > 0.05) 1000.0 / gpuMs else 0.0

    fun toJson(): JSONObject = JSONObject().put("avg", avg).put("low1", low1).put("frames", frames).put("gpu_ms", gpuMs).put("gpu_low1", gpuLow1)

    companion object {
        fun from(o: JSONObject?) = Phase(o?.optDouble("avg", 0.0) ?: 0.0, o?.optDouble("low1", 0.0) ?: 0.0,
            o?.optInt("frames") ?: 0, o?.optDouble("gpu_ms", 0.0) ?: 0.0, o?.optDouble("gpu_low1", 0.0) ?: 0.0)
    }
}

data class RunResult(
    val combo: Combo,
    /** ok | failed | crash | timeout | skipped */
    val status: String,
    val detail: String,
    val adapter: String,
    val cpuSt: Double,
    val cpuMt: Double,
    val gpu: Phase,
    val mix: Phase,
    val seconds: Double,
    val tempBefore: Float,
    val tempAfter: Float,
    /** The watchdog's per-phase clocks, cycles, GPU load and temperature ([Telemetry]); may be empty. */
    val telemetry: JSONObject = JSONObject(),
    /** The YurnBench phase the run ended in (for a crash: where it happened). */
    val phase: String = "",
) {
    val ok: Boolean get() = status == "ok"

    private fun ph(name: String): JSONObject? = telemetry.optJSONObject("phases")?.optJSONObject(name)

    /** Clock / maximum during a phase (1.0 = full clocks, lower = throttled); null when unknown. */
    private fun ratio(name: String, key: String): Double? = ph(name)?.optDouble(key, -1.0)?.takeIf { it > 0.05 }

    val cpuStRatio: Double? get() = ratio("cpu", "cpu_top_ratio")
    val cpuMtRatio: Double? get() = ratio("cpu", "cpu_ratio")
    val gpuRatio: Double? get() = ratio("gpu", "gpu_ratio")
    val mixGpuRatio: Double? get() = ratio("mix", "gpu_ratio")
    val mixCpuRatio: Double? get() = ratio("mix", "cpu_top_ratio")
    fun gpuMhz(name: String): Double = ph(name)?.optDouble("gpu_mhz", -1.0) ?: -1.0
    fun cpuMhz(name: String): Double = ph(name)?.optDouble("cpu_mhz", -1.0) ?: -1.0
    fun gpuBusy(name: String): Double = ph(name)?.optDouble("gpu_busy", -1.0) ?: -1.0
    val maxCpuTemp: Int get() = listOf("cpu", "gpu", "mix").maxOf { ph(it)?.optInt("cpu_temp", -1) ?: -1 }

    /**
     * CPU work per cycle in the CPU phase: how well the emulator turns cycles into work.
     * Cycles from the hardware counters (root), else the game's CPU time × clock, else all cores' clocks.
     */
    val cpuPerMcycle: Double get() = cpuCycles.takeIf { it > 0 }?.let { cyc -> (cpuSt * 2 + cpuMt * 4) / cyc } ?: 0.0

    /** Megacycles spent in the CPU phase and where the number comes from: "hw", "busy" or "clocks". */
    val cpuCycles: Double get() = ph("cpu")?.let { p ->
        p.optDouble("hw_mcycles", 0.0).takeIf { it > 0 } ?: p.optDouble("busy_mcycles", 0.0).takeIf { it > 0 }
            ?: p.optDouble("cpu_mcycles", 0.0)
    } ?: 0.0
    val cyclesSource: String get() = ph("cpu")?.let { p ->
        when { p.optDouble("hw_mcycles", 0.0) > 0 -> "hw"; p.optDouble("busy_mcycles", 0.0) > 0 -> "busy"; else -> "clocks" }
    } ?: ""
    fun cpuLoad(name: String): Double = ph(name)?.optDouble("cpu_load", -1.0) ?: -1.0
    fun procCpu(name: String): Double = ph(name)?.optDouble("proc_cpu_s", 0.0) ?: 0.0
    fun ipc(name: String): Double = ph(name)?.optDouble("ipc", 0.0) ?: 0.0
    val rootUsed: Boolean get() = telemetry.optJSONObject("root")?.optBoolean("enabled") == true
    val rootNote: String get() = telemetry.optJSONObject("root")?.let { r ->
        listOfNotNull(r.optString("sources").takeIf { it.isNotBlank() }, r.optString("perf_error").takeIf { it.isNotBlank() }).joinToString(" · ")
    }.orEmpty()
    val gpuReps: Int get() = telemetry.optInt("gpu_reps", 0)

    /**
     * One number to sort by: frame rates weigh most (GPU and CPU+GPU, their 1% lows half as
     * much), the multi-core CPU score adds a little. Same formula for every run, so only the
     * order matters.
     */
    val score: Int get() = if (!ok) 0 else scoreOf(gpuPower, mix.avg, gpuLowPower, mix.low1, cpuMt)

    /** The GPU phase's 1% low: per scene on the GPU when measured (the presented one is capped / batched). */
    val gpuLowPower: Double get() = if (gpu.gpuLow1 > 0) gpu.gpuLow1 else gpu.low1

    /**
     * The GPU phase's speed: what the GPU itself can do per its timestamps when measured (the
     * presented frame rate is often held at 60-70 FPS by the display path and hides the
     * difference between drivers), else the presented FPS.
     */
    val gpuPower: Double get() = if (gpu.gpuFps > 0) gpu.gpuFps else gpu.avg

    /**
     * The same score as if the phone had kept its full clocks: each part divided by how far its
     * clock was held down (never more than ×2). An estimate: speed doesn't follow the clock exactly.
     */
    val scoreFullClocks: Int get() {
        if (!ok) return 0
        fun up(v: Double, r: Double?) = if (r == null) v else v / r.coerceIn(0.5, 1.0)
        val mixR = listOfNotNull(mixGpuRatio, mixCpuRatio).minOrNull()
        return scoreOf(up(gpuPower, gpuRatio), up(mix.avg, mixR), up(gpuLowPower, gpuRatio), up(mix.low1, mixR), up(cpuMt, cpuMtRatio))
    }

    /** How much was lost to throttling, in percent (0 when the clocks stayed up or are unreadable). */
    val throttlePercent: Int get() = if (score <= 0) 0 else ((scoreFullClocks - score) * 100 / score).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().put("combo", combo.toJson()).put("status", status).put("detail", detail)
        .put("adapter", adapter).put("cpuSt", cpuSt).put("cpuMt", cpuMt)
        .put("gpu", gpu.toJson()).put("mix", mix.toJson())
        .put("seconds", seconds).put("tempBefore", tempBefore.toDouble()).put("tempAfter", tempAfter.toDouble())
        .put("telemetry", telemetry).put("phase", phase)

    companion object {
        private fun scoreOf(gpuAvg: Double, mixAvg: Double, gpuLow: Double, mixLow: Double, cpuMt: Double) =
            (4 * gpuAvg + 4 * mixAvg + 2 * (gpuLow + mixLow) + cpuMt / 50.0).toInt()

        fun from(o: JSONObject) = RunResult(
            Combo.from(o.optJSONObject("combo") ?: JSONObject()), o.optString("status"), o.optString("detail"),
            o.optString("adapter"), o.optDouble("cpuSt", 0.0), o.optDouble("cpuMt", 0.0),
            Phase.from(o.optJSONObject("gpu")), Phase.from(o.optJSONObject("mix")), o.optDouble("seconds", 0.0),
            o.optDouble("tempBefore", 0.0).toFloat(), o.optDouble("tempAfter", 0.0).toFloat(),
            o.optJSONObject("telemetry") ?: JSONObject(), o.optString("phase"),
        )
    }
}

data class BenchState(
    val containerId: Int,
    val combos: List<Combo>,
    val next: Int,
    val running: Boolean,
    /** A run was launched and its outcome not read yet. */
    val awaiting: Boolean,
    val results: List<RunResult>,
    val tempBefore: Float = 0f,
    /** Confirmation series: longer phases (YurnBench --long), each bundle several times, cooling down in between. */
    val long: Boolean = false,
    /** Temperatures when the series started: the cooling target. */
    val baseBattery: Float = 0f,
    val baseCpu: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject().put("containerId", containerId).put("next", next).put("running", running)
        .put("awaiting", awaiting).put("tempBefore", tempBefore.toDouble()).put("long", long)
        .put("baseBattery", baseBattery.toDouble()).put("baseCpu", baseCpu)
        .put("combos", JSONArray().apply { combos.forEach { put(it.toJson()) } })
        .put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): BenchState {
            val c = o.optJSONArray("combos") ?: JSONArray()
            val r = o.optJSONArray("results") ?: JSONArray()
            return BenchState(
                o.optInt("containerId"), (0 until c.length()).map { Combo.from(c.getJSONObject(it)) }, o.optInt("next"),
                o.optBoolean("running"), o.optBoolean("awaiting"), (0 until r.length()).map { RunResult.from(r.getJSONObject(it)) },
                o.optDouble("tempBefore", 0.0).toFloat(), o.optBoolean("long"),
                o.optDouble("baseBattery", 0.0).toFloat(), o.optInt("baseCpu", -1),
            )
        }
    }
}

/**
 * A bundle that doesn't work on this phone with this Proton. Blank fields match anything:
 * a driver + DXVK pair that can't create a D3D11 device fails with every FEX / Box64, so it's
 * stored without them; other failures (crash, hang, no start) are stored for the exact bundle.
 */
data class Conflict(
    val proton: String, val driver: String, val dxvk: String, val emulator: String, val emulatorVersion: String,
    val reason: String, val date: Long, val api: String = Combo.API11, val vkd3d: String = "",
) {
    fun matches(proton: String, c: Combo): Boolean = this.proton == proton && api == c.api &&
        (driver.isEmpty() || driver == c.driverId) &&
        (if (c.dx12) vkd3d.isEmpty() || vkd3d == c.vkd3d else dxvk.isEmpty() || dxvk == c.dxvk) &&
        (emulator.isEmpty() || (emulator == c.emulator && emulatorVersion == c.emulatorVersion))

    val label: String get() = listOfNotNull(
        if (api == Combo.API12) "DX12" else null,
        driver.takeIf { it.isNotEmpty() },
        if (api == Combo.API12) vkd3d.takeIf { it.isNotEmpty() }?.let { "VKD3D $it" } else dxvk.takeIf { it.isNotEmpty() }?.let { "DXVK $it" },
        emulator.takeIf { it.isNotEmpty() }?.let { (if (it == "FEXCore") "FEX " else "Box64 ") + emulatorVersion },
    ).joinToString(" · ") + if (emulator.isEmpty()) " (any FEX / Box64)" else ""

    fun toJson(): JSONObject = JSONObject().put("proton", proton).put("driver", driver).put("dxvk", dxvk)
        .put("emulator", emulator).put("emulatorVersion", emulatorVersion).put("reason", reason).put("date", date)
        .put("api", api).put("vkd3d", vkd3d)

    companion object {
        fun from(o: JSONObject) = Conflict(o.optString("proton"), o.optString("driver"), o.optString("dxvk"),
            o.optString("emulator"), o.optString("emulatorVersion"), o.optString("reason"), o.optLong("date"),
            o.optString("api").ifEmpty { Combo.API11 }, o.optString("vkd3d"))
    }
}

object Bench {
    private const val TAG = "BundleFinder"
    const val EXTRA_BENCH = "yurn_bench"
    private const val BENCH_DIR = "yurnbench"

    fun dir(context: Context) = File(context.filesDir, "bench").apply { mkdirs() }
    private fun stateFile(context: Context) = File(dir(context), "state.json")
    fun outcomeFile(context: Context) = File(dir(context), "outcome.json")

    fun load(context: Context): BenchState? = runCatching { BenchState.from(JSONObject(stateFile(context).readText())) }.getOrNull()

    fun save(context: Context, s: BenchState) {
        val f = stateFile(context)
        val tmp = File(f.parentFile, "state.tmp")
        tmp.writeText(s.toJson().toString())
        tmp.renameTo(f)
    }

    fun clear(context: Context) { stateFile(context).delete(); outcomeFile(context).delete() }

    // ── known conflicts (kept across series; "Clear results" doesn't touch them) ──────────────

    private fun conflictFile(context: Context) = File(dir(context), "conflicts.json")

    fun conflicts(context: Context): List<Conflict> = runCatching {
        val a = JSONArray(conflictFile(context).readText())
        (0 until a.length()).map { Conflict.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveConflicts(context: Context, list: List<Conflict>) =
        conflictFile(context).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())

    fun forgetConflicts(context: Context) { conflictFile(context).delete() }

    fun forgetConflict(context: Context, c: Conflict) = saveConflicts(context, conflicts(context).filter { it != c })

    fun conflictFor(list: List<Conflict>, proton: String, combo: Combo): Conflict? = list.firstOrNull { it.matches(proton, combo) }

    /** Records why [run] failed, as narrowly as the failure says. */
    private fun recordConflict(context: Context, proton: String, run: RunResult) {
        val c = run.combo
        // No D3D11 device / shaders / buffers: the driver and DXVK don't work together, whatever FEX does.
        val pair = run.status == "failed" && listOf("D3D11CreateDevice", "D3D12CreateDevice", "Create", "D3D12Serialize", "IDXGISwapChain",
            "GetBuffer", "shader", "buffers", "upload buffer").any { run.detail.startsWith(it) }
        val now = System.currentTimeMillis() / 1000
        val dxvk = if (c.dx12) "" else c.dxvk
        val conflict = if (pair) Conflict(proton, c.driverId, dxvk, "", "", run.detail, now, c.api, c.vkd3d)
        else Conflict(proton, c.driverId, dxvk, c.emulator, c.emulatorVersion,
            "${run.status}${run.phase.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""}: ${run.detail}", now, c.api, c.vkd3d)
        val list = conflicts(context)
        if (list.none { it.matches(proton, c) && it.emulator.isEmpty() == conflict.emulator.isEmpty() }) saveConflicts(context, list + conflict)
    }

    /** The folder YurnBench runs from and writes into: the container's C:\yurnbench. */
    fun benchFolder(container: Container) = File(container.getRootDir(), ".wine/drive_c/$BENCH_DIR")

    /** Battery temperature in °C (always readable; the SoC sensors often aren't). */
    fun temperature(context: Context): Float = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }.getOrDefault(0f)

    private fun writeConfig(config: String, key: String, value: String, sep: Char): String {
        val out = ArrayList<String>()
        var found = false
        config.split(sep).filter { it.isNotBlank() }.forEach { t ->
            val i = t.indexOf('=')
            if (i > 0 && t.substring(0, i).trim() == key) { out += "$key=$value"; found = true } else out += t.trim()
        }
        if (!found) out += "$key=$value"
        return out.joinToString(sep.toString())
    }

    /** Copies YurnBench into the container and writes the run's shortcut. */
    private fun prepare(context: Context, container: Container, combo: Combo, long: Boolean): File {
        val folder = benchFolder(container).apply { mkdirs() }
        listOf("status.json", "result.json", "status.tmp", "result.tmp").forEach { File(folder, it).delete() }
        val exe = File(folder, "yurnbench.exe")
        context.assets.open("yurnbench/yurnbench.exe").use { input -> exe.outputStream().use { input.copyTo(it) } }

        val dxwrapper = container.getDXWrapper().takeIf { it.contains("dxvk") } ?: "dxvk+vkd3d"
        val shortcut = File(dir(context), "run.desktop")
        val extras = linkedMapOf(
            "graphicsDriverConfig" to writeConfig(container.getGraphicsDriverConfig(), "version", combo.driverId, ';'),
            "dxwrapper" to dxwrapper,
            "dxwrapperConfig" to writeConfig(container.getDXWrapperConfig(), "version", combo.dxvk, ',').let {
                if (combo.dx12) writeConfig(it, "vkd3dVersion", combo.vkd3d, ',') else it
            },
            "execArgs" to listOfNotNull(if (combo.dx12) "--dx12" else null, if (long) "--long" else null).joinToString(" "),
            "emulator" to combo.emulator,
            if (combo.emulator == "FEXCore") "fexcoreVersion" to combo.emulatorVersion else "box64Version" to combo.emulatorVersion,
        )
        // The guest sees the active container's home as /home/xuser.
        val guestExe = "/home/xuser/.wine/drive_c/$BENCH_DIR/yurnbench.exe"
        val text = buildString {
            append("[Desktop Entry]\nName=YurnBench\nExec=env WINEPREFIX=\"/home/xuser/.wine\" wine \"$guestExe\"\nType=Application\n")
            append("container_id:${container.id}\n\n[Extra Data]\n")
            extras.forEach { (k, v) -> append("$k=$v\n") }
        }
        FileUtils.writeString(shortcut, text)
        return shortcut
    }

    /** Starts the series' next run; false when there is none (the series is then marked finished). */
    fun launchNext(activity: Activity): Boolean {
        val s = load(activity) ?: return false
        if (!s.running || s.next >= s.combos.size) {
            save(activity, s.copy(running = false, awaiting = false))
            return false
        }
        val container = ContainerManager(activity).getContainerById(s.containerId) ?: run {
            save(activity, s.copy(running = false)); return false
        }
        val combo = s.combos[s.next]
        val proton = container.getWineVersion().orEmpty()
        conflictFor(conflicts(activity), proton, combo)?.let { known ->
            save(activity, s.copy(next = s.next + 1, results = s.results + RunResult(combo, "skipped", "known conflict: ${known.reason}", "",
                0.0, 0.0, Phase(0.0, 0.0, 0), Phase(0.0, 0.0, 0), 0.0, 0f, 0f)))
            return launchNext(activity)
        }
        val shortcut = try { prepare(activity, container, combo, s.long) } catch (e: Exception) {
            Log.e(TAG, "prepare", e)
            save(activity, s.copy(results = s.results + RunResult(combo, "failed", "couldn't prepare: ${e.message}", "", 0.0, 0.0,
                Phase(0.0, 0.0, 0), Phase(0.0, 0.0, 0), 0.0, 0f, 0f), next = s.next + 1))
            return launchNext(activity)
        }
        outcomeFile(activity).delete()
        save(activity, s.copy(awaiting = true, tempBefore = temperature(activity)))
        Log.i(TAG, "run ${s.next + 1}/${s.combos.size}: ${combo.label}")
        activity.startActivity(Intent(activity, XServerDisplayActivity::class.java)
            .putExtra("container_id", container.id)
            .putExtra("shortcut_path", shortcut.path)
            .putExtra("shortcut_name", "YurnBench")
            .putExtra(EXTRA_BENCH, true))
        return true
    }

    /** Back from a run: records its outcome. True when another run should start. */
    fun onReturn(context: Context): Boolean {
        val s = load(context) ?: return false
        if (!s.awaiting) return s.running && s.next < s.combos.size
        val container = ContainerManager(context).getContainerById(s.containerId)
        val combo = s.combos.getOrNull(s.next) ?: return false
        val outcome = runCatching { JSONObject(outcomeFile(context).readText()) }.getOrNull()
        val result = container?.let { runCatching { JSONObject(File(benchFolder(it), "result.json").readText()) }.getOrNull() }
        val status = when {
            result != null && result.optBoolean("done") -> "ok"
            result != null -> "failed"
            outcome != null -> outcome.optString("status", "crash")
            else -> "crash"
        }
        val detail = when {
            result != null && result.optString("error").isNotBlank() -> result.optString("error")
            outcome != null -> outcome.optString("detail")
            else -> "the session ended without a result"
        }
        val run = RunResult(
            combo, status, detail, result?.optString("adapter").orEmpty(),
            result?.optJSONObject("cpu")?.optDouble("st", 0.0) ?: 0.0, result?.optJSONObject("cpu")?.optDouble("mt", 0.0) ?: 0.0,
            Phase.from(result?.optJSONObject("gpu")), Phase.from(result?.optJSONObject("mix")),
            outcome?.optDouble("seconds", 0.0) ?: 0.0, s.tempBefore, temperature(context),
            (outcome?.optJSONObject("telemetry") ?: JSONObject()).also { t ->
                outcome?.optJSONObject("root")?.let { t.put("root", it) }
                result?.optJSONObject("gpu")?.optInt("reps")?.let { t.put("gpu_reps", it) }
            }, outcome?.optString("phase").orEmpty(),
        )
        if (!run.ok && container != null) recordConflict(context, container.getWineVersion().orEmpty(), run)
        val next = s.copy(results = s.results + run, next = s.next + 1, awaiting = false)
        save(context, next)
        return next.running && next.next < next.combos.size
    }

    /** CPU temperature in °C, or -1 when the system doesn't show it. */
    fun cpuTemp(): Int = com.winlator.cmod.core.CPUStatus.getCpuTempC()

    /**
     * Whether the phone should cool down before the next run of [s]: the CPU back within 5 °C of
     * where the series started (or under 45 °C), else the battery within 1.5 °C.
     */
    fun tooHot(context: Context, s: BenchState): String? {
        val cpu = cpuTemp()
        if (cpu > 0 && s.baseCpu > 0) return if (cpu > maxOf(s.baseCpu + 5, 45)) "CPU $cpu °C → ${maxOf(s.baseCpu + 5, 45)} °C" else null
        val bat = temperature(context)
        if (bat > 0 && s.baseBattery > 0 && bat > s.baseBattery + 1.5f) return "battery ${"%.1f".format(bat)} °C → ${"%.1f".format(s.baseBattery + 1.5f)} °C"
        return null
    }

    /**
     * Groups repeated runs of the same bundle: mean score and the spread (lowest / highest),
     * best first. Failed runs count against a bundle only when it never succeeded.
     */
    data class Aggregate(val combo: Combo, val runs: List<RunResult>) {
        val ok: List<RunResult> get() = runs.filter { it.ok }
        val score: Int get() = ok.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.toInt() ?: 0
        val min: Int get() = ok.minOfOrNull { it.score } ?: 0
        val max: Int get() = ok.maxOfOrNull { it.score } ?: 0
        val spreadPercent: Int get() = if (score > 0) (max - min) * 50 / score else 0
    }

    fun aggregate(results: List<RunResult>): List<Aggregate> =
        results.groupBy { it.combo.key }.map { (_, runs) -> Aggregate(runs.first().combo, runs) }.sortedByDescending { it.score }

    /** Top [n] working bundles per API of [s], as a confirmation series: each [repeats] times, long phases. */
    fun confirmation(s: BenchState, n: Int = 3, repeats: Int = 3): List<Combo> {
        val top = aggregate(s.results).filter { it.ok.isNotEmpty() }.groupBy { it.combo.api }.values
            .flatMap { it.take(n) }.map { it.combo }
        // Rounds, not one bundle three times in a row: warming up over the series favours no one.
        return (0 until repeats).flatMap { top }
    }

    /** Makes [combo] the container's own settings. */
    fun apply(context: Context, containerId: Int, combo: Combo): Boolean {
        val c = ContainerManager(context).getContainerById(containerId) ?: return false
        c.setGraphicsDriverConfig(writeConfig(c.getGraphicsDriverConfig(), "version", combo.driverId, ';'))
        if (!c.getDXWrapper().contains("dxvk")) c.setDXWrapper("dxvk+vkd3d")
        c.setDXWrapperConfig(writeConfig(c.getDXWrapperConfig(), "version", combo.dxvk, ',').let {
            if (combo.dx12) writeConfig(it, "vkd3dVersion", combo.vkd3d, ',') else it
        })
        c.setEmulator(combo.emulator)
        if (combo.emulator == "FEXCore") c.setFEXCoreVersion(combo.emulatorVersion) else c.setBox64Version(combo.emulatorVersion)
        c.saveData()
        return true
    }
}
