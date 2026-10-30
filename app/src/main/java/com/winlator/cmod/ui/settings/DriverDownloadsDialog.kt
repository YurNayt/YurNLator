package com.winlator.cmod.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.ui.components.ALL_COMPONENT_TYPES
import com.winlator.cmod.ui.components.BrowserItem
import com.winlator.cmod.ui.components.ComponentBrowser
import com.winlator.cmod.ui.components.GPU_DRIVER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen GPU driver downloads from the container / shortcut editor: the same
 * browser as Settings > Components, opened on "GPU driver" with the author selector.
 * A finished download is installed and selected through [onInstall] (the caller
 * reloads its list), so the dialog can stay open for more.
 */
@Composable
internal fun DriverDownloadsDialog(
    options: List<DriverOption>,
    installing: Set<String>,
    onInstall: (DriverOption) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpuType = remember { ALL_COMPONENT_TYPES.filter { it.key == GPU_DRIVER } }

    // Installed drivers by id; downloads by their URL (unique across authors).
    val items = remember(options) {
        options.map { o ->
            BrowserItem(
                id = o.remoteUrl ?: o.id,
                type = GPU_DRIVER,
                name = o.label,
                author = o.author,
                installed = o.installed,
                removable = false // drivers are removed in Settings > Components, not from an editor
            )
        }
    }
    val busyIds = remember(installing) {
        installing.filter { it.startsWith("driver:") }.map { it.removePrefix("driver:") }.toSet()
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { AdrenotoolsManager(context).installDriver(uri) }.getOrDefault("")
            }
            if (id.isBlank()) Toast.makeText(context, "Not a driver archive (.zip with meta.json)", Toast.LENGTH_LONG).show()
            else onInstall(DriverOption(id, id, true))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ComponentBrowser(
                items = items,
                loading = false,
                types = gpuType,
                initialType = GPU_DRIVER,
                onClose = onDismiss,
                onInstall = { item -> options.firstOrNull { (it.remoteUrl ?: it.id) == item.id }?.let(onInstall) },
                onRemove = {},
                onLocalPackage = {
                    fileLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
                busyIds = busyIds,
                busyLabel = "Downloading"
            )
        }
    }
}
