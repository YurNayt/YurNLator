package com.winlator.cmod.ui.settings

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
        status = "Reading…"
        scope.launch {
            val s = withContext(Dispatchers.IO) { HudSensorFeed.probe(context) }
            status = if (s == null) "No access: the shell didn't start."
                     else "CPU ${s.cpuText()}\nGPU ${s.gpuText()}"
            busy = false
        }
    }

    fun choose(want: String) {
        if (busy || want == mode) return
        when (want) {
            HudSensorAccess.OFF -> { mode = want; HudSensorAccess.setMode(context, want); status = "" }
            HudSensorAccess.ROOT -> {
                busy = true; status = "Asking for root…"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { RootMode.available() }
                    busy = false
                    if (ok) { mode = want; HudSensorAccess.setMode(context, want); test() }
                    else status = "Root wasn't granted (no su, or the root manager refused)."
                }
            }
            HudSensorAccess.ADB -> when (HudSensorAccess.shizukuState()) {
                0 -> status = "Shizuku isn't running. Install Shizuku, start it (wireless debugging or adb), then pick ADB again."
                1 -> {
                    status = "Allow YurNLator in Shizuku's dialog…"
                    HudSensorAccess.requestShizuku { granted ->
                        scope.launch {
                            if (granted) { mode = want; HudSensorAccess.setMode(context, want); test() }
                            else status = "Shizuku permission was denied."
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
            Text("Precise sensors", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "Android hides the real CPU load, GPU load and clocks, and most temperatures from apps. " +
                    "With root or ADB rights (through Shizuku) both HUDs and MangoHud show them. Applies from the next game start.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HudSensorAccess.OFF to "Off", HudSensorAccess.ROOT to "Root", HudSensorAccess.ADB to "ADB (Shizuku)").forEach { (key, label) ->
                    FilterChip(selected = mode == key, onClick = { choose(key) }, label = { Text(label) }, enabled = !busy)
                }
            }
            if (mode != HudSensorAccess.OFF) {
                OutlinedButton(onClick = { test() }, enabled = !busy) { Text("Test") }
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
