package com.winlator.cmod.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import com.winlator.cmod.ui.uiStr
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private val WHEEL_HUES = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
)

/** A small rainbow circle: the swatch that opens the colour wheel. */
@Composable
internal fun RainbowSwatch(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Brush.sweepGradient(WHEEL_HUES))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent)), radius = size.minDimension / 2.6f)
    }
}

/**
 * Any colour: hue around the wheel, saturation from the centre out, brightness on the slider.
 * [onPick] gets the colour as ARGB.
 */
@Composable
internal fun ColorWheelDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(if (initial == 0) 0xFF82B8FF.toInt() else initial, it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.25f)) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            KeepDialogFullscreen()
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                fun pick(p: Offset, w: Float, h: Float) {
                    val cx = w / 2f; val cy = h / 2f; val r = min(cx, cy)
                    val dx = p.x - cx; val dy = p.y - cy
                    hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
                    sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                }
                Canvas(
                    Modifier.size(232.dp)
                        .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { pick(it, size.width.toFloat(), size.height.toFloat()) },
                                onDrag = { change, _ -> change.consume(); pick(change.position, size.width.toFloat(), size.height.toFloat()) }
                            )
                        }
                ) {
                    val r = size.minDimension / 2f
                    drawCircle(Brush.sweepGradient(WHEEL_HUES), r)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
                    drawCircle(Color.Black.copy(alpha = 1f - value), r)
                    val a = Math.toRadians(hue.toDouble())
                    val p = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                    drawCircle(Color(argb), 11.dp.toPx(), p)
                    drawCircle(Color.White, 11.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = .5f), 12.5f.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                }
                Spacer(Modifier.size(14.dp))
                Text(uiStr(R.string.l_thm_brightness), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Slider(value = value, onValueChange = { value = it }, valueRange = 0.25f..1f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start) {
                    Box(Modifier.size(36.dp).background(Color(argb), RoundedCornerShape(10.dp))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(String.format("#%06X", argb and 0xFFFFFF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(argb); onDismiss() }) { Text(uiStr(R.string.l_thm_wheel_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiStr(R.string.l_thm_wheel_cancel)) } }
    )
}

/** Swatch frame shared by the palettes. */
internal fun Modifier.swatchFrame(selected: Boolean, ring: Color, idle: Color): Modifier =
    border(if (selected) BorderStroke(3.dp, ring) else BorderStroke(1.dp, idle), CircleShape)
