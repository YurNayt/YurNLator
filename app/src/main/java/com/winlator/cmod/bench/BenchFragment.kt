package com.winlator.cmod.bench

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateMapOf
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
        // The toolbar is shared by all tabs; without this it keeps "Library".
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = uiStr(R.string.l_nav_benchmark)
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
                    cooling.value = uiStr(R.string.l_bench_cooling_down_1_s, hot)
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

    private fun start(containerId: Int, combos: List<Combo>, long: Boolean, wrapper: String = "", game: BenchGame? = null) {
        val ctx = context ?: return
        Bench.save(ctx, BenchState(containerId, combos, 0, running = true, awaiting = false, results = emptyList(),
            long = long, baseBattery = Bench.temperature(ctx), baseCpu = Bench.cpuTemp(), wrapper = wrapper, game = game))
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
    start: (Int, List<Combo>, Boolean, String, BenchGame?) -> Unit,
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
    val wrappers = remember { mutableStateListOf<String>() }  // "" = the container's
    var conflicts by remember { mutableStateOf(Bench.conflicts(context)) }
    var gameMode by remember { mutableStateOf(false) }
    var gamePath by remember { mutableStateOf("") }
    var gameName by remember { mutableStateOf("") }
    var gameArgs by remember { mutableStateOf("") }
    var gameWarmup by remember { mutableStateOf("15") }
    var gameMeasure by remember { mutableStateOf("45") }
    var gameUntilEnd by remember { mutableStateOf(false) }
    var gamePreset by remember { mutableStateOf<BenchPreset?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    LaunchedEffect(container?.id) { gamePath = ""; gameName = "" }
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
    // Landscape: the tabs' top bar (Library / Containers / Settings / Benchmark), like the other tabs.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val mainActivity = LocalContext.current as? com.winlator.cmod.MainActivity
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (landscape) com.winlator.cmod.ui.LandscapeMainNavigation(mainActivity, com.winlator.cmod.R.id.main_menu_bench, uiStr(R.string.l_nav_benchmark))
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (!landscape) Text(uiStr(R.string.l_nav_benchmark), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(uiStr(R.string.l_bench_runs_yurnbench_cpu_gpu_and_cpu),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (s != null && (s.running || s.results.isNotEmpty())) {
            item { Progress(s, cooling.value, stop, reset) }
            if (!s.running && !s.long && s.results.any { it.ok }) item {
                Section(uiStr(R.string.l_bench_confirm_the_best)) {
                    Text(uiStr(if (s.game != null) R.string.l_bench_runs_every_working_bundle_3_times else R.string.l_bench_confirm_api, Bench.confirmation(s).size * (if (s.game != null) 4 else 5) / 2),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { start(s.containerId, Bench.confirmation(s), true, s.wrapper, s.game) }) { Text(uiStr(R.string.l_bench_confirm_best_long_runs)) }
                }
            }
            listOf(Combo.API11 to "DX11 (DXVK)", Combo.API12 to "DX12 (VKD3D)").forEach { (api, title) ->
                val runs = s.results.filter { it.combo.api == api }
                if (runs.isEmpty()) return@forEach
                val groups = Bench.aggregate(runs)
                val bestGroup = groups.firstOrNull { it.ok.isNotEmpty() }
                val bestKey = bestGroup?.combo?.key
                item { Text(title + if (s.long) uiStr(R.string.l_bench_confirmation_1_s_runs_each, groups.firstOrNull()?.runs?.size ?: 0) else "",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(groups.size) { i ->
                    val g = groups[i]
                    val shown = g.ok.maxByOrNull { it.score } ?: g.runs.last()
                    val tie = bestGroup != null && g.ok.isNotEmpty() && Bench.tie(g, bestGroup)
                    ResultCard(shown, g.combo.key == bestKey, if (g.runs.size > 1) g else null, tie, s.game != null) {
                        val game = s.game
                        if (game != null) {
                            if (Bench.applyToGame(game, g.combo)) Toast.makeText(context, uiStr(R.string.l_bench_applied_to_1_s, game.name), Toast.LENGTH_SHORT).show()
                        } else if (Bench.apply(context, s.containerId, g.combo)) Toast.makeText(context, uiStr(R.string.l_bench_applied_to_the_container), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (s.running) return@LazyColumn
            item { Spacer(Modifier.height(8.dp)); Text(uiStr(R.string.l_bench_new_series), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        }

        item {
            var rootOn by remember { mutableStateOf(RootMode.enabled(context)) }
            var checking by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Section(uiStr(R.string.l_bench_precise_measurements_root)) {
                Text(uiStr(R.string.l_bench_with_root_real_cpu_cycles_and),
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
                            else Toast.makeText(context, uiStr(R.string.l_bench_no_root_access_or_it_was), Toast.LENGTH_LONG).show()
                        }
                    })
                }
            }
        }
        item {
            Section(uiStr(R.string.l_bench_container_its_proton_stays_for_the)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    containers.forEach { c ->
                        FilterChip(selected = container?.id == c.id, onClick = { container = c },
                            label = { Text(c.name + " · " + c.getWineVersion().orEmpty().substringBefore("-arm64ec").take(24)) })
                    }
                }
            }
        }
        item {
            Section(uiStr(R.string.l_bench_what_to_run)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !gameMode, onClick = { gameMode = false }, label = { Text("YurnBench") })
                    FilterChip(selected = gameMode, onClick = { gameMode = true }, label = { Text(uiStr(R.string.l_bench_a_game)) })
                }
                if (gameMode) {
                    val games = remember(container?.id) {
                        runCatching { ContainerManager(context).loadShortcuts().filter { it.container.id == container?.id } }.getOrDefault(emptyList())
                    }
                    if (games.isEmpty()) Text(uiStr(R.string.l_bench_no_games_in_this_container), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        games.forEach { g ->
                            FilterChip(selected = gamePath == g.file.path, onClick = {
                                gamePath = g.file.path; gameName = g.name
                                // The game's own benchmark, found by its .exe (or its name).
                                val p = BenchGame.presetFor(g.path.orEmpty().replace('/', '\\').substringAfterLast('\\').trim('"'), g.name)
                                gamePreset = p
                                gameArgs = p?.args.orEmpty()
                                gameUntilEnd = p?.untilEnd ?: false
                            }, label = { Text(g.name) })
                        }
                    }
                    if (gamePath.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(gamePreset?.let { uiStr(R.string.l_bench_built_in_benchmark_1_s, it.title) } ?: uiStr(R.string.l_bench_no_known_benchmark_your_arguments_or),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { showPresets = !showPresets }) { Text(if (showPresets) uiStr(R.string.l_bench_hide) else uiStr(R.string.l_bench_presets)) }
                        }
                        if (showPresets) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BenchPreset.ALL.forEach { p ->
                                FilterChip(selected = gamePreset == p, onClick = {
                                    gamePreset = p; gameArgs = p.args; gameUntilEnd = p.untilEnd; showPresets = false
                                }, label = { Text(p.title) })
                            }
                            FilterChip(selected = gamePreset == null, onClick = { gamePreset = null; gameArgs = ""; showPresets = false },
                                label = { Text(uiStr(R.string.l_qm_custom)) })
                        }
                        gamePreset?.note?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE3B341))
                        }
                        androidx.compose.material3.OutlinedTextField(value = gameArgs, onValueChange = { gameArgs = it },
                            label = { Text(uiStr(R.string.l_bench_benchmark_arguments_after_the_game_s)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uiStr(R.string.l_bench_measure_until_the_game_ends_its), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            Switch(checked = gameUntilEnd, onCheckedChange = { gameUntilEnd = it })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedTextField(value = gameWarmup, onValueChange = { gameWarmup = it.filter(Char::isDigit).take(3) },
                                label = { Text(uiStr(R.string.l_bench_warm_up_s)) }, singleLine = true, modifier = Modifier.weight(1f))
                            if (!gameUntilEnd) androidx.compose.material3.OutlinedTextField(value = gameMeasure, onValueChange = { gameMeasure = it.filter(Char::isDigit).take(3) },
                                label = { Text(uiStr(R.string.l_bench_measure_s)) }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                    Text(uiStr(R.string.l_bench_the_fps_and_1_0_1),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            // Vulkan wrappers to compare; none chosen = the container's.
            val installed = remember { com.winlator.cmod.contents.WrapperManager.installed(context) }
            val containerWrapper = container?.let {
                Regex("(?:^|;)wrapper=([^;]*)").find(it.getGraphicsDriverConfig().orEmpty())?.groupValues?.get(1)
            }.orEmpty()
            Section(uiStr(R.string.l_bench_vulkan_wrappers_1_s, wrappers.size.coerceAtLeast(1))) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = wrappers.isEmpty(), onClick = { wrappers.clear() },
                        label = { Text(uiStr(R.string.l_bench_container_s) + (installed.firstOrNull { it.id == containerWrapper }?.let { " (${it.name})" } ?: "")) })
                    installed.forEach { w ->
                        FilterChip(selected = w.id in wrappers, onClick = { if (w.id in wrappers) wrappers.remove(w.id) else wrappers.add(w.id) },
                            label = { Text(w.name + (w.author?.let { " · $it" } ?: "")) })
                    }
                }
            }
        }
        val ch = choices
        if (container != null && ch == null) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        if (ch != null) {
            item {
                Section(uiStr(R.string.l_bench_gpu_drivers_1_s, drivers.size)) {
                    if (ch.drivers.isEmpty()) Text(uiStr(R.string.l_bench_no_installed_drivers), style = MaterialTheme.typography.bodySmall)
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
                    if (dx12) Text(uiStr(R.string.l_bench_dx12_runs_use_the_container_s),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (dx12) item {
                Section("VKD3D (${vkd3ds.size})") {
                    if (ch.vkd3d.isEmpty()) Text(uiStr(R.string.l_bench_no_installed_vkd3d), style = MaterialTheme.typography.bodySmall)
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
                val wrapperNames = com.winlator.cmod.contents.WrapperManager.installed(context).associate { it.id to it.name }
                val ws = if (wrappers.isEmpty()) listOf("") else wrappers.toList()
                val base = (if (dx11) drivers.flatMap { d ->
                    dxvks.flatMap { x -> emus.map { e -> Combo(d, label(d), x, e.first, e.second) } }
                } else emptyList()) + (if (dx12) drivers.flatMap { d ->
                    vkd3ds.flatMap { v -> emus.map { e -> Combo(d, label(d), currentDxvk.ifEmpty { dxvks.firstOrNull().orEmpty() }, e.first, e.second, Combo.API12, v) } }
                } else emptyList())
                val combos = ws.flatMap { w -> base.map { it.copy(wrapper = w, wrapperLabel = if (w.isEmpty()) "" else wrapperNames[w] ?: w) } }
                val proton = container?.getWineVersion().orEmpty()
                val known = combos.count { Bench.conflictFor(conflicts, proton, it) != null }
                val game = if (gameMode && gamePath.isNotEmpty()) BenchGame(gamePath, gameName, gameArgs.trim(),
                    gameWarmup.toIntOrNull()?.coerceIn(0, 600) ?: 15,
                    if (gameUntilEnd) BenchGame.UNTIL_END else gameMeasure.toIntOrNull()?.coerceIn(10, 600) ?: 45) else null
                // A game: loading (~1.5 min) + warm-up + measuring (a built-in benchmark ~2 min) per run.
                val perRun = if (game != null) 90 + game.warmup + (if (game.measure == BenchGame.UNTIL_END) 120 else game.measure) else 80
                val minutes = ((combos.size - known) * perRun + 60 + 59) / 60
                Text(uiStr(R.string.l_bench_bundles_1_s, combos.size) + (if (known > 0) uiStr(R.string.l_bench_known_skipped, known) else "") +
                    uiStr(R.string.l_bench_about_min, minutes) + if (combos.size - known > 24) uiStr(R.string.l_bench_large_series) else "",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(enabled = combos.isNotEmpty() && s?.running != true && container != null && (!gameMode || game != null), onClick = {
                    start(container!!.id, combos, false, "", game)
                }) { Text(uiStr(R.string.l_bench_start)) }
            }
        }
        val proton = container?.getWineVersion().orEmpty()
        val mine = conflicts.filter { it.proton == proton }
        if (mine.isNotEmpty()) item {
            Section(uiStr(R.string.l_bench_known_conflicts_with_this_proton_1, mine.size)) {
                mine.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.bodyMedium)
                            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        TextButton(onClick = { Bench.forgetConflict(context, c); conflicts = Bench.conflicts(context) }) { Text(uiStr(R.string.l_bench_retry)) }
                    }
                }
                TextButton(onClick = { Bench.forgetConflicts(context); conflicts = emptyList() }) { Text(uiStr(R.string.l_bench_forget_all_conflicts)) }
            }
        }
        item { TestLogSection() }
        item { HistorySection() }
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
private fun Progress(s: BenchState, cooling: String?, stop: () -> Unit, reset: () -> Unit) {
    Section((if (s.long) uiStr(R.string.l_bench_confirmation_prefix) else "") +
        if (s.running) uiStr(R.string.l_bench_running_1_s_of_2_s, minOf(s.next + 1, s.combos.size), s.combos.size) else uiStr(R.string.l_bench_series_done, s.results.size, s.combos.size)) {
        cooling?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF58A6FF)) }
        LinearProgressIndicator(progress = { if (s.combos.isEmpty()) 0f else s.results.size.toFloat() / s.combos.size }, modifier = Modifier.fillMaxWidth())
        if (s.running) s.combos.getOrNull(s.next)?.let { Text(uiStr(R.string.l_bench_next_1_s, it.label), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.running) OutlinedButton(onClick = stop) { Text(uiStr(R.string.l_bench_stop)) }
            TextButton(onClick = reset) { Text(uiStr(R.string.l_bench_clear_results)) }
        }
    }
}

@Composable
private fun ResultCard(r: RunResult, best: Boolean, agg: Bench.Aggregate?, tie: Boolean = false, game: Boolean = false, apply: () -> Unit) {
    val border = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (best) 2.dp else 1.dp, border)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.combo.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(16.dp))
                Text(if (agg != null && agg.ok.isNotEmpty()) "${agg.score}" else if (r.ok) "${r.score}" else r.status, fontWeight = FontWeight.Bold,
                    color = when { r.ok -> MaterialTheme.colorScheme.primary; r.status == "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error })
            }
            if (tie) Text(uiStr(R.string.l_bench_as_good_as_the_best_within), style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FB950))
            if (agg != null) Text(uiStr(R.string.l_bench_average_of_1_s_of_2, agg.ok.size, agg.runs.size, agg.min, agg.max, agg.spreadPercent) +
                (if (agg.ok.size < agg.runs.size) uiStr(R.string.l_bench_failed_n, agg.runs.size - agg.ok.size) else "") + uiStr(R.string.l_bench_details_best),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF58A6FF))
            if (r.ok && game) {
                Text(uiStr(R.string.l_bench_game_1_s_fps_1_low, f(r.mix.avg), f(r.mix.low1)) +
                    (r.telemetry.optDouble("game_low01", 0.0).takeIf { it > 0 }?.let { uiStr(R.string.l_bench_0_1_low_1_s, f(it)) } ?: "") + ")",
                    style = MaterialTheme.typography.bodySmall)
                if (r.detail.isNotBlank()) Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clocks(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(uiStr(R.string.l_bench_battery_1_s_2_s_c, f(r.tempBefore.toDouble()), f(r.tempAfter.toDouble())) +
                    (if (r.maxCpuTemp > 0) uiStr(R.string.l_bench_cpu_up_to_1_s_c, r.maxCpuTemp) else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (r.ok) {
                Text("GPU ${f(r.gpu.avg)} FPS (1% low ${f(r.gpu.low1)}) · CPU+GPU ${f(r.mix.avg)} FPS (1% low ${f(r.mix.low1)})",
                    style = MaterialTheme.typography.bodySmall)
                if (r.gpu.gpuMs > 0) Text("GPU work ${String.format("%.1f", r.gpu.gpuMs)} ms per scene → ${r.gpu.gpuFps.toInt()} FPS" +
                    (if (r.gpu.gpuLow1 > 0) " (1% low ${r.gpu.gpuLow1.toInt()})" else "") +
                    (if (r.gpuReps > 1) " at full GPU load (${r.gpuReps} scenes per frame)" else " if only the GPU limited") +
                    (if (r.mix.gpuMs > 0) " · CPU+GPU phase ${String.format("%.1f", r.mix.gpuMs)} ms" else ""),
                    style = MaterialTheme.typography.bodySmall)
                Text("CPU ${r.cpuSt.toInt()} / ${r.cpuMt.toInt()} Mops (1 core / all)" +
                    (if (r.cpuPerMcycle > 0) " · ${String.format("%.2f", r.cpuPerMcycle)} ops per CPU cycle" +
                        when (r.cyclesSource) { "hw" -> uiStr(R.string.l_bench_hardware_counters); "busy" -> uiStr(R.string.l_bench_game_s_cpu_time); else -> uiStr(R.string.l_bench_from_clocks) } else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cpuUse(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(clocks(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.throttlePercent >= 3) Text(uiStr(R.string.l_bench_throttled_1_s_at_full_clocks, r.scoreFullClocks, r.throttlePercent),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFE3B341))
                Text(uiStr(R.string.l_bench_battery_1_s_2_s_c, f(r.tempBefore.toDouble()), f(r.tempAfter.toDouble())) +
                    (if (r.maxCpuTemp > 0) uiStr(R.string.l_bench_cpu_up_to_1_s_c, r.maxCpuTemp) else "") + (if (r.adapter.isNotBlank()) " · ${r.adapter}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (r.detail.isNotBlank()) Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (best) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiStr(R.string.l_bench_best), color = Color(0xFF3FB950), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = apply) { Text(if (game) uiStr(R.string.l_bench_apply_to_the_game) else uiStr(R.string.l_bench_apply_to_container)) }
            }
        }
    }
}

private fun f(v: Double) = if (v >= 100) v.toInt().toString() else String.format("%.1f", v)

/** "GPU 640 MHz (71% of max), busy 96% · CPU 2.4 GHz (88%)": clocks per phase from the telemetry. */
private fun clocks(r: RunResult): String {
    val parts = ArrayList<String>()
    // A game run has one phase ("mix": CPU and GPU together); YurnBench has separate GPU / CPU phases.
    val game = r.phase == "game"
    val gp = if (game) "mix" else "gpu"
    val cp = if (game) "mix" else "cpu"
    val g = r.gpuMhz(gp)
    val gRatio = if (game) r.mixGpuRatio else r.gpuRatio
    if (g > 0) parts += "GPU ${g.toInt()} MHz" + (gRatio?.let { " (${(it * 100).toInt()}%)" } ?: "") +
        r.gpuBusy(gp).takeIf { it >= 0 }?.let { ", busy ${it.toInt()}%" }.orEmpty()
    else r.gpuBusy(gp).takeIf { it >= 0 }?.let { parts += "GPU busy ${it.toInt()}%" }
    val c = r.cpuMhz(cp)
    val cRatio = if (game) r.mixCpuRatio else r.cpuMtRatio
    if (c > 0) parts += "CPU ${String.format("%.2f", c / 1000)} GHz" + (cRatio?.let { " (${(it * 100).toInt()}%)" } ?: "")
    return if (parts.isEmpty()) uiStr(R.string.l_bench_clocks_not_readable_on_this_phone) else parts.joinToString(" · ")
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
    Section(uiStr(R.string.l_bench_history_of_best_bundles_1_s, history.size)) {
        TextButton(onClick = { open = !open }) { Text(if (open) uiStr(R.string.l_bench_hide) else uiStr(R.string.l_bench_show)) }
        if (open) {
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            history.forEach { h ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (h.api == Combo.API12) "DX12 · " else "DX11 · ") + h.label, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(16.dp))
                        Text("${h.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(listOf(fmt.format(java.util.Date(h.date * 1000)),
                        if (h.long) uiStr(R.string.l_bench_confirmed_1_s_runs_2_s, h.runs, h.spread) else uiStr(R.string.l_bench_best_of_1_s, h.bundles),
                        "GPU ${h.gpuFps.toInt()} · CPU+GPU ${f(h.mixFps)} FPS", h.container + " · " + h.proton)
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { Bench.clearHistory(context); history = emptyList() }) { Text(uiStr(R.string.l_bench_clear_history)) }
        }
    }
}

/** Every run of every series, filterable by its settings: pick drivers (or wrappers, DXVK, ...) and see their results. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestLogSection() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(Bench.runLog(context)) }
    var open by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    if (log.isEmpty()) return
    val filters = remember { mutableStateMapOf<String, Set<String>>() }
    val dims: List<Pair<String, (Bench.LoggedRun) -> String>> = listOf(
        "API" to { e -> if (e.run.combo.dx12) "DX12" else "DX11" },
        uiStr(R.string.l_bench_driver) to { e -> e.run.combo.driverLabel },
        uiStr(R.string.l_bench_wrapper) to { e -> e.run.combo.wrapperLabel.ifEmpty { uiStr(R.string.l_bench_container_s) } },
        "DXVK / VKD3D" to { e -> e.run.combo.layer },
        "FEX / Box64" to { e -> (if (e.run.combo.emulator == "FEXCore") "FEX " else "Box64 ") + e.run.combo.emulatorVersion },
        "Proton" to { e -> e.proton.ifEmpty { "?" } },
        uiStr(R.string.l_bench_container) to { e -> e.container.ifEmpty { "?" } },
    )
    Section(uiStr(R.string.l_bench_test_history_1_s_runs, log.size)) {
        TextButton(onClick = { open = !open }) { Text(if (open) uiStr(R.string.l_bench_hide) else uiStr(R.string.l_bench_show)) }
        if (open) {
            dims.forEach { (name, key) ->
                val values = log.map(key).distinct().sorted()
                if (values.size < 2) return@forEach
                val sel = filters[name].orEmpty()
                Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    values.forEach { v ->
                        FilterChip(selected = v in sel, onClick = { filters[name] = if (v in sel) sel - v else sel + v },
                            label = { Text(v, style = MaterialTheme.typography.bodySmall) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = failed, onClick = { failed = !failed }, label = { Text(uiStr(R.string.l_bench_show_failed)) })
                Spacer(Modifier.width(8.dp))
                if (filters.values.any { it.isNotEmpty() }) TextButton(onClick = { filters.clear() }) { Text(uiStr(R.string.l_bench_reset_filters)) }
            }
            val shown = log.filter { e ->
                (failed || e.run.ok) && dims.all { (name, key) -> filters[name].orEmpty().let { it.isEmpty() || key(e) in it } }
            }
            // Bundles averaged over their runs, best first; each line shows how many runs and the spread.
            val groups = shown.groupBy { it.run.combo.key + "|" + it.proton }.values.map { runs ->
                val ok = runs.filter { it.run.ok }.map { it.run.score }
                Triple(runs, if (ok.isEmpty()) -1 else ok.average().toInt(), ok)
            }.sortedByDescending { it.second }
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            Text(uiStr(R.string.l_bench_1_s_bundles_2_s_runs, groups.size, shown.size), style = MaterialTheme.typography.bodySmall)
            groups.take(100).forEach { (runs, avg, ok) ->
                val last = runs.maxBy { it.date }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (last.run.combo.dx12) "DX12 · " else "DX11 · ") + last.run.combo.label, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(16.dp))
                        Text(if (avg >= 0) "$avg" else last.run.status, fontWeight = FontWeight.Bold,
                            color = if (avg >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    val spread = if (ok.size > 1) " (${ok.min()}–${ok.max()})" else ""
                    Text(listOf(uiStr(R.string.l_bench_runs_1_s, runs.size) + spread,
                        uiStr(R.string.l_bench_last) + fmt.format(java.util.Date(last.date * 1000)), last.container + " · " + last.proton)
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { Bench.clearRunLog(context); log = emptyList() }) { Text(uiStr(R.string.l_bench_clear_test_history)) }
        }
    }
}
