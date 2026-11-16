package com.winlator.cmod.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.winlatorColorScheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The floating motion editor: a small panel over the app's screens to tune each kind of motion
 * (ui/Motion) and see it at once: pick a kind, drag the curve's two handles as on cubic-bezier.com
 * (or paste its four numbers), set the length; the change is kept and the next animation of that
 * kind uses it. Drag it by its top bar, fold it into a bubble, close it (Settings › Motion editor).
 * It lives in the main window: picker windows open above it, so tune, open one, close, tune again.
 */
object MotionEditorHost {
    private const val PREF_SHOWN = "motion_editor_shown"
    private const val TAG = "motion_editor"

    @JvmStatic
    fun isShown(activity: Activity) =
        PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_SHOWN, false)

    @JvmStatic
    fun setShown(activity: Activity, shown: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putBoolean(PREF_SHOWN, shown).apply()
        sync(activity)
    }

    /** Adds or removes the panel to match the setting (call on resume). */
    @JvmStatic
    fun sync(activity: Activity) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val existing = root.findViewWithTag<android.view.View>(TAG)
        val shown = isShown(activity)
        if (!shown) { existing?.let { root.removeView(it) }; return }
        if (existing != null) return
        Motion.init(activity)
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val view = ComposeView(activity)
        view.tag = TAG
        view.x = prefs.getFloat("motion_editor_x", 24f)
        view.y = prefs.getFloat("motion_editor_y", 300f)
        val move: (Float, Float) -> Unit = { x, y ->
            val maxX = (root.width - view.width).coerceAtLeast(0).toFloat()
            val maxY = (root.height - view.height).coerceAtLeast(0).toFloat()
            view.x = x.coerceIn(0f, maxX); view.y = y.coerceIn(0f, maxY)
        }
        val saveSpot = { prefs.edit().putFloat("motion_editor_x", view.x).putFloat("motion_editor_y", view.y).apply() }
        view.setContent {
            val theme = WinlatorThemeManager.currentTheme(activity)
            MaterialTheme(colorScheme = winlatorColorScheme(theme)) {
                MotionEditorPanel(
                    viewX = { view.x }, viewY = { view.y },
                    onMove = move, onMoveEnd = saveSpot,
                    onClose = { setShown(activity, false) }
                )
            }
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

private val PRESETS = listOf(
    "ease" to floatArrayOf(0.25f, 0.1f, 0.25f, 1f),
    "linear" to floatArrayOf(0f, 0f, 1f, 1f),
    "ease-in" to floatArrayOf(0.42f, 0f, 1f, 1f),
    "ease-out" to floatArrayOf(0f, 0f, 0.58f, 1f),
    "ease-in-out" to floatArrayOf(0.42f, 0f, 0.58f, 1f),
    "Material" to floatArrayOf(0.4f, 0f, 0.2f, 1f),
    "iOS" to floatArrayOf(0.32f, 0.72f, 0f, 1f),
)

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MotionEditorPanel(
    viewX: () -> Float, viewY: () -> Float,
    onMove: (Float, Float) -> Unit, onMoveEnd: () -> Unit, onClose: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var folded by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(MotionKind.SHEET) }
    val curve = Motion.curve(kind)
    var replay by remember { mutableIntStateOf(0) }
    fun format(c: MotionCurve) = "${f2(c.x1)}, ${f2(c.y1)}, ${f2(c.x2)}, ${f2(c.y2)}"
    fun parse(t: String) = t.replace("cubic-bezier", "").replace("(", "").replace(")", "")
        .split(',', ' ', ';').filter { it.isNotBlank() }.mapNotNull { it.trim().toFloatOrNull() }
    var text by remember(kind) { mutableStateOf(format(curve)) }
    // Handles and presets rewrite the numbers; what is being typed is left alone while it matches.
    LaunchedEffect(curve) { if (parse(text) != listOf(curve.x1, curve.y1, curve.x2, curve.y2)) text = format(curve) }
    fun apply(c: MotionCurve) { Motion.set(kind, c); replay++ }

    // Dragging the panel by raw screen coordinates (the view itself moves under the finger);
    // a touch that doesn't move is a tap.
    val slop = 8f * androidx.compose.ui.platform.LocalDensity.current.density
    fun dragArea(onTap: (() -> Unit)? = null): Modifier {
        var downX = 0f; var downY = 0f; var grabX = 0f; var grabY = 0f; var moved = false
        return Modifier.pointerInteropFilter { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; grabX = e.rawX - viewX(); grabY = e.rawY - viewY(); moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (moved || kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop) {
                        moved = true; onMove(e.rawX - grabX, e.rawY - grabY)
                    }
                }
                MotionEvent.ACTION_UP -> { if (moved) onMoveEnd() else onTap?.invoke() }
                MotionEvent.ACTION_CANCEL -> if (moved) onMoveEnd()
            }
            true
        }
    }

    if (folded) {
        Surface(
            shape = CircleShape, color = scheme.primary, shadowElevation = 8.dp,
            modifier = Modifier.padding(6.dp).size(52.dp).then(dragArea { folded = false })
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Animation, null, tint = scheme.onPrimary) }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 10.dp,
        modifier = Modifier.padding(6.dp).width(300.dp)
    ) {
        Column(Modifier.padding(bottom = 10.dp)) {
            // Top bar: drag here.
            Row(
                Modifier.fillMaxWidth().padding(end = 2.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f).then(dragArea()).padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Animation, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiStr(R.string.motion_editor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { folded = true }) { Icon(Icons.Outlined.Remove, null) }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, null) }
            }
            // Which motion.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MotionKind.entries.forEach { k ->
                    FilterChip(selected = k == kind, onClick = { kind = k; replay++ }, label = { Text(uiStr(k.titleRes)) })
                }
            }
            // Curve presets.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEach { (name, p) ->
                    val on = curve.x1 == p[0] && curve.y1 == p[1] && curve.x2 == p[2] && curve.y2 == p[3]
                    FilterChip(selected = on, onClick = { apply(curve.copy(x1 = p[0], y1 = p[1], x2 = p[2], y2 = p[3])) }, label = { Text(name) })
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CurveGraph(curve, Modifier.size(132.dp)) { c -> apply(c) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Preview(curve, replay, Modifier.fillMaxWidth().height(28.dp))
                    TextButton(onClick = { replay++ }) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(uiStr(R.string.motion_replay))
                    }
                    TextButton(onClick = { Motion.reset(kind); replay++ }) { Text(uiStr(R.string.motion_reset)) }
                }
            }
            // The four numbers, as cubic-bezier.com shows them (paste them here).
            OutlinedTextField(
                value = text,
                onValueChange = { t ->
                    text = t
                    val n = parse(t)
                    if (n.size == 4 && n[0] in 0f..1f && n[2] in 0f..1f) apply(curve.copy(x1 = n[0], y1 = n[1], x2 = n[2], y2 = n[3]))
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiStr(R.string.motion_duration), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${curve.ms} ms", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = curve.ms.toFloat(),
                onValueChange = { Motion.set(kind, curve.copy(ms = (it / 10f).roundToInt() * 10)) },
                onValueChangeFinished = { replay++ },
                valueRange = 30f..1500f,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

/** The curve on a square, x 0..1 and y -0.25..1.25, with its two handles to drag. */
@Composable
private fun CurveGraph(curve: MotionCurve, modifier: Modifier, onChange: (MotionCurve) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val yMin = -0.25f; val yMax = 1.25f
    var active by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf(curve) }
    latest = curve
    Canvas(
        modifier
            .background(scheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                fun toX(px: Float) = (px / size.width).coerceIn(0f, 1f)
                fun toY(py: Float) = (yMax - py / size.height * (yMax - yMin)).coerceIn(-1f, 2f)
                fun pos(x: Float, y: Float) = Offset(x * size.width, (yMax - y) / (yMax - yMin) * size.height)
                detectDragGestures(
                    onDragStart = { o ->
                        val c = latest
                        val d1 = (pos(c.x1, c.y1) - o).getDistance(); val d2 = (pos(c.x2, c.y2) - o).getDistance()
                        active = if (d1 <= d2) 1 else 2
                    },
                    onDragEnd = { active = 0 }, onDragCancel = { active = 0 }
                ) { change, _ ->
                    change.consume()
                    val x = toX(change.position.x); val y = toY(change.position.y)
                    val c = latest
                    onChange(if (active == 1) c.copy(x1 = x, y1 = y) else c.copy(x2 = x, y2 = y))
                }
            }
    ) {
        fun pos(x: Float, y: Float) = Offset(x * size.width, (yMax - y) / (yMax - yMin) * size.height)
        val p0 = pos(0f, 0f); val p3 = pos(1f, 1f)
        val h1 = pos(curve.x1, curve.y1); val h2 = pos(curve.x2, curve.y2)
        drawLine(scheme.outlineVariant, pos(0f, 0f).copy(y = pos(0f, 0f).y), pos(1f, 0f), 1f)
        drawLine(scheme.outlineVariant, pos(0f, 1f), pos(1f, 1f), 1f)
        drawLine(scheme.onSurfaceVariant, p0, h1, 2f)
        drawLine(scheme.onSurfaceVariant, p3, h2, 2f)
        val path = Path().apply { moveTo(p0.x, p0.y); cubicTo(h1.x, h1.y, h2.x, h2.y, p3.x, p3.y) }
        drawPath(path, scheme.primary, style = Stroke(width = 3.dp.toPx()))
        drawCircle(scheme.primary, 7.dp.toPx(), h1)
        drawCircle(scheme.tertiary, 7.dp.toPx(), h2)
    }
}

/** A dot crossing the bar on the curve and length, again on every change. */
@Composable
private fun Preview(curve: MotionCurve, replay: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val t = remember { Animatable(0f) }
    LaunchedEffect(replay) {
        t.snapTo(0f)
        t.animateTo(1f, tween(curve.ms, easing = curve.easing()))
    }
    Box(modifier.background(scheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(4.dp)) {
            val travel = maxWidth - 20.dp
            Box(
                Modifier.offset { IntOffset((travel.toPx() * t.value).roundToInt(), 0) }
                    .size(20.dp).background(scheme.primary, RoundedCornerShape(6.dp))
            )
        }
    }
}
