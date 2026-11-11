package com.winlator.cmod.ui.components

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.DownloadTracker
import java.util.Locale

private fun mb(bytes: Long): String {
    val m = bytes / (1024.0 * 1024.0)
    return if (m >= 100) String.format(Locale.US, "%.0f", m) else String.format(Locale.US, "%.1f", m)
}

/** "45 % · 120 / 260 MB" with a bar, pause / resume and cancel. Nothing when [key] isn't downloading. */
@Composable
fun DownloadProgress(key: String?, modifier: Modifier = Modifier) {
    val s = key?.let { DownloadTracker.states[it] } ?: return
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val pct = s.percent
                val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (s.installing || pct < 0) LinearProgressIndicator(modifier = bar)
                else LinearProgressIndicator(progress = { pct / 100f }, modifier = bar)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        s.installing -> uiStr(R.string.l_dl_installing)
                        s.paused -> uiStr(R.string.l_dl_paused) + (if (pct >= 0) "$pct % · " else "") + sizeText(s)
                        pct >= 0 -> "$pct % · " + sizeText(s)
                        s.done > 0 -> uiStr(R.string.l_dl_downloading_1_s_mb, mb(s.done))
                        else -> uiStr(R.string.l_dl_connecting)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
            }
            if (!s.installing) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { if (s.paused) DownloadTracker.resume(key) else DownloadTracker.pause(key) }, modifier = Modifier.size(36.dp)) {
                    Icon(if (s.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        if (s.paused) uiStr(R.string.l_qm_resume) else uiStr(R.string.l_qm_pause), tint = scheme.primary)
                }
                IconButton(onClick = { DownloadTracker.cancel(key) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, "Cancel", tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun sizeText(s: DownloadTracker.State) =
    if (s.total > 0) "${mb(s.done)} / ${mb(s.total)} MB" else "${mb(s.done)} MB"
