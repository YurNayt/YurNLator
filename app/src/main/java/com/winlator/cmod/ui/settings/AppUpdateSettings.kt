package com.winlator.cmod.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.core.AppUpdater
import com.winlator.cmod.ui.components.DownloadProgress
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Settings > Update: checks the GitHub releases (stable by default, test builds on request) and
 * installs the same variant as the installed app (standard, AnTuTu…), so it updates in place.
 */
@Composable
fun AppUpdatePreferenceCard() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val version = remember { AppUpdater.installedVersion(context) }
    Surface(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.SystemUpdate, null, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.l_upd_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(uiStr(R.string.l_upd_installed, version, AppUpdater.flavor), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) AppUpdateSheet { open = false }
}

private sealed class UiState {
    object Idle : UiState()
    object Checking : UiState()
    class Result(val check: AppUpdater.Check) : UiState()
    object Downloading : UiState()
    class Ready(val apk: File) : UiState()
    object DownloadFailed : UiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUpdateSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dev by remember { mutableStateOf(AppUpdater.devChannel(context)) }
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }

    fun check() {
        state = UiState.Checking
        scope.launch { state = UiState.Result(withContext(Dispatchers.IO) { AppUpdater.check(context) }) }
    }
    LaunchedEffect(dev) { check() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        KeepDialogFullscreen()
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(uiStr(R.string.l_upd_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(uiStr(R.string.l_upd_installed, AppUpdater.installedVersion(context), AppUpdater.flavor),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !dev, onClick = { if (dev) { dev = false; AppUpdater.setDevChannel(context, false) } },
                    label = { Text(uiStr(R.string.l_upd_stable)) })
                FilterChip(selected = dev, onClick = { if (!dev) { dev = true; AppUpdater.setDevChannel(context, true) } },
                    label = { Text(uiStr(R.string.l_upd_dev)) })
            }
            Text(uiStr(if (dev) R.string.l_upd_dev_hint else R.string.l_upd_stable_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dev) Text(uiStr(R.string.l_upd_dev_variants), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

            when (val s = state) {
                UiState.Idle, UiState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(uiStr(R.string.l_upd_checking))
                }
                is UiState.Result -> when (val c = s.check) {
                    is AppUpdater.Check.UpToDate -> {
                        Text(uiStr(R.string.l_upd_up_to_date), fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { check() }) { Text(uiStr(R.string.l_upd_check_again)) }
                    }
                    is AppUpdater.Check.NoRelease -> {
                        Text(uiStr(R.string.l_upd_no_release), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { check() }) { Text(uiStr(R.string.l_upd_check_again)) }
                    }
                    is AppUpdater.Check.Failed -> {
                        Text(uiStr(R.string.l_upd_failed, c.reason), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { check() }) { Text(uiStr(R.string.l_upd_check_again)) }
                    }
                    is AppUpdater.Check.Available -> {
                        val u = c.update
                        Text(uiStr(R.string.l_upd_available, u.version), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(String.format(Locale.US, "%s · %.1f MB", u.fileName, u.size / 1048576.0),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (u.notes.isNotBlank()) Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        ) {
                            Text(u.notes, Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            state = UiState.Downloading
                            scope.launch {
                                val apk = withContext(Dispatchers.IO) { AppUpdater.download(context, u) }
                                state = if (apk != null) UiState.Ready(apk) else UiState.DownloadFailed
                                if (apk != null && AppUpdater.canInstall(context)) AppUpdater.install(context, apk)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_upd_download_install)) }
                    }
                }
                UiState.Downloading -> {
                    Text(uiStr(R.string.l_upd_downloading), fontWeight = FontWeight.SemiBold)
                    DownloadProgress(AppUpdater.DOWNLOAD_KEY)
                }
                UiState.DownloadFailed -> {
                    Text(uiStr(R.string.l_upd_download_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { check() }) { Text(uiStr(R.string.l_upd_check_again)) }
                }
                is UiState.Ready -> {
                    Text(uiStr(R.string.l_upd_ready), fontWeight = FontWeight.SemiBold)
                    if (!AppUpdater.canInstall(context)) {
                        Text(uiStr(R.string.l_upd_need_permission), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { AppUpdater.openInstallPermission(context) }) { Text(uiStr(R.string.l_upd_allow)) }
                    }
                    Button(onClick = { AppUpdater.install(context, s.apk) }, modifier = Modifier.fillMaxWidth()) {
                        Text(uiStr(R.string.l_upd_install))
                    }
                }
            }
        }
    }
}
