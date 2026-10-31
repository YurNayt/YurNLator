package com.winlator.cmod.ui.components

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.outlined.Hub
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
import com.winlator.cmod.core.DriverNames
import com.winlator.cmod.core.LocalInstalls
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
    val inUse: Boolean = false,
    /** LocalInstalls key ("type|name"): marks items the user installed from their own files. */
    val localKey: String? = null,
    /** Extra details line pieces, e.g. "Mali / Exynos only" for a wrapper. */
    val details: List<String> = emptyList()
)

/** A section of the browser: one or more internal types under a user-facing name. */
data class ComponentType(val key: String, val title: String, val icon: ImageVector, val types: Set<String>) {
    val isGpuDriver get() = key == GPU_DRIVER
    val isWrapper get() = key == WRAPPER
    /** Types with several publishers get the author selector. */
    val hasAuthors get() = isGpuDriver || isWrapper
    fun matches(item: BrowserItem) = item.type in types
}

const val GPU_DRIVER = "AdrenoTools"
const val WRAPPER = "Wrapper"

val ALL_COMPONENT_TYPES = listOf(
    ComponentType(GPU_DRIVER, "GPU driver", Icons.Outlined.DeveloperBoard, setOf(GPU_DRIVER)),
    ComponentType(WRAPPER, "Wrapper", Icons.Outlined.Hub, setOf(WRAPPER)),
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
private const val AUTHOR_MY_FILES = "My files"

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
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = drawerState.isOpen,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                            }
                            items(types, key = { it.key }) { t ->
                                TypeCard(t, items, loading, selected = t.key == current.key) {
                                    currentKey = t.key
                                    scope.launch { drawerState.close() }
                                }
                            }
                        }
                    }
                }
            ) {
            TypePage(
                onOpenMenu = { scope.launch { drawerState.open() } },
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
            TypeCard(type, items, loading, selected = false) { onOpen(type) }
        }
    }
}

/** A component type as a card: icon, title, "N installed · M available". Used by the list and the side drawer. */
@Composable
private fun TypeCard(type: ComponentType, items: List<BrowserItem>, loading: Boolean, selected: Boolean, onClick: () -> Unit) {
    val ofType = items.filter { type.matches(it) }
    val installed = ofType.count { it.installed }
    val available = ofType.count { !it.installed }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(scheme.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(type.icon, null, tint = if (selected) scheme.primary else scheme.onSurface) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(type.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (loading && ofType.isEmpty()) "Loading…" else "$installed installed · $available available",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            if (selected) Icon(Icons.Outlined.Check, null, tint = scheme.primary)
            else Icon(Icons.Outlined.ChevronRight, null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TypePage(
    onOpenMenu: () -> Unit,
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
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DriverSource?>(null) }

    val localRev = LocalInstalls.revision.intValue
    val myFiles = remember(items, localRev) {
        items.filter { it.installed && LocalInstalls.contains(context, it.localKey) }.map { it.id }.toSet()
    }

    // Selector: GPU drivers have many authors -> All, Installed, authors, My files, + Add.
    // Other types have one source -> no selector, unless there are own files: All, My files.
    val customSources = remember(sourcesRev) { DriverSources.custom(context) }
    val authors = remember(items, type.key, sourcesRev, myFiles) {
        if (type.hasAuthors) {
            val names = linkedSetOf<String>()
            if (type.isGpuDriver) DriverSources.all(context).forEach { names += it.name }
            items.filter { !it.installed }.mapNotNullTo(names) { it.author }
            buildList {
                add(AUTHOR_ALL)
                if (items.any { it.installed }) add(AUTHOR_INSTALLED)
                addAll(names)
                if (myFiles.isNotEmpty()) add(AUTHOR_MY_FILES)
            }
        } else if (myFiles.isNotEmpty()) {
            listOf(AUTHOR_ALL, AUTHOR_MY_FILES)
        } else {
            emptyList()
        }
    }
    // A removed author or a list that changed falls back to "All" (no state write during composition).
    val selectedAuthor = if (author in authors) author else AUTHOR_ALL
    // The list in its own order; installed entries stay in their place (with Delete).
    val shown = when (selectedAuthor) {
        AUTHOR_ALL -> items
        AUTHOR_INSTALLED -> items.filter { it.installed }
        AUTHOR_MY_FILES -> items.filter { it.id in myFiles }
        else -> items.filter { it.author == selectedAuthor }
    }
    // In All and in an author's tab, what's installed is also repeated in a group on top.
    val installedTop = if (selectedAuthor == AUTHOR_ALL || selectedAuthor !in setOf(AUTHOR_INSTALLED, AUTHOR_MY_FILES))
        shown.filter { it.installed } else emptyList()
    val splitList = installedTop.isNotEmpty() && shown.any { !it.installed }
    // Inside one author the author line would only repeat the selection.
    val showAuthorLine = selectedAuthor == AUTHOR_ALL || selectedAuthor == AUTHOR_INSTALLED

    val card: @Composable (BrowserItem) -> Unit = { item ->
        ItemCard(
            item = item,
            gpuDriver = type.isGpuDriver,
            fromFile = item.id in myFiles,
            showAuthor = showAuthorLine,
            busy = item.id in busyIds,
            locked = busyIds.isNotEmpty() && item.id !in busyIds,
            busyLabel = busyLabel,
            busyProgress = busyProgress,
            onInstall = onInstall,
            onRemove = {
                it.localKey?.let { key -> LocalInstalls.forget(context, key) }
                onRemove(it)
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: menu (switch type) on the left, title, close on the right.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (types.size > 1) {
                IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.Menu, "Component types") }
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
        if (authors.isNotEmpty()) LazyRow(
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
                    Text(
                        when {
                            type.isGpuDriver -> "Install driver from file (.zip)"
                            type.isWrapper -> "Install wrapper from file (.tzst)"
                            else -> "Install from file"
                        }
                    )
                }
            }
            item(key = "header") { header() }
            when {
                shown.isNotEmpty() -> {
                    if (splitList) {
                        item(key = "h-installed") { SectionLabel("Installed") }
                        items(installedTop, key = { "top:" + it.id }) { item -> card(item) }
                        item(key = "h-all") { SectionLabel(if (selectedAuthor == AUTHOR_ALL) "All" else selectedAuthor) }
                    }
                    items(shown, key = { it.id }) { item -> card(item) }
                }
                else -> Unit
            }
            when {
                shown.isNotEmpty() -> Unit
                loading -> item(key = "loading") { Hint("Loading the list…") }
                selectedAuthor == AUTHOR_ALL || selectedAuthor == AUTHOR_INSTALLED || selectedAuthor == AUTHOR_MY_FILES ->
                    item(key = "empty") { Hint("Nothing here yet.") }
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
    gpuDriver: Boolean,
    fromFile: Boolean,
    showAuthor: Boolean,
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
                    // GPU drivers: clean title + what the build is for ("OneUI fix · Adreno 710–720").
                    val parsed = remember(item.name, gpuDriver) { if (gpuDriver) DriverNames.parse(item.name) else null }
                    Text(parsed?.title ?: item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val details = buildList {
                        parsed?.tags?.let { addAll(it) }
                        addAll(item.details)
                        if (fromFile) add("From your files")
                        if (item.inUse) add("In use")
                        else if (item.installed && isEmpty()) add("Installed")
                        if (showAuthor && !item.installed) item.author?.let { add(it) }
                    }.joinToString(" · ")
                    val status = when {
                        busy && busyProgress >= 0 -> "${busyLabel ?: "Installing"} • $busyProgress%"
                        busy -> busyLabel ?: "Working…"
                        else -> details
                    }
                    if (status.isNotEmpty()) {
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
