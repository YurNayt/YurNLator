package com.winlator.cmod.ui.components

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.cmod.ui.Motion
import com.winlator.cmod.ui.MotionKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Height of the video-memory limit picker (handle 48 + title 34 + 8 rows of 46 + gaps 48 + bottom
 * 18 dp): the sheet whose speed every sheet moves at.
 */
private const val REFERENCE_SHEET_DP = 516f

private class SheetLook(val containerColor: Color, val dragHandle: (@Composable () -> Unit)?)

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
fun MotionSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.(MotionSheetScope) -> Unit
) {
    val look = SheetLook(containerColor, dragHandle)
    // Drawn inside the current window, over its content, instead of in a window of its own: a new
    // window takes the focus and makes the system show and hide its bars again, which cost a frame
    // in the middle of the slide (it paused and went on). The theme and the rest come along
    // through the parent composition. A window too small for it (a small dialog) gets a Dialog.
    val root = rememberOverlayRoot()
    val latestContent = rememberUpdatedState(content)
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    if (root == null) {
        val closer = remember { mutableStateOf<(() -> Unit)?>(null) }
        Dialog(
            onDismissRequest = { closer.value?.invoke() ?: onDismissRequest() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
        ) {
            (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
                LaunchedEffect(w) { w.setDimAmount(0f); w.setWindowAnimations(0) }
            }
            KeepDialogFullscreen()
            SheetBody(onDismissRequest, { closer.value = it }, look, content)
        }
        return
    }
    InWindowOverlay(root) {
        var close by remember { mutableStateOf<(() -> Unit)?>(null) }
        BackHandler { close?.invoke() ?: latestDismiss.value() }
        SheetBody({ latestDismiss.value() }, { close = it }, look) { sheet -> latestContent.value(this, sheet) }
    }
}

/**
 * The window's content root to draw an overlay in, when the window covers (nearly) the whole
 * screen; null for a small dialog window, where an overlay would be cramped.
 */
@Composable
fun rememberOverlayRoot(): ViewGroup? {
    val host = LocalView.current
    return remember(host) {
        (host.rootView.findViewById<ViewGroup>(android.R.id.content) ?: host.rootView as? ViewGroup)
            ?.takeIf { it.width >= host.resources.displayMetrics.widthPixels * 0.9f }
    }
}

/**
 * Draws [content] over [root]'s content in the same window, as part of this composition (theme,
 * back handling and the rest carry over). No window of its own: a new window takes the focus and
 * makes the system re-hide its bars, and resizing one as its content animates lags behind.
 */
@Composable
fun InWindowOverlay(root: ViewGroup, content: @Composable () -> Unit) {
    val parent = rememberCompositionContext()
    val latest = rememberUpdatedState(content)
    DisposableEffect(root) {
        val overlay = ComposeView(root.context).apply {
            setParentCompositionContext(parent)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { latest.value() }
        }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onDispose { root.removeView(overlay) }
    }
}

@Composable
private fun SheetBody(onDismissRequest: () -> Unit, registerClose: (() -> Unit) -> Unit, look: SheetLook,
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

    // Every sheet moves at the same speed: the tuned length is for a sheet as tall as the
    // video-memory limit picker (REFERENCE_SHEET_DP); a shorter trip takes less time, a longer one
    // more. With one length for all, a short sheet crawled and a tall one rushed.
    val refPx = with(density) { REFERENCE_SHEET_DP.dp.toPx() }
    fun runTo(target: Float, then: (() -> Unit)? = null) {
        job?.cancel()
        job = scope.launch {
            val from = offset.coerceAtMost(heightPx.toFloat())
            val c = Motion.curve(MotionKind.SHEET)
            val ms = (c.ms * kotlin.math.abs(target - from) / refPx).toInt().coerceIn(60, 3000)
            // Going away starts at once (no slow start), like the variables list.
            val easing = if (target > 0f) CubicBezierEasing(0f, 0f, c.x2, c.y2) else c.easing()
            animate(from, target, animationSpec = tween(ms, easing = easing)) { v, _ -> offset = v }
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

    // Open once measured, two frames later: the window hides the system bars as it appears, and
    // the first frames are spent on that and on composing the list.
    LaunchedEffect(heightPx > 0) {
        if (heightPx > 0 && !closing) {
            withFrameNanos { }; withFrameNanos { }
            runTo(0f)
        }
    }
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
                // The sheet's own size never depends on the system bars: they are hidden just after
                // it appears, and a bottom padding that vanished mid-slide made it drop ~48 dp and
                // look as if it stopped short and then moved on.
                .consumeWindowInsets(WindowInsets.systemBars)
                .onSizeChanged { heightPx = it.height }
                .graphicsLayer { translationY = offset.coerceAtLeast(0f) }
                .nestedScroll(nested),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = look.containerColor,
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
                ) { look.dragHandle?.invoke() ?: Spacer(Modifier.height(16.dp)) }
                content(sheetScope)
            }
        }
    }
}
