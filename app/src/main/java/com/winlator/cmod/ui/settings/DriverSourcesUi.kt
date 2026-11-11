package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
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
        title = { Text(uiStr(R.string.l_ds_add_driver_author)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text(uiStr(R.string.l_lbl_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = link, onValueChange = { link = it; error = null },
                    label = { Text(uiStr(R.string.l_ds_github_owner_repo_or_json_link)) },
                    placeholder = { Text("owner/repo") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    uiStr(R.string.l_ds_every_zip_in_the_repository_s),
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
                    error = uiStr(R.string.l_ds_use_owner_repo_a_github_com)
                } else {
                    val result = DriverSources.addCustom(context, name, feed)
                    if (result == null) {
                        onAdded(name.trim())
                        onDismiss()
                    } else error = result
                }
            }) { Text(uiStr(R.string.l_lbl_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiStr(R.string.l_qm_cancel)) } }
    )
}

/** Long press on a custom author in the selector. Built-in authors can't be removed. */
@Composable
fun RemoveDriverSourceDialog(source: DriverSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiStr(R.string.l_ds_remove_1_s, source.name)) },
        text = { Text(uiStr(R.string.l_ds_its_drivers_disappear_from_downloads_drivers)) },
        confirmButton = {
            TextButton(onClick = { DriverSources.removeCustom(context, source); onDismiss() }) { Text(uiStr(R.string.l_ctr_remove)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiStr(R.string.l_qm_cancel)) } }
    )
}
