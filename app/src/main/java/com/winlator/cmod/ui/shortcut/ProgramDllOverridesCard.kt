package com.winlator.cmod.ui.shortcut

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.session.LaunchConfig
import com.winlator.cmod.session.ProgramDllOverrides
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.uiStr

/** Expert settings, folded away: DLL choices for single programs of the game (ProgramDllOverrides). */
@Composable
internal fun ProgramDllOverridesCard(shortcut: Shortcut) {
    var open by remember { mutableStateOf(ProgramDllOverrides.read(shortcut).isNotEmpty()) }
    var list by remember { mutableStateOf(ProgramDllOverrides.read(shortcut)) }
    val modes = listOf("builtin" to uiStr(R.string.l_dov_builtin), "native,builtin" to uiStr(R.string.l_dov_native), "" to uiStr(R.string.l_dov_disabled))
    SettingsCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.l_dov_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(uiStr(R.string.l_dov_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
        }
        if (!open) return@SettingsCard
        list.forEach { e ->
            SettingsDivider()
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${e.exe} · ${e.dll}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(modes.firstOrNull { it.first == e.mode }?.second ?: e.mode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { list = list - e; ProgramDllOverrides.save(shortcut, list) }) { Icon(Icons.Outlined.DeleteOutline, null) }
            }
        }
        SettingsDivider()
        var exe by remember { mutableStateOf(LaunchConfig.exeFile(shortcut.path)?.name.orEmpty()) }
        var dll by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf("builtin") }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(exe, { exe = it.trim() }, label = { Text(uiStr(R.string.l_dov_exe)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(dll, { dll = it.trim() }, label = { Text(uiStr(R.string.l_dov_dll)) }, singleLine = true,
                placeholder = { Text("d3d9, dxgi, xinput1_3…") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            // Scrolls sideways: three chips don't fit a phone's width.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (v, label) -> FilterChip(selected = mode == v, onClick = { mode = v }, label = { Text(label, maxLines = 1, softWrap = false) }) }
            }
            TextButton(enabled = exe.isNotBlank() && dll.isNotBlank(), onClick = {
                val e = ProgramDllOverrides.Entry(exe, dll.removeSuffix(".dll"), mode)
                list = list.filterNot { it.exe.equals(e.exe, true) && it.dll.equals(e.dll, true) } + e
                ProgramDllOverrides.save(shortcut, list)
                dll = ""
            }) { Text(uiStr(R.string.l_dov_add)) }
        }
    }
}
