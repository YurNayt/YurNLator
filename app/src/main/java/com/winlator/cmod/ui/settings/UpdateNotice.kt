package com.winlator.cmod.ui.settings

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
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
        view.setContent {
            WinZTheme {
                Notice(update, onLater = { close() }, onClose = { close() })
            }
        }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @Composable
    private fun Notice(update: AppUpdater.Update, onLater: () -> Unit, onClose: () -> Unit) {
        var installing by remember { mutableStateOf(false) }
        if (installing) {
            AppUpdateSheet(start = update) { onClose() }
            return
        }
        AlertDialog(
            onDismissRequest = onLater,
            icon = { Icon(Icons.Outlined.SystemUpdate, null) },
            title = { Text(uiStr(R.string.l_upd_notice_title, update.version)) },
            text = {
                Column {
                    Text(uiStr(R.string.l_upd_notice_text), style = MaterialTheme.typography.bodyMedium)
                    if (update.notes.isNotBlank()) Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(max = 260.dp)
                    ) {
                        Text(update.notes, Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { Button(onClick = { installing = true }) { Text(uiStr(R.string.l_upd_update)) } },
            dismissButton = { TextButton(onClick = onLater) { Text(uiStr(R.string.l_upd_later)) } }
        )
    }
}
