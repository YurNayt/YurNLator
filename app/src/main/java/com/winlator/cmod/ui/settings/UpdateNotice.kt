package com.winlator.cmod.ui.settings

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import com.winlator.cmod.ui.components.DownloadProgress
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.core.AppUpdater
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr

/**
 * "A new version is out". The update channel (stable or test builds, Settings → Update; until
 * chosen, the kind installed) is asked right away when the app starts, then every 30 minutes while
 * the app is in front, until something newer turns up. The card offers Update (downloads and
 * installs right there) or Later. After Later the app asks no more until it is started again: no
 * card every half hour.
 */
object UpdateNotice {
    private const val EVERY_MS = 30L * 60 * 1000
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var checking = false
    @Volatile private var showing = false
    /** For this run of the app: a card was shown (Later or Update): no more asking. */
    @Volatile private var done = false
    private var startedOnce = false
    private var front: java.lang.ref.WeakReference<Activity>? = null
    private val tick = Runnable { front?.get()?.let { check(it) }; schedule() }
    private val first = Runnable { front?.get()?.let { startedOnce = true; check(it) } }

    /** The app came to the front: ask now when it has just started, then every 30 minutes. */
    @JvmStatic
    fun onResume(activity: Activity) {
        front = java.lang.ref.WeakReference(activity)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(first)
        // Once the first screen has settled.
        if (!startedOnce) handler.postDelayed(first, 1500)
        schedule()
    }

    @JvmStatic
    fun onPause() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(first)
        front = null
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (!done && front != null) handler.postDelayed(tick, EVERY_MS)
    }

    private fun check(activity: Activity) {
        if (done || checking || showing || AppUpdater.runningDownload() != null) return
        checking = true
        val app = activity.applicationContext
        Thread({
            try {
                val update = (AppUpdater.check(app) as? AppUpdater.Check.Available)?.update ?: return@Thread
                activity.runOnUiThread {
                    if (!done && !showing && !activity.isFinishing && !activity.isDestroyed) show(activity, update)
                }
            } finally {
                checking = false
            }
        }, "UpdateNotice").apply { isDaemon = true }.start()
    }

    private const val TAG = "update_banner"
    /** Gone by itself after this long when nothing was touched. */
    private const val SHOWN_MS = 5_000L

    /** The banner's look, as chosen on the phone while it was tuned (dev 353–355). */
    class Look(
        val from: String, val place: String,
        val marginDp: Int, val widthPercent: Int, val pictureDp: Int, val pictureFull: Boolean,
        val textPercent: Int, val opacityPercent: Int, val cornerDp: Int,
    ) {
        companion object {
            val FINAL = Look("top", "top", 30, 90, 46, true, 100, 100, 16)
        }
    }

    /**
     * Where the banner stands: a phone held upright, at the top in the middle; a phone on its side and
     * any tablet, at the top on the right (left of the side tab bar when it is shown). Always the
     * width it has on an upright phone (90 % of the short side; on a tablet at most 460 dp), never
     * stretched across a wide screen.
     */
    private fun show(activity: Activity, update: AppUpdater.Update, preview: Boolean = false) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val look = Look.FINAL
        val view = ComposeView(activity)
        view.tag = TAG
        if (!preview) {
            showing = true
            done = true
            handler.removeCallbacks(tick)
        }
        val close = { root.post { root.removeView(view); if (!preview) showing = false } }
        view.setContent { com.winlator.cmod.ui.theme.WinZOverlayTheme { Banner(update, look, preview) { close() } } }
        // Only the banner's own size: the screen around it stays usable.
        root.addView(view, place(activity, look))
        // The main screen handles turning itself (no restart): place the banner again when it turns.
        val relayout = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) root.post { if (view.parent === root) view.layoutParams = place(activity, look) }
        }
        root.addOnLayoutChangeListener(relayout)
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) { root.removeOnLayoutChangeListener(relayout) }
        })
    }

    private fun place(activity: Activity, look: Look): android.widget.FrameLayout.LayoutParams {
        val res = activity.resources
        val dm = res.displayMetrics
        val cfg = res.configuration
        val dp = dm.density
        val tablet = cfg.smallestScreenWidthDp >= 600
        val landscape = cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        var width = (minOf(dm.widthPixels, dm.heightPixels) * look.widthPercent / 100f).toInt()
        if (tablet) width = minOf(width, (460 * dp).toInt())
        val right = tablet || landscape
        val params = android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or (if (right) android.view.Gravity.END else android.view.Gravity.CENTER_HORIZONTAL))
        // On its side the main screen has its tab bar on the right (ui/LandscapeNavRail, 88 dp).
        if (right) params.marginEnd = ((if (landscape) 88 + 10 else 16) * dp).toInt()
        return params
    }

    private sealed class Step {
        object Offer : Step()
        object Downloading : Step()
        class Ready(val apk: java.io.File) : Step()
        object Failed : Step()
    }

    /**
     * The card slides in from [Look.from] and leaves the same way. Swipe it towards that edge to put
     * it away: the finger moves it 1:1, the other way it gives way with resistance; a flick or a
     * third of its size sends it off with a spring at the finger's speed, otherwise it springs back.
     */
    @Composable
    private fun Banner(update: AppUpdater.Update, look: Look, preview: Boolean, onGone: () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val horizontal = look.from == "left" || look.from == "right"
        // The direction (+1 / -1) along the axis towards the edge it came from.
        val toEdge = if (look.from == "top" || look.from == "left") -1f else 1f
        var sizePx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val screenPx = with(density) {
            val m = context.resources.displayMetrics
            (if (horizontal) m.widthPixels else m.heightPixels).toFloat()
        }
        val hidden = { toEdge * (if (sizePx > 0) sizePx + with(density) { (look.marginDp + 48).dp.toPx() } else screenPx) }
        val offset = remember { androidx.compose.animation.core.Animatable(toEdge * screenPx) }
        var step by remember { mutableStateOf<Step>(Step.Offer) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            offset.snapTo(hidden())
            offset.animateTo(0f, androidx.compose.animation.core.spring(0.8f, androidx.compose.animation.core.Spring.StiffnessMediumLow))
        }
        // Gone by itself after 5 s; a touch (a drag, a button) keeps it.
        var touched by remember { mutableStateOf(false) }
        fun leave(velocity: Float = 0f) {
            scope.launch {
                offset.animateTo(hidden(), androidx.compose.animation.core.spring(1f, androidx.compose.animation.core.Spring.StiffnessMediumLow), velocity)
                onGone()
            }
        }
        androidx.compose.runtime.LaunchedEffect(touched, step) {
            if (!touched && step == Step.Offer) {
                kotlinx.coroutines.delay(SHOWN_MS)
                leave()
            }
        }
        fun finished(apk: java.io.File?) {
            step = if (apk != null) Step.Ready(apk) else Step.Failed
            if (apk != null && AppUpdater.canInstall(context)) AppUpdater.install(context, apk)
        }

        val scheme = MaterialTheme.colorScheme
        val t = look.textPercent.coerceIn(60, 200) / 100f
        val edgePad = look.marginDp.coerceIn(0, 300).dp
        val pad = when (look.place) {
            "bottom" -> Modifier.navigationBarsPadding().padding(top = 8.dp, bottom = edgePad)
            "center" -> Modifier.padding(vertical = 8.dp)
            else -> Modifier.statusBarsPadding().padding(top = edgePad, bottom = 8.dp)
        }
        val drag = Modifier.pointerInput(horizontal) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var total = 0f
            val fling = with(density) { 500.dp.toPx() }
            val onEnd: () -> Unit = {
                val v = tracker.calculateVelocity().let { if (horizontal) it.x else it.y }
                if (v * toEdge > fling || offset.value * toEdge > sizePx / 3f) leave(v)
                else { scope.launch { offset.animateTo(0f, androidx.compose.animation.core.spring(0.8f, androidx.compose.animation.core.Spring.StiffnessMedium), v) } }
            }
            val onDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Float) -> Unit = { change, amount ->
                change.consume()
                total += amount
                tracker.addPosition(change.uptimeMillis, if (horizontal) androidx.compose.ui.geometry.Offset(total, 0f) else androidx.compose.ui.geometry.Offset(0f, total))
                // Towards its edge: 1:1. The other way: a fifth.
                scope.launch { offset.snapTo(if (total * toEdge < 0f) total * 0.2f else total) }
                Unit
            }
            val start: (androidx.compose.ui.geometry.Offset) -> Unit = { touched = true; tracker.resetTracking(); total = offset.value; scope.launch { offset.stop() } }
            if (horizontal) detectHorizontalDragGestures(onDragStart = start, onDragEnd = onEnd, onDragCancel = { scope.launch { offset.animateTo(0f) } }) { c, a -> onDrag(c, a) }
            else detectVerticalDragGestures(onDragStart = start, onDragEnd = onEnd, onDragCancel = { scope.launch { offset.animateTo(0f) } }) { c, a -> onDrag(c, a) }
        }
        val shape = RoundedCornerShape(look.cornerDp.coerceIn(0, 40).dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(pad)
                .onSizeChanged { sizePx = if (horizontal) it.width else it.height }
                .graphicsLayer { if (horizontal) translationX = offset.value else translationY = offset.value }
                .then(drag),
            shape = shape,
            // See-through, so what is underneath still shows.
            color = scheme.surfaceContainerHigh.copy(alpha = look.opacityPercent.coerceIn(10, 100) / 100f),
            border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
            // A shadow under a see-through card shows through it and makes it look solid.
            shadowElevation = if (look.opacityPercent >= 100) 8.dp else 0.dp,
        ) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
            // The picture stands beside the title and the line under it, exactly as tall as the two
            // (or [Look.pictureDp] when "by text height" is off); the buttons or the download's
            // progress run below, under the text.
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                val picture = if (look.pictureFull)
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                else Modifier.size(look.pictureDp.coerceIn(24, 200).dp)
                Box(picture.background(scheme.primaryContainer.copy(alpha = 0.9f), RoundedCornerShape((look.cornerDp * 3 / 4).coerceIn(0, 30).dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = scheme.onPrimaryContainer, modifier = Modifier.fillMaxSize(0.5f))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(uiStr(R.string.l_upd_notice_title, update.version), style = MaterialTheme.typography.titleMedium,
                        fontSize = (18 * t).sp, lineHeight = (22 * t).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        when (step) {
                            Step.Offer -> uiStr(R.string.l_upd_notice_text) + String.format(java.util.Locale.US, " · %.1f MB", update.size / 1048576.0)
                            Step.Downloading -> uiStr(R.string.l_upd_downloading)
                            is Step.Ready -> uiStr(R.string.l_upd_ready)
                            Step.Failed -> uiStr(R.string.l_upd_download_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium, fontSize = (15 * t).sp, lineHeight = (19 * t).sp,
                        color = scheme.onSurfaceVariant, maxLines = 2
                    )
                }
            }
                when (val st = step) {
                    Step.Downloading -> DownloadProgress(AppUpdater.DOWNLOAD_KEY, Modifier.padding(top = 10.dp, end = 4.dp, bottom = 4.dp))
                    else -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { leave() },
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant)) {
                            Text(uiStr(R.string.l_upd_later), fontSize = (15 * t).sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        when (st) {
                            Step.Offer, Step.Failed -> Button(onClick = {
                                if (preview) { leave(); return@Button }
                                step = Step.Downloading
                                scope.launch { finished(AppUpdater.startDownload(context, update).await()) }
                            }) { Text(uiStr(R.string.l_upd_update), fontSize = (15 * t).sp) }
                            is Step.Ready -> {
                                if (!AppUpdater.canInstall(context)) {
                                    OutlinedButton(onClick = { AppUpdater.openInstallPermission(context) }) { Text(uiStr(R.string.l_upd_allow)) }
                                    Spacer(Modifier.width(6.dp))
                                }
                                Button(onClick = { AppUpdater.install(context, st.apk) }) { Text(uiStr(R.string.l_upd_install), fontSize = (15 * t).sp) }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
