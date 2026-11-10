package com.winlator.cmod.ui.ingame

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme

/** The magnifier's floating bar in the quick menu's style: drag handle, zoom -, value, zoom +, close. */
object MagnifierPanel {
    interface Actions {
        fun zoom(step: Float)
        fun close()
        fun drag(dx: Float, dy: Float)
        fun dragEnd()
    }

    /** The bar and the zoom value it shows (set it as the zoom changes). */
    @JvmStatic
    fun create(context: Context, zoom: MutableFloatState, actions: Actions): ComposeView = ComposeView(context).apply {
        setContent {
            WinZTheme {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { actions.dragEnd() }) { change, amount ->
                                change.consume(); actions.drag(amount.x, amount.y)
                            }
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.DragIndicator, "Move", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { actions.zoom(-0.25f) }) { Icon(Icons.Outlined.Remove, "Zoom out") }
                        Text("${(zoom.floatValue * 100).toInt()}%", Modifier.width(56.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { actions.zoom(0.25f) }) { Icon(Icons.Outlined.Add, "Zoom in") }
                        Box(Modifier.size(1.dp, 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        IconButton(onClick = { actions.close() }) { Icon(Icons.Outlined.Close, "Close magnifier") }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun zoomState(initial: Float): MutableFloatState = mutableFloatStateOf(initial)
}
