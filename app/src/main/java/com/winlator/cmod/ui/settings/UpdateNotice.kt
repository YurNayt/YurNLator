package com.winlator.cmod.ui.settings

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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

    private fun show(activity: Activity, update: AppUpdater.Update) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = ComposeView(activity)
        showing = true
        done = true
        handler.removeCallbacks(tick)
        val close = { root.post { root.removeView(view); showing = false } }
        view.setContent { WinZTheme { Banner(update) { close() } } }
        // Only the banner's own height at the top: the screen below stays usable.
        root.addView(view, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
    }

    private sealed class Step {
        object Offer : Step()
        object Downloading : Step()
        class Ready(val apk: java.io.File) : Step()
        object Failed : Step()
    }

    /**
     * A card that slides down from the top edge over whatever is on screen. Swipe it up (where it
     * came from) to put it away: the finger moves it 1:1, downwards it gives way with resistance;
     * a flick or a third of its height sends it off with a spring at the finger's speed, otherwise
     * it springs back.
     */
    @Composable
    private fun Banner(update: AppUpdater.Update, onGone: () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var heightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val hiddenY = { -(heightPx + with(density) { 48.dp.toPx() }) }
        val y = remember { androidx.compose.animation.core.Animatable(with(density) { -200.dp.toPx() }) }
        var step by remember { mutableStateOf<Step>(Step.Offer) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            y.animateTo(0f, androidx.compose.animation.core.spring(0.8f, androidx.compose.animation.core.Spring.StiffnessMediumLow))
        }
        fun leave(velocity: Float = 0f) {
            scope.launch {
                y.animateTo(hiddenY(), androidx.compose.animation.core.spring(1f, androidx.compose.animation.core.Spring.StiffnessMediumLow), velocity)
                onGone()
            }
        }
        fun finished(apk: java.io.File?) {
            step = if (apk != null) Step.Ready(apk) else Step.Failed
            if (apk != null && AppUpdater.canInstall(context)) AppUpdater.install(context, apk)
        }

        val scheme = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                // A little below the top edge, clear of the screen's own title.
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 8.dp)
                .onSizeChanged { heightPx = it.height }
                .graphicsLayer { translationY = y.value }
                .pointerInput(Unit) {
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking(); total = y.value; scope.launch { y.stop() } },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            if (v < -with(density) { 500.dp.toPx() } || y.value < -heightPx / 3f) leave(v)
                            else scope.launch { y.animateTo(0f, androidx.compose.animation.core.spring(0.8f, androidx.compose.animation.core.Spring.StiffnessMedium), v) }
                        },
                        onDragCancel = { scope.launch { y.animateTo(0f) } }
                    ) { change, amount ->
                        change.consume()
                        total += amount
                        tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, total))
                        scope.launch { y.snapTo(if (total > 0f) total * 0.2f else total) }
                    }
                },
            shape = RoundedCornerShape(16.dp),
            // See-through, so what is underneath still shows.
            color = scheme.surfaceContainerHigh.copy(alpha = 0.78f),
            border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            // The picture runs the card's full height; title, state and the buttons or the
            // download's progress sit to its right.
            Row(Modifier.height(IntrinsicSize.Min).padding(10.dp)) {
                Box(
                    Modifier.fillMaxHeight().defaultMinSize(minHeight = 88.dp).aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .background(scheme.primaryContainer.copy(alpha = 0.9f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = scheme.onPrimaryContainer, modifier = Modifier.fillMaxSize(0.5f))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(uiStr(R.string.l_upd_notice_title, update.version), style = MaterialTheme.typography.titleMedium,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        when (step) {
                            Step.Offer -> uiStr(R.string.l_upd_notice_text) + String.format(java.util.Locale.US, " · %.1f MB", update.size / 1048576.0)
                            Step.Downloading -> uiStr(R.string.l_upd_downloading)
                            is Step.Ready -> uiStr(R.string.l_upd_ready)
                            Step.Failed -> uiStr(R.string.l_upd_download_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, color = scheme.onSurfaceVariant, maxLines = 2
                    )
                    when (val st = step) {
                        Step.Downloading -> DownloadProgress(AppUpdater.DOWNLOAD_KEY, Modifier.padding(top = 10.dp, end = 4.dp, bottom = 4.dp))
                        else -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { leave() },
                                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant)) {
                                Text(uiStr(R.string.l_upd_later), fontSize = 15.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            when (st) {
                                Step.Offer, Step.Failed -> Button(onClick = {
                                    step = Step.Downloading
                                    scope.launch { finished(AppUpdater.startDownload(context, update).await()) }
                                }) { Text(uiStr(R.string.l_upd_update), fontSize = 15.sp) }
                                is Step.Ready -> {
                                    if (!AppUpdater.canInstall(context)) {
                                        OutlinedButton(onClick = { AppUpdater.openInstallPermission(context) }) { Text(uiStr(R.string.l_upd_allow)) }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Button(onClick = { AppUpdater.install(context, st.apk) }) { Text(uiStr(R.string.l_upd_install), fontSize = 15.sp) }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}
