package com.winlator.cmod.bench

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
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLaunch: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent { WinZTheme { BenchScreen(state, ::start, ::stop, ::reset) } }
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

    /** A few seconds between runs: the last session's Wine processes are gone and the phone settles. */
    private fun scheduleNext() {
        pendingLaunch?.let(handler::removeCallbacks)
        val r = Runnable {
            pendingLaunch = null
            val a = activity ?: return@Runnable
            if (!Bench.launchNext(a)) state.value = Bench.load(a)
        }
        pendingLaunch = r
        handler.postDelayed(r, 4000)
    }

    private fun start(containerId: Int, combos: List<Combo>) {
        val ctx = context ?: return
        Bench.save(ctx, BenchState(containerId, combos, 0, running = true, awaiting = false, results = emptyList()))
        state.value = Bench.load(ctx)
        scheduleNext()
    }

    private fun stop() {
        val ctx = context ?: return
        pendingLaunch?.let(handler::removeCallbacks)
        pendingLaunch = null
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
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchScreen(
    state: MutableState<BenchState?>,
    start: (Int, List<Combo>) -> Unit,
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

    LaunchedEffect(container?.id) {
        val c = container ?: return@LaunchedEffect
        choices = null
        drivers.clear(); dxvks.clear(); emus.clear()
        val arm64 = c.getWineVersion().orEmpty().contains("arm64ec", true)
        val cat = withContext(Dispatchers.IO) { runCatching { loadSettingsCatalog(context, arm64, allowNetwork = false) }.getOrNull() }
        if (cat == null) { choices = Choices(emptyList(), emptyList(), emptyList()); return@LaunchedEffect }
        val emuList = if (arm64) cat.fex.installed.map { "FEXCore" to it } + cat.wow.installed.map { "Box64" to it }
        else cat.box.installed.map { "Box64" to it }
        choices = Choices(
            cat.drivers.filter { it.installed }.map { it.id to it.label },
            cat.dxvk.installed.filter { it.isNotBlank() && !it.equals("None", true) },
            emuList,
        )
        // Start from what the container uses now.
        val cfgDriver = Regex("(?:^|;)version=([^;]*)").find(c.getGraphicsDriverConfig())?.groupValues?.get(1).orEmpty()
        choices?.drivers?.firstOrNull { it.first.equals(cfgDriver, true) }?.let { drivers += it.first }
        val cfgDxvk = Regex("(?:^|,)version=([^,]*)").find(c.getDXWrapperConfig())?.groupValues?.get(1).orEmpty()
        if (cfgDxvk in (choices?.dxvk ?: emptyList())) dxvks += cfgDxvk
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
            item { Progress(s, stop, reset) }
            val ranked = s.results.sortedByDescending { it.score }
            val best = ranked.firstOrNull { it.ok }
            items(ranked.size) { i ->
                val r = ranked[i]
                ResultCard(r, r === best) {
                    if (Bench.apply(context, s.containerId, r.combo)) Toast.makeText(context, "Applied to the container", Toast.LENGTH_SHORT).show()
                }
            }
            if (s.running) return@LazyColumn
            item { Spacer(Modifier.height(8.dp)); Text("New series", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
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
                val combos = drivers.flatMap { d ->
                    dxvks.flatMap { x -> emus.map { e -> Combo(d, ch.drivers.firstOrNull { it.first == d }?.second ?: d, x, e.first, e.second) } }
                }
                val minutes = (combos.size * 50 + 59) / 60
                Text("${combos.size} bundles, about $minutes min." + if (combos.size > 24) " Large series: pick fewer for a first try." else "",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(enabled = combos.isNotEmpty() && s?.running != true && container != null, onClick = {
                    start(container!!.id, combos)
                }) { Text("Start") }
            }
        }
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
private fun Progress(s: BenchState, stop: () -> Unit, reset: () -> Unit) {
    Section(if (s.running) "Running: ${minOf(s.next + 1, s.combos.size)} of ${s.combos.size}" else "Series: ${s.results.size} of ${s.combos.size} done") {
        LinearProgressIndicator(progress = { if (s.combos.isEmpty()) 0f else s.results.size.toFloat() / s.combos.size }, modifier = Modifier.fillMaxWidth())
        if (s.running) s.combos.getOrNull(s.next)?.let { Text("Next: " + it.label, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.running) OutlinedButton(onClick = stop) { Text("Stop") }
            TextButton(onClick = reset) { Text("Clear results") }
        }
    }
}

@Composable
private fun ResultCard(r: RunResult, best: Boolean, apply: () -> Unit) {
    val border = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (best) 2.dp else 1.dp, border)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.combo.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (r.ok) "${r.score}" else r.status, fontWeight = FontWeight.Bold,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            if (r.ok) {
                Text("GPU ${f(r.gpu.avg)} FPS (1% low ${f(r.gpu.low1)}) · CPU+GPU ${f(r.mix.avg)} FPS (1% low ${f(r.mix.low1)})",
                    style = MaterialTheme.typography.bodySmall)
                Text("CPU ${r.cpuSt.toInt()} / ${r.cpuMt.toInt()} Mops (1 core / all) · battery ${f(r.tempBefore.toDouble())}→${f(r.tempAfter.toDouble())} °C" +
                    (if (r.adapter.isNotBlank()) " · ${r.adapter}" else ""),
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
