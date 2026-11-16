package com.winlator.cmod.ui.ingame

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A short notice at the top of the game screen when a physical gamepad starts or stops being used:
 * a card in the app theme that drops in from the top edge, stays 3 s and leaves the way it came.
 *
 * It can be swiped away upwards (the direction it entered from): while the finger is down the
 * card follows it 1:1, pulled down it gives way with resistance (rubber band), and its opacity is
 * derived from the same position, so fading and moving never drift apart. On release it leaves on
 * a flick or past 40 % of its height, with a spring started at the finger's speed; otherwise it
 * springs back with a slight bounce (only a drag earns a bounce) and its 3 s start again.
 * Everything is a spring, so a card that is entering or leaving can be caught by the finger.
 * One notice at a time; the card takes touches only on itself.
 */
/** How long the notice stays when nobody touches it. */
private const val SHOWN_MS = 3000L

object GamepadNotice {
    private const val TAG = "gamepad_notice"


    @JvmStatic
    fun show(root: FrameLayout?, title: String, subtitle: String?) {
        root ?: return
        root.findViewWithTag<android.view.View>(TAG)?.let { root.removeView(it) }
        val view = ComposeView(root.context)
        view.tag = TAG
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            WinZTheme { Notice(title, subtitle) { root.post { root.removeView(view) } } }
        }
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }
}

@Composable
private fun Notice(title: String, subtitle: String?, onGone: () -> Unit) {
    val density = LocalDensity.current
    val topMargin = with(density) { 16.dp.toPx() }
    val flickPx = with(density) { 500.dp.toPx() }      // px per second
    var heightPx by remember { mutableIntStateOf(0) }
    // Distance to be fully off screen above: the card plus its margin.
    val away = { -(heightPx + topMargin + with(density) { 8.dp.toPx() }) }
    val y = remember { Animatable(-with(density) { 96.dp.toPx() }) }
    val scope = rememberCoroutineScope()
    // Bumped by every touch: restarts the stay timer, and a held card is never taken away.
    var holds by remember { mutableIntStateOf(0) }
    var touching by remember { mutableIntStateOf(0) }

    suspend fun leave(velocity: Float = 0f) {
        y.animateTo(away(), spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), velocity)
        onGone()
    }

    // Enter: no bounce, nothing pushed it. Then stay, then leave upwards.
    LaunchedEffect(holds) {
        if (holds == 0) y.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
        delay(SHOWN_MS)
        if (touching == 0) leave()
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(top = 16.dp, start = 12.dp, end = 12.dp, bottom = 8.dp)
            .onSizeChanged { heightPx = it.height }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        touching = 1
                        tracker.resetTracking()
                        total = y.value
                        scope.launch { y.stop() }
                    },
                    onDragEnd = {
                        touching = 0
                        val v = tracker.calculateVelocity().y
                        scope.launch {
                            if (v < -flickPx || -y.value > heightPx * 0.4f) leave(v)
                            else {
                                y.animateTo(0f, spring(0.8f, Spring.StiffnessMedium), v)
                                holds++
                            }
                        }
                    },
                    onDragCancel = {
                        touching = 0
                        scope.launch { y.animateTo(0f, spring(0.8f, Spring.StiffnessMedium)); holds++ }
                    }
                ) { change, amount ->
                    change.consume()
                    total += amount
                    tracker.addPosition(change.uptimeMillis, Offset(0f, total))
                    // Up: 1:1. Down (there is nothing further): a fifth of the way, like a rubber band.
                    val shown = if (total > 0f) total * 0.2f else total
                    scope.launch { y.snapTo(shown) }
                }
            }
            .graphicsLayer {
                translationY = y.value
                val gone = if (y.value < 0f) (-y.value / -away()).coerceIn(0f, 1f) else 0f
                alpha = 1f - gone
            }
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.SportsEsports, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.widthIn(max = 360.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
