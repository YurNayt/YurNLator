package com.winlator.cmod.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.contents.DriverSource
import com.winlator.cmod.contents.DriverSources

/** "+ Add" in the author selector of the GPU driver downloads. */
@Composable
fun AddDriverSourceDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit = {}) {
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
                    placeholder = { Text("owner/repo") },
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
                    if (result == null) {
                        onAdded(name.trim())
                        onDismiss()
                    } else error = result
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Long press on a custom author in the selector. Built-in authors can't be removed. */
@Composable
fun RemoveDriverSourceDialog(source: DriverSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${source.name}?") },
        text = { Text("Its drivers disappear from downloads. Drivers you already installed stay.") },
        confirmButton = {
            TextButton(onClick = { DriverSources.removeCustom(context, source); onDismiss() }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
