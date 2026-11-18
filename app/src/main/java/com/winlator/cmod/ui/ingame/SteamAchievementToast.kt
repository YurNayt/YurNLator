package com.winlator.cmod.ui.ingame

import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An earned Steam achievement, drawn the way the Steam overlay shows one: a dark blue panel in the
 * bottom-right corner with the achievement's picture, Steam's blue caption, the name in white and
 * the description in grey; a light sweeps across it once as it arrives.
 *
 * It rises from the bottom edge and leaves the same way. Swipe it down (where it came from) to send
 * it off: the finger moves it 1:1, upwards it gives way with resistance, on release a flick or 40 %
 * of its height sends it away with a spring started at the finger's speed, otherwise it springs
 * back with a slight bounce. A tap sends it away. One at a time; only the panel takes touches.
 */
object SteamAchievementToast {
    private const val TAG = "steam_achievement_toast"

    private val TOP = Color(0xFF2A3B50)
    private val BOTTOM = Color(0xFF18212D)
    private val EDGE = Color(0xFF3F5A78)
    private val CAPTION = Color(0xFF66C0F4)
    private val TEXT = Color(0xFFFFFFFF)
    private val DESC = Color(0xFF9AA4AE)

    fun show(root: FrameLayout?, caption: String, title: String, description: String?, picture: Bitmap?,
             shownMs: Long, onGone: (() -> Unit)? = null) {
        root ?: return
        root.findViewWithTag<android.view.View>(TAG)?.let { root.removeView(it) }
        val view = ComposeView(root.context)
        view.tag = TAG
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            Toast(caption, title, description, picture, shownMs) { root.post { root.removeView(view); onGone?.invoke() } }
        }
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END))
    }

    @Composable
    private fun Toast(caption: String, title: String, description: String?, picture: Bitmap?, shownMs: Long, onGone: () -> Unit) {
        val density = LocalDensity.current
        val margin = with(density) { 16.dp.toPx() }
        val flickPx = with(density) { 500.dp.toPx() }
        var heightPx by remember { mutableIntStateOf(0) }
        val away = { heightPx + margin + with(density) { 12.dp.toPx() } }
        val y = remember { Animatable(with(density) { 120.dp.toPx() }) }
        val shine = remember { Animatable(-0.4f) }
        val scope = rememberCoroutineScope()
        var holds by remember { mutableIntStateOf(0) }
        var touching by remember { mutableIntStateOf(0) }

        suspend fun leave(velocity: Float = 0f) {
            y.animateTo(away(), spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), velocity)
            onGone()
        }

        LaunchedEffect(holds) {
            if (holds == 0) {
                y.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
                launch { shine.animateTo(1.4f, tween(900, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))) }
            }
            delay(shownMs)
            if (touching == 0) leave()
        }

        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier
                .padding(end = 16.dp, bottom = 16.dp, start = 16.dp, top = 16.dp)
                .onSizeChanged { heightPx = it.height }
                .graphicsLayer {
                    translationY = y.value
                    val gone = if (y.value > 0f) (y.value / away()).coerceIn(0f, 1f) else 0f
                    alpha = 1f - gone
                }
                .shadow(10.dp, shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(TOP, BOTTOM)))
                .border(1.dp, EDGE.copy(alpha = 0.7f), shape)
                .drawWithContent {
                    drawContent()
                    // One sweep of light across the panel as it arrives.
                    val x = shine.value * size.width
                    val band = size.width * 0.35f
                    drawRect(Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                        start = Offset(x - band, 0f), end = Offset(x + band, size.height)))
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { scope.launch { leave() } }) }
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
                                if (v > flickPx || y.value > heightPx * 0.4f) leave(v)
                                else { y.animateTo(0f, spring(0.8f, Spring.StiffnessMedium), v); holds++ }
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
                        // Down: 1:1. Up (nothing further): a fifth of the way.
                        scope.launch { y.snapTo(if (total < 0f) total * 0.2f else total) }
                    }
                }
        ) {
            Row(Modifier.padding(10.dp).widthIn(max = 380.dp), verticalAlignment = Alignment.CenterVertically) {
                if (picture != null) {
                    Image(remember(picture) { picture.asImageBitmap() }, null, Modifier.size(60.dp).clip(RoundedCornerShape(3.dp)),
                        contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF233246)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.EmojiEvents, null, tint = CAPTION, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(caption, color = CAPTION, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(title, color = TEXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!description.isNullOrBlank()) {
                        Text(description, color = DESC, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
