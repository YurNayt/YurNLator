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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An earned achievement in the store's own look, small, in the bottom-right corner: the
 * achievement's picture, a caption, the name and one line of description.
 *  - [Look.STEAM]: Steam overlay's dark blue panel, Steam's blue caption, a light sweeping across once.
 *  - [Look.EPIC]: Epic's near-black rounded card, grey caption in capitals, an XP pill in the tier's colour.
 *
 * It rises from the bottom edge and leaves the same way. Swipe it down (where it came from) to send
 * it off: the finger moves it 1:1, upwards it gives way with resistance, on release a flick or 40 %
 * of its height sends it away with a spring started at the finger's speed, otherwise it springs
 * back with a slight bounce. A tap sends it away. One at a time; only the card takes touches.
 */
object AchievementToast {
    private const val TAG = "achievement_toast"

    class Look(
        val top: Color, val bottom: Color, val edge: Color, val caption: Color, val text: Color, val desc: Color,
        val iconBg: Color, val corner: Dp, val iconCorner: Dp, val shine: Boolean, val upperCaption: Boolean,
    ) {
        companion object {
            val STEAM = Look(Color(0xFF2A3B50), Color(0xFF18212D), Color(0xFF3F5A78), Color(0xFF66C0F4), Color.White,
                Color(0xFF9AA4AE), Color(0xFF233246), 4.dp, 2.dp, shine = true, upperCaption = false)
            val EPIC = Look(Color(0xFF252529), Color(0xFF1C1C20), Color(0xFF34343A), Color(0xFFA8A8B0), Color.White,
                Color(0xFF8E8E96), Color(0xFF2E2E34), 10.dp, 6.dp, shine = false, upperCaption = true)
        }
    }

    /** [badge]: e.g. "+10 XP" (Epic), drawn as a pill on the right, its dot in [badgeColor]. */
    fun show(root: FrameLayout?, look: Look, caption: String, title: String, description: String?, picture: Bitmap?,
             shownMs: Long, badge: String? = null, badgeColor: Color? = null, onGone: (() -> Unit)? = null) {
        root ?: return
        root.findViewWithTag<android.view.View>(TAG)?.let { root.removeView(it) }
        val view = ComposeView(root.context)
        view.tag = TAG
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            Toast(look, caption, title, description, picture, shownMs, badge, badgeColor) {
                root.post { root.removeView(view); onGone?.invoke() }
            }
        }
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END))
    }

    @Composable
    private fun Toast(look: Look, caption: String, title: String, description: String?, picture: Bitmap?, shownMs: Long,
                      badge: String?, badgeColor: Color?, onGone: () -> Unit) {
        val density = LocalDensity.current
        val margin = with(density) { 12.dp.toPx() }
        val flickPx = with(density) { 500.dp.toPx() }
        var heightPx by remember { mutableIntStateOf(0) }
        val away = { heightPx + margin + with(density) { 12.dp.toPx() } }
        val y = remember { Animatable(with(density) { 96.dp.toPx() }) }
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
                if (look.shine) launch { shine.animateTo(1.4f, tween(900, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))) }
            }
            delay(shownMs)
            if (touching == 0) leave()
        }

        val shape = RoundedCornerShape(look.corner)
        Box(
            Modifier
                .padding(12.dp)
                .onSizeChanged { heightPx = it.height }
                .graphicsLayer {
                    translationY = y.value
                    val gone = if (y.value > 0f) (y.value / away()).coerceIn(0f, 1f) else 0f
                    alpha = 1f - gone
                }
                .shadow(8.dp, shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(look.top, look.bottom)))
                .border(1.dp, look.edge.copy(alpha = 0.7f), shape)
                .drawWithContent {
                    drawContent()
                    if (!look.shine) return@drawWithContent
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
            Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp).widthIn(max = 290.dp), verticalAlignment = Alignment.CenterVertically) {
                val iconShape = RoundedCornerShape(look.iconCorner)
                if (picture != null) {
                    Image(remember(picture) { picture.asImageBitmap() }, null, Modifier.size(42.dp).clip(iconShape),
                        contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(42.dp).clip(iconShape).background(look.iconBg), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.EmojiEvents, null, tint = look.caption, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(if (look.upperCaption) caption.uppercase() else caption, color = look.caption,
                        fontSize = if (look.upperCaption) 9.sp else 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        letterSpacing = if (look.upperCaption) 0.6.sp else 0.sp, lineHeight = 12.sp)
                    Text(title, color = look.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                    if (!description.isNullOrBlank()) {
                        Text(description, color = look.desc, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                    }
                }
                if (!badge.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.clip(RoundedCornerShape(50)).background(look.iconBg).padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(badgeColor ?: look.caption))
                        Spacer(Modifier.width(4.dp))
                        Text(badge, color = look.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}
