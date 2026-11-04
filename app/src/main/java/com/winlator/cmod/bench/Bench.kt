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
    val dxvk: String,
    /** "FEXCore" or "Box64" (WOWBox64 on arm64ec is stored as Box64, like the editors do). */
    val emulator: String,
    val emulatorVersion: String,
) {
    val label: String get() = "$driverLabel · DXVK $dxvk · ${if (emulator == "FEXCore") "FEX" else "Box64"} $emulatorVersion"

    fun toJson(): JSONObject = JSONObject().put("driver", driverId).put("driverLabel", driverLabel).put("dxvk", dxvk)
        .put("emulator", emulator).put("emulatorVersion", emulatorVersion)

    companion object {
        fun from(o: JSONObject) = Combo(o.optString("driver"), o.optString("driverLabel"), o.optString("dxvk"),
            o.optString("emulator"), o.optString("emulatorVersion"))
    }
}

data class Phase(val avg: Double, val low1: Double, val frames: Int) {
    companion object {
        fun from(o: JSONObject?) = Phase(o?.optDouble("avg", 0.0) ?: 0.0, o?.optDouble("low1", 0.0) ?: 0.0, o?.optInt("frames") ?: 0)
    }
}

data class RunResult(
    val combo: Combo,
    /** ok | failed | crash | timeout */
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
) {
    val ok: Boolean get() = status == "ok"

    /**
     * One number to sort by: frame rates weigh most (GPU and CPU+GPU, their 1% lows half as
     * much), the multi-core CPU score adds a little. Same formula for every run, so only the
     * order matters.
     */
    val score: Int get() = if (!ok) 0 else
        (4 * gpu.avg + 4 * mix.avg + 2 * (gpu.low1 + mix.low1) + cpuMt / 50.0).toInt()

    fun toJson(): JSONObject = JSONObject().put("combo", combo.toJson()).put("status", status).put("detail", detail)
        .put("adapter", adapter).put("cpuSt", cpuSt).put("cpuMt", cpuMt)
        .put("gpu", JSONObject().put("avg", gpu.avg).put("low1", gpu.low1).put("frames", gpu.frames))
        .put("mix", JSONObject().put("avg", mix.avg).put("low1", mix.low1).put("frames", mix.frames))
        .put("seconds", seconds).put("tempBefore", tempBefore.toDouble()).put("tempAfter", tempAfter.toDouble())

    companion object {
        fun from(o: JSONObject) = RunResult(
            Combo.from(o.optJSONObject("combo") ?: JSONObject()), o.optString("status"), o.optString("detail"),
            o.optString("adapter"), o.optDouble("cpuSt", 0.0), o.optDouble("cpuMt", 0.0),
            Phase.from(o.optJSONObject("gpu")), Phase.from(o.optJSONObject("mix")), o.optDouble("seconds", 0.0),
            o.optDouble("tempBefore", 0.0).toFloat(), o.optDouble("tempAfter", 0.0).toFloat(),
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
) {
    fun toJson(): JSONObject = JSONObject().put("containerId", containerId).put("next", next).put("running", running)
        .put("awaiting", awaiting).put("tempBefore", tempBefore.toDouble())
        .put("combos", JSONArray().apply { combos.forEach { put(it.toJson()) } })
        .put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): BenchState {
            val c = o.optJSONArray("combos") ?: JSONArray()
            val r = o.optJSONArray("results") ?: JSONArray()
            return BenchState(
                o.optInt("containerId"), (0 until c.length()).map { Combo.from(c.getJSONObject(it)) }, o.optInt("next"),
                o.optBoolean("running"), o.optBoolean("awaiting"), (0 until r.length()).map { RunResult.from(r.getJSONObject(it)) },
                o.optDouble("tempBefore", 0.0).toFloat(),
            )
        }
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
    private fun prepare(context: Context, container: Container, combo: Combo): File {
        val folder = benchFolder(container).apply { mkdirs() }
        listOf("status.json", "result.json", "status.tmp", "result.tmp").forEach { File(folder, it).delete() }
        val exe = File(folder, "yurnbench.exe")
        context.assets.open("yurnbench/yurnbench.exe").use { input -> exe.outputStream().use { input.copyTo(it) } }

        val dxwrapper = container.getDXWrapper().takeIf { it.contains("dxvk") } ?: "dxvk+vkd3d"
        val shortcut = File(dir(context), "run.desktop")
        val extras = linkedMapOf(
            "graphicsDriverConfig" to writeConfig(container.getGraphicsDriverConfig(), "version", combo.driverId, ';'),
            "dxwrapper" to dxwrapper,
            "dxwrapperConfig" to writeConfig(container.getDXWrapperConfig(), "version", combo.dxvk, ','),
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
        val shortcut = try { prepare(activity, container, combo) } catch (e: Exception) {
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
        )
        val next = s.copy(results = s.results + run, next = s.next + 1, awaiting = false)
        save(context, next)
        return next.running && next.next < next.combos.size
    }

    /** Makes [combo] the container's own settings. */
    fun apply(context: Context, containerId: Int, combo: Combo): Boolean {
        val c = ContainerManager(context).getContainerById(containerId) ?: return false
        c.setGraphicsDriverConfig(writeConfig(c.getGraphicsDriverConfig(), "version", combo.driverId, ';'))
        if (!c.getDXWrapper().contains("dxvk")) c.setDXWrapper("dxvk+vkd3d")
        c.setDXWrapperConfig(writeConfig(c.getDXWrapperConfig(), "version", combo.dxvk, ','))
        c.setEmulator(combo.emulator)
        if (combo.emulator == "FEXCore") c.setFEXCoreVersion(combo.emulatorVersion) else c.setBox64Version(combo.emulatorVersion)
        c.saveData()
        return true
    }
}
