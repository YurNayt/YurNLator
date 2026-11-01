package com.winlator.cmod.ui.quickmenu

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
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private enum class Section(val title: String, val icon: ImageVector) {
    HUD("Performance HUD", Icons.Outlined.QueryStats),
    SCREEN("Screen effects", Icons.Outlined.AutoFixHigh),
    CONTROLS("Controls", Icons.Outlined.SportsEsports),
    TASKS("Task manager", Icons.Outlined.ListAlt),
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
                    Text("Quick menu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // Always visible, also on short landscape screens where the rail scrolls.
                    TextButton(onClick = { confirmExit = true }) {
                        Icon(Icons.Outlined.ExitToApp, null, tint = scheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Exit game", color = scheme.error)
                    }
                    IconButton(onClick = { bridge.close() }) { Icon(Icons.Outlined.Close, "Close") }
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
                        RailButton(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) "Resume" else "Pause", selected = paused) {
                            paused = !paused
                            bridge.setPaused(paused)
                        }
                        RailButton(Icons.Outlined.ExitToApp, "Exit", selected = false, danger = true) { confirmExit = true }
                    }
                    Box(Modifier.fillMaxSize().padding(end = 14.dp)) {
                        when (section) {
                            Section.HUD -> HudSection(bridge, opened)
                            Section.SCREEN -> ScreenSection(bridge, opened)
                            Section.CONTROLS -> ControlsSection(bridge, opened)
                            Section.TASKS -> TasksSection(bridge, opened)
                        }
                    }
                }
            }
        }
        if (confirmExit) AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit the game?") },
            text = { Text("Unsaved progress in the game will be lost.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; bridge.exitGame() }) { Text("Exit") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancel") } }
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
                            detectTapGestures { o -> onChange((o.x / widthPx).coerceIn(0f, 1f)); onCommit() }
                        }
                        .pointerInput(widthPx) {
                            detectHorizontalDragGestures(onDragEnd = onCommit) { change, _ ->
                                onChange((change.position.x / widthPx).coerceIn(0f, 1f))
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

    SectionPage("Performance HUD") {
        Card {
            SwitchRow("Performance HUD", "Show or hide the in-game overlay", checked = mode != 0) { on ->
                mode = if (on) 2 else 0
                bridge.setHudMode(mode)
            }
            if (mode != 0) {
                Label("Style")
                Chips(listOf(2 to "Modern", 1 to "Classic"), mode) { mode = it; bridge.setHudMode(it) }
            }
        }
        Card {
            SwitchRow("FPS limit", if (fps == 0) "Off: the game runs as fast as it can" else "Capped at $fps FPS", checked = fps != 0) { on ->
                fps = if (on) 60 else 0
                custom = false
                bridge.setFpsLimit(fps)
            }
            if (fps != 0 || custom) {
                val chips = FPS_PRESETS.drop(1).map { it to it.toString() } + (-1 to "Custom")
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
                        label = { Text("FPS (10 – 240)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (mode == 2) {
            StepSlider("HUD size", (scale - 0.5f) / 1.5f, "${(scale * 100).roundToInt()}%", onChange = { f ->
                scale = 0.5f + f * 1.5f
                bridge.setHudScale(scale)
            })
            StepSlider("HUD opacity", alpha, "${(alpha * 100).roundToInt()}%", onChange = { f ->
                alpha = f
                bridge.setHudAlpha(f)
            })
            Card {
                Text("Shown in the HUD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                listOf(0 to "FPS", 2 to "GPU load", 3 to "CPU load", 7 to "RAM", 4 to "Battery and temperature", 6 to "Renderer").forEach { (idx, name) ->
                    val on = remember(opened, rev, idx) { bridge.hudElement(idx) }
                    SwitchRow(name, checked = on) { bridge.setHudElement(idx, it); rev++ }
                }
                TextButton(onClick = { bridge.resetHud(); rev++ }) { Text("Reset HUD position") }
            }
        } else if (mode == 1) {
            Card { Text("The classic HUD shows FPS and GPU info. Switch to Modern for size, opacity and metrics.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---------- Screen ----------

private val SHADERS = listOf(
    0 to "Off", 1 to "DLS sharpen", 2 to "CRT", 3 to "HDR", 4 to "Natural",
    10 to "Game Clarity", 11 to "Cinematic", 12 to "Vivid", 13 to "Competitive", 14 to "Adaptive Sharpen",
    15 to "Filmic", 16 to "Arcade", 17 to "Retro CRT", 18 to "Upscale Sharp", 19 to "Pixel Clean", 20 to "Anime Edge"
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

    SectionPage("Screen effects") {
        if (!vulkan) {
            Card { Text("Upscaling and shaders need the Vulkan renderer (container settings › Renderer).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Card {
                SwitchRow("Upscaling", "Sharper image at the game's resolution", checked = upscaler >= 0) { on ->
                    upscaler = if (on) 1 else -1
                    bridge.setUpscaler(upscaler)
                    if (on && shader >= 10) shader = 0
                }
                if (upscaler >= 0) Chips(listOf(1 to "FSR", 0 to "SGSR"), upscaler) { upscaler = it; bridge.setUpscaler(it) }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Shader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(SHADERS.firstOrNull { it.first == shader }?.second ?: "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
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
                    if (shader >= 10) Text("Effect shaders replace upscaling while on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (upscaler >= 0 || shader != 0) {
                StepSlider(if (shader >= 10) "Effect strength" else "Sharpness", sharp / 100f, "$sharp%",
                    onChange = { f -> sharp = (f * 100).roundToInt(); bridge.setSharpness(sharp, false) },
                    onCommit = { bridge.setSharpness(sharp, true) })
            }
        }
        FrameGenCard(bridge, opened)
        if (vulkan) {
            StepSlider("Brightness", (bright + 100) / 200f, "$bright%", step = 0.025f,
                onChange = { f -> bright = (f * 200 - 100).roundToInt(); bridge.setColorAdjust(bright, contrast, gamma, false) },
                onCommit = { bridge.setColorAdjust(bright, contrast, gamma, true) })
            StepSlider("Contrast", (contrast + 100) / 200f, "$contrast%", step = 0.025f,
                onChange = { f -> contrast = (f * 200 - 100).roundToInt(); bridge.setColorAdjust(bright, contrast, gamma, false) },
                onCommit = { bridge.setColorAdjust(bright, contrast, gamma, true) })
            StepSlider("Gamma", (gamma - 0.5f) / 2f, String.format(Locale.getDefault(), "%.2fx", gamma), step = 0.025f,
                onChange = { f -> gamma = 0.5f + f * 2f; bridge.setColorAdjust(bright, contrast, gamma, false) },
                onCommit = { bridge.setColorAdjust(bright, contrast, gamma, true) })
            if (bright != 0 || contrast != 0 || kotlin.math.abs(gamma - 1f) > 0.005f) {
                TextButton(onClick = { bright = 0; contrast = 0; gamma = 1f; bridge.setColorAdjust(0, 0, 1f, true) }) { Text("Reset picture") }
            }
        }
        Card {
            Text("Image scaling", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Chips(listOf(0 to "Fit", 1 to "Fullscreen", 2 to "Stretch"), scaling) { scaling = it; bridge.setScaling(it) }
        }
        Card {
            ActionRow(Icons.Outlined.ZoomIn, "Magnifier", "Zoom into part of the screen") { bridge.openMagnifier(); bridge.close() }
            ActionRow(Icons.Outlined.PictureInPicture, "Picture in picture") { bridge.enterPip(); bridge.close() }
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

    SectionPage("Controls") {
        Card {
            Text("On-screen controls profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Chips(listOf(0 to "None") + profiles.map { it.id to it.name }, profile) { profile = it; bridge.setControlProfile(it) }
            ActionRow(Icons.Outlined.Edit, "Edit controls", "Open the editor for this profile") { bridge.editControls() }
        }
        Card {
            SwitchRow("Show touch controls", checked = touch) { touch = it; bridge.setTouchControls(it) }
            SwitchRow("Hide when not touched", "Controls fade out after a few seconds", checked = timeout) { timeout = it; bridge.setTouchTimeout(it) }
            SwitchRow("Vibrate on touch", checked = haptics) { haptics = it; bridge.setTouchHaptics(it) }
        }
        StepSlider("Controls opacity", opacity, "${(opacity * 100).roundToInt()}%", onChange = { f -> opacity = f; bridge.setControlsOpacity(f) })
        Card {
            SwitchRow("Relative mouse", "Mouse moves by touch delta, for 3D games", checked = relative) { relative = it; bridge.setRelativeMouse(it) }
            SwitchRow("Disable mouse", checked = noMouse) { noMouse = it; bridge.setMouseDisabled(it) }
        }
        GyroCard(bridge, opened)
        Card {
            ActionRow(Icons.Outlined.Keyboard, "Show keyboard") { bridge.showKeyboard(); bridge.close() }
            ActionRow(Icons.Outlined.Vibration, "Gamepad vibration") { bridge.vibrationSettings() }
        }
    }
}

// ---------- Tasks ----------

@Composable
private fun TasksSection(bridge: QuickMenuBridge, opened: Int) {
    val info = remember(opened) { bridge.sessionInfo() }
    var tasks by remember { mutableStateOf(QmTasks(0, "…", emptyList())) }
    var ending by remember { mutableStateOf<QmProcess?>(null) }
    DisposableEffect(opened) {
        bridge.startTasks { tasks = it }
        onDispose { bridge.stopTasks() }
    }
    SectionPage("Task manager") {
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
            Box(Modifier.weight(1.4f)) { Metric("Memory", tasks.memory) }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Processes: ${tasks.processes.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { bridge.newTask() }) { Text("+ New task") }
            }
            if (tasks.processes.isEmpty()) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            tasks.processes.forEach { p -> ProcessRow(p, bridge) { ending = p } }
        }
        if (bridge.logsAvailable()) Card { TextButton(onClick = { bridge.showLogs(); bridge.close() }) { Text("Show logs") } }
    }
    ending?.let { p ->
        AlertDialog(
            onDismissRequest = { ending = null },
            title = { Text("End ${p.name}?") },
            text = { Text("The process is closed without saving.") },
            confirmButton = { TextButton(onClick = { bridge.endProcess(p.name); ending = null }) { Text("End") } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Cancel") } }
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
private fun ProcessRow(p: QmProcess, bridge: QuickMenuBridge, onEnd: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name + if (p.wow64) " *32" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("PID ${p.pid} · ${p.memory}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Bring to front") }, onClick = { menu = false; bridge.bringToFront(p.name); bridge.close() })
                DropdownMenuItem(text = { Text("Processor affinity") }, onClick = { menu = false; bridge.processAffinity(p.pid) })
                DropdownMenuItem(text = { Text("End process") }, onClick = { menu = false; onEnd() })
            }
        }
    }
}

@Suppress("unused")
private fun pct(v: Float) = String.format(Locale.US, "%d%%", (v * 100).roundToInt())


// ---------- Gyroscope ----------

private val GYRO_BUTTONS = listOf(4 to "LB", 5 to "RB", 10 to "LT", 11 to "RT", 8 to "L3", 9 to "R3", 0 to "A", 1 to "B", 2 to "X", 3 to "Y")

@Composable
private fun GyroCard(bridge: QuickMenuBridge, opened: Int) {
    var g by remember(opened) { mutableStateOf(bridge.gyro()) }
    fun update(next: QmGyro) { g = next; bridge.setGyro(next) }
    Card {
        SwitchRow(
            "Gyroscope",
            if (!g.available) "This phone has no gyroscope" else "Aim by turning the phone",
            checked = g.enabled && g.available
        ) { if (g.available) update(g.copy(enabled = it)) }
        if (g.enabled && g.available) {
            Label("Controls")
            Chips(listOf(0 to "Mouse", 1 to "Right stick", 2 to "Left stick"), g.target) { update(g.copy(target = it)) }
            Label("Active")
            Chips(listOf(0 to "Always", 1 to "Hold button", 2 to "Toggle button"), g.activation) { update(g.copy(activation = it)) }
            if (g.activation != 0) {
                Label("Button (gamepad or on-screen gamepad)")
                Chips(GYRO_BUTTONS, g.button) { update(g.copy(button = it)) }
            }
            SwitchRow("High polling rate", "Smoother, uses a bit more battery", checked = g.fast) { update(g.copy(fast = it)) }
            SwitchRow("Invert horizontal", checked = g.invertX) { update(g.copy(invertX = it)) }
            SwitchRow("Invert vertical", checked = g.invertY) { update(g.copy(invertY = it)) }
        }
    }
    if (g.enabled && g.available) {
        StepSlider("Gyro sensitivity", (g.sensitivity - 0.1f) / 4.9f, String.format(Locale.US, "%.1fx", g.sensitivity), step = 0.02f,
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
            "Frame generation",
            "Win-FG: adds generated frames between real ones (Vulkan games)",
            checked = fg.on
        ) { update(fg.copy(on = it)) }
        if (fg.on && !fg.loaded) {
            Text("Starts with the next launch of the game: the engine loads when the game starts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (fg.on) {
            Label("Multiplier")
            Chips(listOf(2 to "2×", 3 to "3×", 4 to "4×"), fg.multiplier) { update(fg.copy(multiplier = it)) }
            Label("Mode")
            Chips(listOf(0 to "Quality", 1 to "Balanced", 2 to "Performance"), fg.preset) { update(fg.copy(preset = it)) }
            Text("Works best when the game holds a steady FPS: set the FPS limit to half (2×) of the screen rate.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (fg.on) {
        StepSlider("Motion detail", (fg.flowScale - 0.2f) / 0.8f, "${(fg.flowScale * 100).roundToInt()}%", step = 0.05f,
            onChange = { f -> fg = fg.copy(flowScale = 0.2f + f * 0.8f) },
            onCommit = { bridge.setFrameGen(fg) })
    }
}
