package com.winlator.cmod.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.cmod.ui.Motion
import com.winlator.cmod.ui.MotionKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a sheet's content can do: close it the animated way (then onDismissRequest runs). */
interface MotionSheetScope {
    fun close()
}

/**
 * A bottom sheet like Material's ModalBottomSheet (same shape, colour, handle and dim), but its
 * slide up and away runs on the app's tunable "Picker windows" motion (Motion SHEET), which the
 * Material one doesn't allow. The dim behind it is derived from the sheet's position, so the two
 * never drift apart.
 *
 * Dragging (handle, or the content once its list is at the top) follows the finger 1:1; on release
 * it closes past 40 % of its height or on a downward flick, otherwise it returns. An opening or
 * closing sheet can be caught by the finger. Back and a tap on the dim close it.
 */
@Composable
fun MotionSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.(MotionSheetScope) -> Unit) {
    // Back goes through the dialog's own dismiss request to the animated close.
    val closer = remember { mutableStateOf<(() -> Unit)?>(null) }
    Dialog(
        onDismissRequest = { closer.value?.invoke() ?: onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        // Our own dim and motion: none from the window.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
            LaunchedEffect(w) {
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
            }
        }
        KeepDialogFullscreen()
        SheetBody(onDismissRequest, { closer.value = it }, content)
    }
}

@Composable
private fun SheetBody(onDismissRequest: () -> Unit, registerClose: (() -> Unit) -> Unit,
                      content: @Composable ColumnScope.(MotionSheetScope) -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    var heightPx by remember { mutableIntStateOf(0) }
    // Distance below its resting place; starts far below until the sheet is measured.
    var offset by remember { mutableFloatStateOf(100_000f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    val flickPx = with(density) { 900.dp.toPx() }

    fun runTo(target: Float, then: (() -> Unit)? = null) {
        job?.cancel()
        job = scope.launch {
            val curve = Motion.tween<Float>(MotionKind.SHEET)
            animate(offset.coerceAtMost(heightPx.toFloat()), target, animationSpec = curve) { v, _ -> offset = v }
            then?.invoke()
        }
    }
    val sheetScope = remember {
        object : MotionSheetScope {
            override fun close() {
                if (closing) return
                closing = true
                runTo(heightPx.toFloat()) { onDismissRequest() }
            }
        }
    }
    fun settle(velocity: Float) {
        if (velocity > flickPx || offset > heightPx * 0.4f) sheetScope.close() else runTo(0f)
    }

    // Open once measured.
    LaunchedEffect(heightPx > 0) { if (heightPx > 0 && !closing) runTo(0f) }
    LaunchedEffect(sheetScope) { registerClose { sheetScope.close() } }

    // Drags that start in the list: when it can't scroll up any more, the sheet moves instead.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && offset > 0f) {
                    job?.cancel()
                    val used = maxOf(available.y, -offset)
                    offset += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    job?.cancel()
                    offset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset > 0.5f) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    val fraction = if (heightPx > 0) 1f - (offset / heightPx).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxSize()) {
        // Dim, as Material's (32 % black), following the sheet.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = fraction }
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sheetScope.close() }
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .onSizeChanged { heightPx = it.height }
                .graphicsLayer { translationY = offset.coerceAtLeast(0f) }
                .nestedScroll(nested),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = BottomSheetDefaults.ContainerColor,
            tonalElevation = 1.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                // The handle: drag the sheet from here at any time.
                Box(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { job?.cancel(); tracker.resetTracking(); total = offset },
                            onDragEnd = { settle(tracker.calculateVelocity().y) },
                            onDragCancel = { settle(0f) }
                        ) { change, amount ->
                            change.consume()
                            total = (total + amount).coerceAtLeast(0f)
                            tracker.addPosition(change.uptimeMillis, Offset(0f, total))
                            offset = total
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { BottomSheetDefaults.DragHandle() }
                content(sheetScope)
            }
        }
    }
}
