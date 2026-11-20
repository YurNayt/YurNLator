package com.winlator.cmod.ui.shortcut

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.uiStr
import java.io.File
import java.util.Locale

private val RUNNABLE = setOf("exe", "bat", "lnk", "msi")

/**
 * Picks the program a library entry starts: the container's drives (C: and the mapped ones), their
 * folders and the programs in them (.exe, .bat, .lnk, .msi). Opens in the current program's folder.
 */
@Composable
fun ExePickerDialog(container: Container, start: File?, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val drives = remember(container) { ShortcutExe.drives(container) }
    val startDrive = remember(start) {
        start?.let { f -> drives.firstOrNull { f.absolutePath.startsWith(it.second.absolutePath.trimEnd('/') + "/") } }
            ?: drives.first()
    }
    var drive by remember { mutableStateOf(startDrive) }
    var dir by remember { mutableStateOf(start?.parentFile?.takeIf { it.isDirectory && start.absolutePath.startsWith(startDrive.second.absolutePath) } ?: startDrive.second) }

    val root = drive.second.absolutePath.trimEnd('/')
    val atRoot = dir.absolutePath.trimEnd('/') == root
    fun up() { dir.parentFile?.let { if (it.absolutePath.length >= root.length) dir = it } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (atRoot) onDismiss() else up() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, null) }
                    Column(Modifier.weight(1f)) {
                        Text(uiStr(R.string.l_sc_pick_exe), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        val rel = dir.absolutePath.removePrefix(root).trimStart('/').replace('/', '\\')
                        Text(drive.first + "\\" + rel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (drives.size > 1) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (d in drives) FilterChip(selected = d == drive, onClick = { drive = d; dir = d.second },
                        label = { Text(d.first) }, leadingIcon = { Icon(Icons.Outlined.Storage, null, Modifier.size(18.dp)) })
                }
                val entries = remember(dir) {
                    val all = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
                    val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
                    val files = all.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in RUNNABLE }
                        .sortedBy { it.name.lowercase(Locale.ROOT) }
                    dirs + files
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!atRoot) item {
                        Entry(Icons.Outlined.Folder, "..", null, current = false) { up() }
                    }
                    items(entries, key = { it.absolutePath }) { f ->
                        if (f.isDirectory) Entry(Icons.Outlined.Folder, f.name, null, current = false) { dir = f }
                        else Entry(Icons.Outlined.SportsEsports, f.name, String.format(Locale.US, "%.1f MB", f.length() / 1048576.0),
                            current = f.absolutePath == start?.absolutePath) { onPick(f) }
                    }
                    if (entries.isEmpty()) item {
                        Text(uiStr(R.string.l_sc_pick_exe_empty), Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { Spacer(Modifier.size(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Entry(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String?, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp), color = scheme.surface,
        border = BorderStroke(1.dp, if (current) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(scheme.surfaceVariant, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(19.dp), tint = if (detail != null) scheme.primary else scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (detail != null) FontWeight.SemiBold else FontWeight.Normal)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
