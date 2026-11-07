package com.winlator.cmod.bench

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Switch
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.ui.settings.loadSettingsCatalog
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bundle finder tab (hidden developer option, rightmost in the bottom bar). */
class BenchFragment : Fragment() {
    private val state = mutableStateOf<BenchState?>(null)
    private val cooling = mutableStateOf<String?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLaunch: Runnable? = null
    private var coolSince = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent { WinZTheme { BenchScreen(state, cooling, ::start, ::stop, ::reset) } }
        }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        val more = Bench.onReturn(ctx)
        state.value = Bench.load(ctx)
        if (more) scheduleNext()
    }

    override fun onPause() {
        super.onPause()
        pendingLaunch?.let(handler::removeCallbacks)
        pendingLaunch = null
    }

    /**
     * Between runs: a few seconds for the last session's Wine processes to go, then the phone
     * cools down to where the series started (up to 1.5 min, 4 min in a confirmation series).
     */
    private fun scheduleNext(delay: Long = 4000) {
        pendingLaunch?.let(handler::removeCallbacks)
        val r = Runnable {
            pendingLaunch = null
            val a = activity ?: return@Runnable
            val s = Bench.load(a)
            val hot = s?.let { Bench.tooHot(a, it) }
            val limit = if (s?.long == true) 240_000L else 90_000L
            if (hot != null && s.running) {
                if (coolSince == 0L) coolSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - coolSince < limit) {
                    cooling.value = "Cooling down: $hot"
                    scheduleNext(10_000)
                    return@Runnable
                }
            }
            coolSince = 0L
            cooling.value = null
            if (!Bench.launchNext(a)) state.value = Bench.load(a)
        }
        pendingLaunch = r
        handler.postDelayed(r, delay)
    }

    private fun start(containerId: Int, combos: List<Combo>, long: Boolean, wrapper: String = "") {
        val ctx = context ?: return
        Bench.save(ctx, BenchState(containerId, combos, 0, running = true, awaiting = false, results = emptyList(),
            long = long, baseBattery = Bench.temperature(ctx), baseCpu = Bench.cpuTemp(), wrapper = wrapper))
        state.value = Bench.load(ctx)
        scheduleNext()
    }

    private fun stop() {
        val ctx = context ?: return
        pendingLaunch?.let(handler::removeCallbacks)
        pendingLaunch = null
        cooling.value = null
        coolSince = 0L
        Bench.load(ctx)?.let { Bench.save(ctx, it.copy(running = false)) }
        state.value = Bench.load(ctx)
    }

    private fun reset() {
        val ctx = context ?: return
        stop()
        Bench.clear(ctx)
        state.value = null
    }
}

private data class Choices(
    val drivers: List<Pair<String, String>>,   // id to label
    val dxvk: List<String>,
    val emulators: List<Pair<String, String>>, // emulator to version
    val vkd3d: List<String> = emptyList(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchScreen(
    state: MutableState<BenchState?>,
    cooling: MutableState<String?>,
    start: (Int, List<Combo>, Boolean, String) -> Unit,
    stop: () -> Unit,
    reset: () -> Unit,
) {
    val context = LocalContext.current
    val containers = remember { ContainerManager(context).containers.toList() }
    var container by remember { mutableStateOf<Container?>(containers.firstOrNull()) }
    var choices by remember { mutableStateOf<Choices?>(null) }
    val drivers = remember { mutableStateListOf<String>() }
    val dxvks = remember { mutableStateListOf<String>() }
    val emus = remember { mutableStateListOf<Pair<String, String>>() }
    val vkd3ds = remember { mutableStateListOf<String>() }
    var dx11 by remember { mutableStateOf(true) }
    var dx12 by remember { mutableStateOf(false) }
    var wrapper by remember { mutableStateOf("") }
    var conflicts by remember { mutableStateOf(Bench.conflicts(context)) }
    LaunchedEffect(state.value) { conflicts = Bench.conflicts(context) }

    LaunchedEffect(container?.id) {
        val c = container ?: return@LaunchedEffect
        choices = null
        drivers.clear(); dxvks.clear(); emus.clear(); vkd3ds.clear()
        val arm64 = c.getWineVersion().orEmpty().contains("arm64ec", true)
        val cat = withContext(Dispatchers.IO) { runCatching { loadSettingsCatalog(context, arm64, allowNetwork = false) }.getOrNull() }
        if (cat == null) { choices = Choices(emptyList(), emptyList(), emptyList()); return@LaunchedEffect }
        val emuList = if (arm64) cat.fex.installed.map { "FEXCore" to it } + cat.wow.installed.map { "Box64" to it }
        else cat.box.installed.map { "Box64" to it }
        choices = Choices(
            cat.drivers.filter { it.installed }.map { it.id to it.label },
            cat.dxvk.installed.filter { it.isNotBlank() && !it.equals("None", true) },
            emuList,
            cat.vkd3d.installed.filter { it.isNotBlank() && !it.equals("None", true) },
        )
        // Start from what the container uses now.
        val cfgDriver = Regex("(?:^|;)version=([^;]*)").find(c.getGraphicsDriverConfig())?.groupValues?.get(1).orEmpty()
        choices?.drivers?.firstOrNull { it.first.equals(cfgDriver, true) }?.let { drivers += it.first }
        val cfgDxvk = Regex("(?:^|,)version=([^,]*)").find(c.getDXWrapperConfig())?.groupValues?.get(1).orEmpty()
        if (cfgDxvk in (choices?.dxvk ?: emptyList())) dxvks += cfgDxvk
        val cfgVkd3d = Regex("(?:^|,)vkd3dVersion=([^,]*)").find(c.getDXWrapperConfig())?.groupValues?.get(1).orEmpty()
        if (cfgVkd3d in (choices?.vkd3d ?: emptyList())) vkd3ds += cfgVkd3d
        val cur = if (c.getEmulator().equals("FEXCore", true)) "FEXCore" to c.getFEXCoreVersion().orEmpty() else "Box64" to c.getBox64Version().orEmpty()
        if (cur in emuList) emus += cur
    }

    val s = state.value
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Bundle finder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Runs YurnBench (CPU, GPU and CPU+GPU, about 40 s each) with every chosen GPU driver, DXVK and FEX / Box64. " +
                "Proton stays the container's. Keep the phone on the charger, screen on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (s != null && (s.running || s.results.isNotEmpty())) {
            item { Progress(s, cooling.value, stop, reset) }
            if (!s.running && !s.long && s.results.any { it.ok }) item {
                Section("Confirm the best") {
                    Text("Runs the 3 best bundles of each API 3 times each with phases 2.5× longer, cooling down in between, " +
                        "and shows the average and the spread. Takes about ${Bench.confirmation(s).size * 2} min.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { start(s.containerId, Bench.confirmation(s), true, s.wrapper) }) { Text("Confirm best (long runs)") }
                }
            }
            listOf(Combo.API11 to "DX11 (DXVK)", Combo.API12 to "DX12 (VKD3D)").forEach { (api, title) ->
                val runs = s.results.filter { it.combo.api == api }
                if (runs.isEmpty()) return@forEach
                val groups = Bench.aggregate(runs)
                val bestGroup = groups.firstOrNull { it.ok.isNotEmpty() }
                val bestKey = bestGroup?.combo?.key
                item { Text(title + if (s.long) " · confirmation, ${groups.firstOrNull()?.runs?.size ?: 0} runs each" else "",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(groups.size) { i ->
                    val g = groups[i]
                    val shown = g.ok.maxByOrNull { it.score } ?: g.runs.last()
                    val tie = bestGroup != null && g.ok.isNotEmpty() && Bench.tie(g, bestGroup)
                    ResultCard(shown, g.combo.key == bestKey, if (g.runs.size > 1) g else null, tie) {
                        if (Bench.apply(context, s.containerId, g.combo)) Toast.makeText(context, "Applied to the container", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (s.running) return@LazyColumn
            item { Spacer(Modifier.height(8.dp)); Text("New series", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        }

        item {
            var rootOn by remember { mutableStateOf(RootMode.enabled(context)) }
            var checking by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Section("Precise measurements (root)") {
                Text("With root: real CPU cycles and instructions (Android's simpleperf) and the GPU clock and load " +
                    "the system hides from apps. Without root: the game's own CPU time and the CPU clocks.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rootOn) "On" else "Off", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Switch(checked = rootOn, onCheckedChange = { want ->
                        if (!want) { rootOn = false; RootMode.setEnabled(context, false); return@Switch }
                        checking = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootMode.available() }
                            checking = false
                            if (ok) { rootOn = true; RootMode.setEnabled(context, true) }
                            else Toast.makeText(context, "No root access (or it was denied)", Toast.LENGTH_LONG).show()
                        }
                    })
                }
            }
        }
        item {
            Section("Container (its Proton stays for the whole series)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    containers.forEach { c ->
                        FilterChip(selected = container?.id == c.id, onClick = { container = c },
                            label = { Text(c.name + " · " + c.getWineVersion().orEmpty().substringBefore("-arm64ec").take(24)) })
                    }
                }
            }
        }
        item {
            // One Vulkan wrapper for the whole series, as Proton: the bundles are compared under it.
            val wrappers = remember { com.winlator.cmod.contents.WrapperManager.installed(context) }
            val containerWrapper = container?.let {
                Regex("(?:^|;)wrapper=([^;]*)").find(it.getGraphicsDriverConfig().orEmpty())?.groupValues?.get(1)
            }.orEmpty()
            Section("Vulkan wrapper (stays for the whole series)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = wrapper.isEmpty(), onClick = { wrapper = "" },
                        label = { Text("Container's" + (wrappers.firstOrNull { it.id == containerWrapper }?.let { " (${it.name})" } ?: "")) })
                    wrappers.forEach { w ->
                        FilterChip(selected = wrapper == w.id, onClick = { wrapper = w.id },
                            label = { Text(w.name + (w.author?.let { " · $it" } ?: "")) })
                    }
                }
            }
        }
        val ch = choices
        if (container != null && ch == null) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        if (ch != null) {
            item {
                Section("GPU drivers (${drivers.size})") {
                    if (ch.drivers.isEmpty()) Text("No installed drivers.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ch.drivers.forEach { (id, label) ->
                            FilterChip(selected = id in drivers, onClick = { if (id in drivers) drivers.remove(id) else drivers.add(id) }, label = { Text(label) })
                        }
                    }
                }
            }
            item {
                Section("API") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = dx11, onClick = { dx11 = !dx11 }, label = { Text("DX11 (DXVK)") })
                        FilterChip(selected = dx12, onClick = { dx12 = !dx12 }, label = { Text("DX12 (VKD3D)") })
                    }
                    if (dx12) Text("DX12 runs use the container's DXVK for presenting and compare the VKD3D versions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (dx12) item {
                Section("VKD3D (${vkd3ds.size})") {
                    if (ch.vkd3d.isEmpty()) Text("No installed VKD3D.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ch.vkd3d.forEach { v -> FilterChip(selected = v in vkd3ds, onClick = { if (v in vkd3ds) vkd3ds.remove(v) else vkd3ds.add(v) }, label = { Text(v) }) }
                    }
                }
            }
            if (dx11) item {
                Section("DXVK (${dxvks.size})") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ch.dxvk.forEach { v -> FilterChip(selected = v in dxvks, onClick = { if (v in dxvks) dxvks.remove(v) else dxvks.add(v) }, label = { Text(v) }) }
                    }
                }
            }
            item {
                Section("FEX / Box64 (${emus.size})") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ch.emulators.forEach { e ->
                            FilterChip(selected = e in emus, onClick = { if (e in emus) emus.remove(e) else emus.add(e) },
                                label = { Text((if (e.first == "FEXCore") "FEX " else "Box64 ") + e.second) })
                        }
                    }
                }
            }
            item {
                val c0 = container
                val currentDxvk = c0?.let { Regex("(?:^|,)version=([^,]*)").find(it.getDXWrapperConfig())?.groupValues?.get(1) }.orEmpty()
                fun label(d: String) = ch.drivers.firstOrNull { it.first == d }?.second ?: d
                val combos = (if (dx11) drivers.flatMap { d ->
                    dxvks.flatMap { x -> emus.map { e -> Combo(d, label(d), x, e.first, e.second) } }
                } else emptyList()) + (if (dx12) drivers.flatMap { d ->
                    vkd3ds.flatMap { v -> emus.map { e -> Combo(d, label(d), currentDxvk.ifEmpty { dxvks.firstOrNull().orEmpty() }, e.first, e.second, Combo.API12, v) } }
                } else emptyList())
                val proton = container?.getWineVersion().orEmpty()
                val known = combos.count { Bench.conflictFor(conflicts, proton, it) != null }
                val minutes = ((combos.size - known) * 50 + 59) / 60
                Text("${combos.size} bundles" + (if (known > 0) ", $known skipped as known conflicts" else "") +
                    ", about $minutes min." + if (combos.size - known > 24) " Large series: pick fewer for a first try." else "",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(enabled = combos.isNotEmpty() && s?.running != true && container != null, onClick = {
                    start(container!!.id, combos, false, wrapper)
                }) { Text("Start") }
            }
        }
        val proton = container?.getWineVersion().orEmpty()
        val mine = conflicts.filter { it.proton == proton }
        if (mine.isNotEmpty()) item {
            Section("Known conflicts with this Proton (${mine.size}): not run again") {
                mine.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.bodyMedium)
                            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        TextButton(onClick = { Bench.forgetConflict(context, c); conflicts = Bench.conflicts(context) }) { Text("Retry") }
                    }
                }
                TextButton(onClick = { Bench.forgetConflicts(context); conflicts = emptyList() }) { Text("Forget all conflicts") }
            }
        }
        item { HistorySection() }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Progress(s: BenchState, cooling: String?, stop: () -> Unit, reset: () -> Unit) {
    Section((if (s.long) "Confirmation. " else "") +
        if (s.running) "Running: ${minOf(s.next + 1, s.combos.size)} of ${s.combos.size}" else "Series: ${s.results.size} of ${s.combos.size} done") {
        cooling?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF58A6FF)) }
        LinearProgressIndicator(progress = { if (s.combos.isEmpty()) 0f else s.results.size.toFloat() / s.combos.size }, modifier = Modifier.fillMaxWidth())
        if (s.running) s.combos.getOrNull(s.next)?.let { Text("Next: " + it.label, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.running) OutlinedButton(onClick = stop) { Text("Stop") }
            TextButton(onClick = reset) { Text("Clear results") }
        }
    }
}

@Composable
private fun ResultCard(r: RunResult, best: Boolean, agg: Bench.Aggregate?, tie: Boolean = false, apply: () -> Unit) {
    val border = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (best) 2.dp else 1.dp, border)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.combo.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (agg != null && agg.ok.isNotEmpty()) "${agg.score}" else if (r.ok) "${r.score}" else r.status, fontWeight = FontWeight.Bold,
                    color = when { r.ok -> MaterialTheme.colorScheme.primary; r.status == "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error })
            }
            if (tie) Text("≈ as good as the best: within 1%, the run-to-run noise", style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FB950))
            if (agg != null) Text("Average of ${agg.ok.size} of ${agg.runs.size} runs: ${agg.min}–${agg.max} (±${agg.spreadPercent}%)" +
                (if (agg.ok.size < agg.runs.size) " · ${agg.runs.size - agg.ok.size} failed" else "") + ". Details: the best run.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF58A6FF))
            if (r.ok) {
                Text("GPU ${f(r.gpu.avg)} FPS (1% low ${f(r.gpu.low1)}) · CPU+GPU ${f(r.mix.avg)} FPS (1% low ${f(r.mix.low1)})",
                    style = MaterialTheme.typography.bodySmall)
                if (r.gpu.gpuMs > 0) Text("GPU work ${String.format("%.1f", r.gpu.gpuMs)} ms per scene → ${r.gpu.gpuFps.toInt()} FPS" +
                    (if (r.gpu.gpuLow1 > 0) " (1% low ${r.gpu.gpuLow1.toInt()})" else "") +
                    (if (r.gpuReps > 1) " at full GPU load (${r.gpuReps} scenes per frame)" else " if only the GPU limited") +
                    (if (r.mix.gpuMs > 0) " · CPU+GPU phase ${String.format("%.1f", r.mix.gpuMs)} ms" else ""),
                    style = MaterialTheme.typography.bodySmall)
                Text("CPU ${r.cpuSt.toInt()} / ${r.cpuMt.toInt()} Mops (1 core / all)" +
                    (if (r.cpuPerMcycle > 0) " · ${String.format("%.2f", r.cpuPerMcycle)} ops per CPU cycle" +
                        when (r.cyclesSource) { "hw" -> " (hardware counters)"; "busy" -> " (game's CPU time)"; else -> " (from clocks)" } else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cpuUse(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(clocks(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.throttlePercent >= 3) Text("Throttled: ~${r.scoreFullClocks} at full clocks (+${r.throttlePercent}%), an estimate",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFE3B341))
                Text("Battery ${f(r.tempBefore.toDouble())}→${f(r.tempAfter.toDouble())} °C" +
                    (if (r.maxCpuTemp > 0) " · CPU up to ${r.maxCpuTemp} °C" else "") + (if (r.adapter.isNotBlank()) " · ${r.adapter}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (r.detail.isNotBlank()) Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (best) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Best", color = Color(0xFF3FB950), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = apply) { Text("Apply to container") }
            }
        }
    }
}

private fun f(v: Double) = if (v >= 100) v.toInt().toString() else String.format("%.1f", v)

/** "GPU 640 MHz (71% of max), busy 96% · CPU 2.4 GHz (88%)": clocks per phase from the telemetry. */
private fun clocks(r: RunResult): String {
    val parts = ArrayList<String>()
    val g = r.gpuMhz("gpu")
    if (g > 0) parts += "GPU ${g.toInt()} MHz" + (r.gpuRatio?.let { " (${(it * 100).toInt()}%)" } ?: "") +
        r.gpuBusy("gpu").takeIf { it >= 0 }?.let { ", busy ${it.toInt()}%" }.orEmpty()
    else r.gpuBusy("gpu").takeIf { it >= 0 }?.let { parts += "GPU busy ${it.toInt()}%" }
    val c = r.cpuMhz("cpu")
    if (c > 0) parts += "CPU ${String.format("%.2f", c / 1000)} GHz" + (r.cpuMtRatio?.let { " (${(it * 100).toInt()}%)" } ?: "")
    return if (parts.isEmpty()) "Clocks: not readable on this phone" else parts.joinToString(" · ")
}

/** "Game CPU: 87% of all cores, 21.4 s CPU time · IPC 1.42" from the CPU and CPU+GPU phases. */
private fun cpuUse(r: RunResult): String? {
    val parts = ArrayList<String>()
    val load = r.cpuLoad("cpu")
    if (load >= 0) parts += "Game CPU load ${(load * 100).toInt()}% of all cores (CPU phase), ${String.format("%.1f", r.procCpu("cpu") + r.procCpu("gpu") + r.procCpu("mix"))} s CPU time"
    val mixLoad = r.cpuLoad("mix")
    if (mixLoad >= 0) parts += "${(mixLoad * 100).toInt()}% in CPU+GPU"
    if (r.ipc("cpu") > 0) parts += "IPC ${String.format("%.2f", r.ipc("cpu"))}"
    if (r.rootUsed && r.rootNote.isNotBlank()) parts += "root: ${r.rootNote}"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun HistorySection() {
    val context = LocalContext.current
    var history by remember { mutableStateOf(Bench.history(context)) }
    var open by remember { mutableStateOf(false) }
    if (history.isEmpty()) return
    Section("History of best bundles (${history.size})") {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        if (open) {
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            history.forEach { h ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (h.api == Combo.API12) "DX12 · " else "DX11 · ") + h.label, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("${h.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(listOf(fmt.format(java.util.Date(h.date * 1000)),
                        if (h.long) "confirmed, ${h.runs} runs ±${h.spread}%" else "best of ${h.bundles}",
                        "GPU ${h.gpuFps.toInt()} · CPU+GPU ${f(h.mixFps)} FPS", h.container + " · " + h.proton)
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { Bench.clearHistory(context); history = emptyList() }) { Text("Clear history") }
        }
    }
}
