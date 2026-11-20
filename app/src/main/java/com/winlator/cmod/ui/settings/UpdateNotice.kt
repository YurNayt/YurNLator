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
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.core.AppUpdater
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr

/**
 * "A new version is out": on start the app asks GitHub (at most every 6 hours) whether its channel
 * (stable, or test builds when chosen in Settings → Update) has something newer. If so a card with
 * the version and its notes offers Update (downloads and installs right there) or Later (that
 * version stays quiet for a day).
 */
object UpdateNotice {
    private const val PREF_LAST = "yurn_update_notice_last"
    private const val PREF_SNOOZE_VERSION = "yurn_update_notice_snooze_version"
    private const val PREF_SNOOZE_UNTIL = "yurn_update_notice_snooze_until"
    private const val EVERY_MS = 6L * 3600 * 1000
    private const val SNOOZE_MS = 24L * 3600 * 1000
    @Volatile private var checking = false

    @JvmStatic
    fun maybeShow(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val now = System.currentTimeMillis()
        if (checking || now - prefs.getLong(PREF_LAST, 0L) < EVERY_MS) return
        if (AppUpdater.runningDownload() != null) return
        checking = true
        val app = activity.applicationContext
        Thread({
            try {
                val result = AppUpdater.check(app)
                if (result is AppUpdater.Check.Failed) return@Thread
                prefs.edit().putLong(PREF_LAST, System.currentTimeMillis()).apply()
                val update = (result as? AppUpdater.Check.Available)?.update ?: return@Thread
                if (prefs.getString(PREF_SNOOZE_VERSION, null) == update.version &&
                    System.currentTimeMillis() < prefs.getLong(PREF_SNOOZE_UNTIL, 0L)) return@Thread
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) show(activity, update) }
            } finally {
                checking = false
            }
        }, "UpdateNotice").apply { isDaemon = true }.start()
    }

    private fun show(activity: Activity, update: AppUpdater.Update) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = ComposeView(activity)
        val close = { root.post { root.removeView(view) } }
        view.setContent {
            WinZTheme {
                Notice(update, onLater = {
                    PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putString(PREF_SNOOZE_VERSION, update.version)
                        .putLong(PREF_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
                        .apply()
                    close()
                }, onClose = { close() })
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
