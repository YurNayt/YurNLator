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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.contents.DriverSource
import com.winlator.cmod.contents.DriverSources

/** Settings row: "Driver authors · 7 of 7 on", opens [DriverSourcesDialog]. */
@Composable
fun DriverSourcesPreferenceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val rev = DriverSources.revision.intValue
    val (on, total) = remember(rev) {
        val all = DriverSources.all(context)
        all.count { DriverSources.isEnabled(context, it) } to all.size
    }

    Surface(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.People, null, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Driver authors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text("$on of $total shown in driver downloads", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (open) DriverSourcesDialog(onDismiss = { open = false })
}

/**
 * Pick which driver authors appear in driver downloads, add your own
 * (GitHub owner/repo or a JSON link) and remove added ones.
 */
@Composable
fun DriverSourcesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rev = DriverSources.revision.intValue
    val sources = remember(rev) { DriverSources.all(context) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Driver authors") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Downloads from switched-off authors are hidden. Installed drivers always stay available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.padding(top = 6.dp))
                sources.forEachIndexed { index, source ->
                    if (index > 0) HorizontalDivider()
                    SourceRow(source, DriverSources.isEnabled(context, source))
                }
                HorizontalDivider()
                TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add author")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )

    if (adding) AddSourceDialog(onDismiss = { adding = false })
}

@Composable
private fun SourceRow(source: DriverSource, enabled: Boolean) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                source.note.ifBlank { source.feeds.joinToString(", ") { it.describe() } },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!source.builtIn) {
            IconButton(onClick = { DriverSources.removeCustom(context, source) }) {
                Icon(Icons.Outlined.DeleteOutline, "Remove ${source.name}")
            }
        }
        Switch(checked = enabled, onCheckedChange = { DriverSources.setEnabled(context, source, it) })
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add driver author") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = link, onValueChange = { link = it; error = null },
                    label = { Text("GitHub owner/repo or JSON link") },
                    placeholder = { Text("K11MCH1/AdrenoToolsDrivers") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Every .zip in the repository's GitHub releases is listed as a driver. " +
                        "A JSON link must be a list of {\"verName\", \"remoteUrl\"} entries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val feed = DriverSources.parseFeed(link)
                if (feed == null) {
                    error = "Use owner/repo, a github.com link or a .json link"
                } else {
                    val result = DriverSources.addCustom(context, name, feed)
                    if (result == null) onDismiss() else error = result
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
