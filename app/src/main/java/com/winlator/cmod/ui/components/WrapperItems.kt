package com.winlator.cmod.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.contents.WrapperManager
import com.winlator.cmod.core.GpuProfile
import com.winlator.cmod.core.GpuProfileManager
import com.winlator.cmod.core.LocalInstalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browser items and actions for Vulkan wrappers (installed + catalog). */
class WrapperItems internal constructor(
    val items: List<BrowserItem>,
    val busyIds: Set<String>,
    val progress: Int,
    val install: (BrowserItem) -> Unit,
    val remove: (BrowserItem) -> Unit,
    val importFile: () -> Unit
) {
    fun owns(item: BrowserItem) = item.id.startsWith(PREFIX)

    companion object {
        const val PREFIX = "wrapper:"
        fun wrapperId(item: BrowserItem) = item.id.removePrefix(PREFIX)
    }
}

/**
 * Installed wrappers first (built-in included), then catalog entries not installed yet,
 * each under its author (Bannerlator, WinlatorMali, WinNative, GameNative, Ludashi, Pipetto).
 * The saved catalog shows instantly and offline; a fresh copy replaces it when online.
 * [onInstalled] fires with the wrapper id after a successful download or file install.
 */
@Composable
fun rememberWrapperItems(onInstalled: (String) -> Unit = {}): WrapperItems {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rev = WrapperManager.revision.intValue
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(-1) }

    val installed = remember(rev) { WrapperManager.installed(context) }
    // Mali / Exynos-only wrappers are offered on the Mali and Universal profiles, not on Adreno.
    val profile = remember { GpuProfileManager.current(context) }
    fun fitsProfile(targets: List<String>) = targets.isEmpty() || profile != GpuProfile.ADRENO
    val catalog by produceState(emptyList<WrapperManager.CatalogEntry>(), rev) {
        value = withContext(Dispatchers.IO) { WrapperManager.catalog(context, allowNetwork = false) }
        value = withContext(Dispatchers.IO) { WrapperManager.catalog(context, allowNetwork = true) }
    }

    val items = remember(installed, catalog) {
        val catalogIds = catalog.map { it.id }.toSet()
        fun installedItem(w: WrapperManager.Installed) = BrowserItem(
            id = WrapperItems.PREFIX + w.id,
            type = WrapperManager.TYPE,
            name = w.name,
            author = w.author,
            installed = true,
            removable = w.id != WrapperManager.BUILTIN_ID,
            localKey = LocalInstalls.key(WrapperManager.TYPE, w.id),
            details = listOfNotNull(
                if (w.id == WrapperManager.BUILTIN_ID) "Comes with the app" else null,
                WrapperManager.targetsLabel(w.targets)
            )
        )
        val byId = installed.associateBy { it.id }
        // Built-in and file wrappers first, then the catalog in its own order, where an
        // installed wrapper keeps its place (shown as installed, with Delete).
        installed.filter { it.id !in catalogIds }.map(::installedItem) +
            catalog.filter { it.id in byId || fitsProfile(it.targets) }.map { e ->
                byId[e.id]?.let(::installedItem) ?: BrowserItem(
                    id = WrapperItems.PREFIX + e.id,
                    type = WrapperManager.TYPE,
                    name = e.title,
                    author = e.author,
                    installed = false,
                    details = listOfNotNull(WrapperManager.targetsLabel(e.targets))
                )
            }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = withContext(Dispatchers.IO) { WrapperManager.installFromFile(context, uri) }
            if (id == null) Toast.makeText(context, "Not a wrapper archive (.tzst)", Toast.LENGTH_LONG).show()
            else onInstalled(id)
        }
    }

    return WrapperItems(
        items = items,
        busyIds = setOfNotNull(busy),
        progress = progress,
        install = install@{ item ->
            if (busy != null) return@install
            val entry = catalog.firstOrNull { it.id == WrapperItems.wrapperId(item) } ?: return@install
            busy = item.id
            progress = 0
            scope.launch {
                val error = withContext(Dispatchers.IO) {
                    WrapperManager.download(context, entry) { p -> scope.launch { progress = p } }
                }
                busy = null
                progress = -1
                if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                else onInstalled(entry.id)
            }
        },
        remove = { item -> WrapperManager.remove(context, WrapperItems.wrapperId(item)) },
        importFile = {
            importLauncher.launch(arrayOf("application/zstd", "application/x-zstd", "application/octet-stream"))
        }
    )
}
