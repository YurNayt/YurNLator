package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import com.winlator.cmod.ui.components.GlossaryHelpIcon
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.contents.WrapperManager
import com.winlator.cmod.ui.components.ALL_COMPONENT_TYPES
import com.winlator.cmod.ui.components.ComponentBrowser
import com.winlator.cmod.ui.components.PickerAction
import com.winlator.cmod.ui.components.PickerOption
import com.winlator.cmod.ui.components.PickerSheet
import com.winlator.cmod.ui.components.WRAPPER
import com.winlator.cmod.ui.components.WrapperItems
import com.winlator.cmod.ui.components.rememberWrapperItems

/**
 * "Vulkan Wrapper" row in container / game settings. Lists installed wrappers
 * (built-in first), installs one from a .tzst file, or opens the download screen
 * on Wrapper with the author selector. Writes "wrapper=<id>" via [onSelected].
 */
@Composable
internal fun SettingWrapperChoice(selected: String, onSelected: (String) -> Unit) {
    val context = LocalContext.current
    val rev = WrapperManager.revision.intValue
    val installed = remember(rev) { WrapperManager.installed(context) }
    val current = selected.ifBlank { WrapperManager.BUILTIN_ID }
    val currentItem = installed.firstOrNull { it.id == current }
    var expanded by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf(false) }
    // Picking a freshly installed wrapper right away is what people expect.
    val wrappers = rememberWrapperItems(onInstalled = onSelected)

    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(settingFieldLabel("Vulkan Wrapper"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        currentItem?.name ?: uiStr(R.string.l_wrp_built_in_wrapper_selected_one_is),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = listOfNotNull(currentItem?.author, currentItem?.let { WrapperManager.targetsLabel(it.targets) })
                    if (sub.isNotEmpty()) {
                        Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GlossaryHelpIcon("Vulkan Wrapper")
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
    }

    if (expanded) {
        PickerSheet(
            title = uiStr(R.string.l_wrp_vulkan_wrapper),
            subtitle = uiStr(R.string.l_wrp_installed_wrappers_or_get_a_new),
            icon = Icons.Outlined.Hub,
            actions = listOf(
                PickerAction(Icons.Outlined.FileOpen, uiStr(R.string.l_wrp_install_from_file), uiStr(R.string.l_wrp_wrapper_tzst_from_your_storage_works)) {
                    expanded = false
                    wrappers.importFile()
                },
                PickerAction(Icons.Outlined.CloudDownload, uiStr(R.string.l_wrp_download_wrappers), uiStr(R.string.l_wrp_pick_an_author_then_a_wrapper)) {
                    expanded = false
                    downloads = true
                }
            ),
            options = installed.map { w ->
                PickerOption(w.id, w.name, listOfNotNull(w.author, WrapperManager.targetsLabel(w.targets)).joinToString(" · ").ifBlank { null })
            },
            selectedId = current,
            onSelect = { expanded = false; onSelected(it) },
            onDismiss = { expanded = false }
        )
    }

    if (downloads) {
        Dialog(onDismissRequest = { downloads = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                KeepDialogFullscreen()
                ComponentBrowser(
                    items = wrappers.items,
                    loading = false,
                    types = ALL_COMPONENT_TYPES.filter { it.key == WRAPPER },
                    initialType = WRAPPER,
                    onClose = { downloads = false },
                    onInstall = wrappers.install,
                    onRemove = { item ->
                        wrappers.remove(item)
                        // Removing the wrapper this container / game uses: back to the built-in one.
                        if (WrapperItems.wrapperId(item) == current) onSelected(WrapperManager.BUILTIN_ID)
                    },
                    onLocalPackage = { wrappers.importFile() },
                    busyIds = wrappers.busyIds,
                    busyLabel = "Downloading",
                    busyProgress = wrappers.progress
                )
            }
        }
    }
}
