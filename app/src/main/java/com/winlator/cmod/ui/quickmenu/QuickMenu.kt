package com.winlator.cmod.ui.quickmenu

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.WinlatorThemeType
import com.winlator.cmod.ui.theme.winlatorColorScheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * In-game quick menu (the side drawer), GameNative-style: a rail of sections on the left,
 * the section's settings on the right.
 *   HUD       · on/off, style, FPS limit (presets + custom), size, opacity, metrics
 *   Screen    · upscaler + sharpness, shaders + strength, scaling, magnifier, PiP
 *   Controls  · on-screen profile, touch options, opacity, mouse, keyboard, vibration
 *   Tasks     · session info (Wine, renderer, driver…), CPU / memory, processes
 */
object QuickMenuHost {
    /** Bumped every time the drawer opens, so the menu re-reads the live state. */
    private val opened = mutableIntStateOf(0)

    @JvmStatic
    fun attach(view: ComposeView, bridge: QuickMenuBridge) {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        view.setContent { QuickMenu(bridge, opened.intValue) }
    }

    @JvmStatic
    fun onOpened() {
        opened.intValue++
    }
}

private enum class Section(val titleRes: Int, val icon: ImageVector) {
    HUD(R.string.l_qm_performance_hud, Icons.Outlined.QueryStats),
    PERF(R.string.l_qm_performance, Icons.Outlined.Speed),
    SCREEN(R.string.l_qm_screen_effects, Icons.Outlined.AutoFixHigh),
    CONTROLS(R.string.l_qm_controls, Icons.Outlined.SportsEsports),
    TASKS(R.string.l_qm_task_manager, Icons.Outlined.ListAlt);

    val title: String get() = uiStr(titleRes)
}

@Composable
private fun QuickMenu(bridge: QuickMenuBridge, opened: Int) {
    val context = LocalContext.current
    val theme = WinlatorThemeManager.currentTheme(context).let { if (it == WinlatorThemeType.WHITE) WinlatorThemeType.BLACK else it }
    MaterialTheme(colorScheme = winlatorColorScheme(theme)) {
        var section by remember { mutableStateOf(Section.HUD) }
        var paused by remember(opened) { mutableStateOf(bridge.isPaused()) }
        var confirmExit by remember { mutableStateOf(false) }
        val scheme = MaterialTheme.colorScheme
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        Surface(
            modifier = Modifier.fillMaxHeight().width(minOf(screenWidth * 0.62f, 520.dp)),
            color = scheme.background,
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiStr(R.string.l_qm_quick_menu), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // Always visible, also on short landscape screens where the rail scrolls.
                    TextButton(onClick = { confirmExit = true }) {
                        Icon(Icons.Outlined.ExitToApp, null, tint = scheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiStr(R.string.l_qm_exit_game), color = scheme.error)
                    }
                    IconButton(onClick = { bridge.close() }) { Icon(Icons.Outlined.Close, uiStr(R.string.l_qm_close)) }
                }
                Row(Modifier.fillMaxSize()) {
                    // Rail
                    Column(
                        Modifier.width(80.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Section.entries.forEach { s ->
                            RailButton(s.icon, s.title, selected = s == section) { section = s }
                        }
                        Spacer(Modifier.height(8.dp))
                        RailButton(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) uiStr(R.string.l_qm_resume) else uiStr(R.string.l_qm_pause), selected = paused) {
                            paused = !paused
                            bridge.setPaused(paused)
                        }
                        RailButton(Icons.Outlined.ExitToApp, uiStr(R.string.l_qm_exit), selected = false, danger = true) { confirmExit = true }
                    }
                    Box(Modifier.fillMaxSize().padding(end = 14.dp)) {
                        when (section) {
                            Section.HUD -> HudSection(bridge, opened)
                            Section.PERF -> PerformanceSection(bridge, opened)
                            Section.SCREEN -> ScreenSection(bridge, opened)
                            Section.CONTROLS -> ControlsSection(bridge, opened)
                            Section.TASKS -> TasksSection(bridge, opened)
                        }
                    }
                }
            }
        }
        if (confirmExit) AlertDialog(
            shape = RoundedCornerShape(28.dp), containerColor = QM_DIALOG_BG, tonalElevation = 0.dp,
            onDismissRequest = { confirmExit = false },
            title = { Text(uiStr(R.string.l_qm_exit_the_game)) },
            text = { Text(uiStr(R.string.l_qm_unsaved_progress_in_the_game_will)) },
            confirmButton = { TextButton(onClick = { confirmExit = false; bridge.exitGame() }) { Text(uiStr(R.string.l_qm_exit)) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(uiStr(R.string.l_qm_cancel)) } }
        )
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, selected: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        danger -> scheme.error.copy(alpha = 0.16f)
        selected -> scheme.primary.copy(alpha = 0.22f)
        else -> scheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val tint = when {
        danger -> scheme.error
        selected -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = bg, modifier = Modifier.size(58.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp)) }
    }
}

// ---------- building blocks ----------

@Composable
private fun SectionPage(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) { Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Horizontal chips, one selected. */
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.second }) { (value, label) ->
            val on = value == selected
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(50),
                color = if (on) scheme.primary else Color.Transparent,
                contentColor = if (on) scheme.onPrimary else scheme.onSurface,
                border = if (on) null else BorderStroke(1.dp, scheme.outlineVariant)
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Slider card: title and value on top, then [-] [thick filled bar · thin rest with an end dot] [+].
 * [value] is 0…1; [step] is what -/+ change. [onCommit] runs when a change is finished.
 */
@Composable
private fun StepSlider(
    title: String,
    value: Float,
    valueLabel: String,
    step: Float = 0.05f,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    // The gesture handlers live as long as the bar's width doesn't change: they must call the
    // latest callbacks, not the ones of the first composition (a drag on Brightness changed the
    // picture through a stale callback while the bar's own state stayed where it was).
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { onChange((value - step).coerceIn(0f, 1f)); onCommit() }
            BoxWithConstraints(Modifier.weight(1f).height(36.dp).padding(horizontal = 12.dp)) {
                val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(widthPx) {
                            detectTapGestures { o -> change((o.x / widthPx).coerceIn(0f, 1f)); commit() }
                        }
                        .pointerInput(widthPx) {
                            detectHorizontalDragGestures(onDragEnd = { commit() }) { pointer, _ ->
                                change((pointer.position.x / widthPx).coerceIn(0f, 1f))
                            }
                        }
                ) {
                    val cy = size.height / 2
                    val thick = 12.dp.toPx()
                    val thin = 6.dp.toPx()
                    val gap = 6.dp.toPx()
                    val split = (size.width * value).coerceIn(thick, size.width)
                    drawRoundRect(scheme.primary, Offset(0f, cy - thick / 2), Size(split, thick), CornerRadius(thick / 2))
                    val restStart = split + gap
                    if (restStart < size.width) {
                        drawRoundRect(scheme.onSurface.copy(alpha = 0.12f), Offset(restStart, cy - thin / 2), Size(size.width - restStart, thin), CornerRadius(thin / 2))
                        drawCircle(scheme.primary, radius = 3.dp.toPx(), center = Offset(size.width - 5.dp.toPx(), cy))
                    }
                }
            }
            StepButton("+") { onChange((value + step).coerceIn(0f, 1f)); onCommit() }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(52.dp)
    ) { Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- HUD ----------

private val FPS_PRESETS = listOf(0, 30, 45, 60, 90, 120)

@Composable
private fun HudSection(bridge: QuickMenuBridge, opened: Int) {
    var mode by remember(opened) { mutableIntStateOf(bridge.hudMode()) }
    var fps by remember(opened) { mutableIntStateOf(bridge.fpsLimit()) }
    var custom by remember(opened) { mutableStateOf(fps != 0 && fps !in FPS_PRESETS) }
    var customText by remember(opened) { mutableStateOf(if (fps != 0 && fps !in FPS_PRESETS) fps.toString() else "") }
    var scale by remember(opened, mode) { mutableFloatStateOf(bridge.hudScale()) }
    var alpha by remember(opened, mode) { mutableFloatStateOf(bridge.hudAlpha()) }
    var rev by remember { mutableIntStateOf(0) }

    SectionPage(uiStr(R.string.l_qm_performance_hud)) {
        Card {
            SwitchRow(uiStr(R.string.l_qm_performance_hud), uiStr(R.string.l_qm_show_or_hide_the_in_game), checked = mode != 0) { on ->
                mode = if (on) 2 else 0
                bridge.setHudMode(mode)
            }
            if (mode != 0) {
                Label(uiStr(R.string.l_qm_style))
                Chips(listOf(2 to "YurNLator", 4 to "Bannerlator", 3 to "MangoHud"), mode) { mode = it; bridge.setHudMode(it) }
            }
        }
        if (mode == 3) {
            val running = remember(opened) { bridge.mangoHudRunning() }
            var log by remember(opened) { mutableStateOf(bridge.mangoHudLog()) }
            Card {
                Text(if (running) uiStr(R.string.l_qm_mangohud_is_drawn_inside_the_game)
                     else uiStr(R.string.l_qm_mangohud_loads_with_the_game_restart),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow(uiStr(R.string.l_qm_write_a_log), uiStr(R.string.l_qm_mangohud_csv_in_download_yurnlator_mangohud), checked = log) {
                    log = it; bridge.setMangoHudLog(it)
                }
            }
        }
        Card {
            SwitchRow(uiStr(R.string.l_qm_fps_limit), if (fps == 0) uiStr(R.string.l_qm_off_the_game_runs_as_fast) else uiStr(R.string.l_qm_capped_at_1_d_fps, fps), checked = fps != 0) { on ->
                fps = if (on) 60 else 0
                custom = false
                bridge.setFpsLimit(fps)
            }
            if (fps != 0 || custom) {
                val chips = FPS_PRESETS.drop(1).map { it to it.toString() } + (-1 to uiStr(R.string.l_qm_custom))
                Chips(chips, if (custom) -1 else fps) { v ->
                    if (v == -1) custom = true
                    else { custom = false; fps = v; bridge.setFpsLimit(v) }
                }
                if (custom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { t ->
                            customText = t.filter { it.isDigit() }.take(3)
                            val v = customText.toIntOrNull()
                            if (v != null && v in 10..240) { fps = v; bridge.setFpsLimit(v) }
                        },
                        label = { Text(uiStr(R.string.l_qm_fps_10_240)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (mode == 2) {
            StepSlider(uiStr(R.string.l_qm_hud_size), (scale - 0.5f) / 1.5f, "${(scale * 100).roundToInt()}%", onChange = { f ->
                scale = 0.5f + f * 1.5f
                bridge.setHudScale(scale)
            })
            StepSlider(uiStr(R.string.l_qm_hud_opacity), alpha, "${(alpha * 100).roundToInt()}%", onChange = { f ->
                alpha = f
                bridge.setHudAlpha(f)
            })
            Card {
                Text(uiStr(R.string.l_qm_shown_in_the_hud), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                // Tiles, 3 per row: a lit tile is shown (WinlatorHUD item indices).
                ToggleTiles(listOf(
                    0 to "FPS", 13 to "1% / 0.1%", 6 to uiStr(R.string.l_qm_renderer),
                    2 to uiStr(R.string.l_qm_gpu_load), 8 to uiStr(R.string.l_qm_gpu_clock), 9 to uiStr(R.string.l_qm_gpu_temp),
                    3 to uiStr(R.string.l_qm_cpu_load), 10 to uiStr(R.string.l_qm_cpu_clock), 11 to uiStr(R.string.l_qm_cpu_temp),
                    7 to "RAM", 4 to uiStr(R.string.l_qm_power), 12 to uiStr(R.string.l_qm_battery_temp),
                    14 to uiStr(R.string.l_qm_gpu_name),
                ), isOn = { idx -> remember(opened, rev, idx) { bridge.hudElement(idx) } }) { idx, on -> bridge.setHudElement(idx, on); rev++ }
                TextButton(onClick = { bridge.resetHud(); rev++ }) { Text(uiStr(R.string.l_qm_reset_hud_position)) }
            }
        } else if (mode == 4) {
            StepSlider(uiStr(R.string.l_qm_hud_size), (scale - 0.5f) / 1.5f, "${(scale * 100).roundToInt()}%", onChange = { f ->
                scale = 0.5f + f * 1.5f
                bridge.setHudScale(scale)
            })
            Card {
                Text(uiStr(R.string.l_qm_bannerlator_s_hud_styles_tap_the),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (mode == 2 || mode == 4) {
            val ctx = LocalContext.current
            var dual by remember(opened) { mutableStateOf(com.winlator.cmod.widget.WinlatorHUD.dualCell(ctx)) }
            Card {
                SwitchRow(uiStr(R.string.l_hud_dual_cell), uiStr(R.string.l_hud_dual_cell_hint), checked = dual) {
                    dual = it; com.winlator.cmod.widget.WinlatorHUD.setDualCell(ctx, it)
                }
            }
        }
    }
}

// ---------- Performance (from Bannerlator's panel) ----------

@Composable
private fun PerformanceSection(bridge: QuickMenuBridge, opened: Int) {
    val perf = bridge.performance()
    SectionPage(uiStr(R.string.l_qm_performance)) {
        if (perf == null) {
            Card { Text(uiStr(R.string.l_qm_available_once_the_game_has_started), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@SectionPage
        }
        val context = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var rev by remember { mutableIntStateOf(0) }
        val root by com.winlator.cmod.perf.RootManager.state.collectAsState()
        val granted = root == com.winlator.cmod.perf.RootManager.RootState.GRANTED

        // Live readings: GPU clock, hottest CPU / GPU sensor, CPU governor.
        var gpuMhz by remember { mutableStateOf<Int?>(null) }
        var tempC by remember { mutableStateOf<Int?>(null) }
        var governor by remember { mutableStateOf("") }
        androidx.compose.runtime.LaunchedEffect(opened) {
            val metrics = com.winlator.cmod.widget.banner.HudMetrics(context.applicationContext)
            while (true) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    gpuMhz = runCatching { metrics.gpuClockMhz }.getOrNull()
                    tempC = runCatching { maxOf(metrics.cpuTempC ?: 0, metrics.gpuTempC ?: 0).takeIf { it > 0 } }.getOrNull()
                    governor = runCatching { java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim() }.getOrDefault("")
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PerfStat(uiStr(R.string.l_qm_gpu_clock), gpuMhz?.let { "$it MHz" } ?: "—")
                PerfStat(uiStr(R.string.l_qm_hottest_sensor), tempC?.let { "$it °C" } ?: "—")
                PerfStat(uiStr(R.string.l_qm_cpu_governor), governor.ifEmpty { "—" })
            }
        }

        val profile = remember(rev, opened, granted) { perf.currentProfile() }
        Card {
            Label(uiStr(R.string.l_qm_power_profile))
            Chips(listOf(
                com.winlator.cmod.session.PerfSession.PROFILE_STOCK to uiStr(R.string.l_qm_stock),
                com.winlator.cmod.session.PerfSession.PROFILE_BALANCED to uiStr(R.string.l_qm_balanced),
                com.winlator.cmod.session.PerfSession.PROFILE_PERFORMANCE to uiStr(R.string.l_qm_performance),
            ), profile) { perf.applyProfile(it); rev++ }
            Text(when (profile) {
                com.winlator.cmod.session.PerfSession.PROFILE_STOCK -> uiStr(R.string.l_qm_android_decides_everything_as_without_yurnlator)
                com.winlator.cmod.session.PerfSession.PROFILE_BALANCED -> uiStr(R.string.l_qm_sustained_performance_mode_only_steadier_clocks)
                com.winlator.cmod.session.PerfSession.PROFILE_PERFORMANCE -> uiStr(R.string.l_qm_everything_below_on_root_items_too)
                else -> uiStr(R.string.l_qm_custom_your_own_mix_of_the)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        @Composable
        fun PerfSwitch(key: String, title: String, subtitle: String, enabled: Boolean = true) {
            val on = remember(rev, opened, key) { perf.value(key) }
            val own = remember(rev, opened, key) { perf.isOwn(key) }
            SwitchRow(title, subtitle + if (own) uiStr(R.string.l_qm_this_game) else "", checked = on && enabled) {
                if (enabled) { perf.set(key, it); rev++ }
            }
        }

        Card {
            Label(uiStr(R.string.l_qm_without_root))
            PerfSwitch(com.winlator.cmod.session.PerfSession.SUSTAINED, uiStr(R.string.l_qm_sustained_performance_mode),
                uiStr(R.string.l_qm_android_s_mode_for_long_sessions))
            PerfSwitch(com.winlator.cmod.session.PerfSession.PRIORITY, uiStr(R.string.l_qm_thread_priority_boost),
                uiStr(R.string.l_qm_the_emulator_s_and_the_game))
            PerfSwitch(com.winlator.cmod.session.PerfSession.BIG_CORES, uiStr(R.string.l_qm_prefer_big_cores),
                uiStr(R.string.l_qm_the_game_s_processes_run_on))
            val gpuPin = granted || com.winlator.cmod.perf.PerfGpuTurbo.isSupported
            PerfSwitch(com.winlator.cmod.perf.PerfRootApplier.KEY_GPU_CLOCK_LOCK, uiStr(R.string.l_qm_gpu_at_max_clock),
                if (granted) uiStr(R.string.l_qm_pins_the_gpu_s_lowest_clock)
                else if (gpuPin) uiStr(R.string.l_qm_adreno_turbo_the_gpu_stays_at)
                else uiStr(R.string.l_qm_needs_an_adreno_gpu_or_root), enabled = gpuPin)
        }

        Card {
            Label(uiStr(R.string.l_qm_root))
            if (!granted) {
                Text(when (root) {
                    com.winlator.cmod.perf.RootManager.RootState.UNAVAILABLE -> uiStr(R.string.l_qm_no_root_on_this_device)
                    com.winlator.cmod.perf.RootManager.RootState.DENIED -> uiStr(R.string.l_qm_root_was_refused)
                    else -> uiStr(R.string.l_qm_clocks_governor_and_cores_need_root)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (root != com.winlator.cmod.perf.RootManager.RootState.UNAVAILABLE)
                    TextButton(onClick = { scope.launch { com.winlator.cmod.perf.RootManager.requestGrant(); rev++ } }) { Text(uiStr(R.string.l_qm_grant_root)) }
            } else {
                PerfSwitch(com.winlator.cmod.perf.PerfRootApplier.KEY_CPU_GOVERNOR, uiStr(R.string.l_qm_cpu_governor_performance),
                    uiStr(R.string.l_qm_every_core_s_governor_set_to))
                PerfSwitch(com.winlator.cmod.perf.PerfRootApplier.KEY_CPU_FREQ_LOCK, uiStr(R.string.l_qm_cpu_at_max_clock),
                    uiStr(R.string.l_qm_each_core_s_lowest_clock_pinned))
                PerfSwitch(com.winlator.cmod.perf.PerfRootApplier.KEY_CORES_ONLINE, uiStr(R.string.l_qm_all_cores_online),
                    uiStr(R.string.l_qm_keeps_the_system_from_parking_cores))
                PerfSwitch(com.winlator.cmod.perf.PerfRootApplier.KEY_FAN_MAX, uiStr(R.string.l_qm_fan_at_full_speed),
                    uiStr(R.string.l_qm_phones_and_handhelds_with_a_built))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.winlator.cmod.perf.PerfRootApplier.freeMemoryNow() } }) { Text(uiStr(R.string.l_qm_drop_caches)) }
                    TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.winlator.cmod.perf.PerfRootApplier.deepCleanMemory() } }) { Text(uiStr(R.string.l_qm_close_background_apps)) }
                }
            }
            Text(uiStr(R.string.l_qm_everything_changed_here_is_put_back), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (granted) FreqEditorCard(perf, opened)

        Card {
            val wdOn by com.winlator.cmod.perf.TempWatchdog.enabled.collectAsState()
            val mode by com.winlator.cmod.perf.TempWatchdog.mode.collectAsState()
            val ceiling by com.winlator.cmod.perf.TempWatchdog.ceilingC.collectAsState()
            SwitchRow(uiStr(R.string.l_qm_temperature_guard), uiStr(R.string.l_qm_above_1_d_c_the_clock, ceiling), checked = wdOn) {
                com.winlator.cmod.perf.TempWatchdog.setWatchdogEnabled(it)
            }
            if (wdOn) Chips(listOf(
                com.winlator.cmod.perf.TempWatchdog.ThresholdMode.CONSERVATIVE to uiStr(R.string.l_qm_cautious),
                com.winlator.cmod.perf.TempWatchdog.ThresholdMode.BALANCED to uiStr(R.string.l_qm_balanced),
                com.winlator.cmod.perf.TempWatchdog.ThresholdMode.AGGRESSIVE to uiStr(R.string.l_qm_late),
            ), mode) { com.winlator.cmod.perf.TempWatchdog.setMode(it) }
        }

        TextButton(onClick = { perf.resetToDefaults(); rev++ }) { Text(uiStr(R.string.l_qm_use_the_default_settings_for_this)) }
    }
}

/** On / off switches as small tiles, [perRow] in a row: lit (accent) = on. */
@Composable
private fun ToggleTiles(items: List<Pair<Int, String>>, perRow: Int = 3, isOn: @Composable (Int) -> Boolean, onToggle: (Int, Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((idx, name) in row) {
                    val on = isOn(idx)
                    Surface(
                        onClick = { onToggle(idx, !on) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (on) scheme.primary else scheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = if (on) scheme.onPrimary else scheme.onSurfaceVariant)
                        }
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PerfStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- Screen ----------

private val SHADERS get() = listOf(
    0 to uiStr(R.string.l_qm_off), 1 to uiStr(R.string.l_qm_dls_sharpen), 2 to "CRT", 3 to "HDR", 4 to uiStr(R.string.l_qm_natural),
    10 to uiStr(R.string.l_qm_game_clarity), 11 to uiStr(R.string.l_qm_cinematic), 12 to uiStr(R.string.l_qm_vivid), 13 to uiStr(R.string.l_qm_competitive), 14 to uiStr(R.string.l_qm_adaptive_sharpen),
    15 to uiStr(R.string.l_qm_filmic), 16 to uiStr(R.string.l_qm_arcade), 17 to uiStr(R.string.l_qm_retro_crt), 18 to uiStr(R.string.l_qm_upscale_sharp), 19 to uiStr(R.string.l_qm_pixel_clean), 20 to uiStr(R.string.l_qm_anime_edge)
)

@Composable
private fun ScreenSection(bridge: QuickMenuBridge, opened: Int) {
    val vulkan = remember(opened) { bridge.isVulkanRenderer() }
    var upscaler by remember(opened) { mutableIntStateOf(bridge.upscaler()) }
    var sharp by remember(opened) { mutableIntStateOf(bridge.sharpness()) }
    var shader by remember(opened) { mutableIntStateOf(bridge.shader()) }
    var scaling by remember(opened) { mutableIntStateOf(bridge.scaling()) }
    var bright by remember(opened) { mutableIntStateOf(bridge.brightness()) }
    var contrast by remember(opened) { mutableIntStateOf(bridge.contrast()) }
    var gamma by remember(opened) { mutableFloatStateOf(bridge.gamma()) }
    var shaderMenu by remember { mutableStateOf(false) }

    SectionPage(uiStr(R.string.l_qm_screen_effects)) {
        val zc0 = remember(opened) { bridge.zeroCopy() }
        if (zc0 >= 0) {
            var zc by remember(opened) { mutableStateOf(zc0 == 1) }
            Card {
                SwitchRow(uiStr(R.string.l_qm_direct_presentation), uiStr(R.string.l_qm_wayland_the_game_s_frames_go),
                    checked = zc) { zc = it; bridge.setZeroCopy(it) }
            }
        }
        if (!vulkan) {
            Card { Text(uiStr(R.string.l_qm_upscaling_and_shaders_need_the_vulkan), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Card {
                SwitchRow(uiStr(R.string.l_qm_upscaling), uiStr(R.string.l_qm_sharper_image_at_the_game_s), checked = upscaler >= 0) { on ->
                    upscaler = if (on) 1 else -1
                    bridge.setUpscaler(upscaler)
                    if (on && shader >= 10) shader = 0
                }
                if (upscaler >= 0) Chips(listOf(1 to "FSR", 0 to "SGSR"), upscaler) { upscaler = it; bridge.setUpscaler(it) }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiStr(R.string.l_qm_shader), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(SHADERS.firstOrNull { it.first == shader }?.second ?: uiStr(R.string.l_qm_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = shader != 0, onCheckedChange = { on ->
                        shader = if (on) 10 else 0
                        bridge.setShader(shader)
                        if (shader >= 10) upscaler = -1
                    })
                }
                if (shader != 0) {
                    Box {
                        Surface(onClick = { shaderMenu = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(SHADERS.firstOrNull { it.first == shader }?.second ?: "", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(uiStr(R.string.l_qm_change), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        DropdownMenu(expanded = shaderMenu, onDismissRequest = { shaderMenu = false }) {
                            SHADERS.drop(1).forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    shaderMenu = false
                                    shader = id
                                    bridge.setShader(id)
                                    if (id >= 10) upscaler = -1
                                })
                            }
                        }
                    }
                    if (shader >= 10) Text(uiStr(R.string.l_qm_effect_shaders_replace_upscaling_while_on), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (upscaler >= 0 || shader != 0) {
                StepSlider(if (shader >= 10) uiStr(R.string.l_qm_effect_strength) else uiStr(R.string.l_qm_sharpness), sharp / 100f, "$sharp%",
                    onChange = { f -> sharp = (f * 100).roundToInt(); bridge.setSharpness(sharp, false) },
                    onCommit = { bridge.setSharpness(sharp, true) })
            }
        }
        FrameGenCard(bridge, opened)
        if (vulkan) {
            StepSlider(uiStr(R.string.l_qm_brightness), (bright + 100) / 200f, "$bright%", step = 0.025f,
                onChange = { f -> bright = (f * 200 - 100).roundToInt(); bridge.setColorAdjust(bright, contrast, gamma, false) },
                onCommit = { bridge.setColorAdjust(bright, contrast, gamma, true) })
            StepSlider(uiStr(R.string.l_qm_contrast), (contrast + 100) / 200f, "$contrast%", step = 0.025f,
                onChange = { f -> contrast = (f * 200 - 100).roundToInt(); bridge.setColorAdjust(bright, contrast, gamma, false) },
                onCommit = { bridge.setColorAdjust(bright, contrast, gamma, true) })
            StepSlider(uiStr(R.string.l_qm_gamma), (gamma - 0.5f) / 2f, String.format(Locale.getDefault(), "%.2fx", gamma), step = 0.025f,
                onChange = { f -> gamma = 0.5f + f * 2f; bridge.setColorAdjust(bright, contrast, gamma, false) },
                onCommit = { bridge.setColorAdjust(bright, contrast, gamma, true) })
            if (bright != 0 || contrast != 0 || kotlin.math.abs(gamma - 1f) > 0.005f) {
                TextButton(onClick = { bright = 0; contrast = 0; gamma = 1f; bridge.setColorAdjust(0, 0, 1f, true) }) { Text(uiStr(R.string.l_qm_reset_picture)) }
            }
        }
        Card {
            Text(uiStr(R.string.l_qm_image_scaling), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Chips(listOf(0 to uiStr(R.string.l_qm_fit), 1 to uiStr(R.string.l_qm_fullscreen), 2 to uiStr(R.string.l_qm_stretch)), scaling) { scaling = it; bridge.setScaling(it) }
        }
        Card {
            ActionRow(Icons.Outlined.ZoomIn, uiStr(R.string.l_qm_magnifier), uiStr(R.string.l_qm_zoom_into_part_of_the_screen)) { bridge.openMagnifier(); bridge.close() }
            ActionRow(Icons.Outlined.PictureInPicture, uiStr(R.string.l_qm_picture_in_picture)) { bridge.enterPip(); bridge.close() }
        }
    }
}

// ---------- Controls ----------

@Composable
private fun ControlsSection(bridge: QuickMenuBridge, opened: Int) {
    val profiles = remember(opened) { bridge.controlProfiles() }
    var profile by remember(opened) { mutableIntStateOf(bridge.controlProfile()) }
    var touch by remember(opened) { mutableStateOf(bridge.touchControls()) }
    var timeout by remember(opened) { mutableStateOf(bridge.touchTimeout()) }
    var haptics by remember(opened) { mutableStateOf(bridge.touchHaptics()) }
    var opacity by remember(opened) { mutableFloatStateOf(bridge.controlsOpacity()) }
    var relative by remember(opened) { mutableStateOf(bridge.relativeMouse()) }
    var noMouse by remember(opened) { mutableStateOf(bridge.mouseDisabled()) }
    var mouseSpeed by remember(opened) { mutableFloatStateOf(bridge.mouseSpeed()) }

    SectionPage(uiStr(R.string.l_qm_controls)) {
        Card {
            Text(uiStr(R.string.l_qm_on_screen_controls_profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Chips(listOf(0 to uiStr(R.string.l_qm_none)) + profiles.map { it.id to it.name }, profile) { profile = it; bridge.setControlProfile(it) }
            ActionRow(Icons.Outlined.Edit, uiStr(R.string.l_qm_edit_controls), uiStr(R.string.l_qm_open_the_editor_for_this_profile)) { bridge.editControls() }
        }
        Card {
            SwitchRow(uiStr(R.string.l_qm_show_touch_controls), uiStr(R.string.l_qm_off_the_on_screen_buttons_are), checked = touch) { touch = it; bridge.setTouchControls(it) }
            SwitchRow(uiStr(R.string.l_qm_hide_when_not_touched), uiStr(R.string.l_qm_buttons_fade_out_after_5_s), checked = timeout) { timeout = it; bridge.setTouchTimeout(it) }
            SwitchRow(uiStr(R.string.l_qm_vibrate_on_touch), checked = haptics) { haptics = it; bridge.setTouchHaptics(it) }
        }
        StepSlider(uiStr(R.string.l_qm_controls_opacity), opacity, "${(opacity * 100).roundToInt()}%", onChange = { f -> opacity = f; bridge.setControlsOpacity(f) })
        Card {
            SwitchRow(uiStr(R.string.l_qm_relative_mouse), uiStr(R.string.l_qm_mouse_moves_by_touch_delta_for), checked = relative) { relative = it; bridge.setRelativeMouse(it) }
            SwitchRow(uiStr(R.string.l_qm_disable_mouse), checked = noMouse) { noMouse = it; bridge.setMouseDisabled(it) }
        }
        StepSlider(
            uiStr(R.string.l_qm_mouse_sensitivity), (mouseSpeed - 0.1f) / 4.9f,
            String.format(java.util.Locale.US, "%.1f×", mouseSpeed), step = 0.1f / 4.9f,
            onChange = { f -> mouseSpeed = 0.1f + f * 4.9f; bridge.setMouseSpeed(mouseSpeed) }
        )
        GyroCard(bridge, opened)
        Card {
            ActionRow(Icons.Outlined.Keyboard, uiStr(R.string.l_qm_show_keyboard)) { bridge.showKeyboard(); bridge.close() }
            ActionRow(Icons.Outlined.Vibration, uiStr(R.string.l_qm_gamepad_vibration)) { bridge.vibrationSettings() }
        }
    }
}

// ---------- Tasks ----------

@Composable
private fun TasksSection(bridge: QuickMenuBridge, opened: Int) {
    val info = remember(opened) { bridge.sessionInfo() }
    var tasks by remember { mutableStateOf(QmTasks(0, "…", emptyList())) }
    var ending by remember { mutableStateOf<QmProcess?>(null) }
    var cores by remember { mutableStateOf<QmProcess?>(null) }
    var newTask by remember { mutableStateOf(false) }
    DisposableEffect(opened) {
        bridge.startTasks { tasks = it }
        onDispose { bridge.stopTasks() }
    }
    SectionPage(uiStr(R.string.l_qm_task_manager)) {
        Card {
            info.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 110.dp))
                    Text(row.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Metric("CPU", "${tasks.cpuPercent}%") }
            Box(Modifier.weight(1.4f)) { Metric(uiStr(R.string.l_qm_memory), tasks.memory) }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiStr(R.string.l_qm_processes_1_d, tasks.processes.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { newTask = true }) { Text(uiStr(R.string.l_qm_new_task)) }
            }
            if (tasks.processes.isEmpty()) Text(uiStr(R.string.l_qm_loading), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            tasks.processes.forEach { p -> ProcessRow(p, bridge, onCores = { cores = p }) { ending = p } }
        }
        if (bridge.logsAvailable()) Card { TextButton(onClick = { bridge.showLogs(); bridge.close() }) { Text(uiStr(R.string.l_qm_show_logs)) } }
    }
    cores?.let { p -> CoresDialog(p, bridge) { cores = null } }
    if (newTask) NewTaskDialog(bridge) { newTask = false }
    ending?.let { p ->
        AlertDialog(
            shape = RoundedCornerShape(28.dp), containerColor = QM_DIALOG_BG, tonalElevation = 0.dp,
            onDismissRequest = { ending = null },
            title = { Text(uiStr(R.string.l_qm_end_1_s, p.name)) },
            text = { Text(uiStr(R.string.l_qm_the_process_is_closed_without_saving)) },
            confirmButton = { TextButton(onClick = { bridge.endProcess(p.name); ending = null }) { Text(uiStr(R.string.l_qm_end)) } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text(uiStr(R.string.l_qm_cancel)) } }
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Card {
        Label(label)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ProcessRow(p: QmProcess, bridge: QuickMenuBridge, onCores: () -> Unit, onEnd: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name + if (p.wow64) " *32" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("PID ${p.pid} · ${p.memory}" + (if (p.cores.isNotEmpty()) uiStr(R.string.l_qm_cores_1_s, p.cores) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.l_qm_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp),
                containerColor = QM_DIALOG_BG, tonalElevation = 0.dp, shadowElevation = 12.dp) {
                DropdownMenuItem(text = { Text(uiStr(R.string.l_qm_bring_to_front)) }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                    onClick = { menu = false; bridge.bringToFront(p.name); bridge.close() })
                DropdownMenuItem(text = { Text(uiStr(R.string.l_qm_processor_affinity)) }, leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                    onClick = { menu = false; onCores() })
                DropdownMenuItem(text = { Text(uiStr(R.string.l_qm_end_process), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onEnd() })
            }
        }
    }
}

@Suppress("unused")
private fun pct(v: Float) = String.format(Locale.US, "%d%%", (v * 100).roundToInt())


// ---------- Gyroscope ----------

private val GYRO_BUTTONS = listOf(4 to "LB", 5 to "RB", 10 to "LT", 11 to "RT", 8 to "L3", 9 to "R3", 0 to "A", 1 to "B", 2 to "X", 3 to "Y")
/** Mouse (physical, touchpad or on-screen) and keyboard keys: GyroInput.BUTTON_MOUSE_BASE / BUTTON_KEY_BASE + X keycode. */
private val GYRO_MOUSE_KEYS get() = listOf(101 to uiStr(R.string.l_qm_right_mouse), 100 to uiStr(R.string.l_qm_left_mouse), 102 to uiStr(R.string.l_qm_middle_mouse),
    250 to "Shift", 237 to "Ctrl", 264 to "Alt", 265 to uiStr(R.string.l_qm_space), 224 to "Q", 226 to "E", 241 to "F", 227 to "R", 254 to "C")

@Composable
private fun GyroCard(bridge: QuickMenuBridge, opened: Int) {
    var g by remember(opened) { mutableStateOf(bridge.gyro()) }
    fun update(next: QmGyro) { g = next; bridge.setGyro(next) }
    Card {
        SwitchRow(
            uiStr(R.string.l_qm_gyroscope),
            if (!g.available) uiStr(R.string.l_qm_this_phone_has_no_gyroscope) else uiStr(R.string.l_qm_aim_by_turning_the_phone),
            checked = g.enabled && g.available
        ) { if (g.available) update(g.copy(enabled = it)) }
        if (g.enabled && g.available) {
            Label(uiStr(R.string.l_qm_controls))
            Chips(listOf(0 to uiStr(R.string.l_qm_mouse), 1 to uiStr(R.string.l_qm_right_stick), 2 to uiStr(R.string.l_qm_left_stick)), g.target) { update(g.copy(target = it)) }
            if (g.target == 0) {
                Label(uiStr(R.string.l_qm_mouse_goes_to))
                Chips(listOf(0 to uiStr(R.string.l_qm_auto), 1 to uiStr(R.string.l_qm_game_windows_input), 2 to uiStr(R.string.l_qm_desktop_pointer)), g.mouseMode) { update(g.copy(mouseMode = it)) }
            }
            Label(uiStr(R.string.l_qm_active))
            Chips(listOf(0 to uiStr(R.string.l_qm_always), 1 to uiStr(R.string.l_qm_hold_button), 2 to uiStr(R.string.l_qm_toggle_button)), g.activation) { update(g.copy(activation = it)) }
            if (g.activation != 0) {
                Label(uiStr(R.string.l_qm_gamepad_button))
                Chips(GYRO_BUTTONS, g.button) { update(g.copy(button = it)) }
                Label(uiStr(R.string.l_qm_or_mouse_button_key))
                Chips(GYRO_MOUSE_KEYS, g.button) { update(g.copy(button = it)) }
            }
            SwitchRow(uiStr(R.string.l_qm_high_polling_rate), uiStr(R.string.l_qm_smoother_uses_a_bit_more_battery), checked = g.fast) { update(g.copy(fast = it)) }
            SwitchRow(uiStr(R.string.l_qm_invert_horizontal), checked = g.invertX) { update(g.copy(invertX = it)) }
            SwitchRow(uiStr(R.string.l_qm_invert_vertical), checked = g.invertY) { update(g.copy(invertY = it)) }
        }
    }
    if (g.enabled && g.available) {
        StepSlider(uiStr(R.string.l_qm_gyro_sensitivity), (g.sensitivity - 0.1f) / 4.9f, String.format(Locale.US, "%.1fx", g.sensitivity), step = 0.02f,
            onChange = { f -> g = g.copy(sensitivity = 0.1f + f * 4.9f) },
            onCommit = { bridge.setGyro(g) })
    }
}


// ---------- Frame generation ----------

@Composable
private fun FrameGenCard(bridge: QuickMenuBridge, opened: Int) {
    var fg by remember(opened) { mutableStateOf(bridge.frameGen()) }
    fun update(next: QmFrameGen) { fg = next; bridge.setFrameGen(next) }
    Card {
        SwitchRow(
            uiStr(R.string.l_qm_frame_generation),
            (when (fg.engine) { "lsfg" -> "LSFG"; "wfg" -> "WFG"; else -> "Win-FG" }) + uiStr(R.string.l_qm_generated_frames_between_real_ones_shown),
            checked = fg.on
        ) { update(fg.copy(on = it)) }
        if (!fg.loaded) {
            Text(uiStr(R.string.l_qm_needs_the_vulkan_renderer_container_settings),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (fg.on) {
            Label(uiStr(R.string.l_qm_engine))
            Chips(listOf("winfg" to "Win-FG", "wfg" to "WFG", "lsfg" to "LSFG"), fg.engine) { update(fg.copy(engine = it)) }
            if (fg.engine == "lsfg" && !fg.lsfgImported) {
                Text(uiStr(R.string.l_qm_import_your_own_lossless_dll_in),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (fg.engine != "winfg") {
                Label(uiStr(R.string.l_qm_target_adaptive_generates_as_many_frames))
                Chips(listOf(0 to uiStr(R.string.l_qm_off), 60 to "60", 90 to "90", 120 to "120", 144 to "144"), fg.target) { update(fg.copy(target = it)) }
            }
            Label(if (fg.engine != "winfg" && fg.target > 0) uiStr(R.string.l_qm_multiplier_ignored_with_a_target) else uiStr(R.string.l_qm_multiplier))
            Chips(listOf(2 to "2×", 3 to "3×", 4 to "4×"), fg.multiplier) { update(fg.copy(multiplier = it)) }
            if (fg.engine != "lsfg") {
                Label(if (fg.engine == "wfg") uiStr(R.string.l_qm_motion_search_detail_vs_speed) else uiStr(R.string.l_qm_mode))
                Chips(listOf(0 to uiStr(R.string.l_qm_quality), 1 to uiStr(R.string.l_qm_balanced), 2 to uiStr(R.string.l_qm_performance)), fg.preset) { update(fg.copy(preset = it)) }
            }
            Text(uiStr(R.string.l_qm_works_best_when_the_game_holds),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (fg.on && fg.engine != "wfg") {
        StepSlider(uiStr(R.string.l_qm_motion_detail), (fg.flowScale - 0.2f) / 0.8f, "${(fg.flowScale * 100).roundToInt()}%", step = 0.05f,
            onChange = { f -> fg = fg.copy(flowScale = 0.2f + f * 0.8f) },
            onCommit = { bridge.setFrameGen(fg) })
    }
}


/** The cores a process may run on, as toggles (the old dialog-style picker, restyled). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CoresDialog(p: QmProcess, bridge: QuickMenuBridge, onDone: () -> Unit) {
    val n = remember { bridge.cpuCount().coerceIn(1, 31) }
    val all = (1 shl n) - 1
    var mask by remember { mutableIntStateOf(bridge.processCores(p.pid).let { if (it == 0) all else it and all }) }
    AlertDialog(
        shape = RoundedCornerShape(28.dp), containerColor = QM_DIALOG_BG, tonalElevation = 0.dp,
        onDismissRequest = onDone,
        title = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uiStr(R.string.l_qm_cores_this_process_may_run_on), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 4 per row (2 rows on an 8-core phone); a selected core is filled with the accent.
                for (row in 0 until (n + 3) / 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0 until 4) {
                            val i = row * 4 + col
                            if (i >= n) { Spacer(Modifier.weight(1f)); continue }
                            val on = mask and (1 shl i) != 0
                            Surface(
                                onClick = { val m = mask xor (1 shl i); if (m != 0) mask = m },
                                shape = RoundedCornerShape(14.dp),
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(uiStr(R.string.l_qm_cpu_1_d, i), fontWeight = FontWeight.SemiBold,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { bridge.setProcessCores(p.pid, mask); onDone() }) { Text(uiStr(R.string.l_qm_apply)) } },
        dismissButton = { TextButton(onClick = onDone) { Text(uiStr(R.string.l_qm_cancel)) } }
    )
}

/** Runs a program in the session (taskmgr.exe, cmd.exe, regedit.exe, a game's other .exe). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewTaskDialog(bridge: QuickMenuBridge, onDone: () -> Unit) {
    var command by remember { mutableStateOf("taskmgr.exe") }
    AlertDialog(
        shape = RoundedCornerShape(28.dp), containerColor = QM_DIALOG_BG, tonalElevation = 0.dp,
        onDismissRequest = onDone,
        title = { Text(uiStr(R.string.l_qm_new_task_2)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = command, onValueChange = { command = it }, singleLine = true,
                    label = { Text(uiStr(R.string.l_qm_program)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("taskmgr.exe", "cmd.exe", "regedit.exe", "winecfg.exe").forEach { c ->
                        FilterChip(selected = command == c, onClick = { command = c }, label = { Text(c.removeSuffix(".exe")) }, shape = RoundedCornerShape(12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = command.isNotBlank(), onClick = { bridge.runTask(command.trim()); onDone() }) { Text(uiStr(R.string.l_qm_run)) } },
        dismissButton = { TextButton(onClick = onDone) { Text(uiStr(R.string.l_qm_cancel)) } }
    )
}

/** Dialogs and menus of the quick menu: its near-black panel colour, not Material's grey-blue. */
private val QM_DIALOG_BG = Color(0xFF16181C)

// ---------- Own CPU / GPU clocks (root) ----------

/** Per-cluster CPU and GPU clock ranges for this game, applied live and kept by [com.winlator.cmod.perf.FreqTuner]. */
@Composable
private fun FreqEditorCard(perf: com.winlator.cmod.session.PerfSession, opened: Int) {
    val info by androidx.compose.runtime.produceState<Pair<List<com.winlator.cmod.perf.FreqTuner.Cluster>, com.winlator.cmod.perf.FreqTuner.Gpu?>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.winlator.cmod.perf.FreqTuner.clusters() to com.winlator.cmod.perf.FreqTuner.gpu()
        }
    }
    var profile by remember(opened) { mutableStateOf(perf.freqProfile()) }
    var live by remember { mutableStateOf<com.winlator.cmod.perf.FreqTuner.Live?>(null) }
    androidx.compose.runtime.LaunchedEffect(opened) {
        while (true) {
            live = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.winlator.cmod.perf.FreqTuner.live() }.getOrNull() }
            kotlinx.coroutines.delay(1000)
        }
    }
    Card {
        Label(uiStr(R.string.l_frq_title))
        Text(uiStr(R.string.l_frq_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val (clusters, gpu) = info ?: return@Card
        if (clusters.isEmpty() && gpu == null) {
            Text(uiStr(R.string.l_frq_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Card
        }
        fun ghz(khz: Int) = uiStr(R.string.l_frq_ghz, String.format(java.util.Locale.US, "%.2f", khz / 1_000_000.0))
        for (c in clusters) {
            FreqRangeRow(
                title = uiStr(R.string.l_frq_cpu, c.cpuLabel),
                steps = c.steps,
                range = profile.cpu[c.policy] ?: com.winlator.cmod.perf.FreqTuner.Range(),
                format = ::ghz,
                now = live?.cpuCur?.get(c.policy)?.takeIf { it > 0 },
                effectiveMax = live?.cpuMax?.get(c.policy)?.takeIf { it > 0 },
            ) { r ->
                profile = profile.copy(cpu = profile.cpu + (c.policy to r))
                perf.setFreqProfile(profile)
            }
        }
        if (gpu != null) {
            FreqRangeRow(
                title = uiStr(R.string.l_frq_gpu),
                steps = gpu.steps,
                range = profile.gpu,
                format = { uiStr(R.string.l_frq_mhz, it) },
                now = live?.gpuCurMhz?.takeIf { it > 0 },
                effectiveMax = null,
            ) { r ->
                profile = profile.copy(gpu = r)
                perf.setFreqProfile(profile)
            }
        }
        if (!profile.isEmpty) TextButton(onClick = {
            profile = com.winlator.cmod.perf.FreqTuner.Profile()
            perf.setFreqProfile(profile)
        }) { Text(uiStr(R.string.l_frq_reset)) }
    }
}

/**
 * Lowest / highest clock on a range slider over the real steps. The full range means "the system
 * decides" (stored as 0, nothing written).
 */
@Composable
private fun FreqRangeRow(
    title: String,
    steps: List<Int>,
    range: com.winlator.cmod.perf.FreqTuner.Range,
    format: (Int) -> String,
    now: Int?,
    effectiveMax: Int?,
    onChange: (com.winlator.cmod.perf.FreqTuner.Range) -> Unit,
) {
    val last = steps.size - 1
    fun idx(v: Int, fallback: Int) = if (v <= 0) fallback else steps.indices.minByOrNull { kotlin.math.abs(steps[it] - v) } ?: fallback
    var sel by remember(range, steps) { mutableStateOf(idx(range.min, 0).toFloat()..idx(range.max, last).toFloat()) }
    val lo = sel.start.roundToInt().coerceIn(0, last)
    val hi = sel.endInclusive.roundToInt().coerceIn(lo, last)
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(if (lo == 0 && hi == last) uiStr(R.string.l_frq_system) else "${format(steps[lo])} – ${format(steps[hi])}",
                style = MaterialTheme.typography.labelLarge, color = if (lo == 0 && hi == last) scheme.onSurfaceVariant else scheme.primary)
        }
        androidx.compose.material3.RangeSlider(
            value = sel,
            onValueChange = { sel = it },
            valueRange = 0f..last.toFloat(),
            steps = (steps.size - 2).coerceAtLeast(0),
            onValueChangeFinished = {
                onChange(com.winlator.cmod.perf.FreqTuner.Range(
                    min = if (lo == 0) 0 else steps[lo],
                    max = if (hi == last) 0 else steps[hi],
                ))
            }
        )
        val parts = buildList {
            if (now != null) add(uiStr(R.string.l_frq_now, format(now)))
            if (effectiveMax != null && effectiveMax < steps[hi] - 1000) add(uiStr(R.string.l_frq_capped, format(effectiveMax)))
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = if (parts.size > 1) scheme.primary else scheme.onSurfaceVariant)
    }
}
