package com.winlator.cmod.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.contents.DriverSource
import com.winlator.cmod.contents.DriverSources
import com.winlator.cmod.ui.settings.AddDriverSourceDialog
import com.winlator.cmod.ui.settings.RemoveDriverSourceDialog

/** One installable or installed component as the browser shows it. */
data class BrowserItem(
    val id: String,
    /** Internal type: "AdrenoTools", "Wine", "Proton", "DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64". */
    val type: String,
    val name: String,
    /** Who publishes it (driver author / content source); null for installed-only items. */
    val author: String?,
    val installed: Boolean,
    val removable: Boolean = installed,
    val inUse: Boolean = false
)

/** A section of the browser: one or more internal types under a user-facing name. */
data class ComponentType(val key: String, val title: String, val icon: ImageVector, val types: Set<String>) {
    val isGpuDriver get() = key == GPU_DRIVER
    fun matches(item: BrowserItem) = item.type in types
}

const val GPU_DRIVER = "AdrenoTools"

val ALL_COMPONENT_TYPES = listOf(
    ComponentType(GPU_DRIVER, "GPU driver", Icons.Outlined.DeveloperBoard, setOf(GPU_DRIVER)),
    ComponentType("wine", "Wine & Proton", Icons.Outlined.WineBar, setOf("Wine", "Proton")),
    ComponentType("DXVK", "DXVK", Icons.Outlined.Layers, setOf("DXVK")),
    ComponentType("VKD3D", "VKD3D", Icons.Outlined.Layers, setOf("VKD3D")),
    ComponentType("FEXCore", "FEXCore", Icons.Outlined.Memory, setOf("FEXCore")),
    ComponentType("Box64", "Box64", Icons.Outlined.Memory, setOf("Box64")),
    ComponentType("WOWBox64", "WOWBox64", Icons.Outlined.Memory, setOf("WOWBox64")),
)

/** User-facing name of an internal type ("AdrenoTools" -> "GPU driver"). */
fun componentTypeTitle(type: String): String =
    ALL_COMPONENT_TYPES.firstOrNull { type in it.types && it.types.size == 1 }?.title
        ?: if (type == GPU_DRIVER) "GPU driver" else type

private const val AUTHOR_ALL = "All"
private const val AUTHOR_INSTALLED = "Installed"

/**
 * Download / manage screen for components.
 *
 * Without [initialType]: a vertical list of types first; tapping one opens it.
 * Inside a type: the title, a horizontal author selector under it, then the versions.
 * The menu button on the left switches to another type; with a single type it's hidden.
 */
@Composable
fun ComponentBrowser(
    items: List<BrowserItem>,
    loading: Boolean,
    onClose: () -> Unit,
    onInstall: (BrowserItem) -> Unit,
    onRemove: (BrowserItem) -> Unit,
    onLocalPackage: (ComponentType) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Components",
    subtitle: String = "Download and manage drivers and runtimes.",
    types: List<ComponentType> = ALL_COMPONENT_TYPES,
    initialType: String? = null,
    busyIds: Set<String> = emptySet(),
    busyLabel: String? = null,
    busyProgress: Int = -1,
    typeHeader: @Composable (ComponentType) -> Unit = {}
) {
    var currentKey by rememberSaveable { mutableStateOf(initialType) }
    val current = types.firstOrNull { it.key == currentKey }
    val canGoToList = initialType == null && types.size > 1

    BackHandler(enabled = current != null && canGoToList) { currentKey = null }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (current == null) {
            TypeList(title, subtitle, types, items, loading, onClose) { currentKey = it.key }
        } else {
            TypePage(
                type = current,
                types = types,
                items = items.filter { current.matches(it) },
                loading = loading,
                busyIds = busyIds,
                busyLabel = busyLabel,
                busyProgress = busyProgress,
                onSwitchType = { currentKey = it.key },
                onClose = { if (canGoToList) currentKey = null else onClose() },
                onInstall = onInstall,
                onRemove = onRemove,
                onLocalPackage = { onLocalPackage(current) },
                header = { typeHeader(current) }
            )
        }
    }
}

@Composable
private fun TypeList(
    title: String,
    subtitle: String,
    types: List<ComponentType>,
    items: List<BrowserItem>,
    loading: Boolean,
    onClose: () -> Unit,
    onOpen: (ComponentType) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
            }
            Spacer(Modifier.height(6.dp))
        }
        items(types, key = { it.key }) { type ->
            val ofType = items.filter { type.matches(it) }
            val installed = ofType.count { it.installed }
            val available = ofType.count { !it.installed }
            Surface(
                onClick = { onOpen(type) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(type.icon, null) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(type.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (loading && ofType.isEmpty()) "Loading…" else "$installed installed · $available available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TypePage(
    type: ComponentType,
    types: List<ComponentType>,
    items: List<BrowserItem>,
    loading: Boolean,
    busyIds: Set<String>,
    busyLabel: String?,
    busyProgress: Int,
    onSwitchType: (ComponentType) -> Unit,
    onClose: () -> Unit,
    onInstall: (BrowserItem) -> Unit,
    onRemove: (BrowserItem) -> Unit,
    onLocalPackage: () -> Unit,
    header: @Composable () -> Unit
) {
    val context = LocalContext.current
    val sourcesRev = DriverSources.revision.intValue
    var author by rememberSaveable(type.key) { mutableStateOf(AUTHOR_ALL) }
    var typeMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DriverSource?>(null) }

    // Author chips: All, Installed, then publishers. For GPU drivers every known
    // author is listed even before its list has loaded (or when offline).
    val customSources = remember(sourcesRev) { DriverSources.custom(context) }
    val authors = remember(items, type.key, sourcesRev) {
        val names = linkedSetOf<String>()
        if (type.isGpuDriver) DriverSources.all(context).forEach { names += it.name }
        items.filter { !it.installed }.mapNotNullTo(names) { it.author }
        buildList {
            add(AUTHOR_ALL)
            if (items.any { it.installed }) add(AUTHOR_INSTALLED)
            addAll(names)
        }
    }
    // A removed author or a list that changed falls back to "All" (no state write during composition).
    val selectedAuthor = if (author in authors) author else AUTHOR_ALL
    val shown = when (selectedAuthor) {
        AUTHOR_ALL -> items.sortedByDescending { it.installed }
        AUTHOR_INSTALLED -> items.filter { it.installed }
        else -> items.filter { !it.installed && it.author == selectedAuthor }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: menu (switch type) on the left, title, close on the right.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (types.size > 1) {
                Box {
                    IconButton(onClick = { typeMenu = true }) { Icon(Icons.Outlined.Menu, "Component types") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        types.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.title, fontWeight = if (t.key == type.key) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { Icon(t.icon, null) },
                                trailingIcon = { if (t.key == type.key) Icon(Icons.Outlined.Check, null) },
                                onClick = { typeMenu = false; onSwitchType(t) }
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                type.title,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
        }

        // Author selector, scrolls sideways.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(authors, key = { it }) { name ->
                val custom = customSources.firstOrNull { it.name == name }
                AuthorChip(
                    label = name,
                    selected = name == selectedAuthor,
                    onClick = { author = name },
                    onLongClick = custom?.let { { removing = it } }
                )
            }
            if (type.isGpuDriver) {
                item(key = "+add") {
                    AuthorChip(label = "Add", icon = Icons.Outlined.Add, selected = false, onClick = { adding = true })
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "local") {
                OutlinedButton(onClick = onLocalPackage, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FileOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (type.isGpuDriver) "Install driver from file (.zip)" else "Install from file")
                }
            }
            item(key = "header") { header() }
            when {
                shown.isNotEmpty() -> items(shown, key = { it.id }) { item ->
                    ItemCard(item, item.id in busyIds, busyIds.isNotEmpty() && item.id !in busyIds, busyLabel, busyProgress, onInstall, onRemove)
                }
                loading -> item(key = "loading") { Hint("Loading the list…") }
                selectedAuthor == AUTHOR_ALL || selectedAuthor == AUTHOR_INSTALLED -> item(key = "empty") { Hint("Nothing here yet.") }
                else -> item(key = "empty") {
                    Hint("No saved list from $selectedAuthor yet. Connect to the internet once and it will appear, also offline later.")
                }
            }
        }
    }

    if (adding) AddDriverSourceDialog(onDismiss = { adding = false }, onAdded = { author = it })
    removing?.let { RemoveDriverSourceDialog(it, onDismiss = { removing = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuthorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(50),
        color = if (selected) scheme.primary else scheme.surface,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ItemCard(
    item: BrowserItem,
    busy: Boolean,
    locked: Boolean,
    busyLabel: String?,
    busyProgress: Int,
    onInstall: (BrowserItem) -> Unit,
    onRemove: (BrowserItem) -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        busy && busyProgress >= 0 -> "${busyLabel ?: "Installing"} • $busyProgress%"
                        busy -> busyLabel ?: "Working…"
                        item.inUse -> "In use"
                        item.installed -> "Installed"
                        else -> item.author ?: componentTypeTitle(item.type)
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    item.installed && item.removable -> OutlinedButton(onClick = { onRemove(item) }, enabled = !locked && !item.inUse) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (item.inUse) "In use" else "Delete")
                    }
                    !item.installed -> OutlinedButton(onClick = { onInstall(item) }, enabled = !locked) { Text("Download") }
                    else -> Icon(Icons.Outlined.Check, null)
                }
            }
            if (busy) {
                Spacer(Modifier.height(9.dp))
                if (busyProgress >= 0) {
                    LinearProgressIndicator(progress = { busyProgress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
