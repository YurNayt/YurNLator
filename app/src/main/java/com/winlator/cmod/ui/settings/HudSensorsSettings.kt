package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.bench.RootMode
import com.winlator.cmod.core.HudSensorAccess
import com.winlator.cmod.core.HudSensorFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Performance HUD: precise sensors through root or ADB (Shizuku). Both HUDs then show
 * the real CPU load, CPU / GPU clocks and temperatures, which Android hides from apps.
 */
@Composable
fun HudSensorsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(HudSensorAccess.mode(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun test() {
        busy = true
        status = uiStr(R.string.l_hs_reading)
        scope.launch {
            val s = withContext(Dispatchers.IO) { HudSensorFeed.probe(context) }
            status = if (s == null) uiStr(R.string.l_hs_no_access_the_shell_didn_t)
                     else "CPU ${s.cpuText()}\nGPU ${s.gpuText()}"
            busy = false
        }
    }

    fun choose(want: String) {
        if (busy || want == mode) return
        when (want) {
            HudSensorAccess.OFF -> { mode = want; HudSensorAccess.setMode(context, want); status = "" }
            HudSensorAccess.ROOT -> {
                busy = true; status = uiStr(R.string.l_hs_asking_for_root)
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { RootMode.available() }
                    busy = false
                    if (ok) { mode = want; HudSensorAccess.setMode(context, want); test() }
                    else status = uiStr(R.string.l_hs_root_wasn_t_granted_no_su)
                }
            }
            HudSensorAccess.ADB -> when (HudSensorAccess.shizukuState()) {
                0 -> status = uiStr(R.string.l_hs_shizuku_isn_t_running_install_shizuku)
                1 -> {
                    status = uiStr(R.string.l_hs_allow_yurnlator_in_shizuku_s_dialog)
                    HudSensorAccess.requestShizuku { granted ->
                        scope.launch {
                            if (granted) { mode = want; HudSensorAccess.setMode(context, want); test() }
                            else status = uiStr(R.string.l_hs_shizuku_permission_was_denied)
                        }
                    }
                }
                else -> { mode = want; HudSensorAccess.setMode(context, want); test() }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiStr(R.string.l_hs_precise_sensors), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                uiStr(R.string.l_hs_android_hides_the_real_cpu_load),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HudSensorAccess.OFF to uiStr(R.string.l_qm_off), HudSensorAccess.ROOT to "Root", HudSensorAccess.ADB to "ADB (Shizuku)").forEach { (key, label) ->
                    FilterChip(selected = mode == key, onClick = { choose(key) }, label = { Text(label) }, enabled = !busy)
                }
            }
            if (mode != HudSensorAccess.OFF) {
                OutlinedButton(onClick = { test() }, enabled = !busy) { Text(uiStr(R.string.l_hs_test)) }
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            var dual by remember { mutableStateOf(com.winlator.cmod.widget.WinlatorHUD.dualCell(context)) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiStr(R.string.l_hud_dual_cell), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(uiStr(R.string.l_hud_dual_cell_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = dual, onCheckedChange = {
                    dual = it; com.winlator.cmod.widget.WinlatorHUD.setDualCell(context, it)
                })
            }
        }
    }
}
