package com.winlator.cmod.ui.library

import com.winlator.cmod.ui.components.MotionSheet

import com.winlator.cmod.ui.uiStr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun Bitmap.asImageBitmap(): ImageBitmap = this.composeAsImageBitmap()

@Composable
internal fun LibraryRoot(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    cb: LibraryCallbacks
) {
    var filterName by rememberSaveable { mutableStateOf(LibraryFilter.All.name) }
    val filter = LibraryFilter.valueOf(filterName)
    // Every game's cover and background read ahead into memory: switching grid / list / the
    // landscape artwork view then shows them at once instead of each popping in a moment later.
    LaunchedEffect(items) {
        withContext(Dispatchers.IO) {
            for (it in items) { ArtworkCache.load(it.coverPath); ArtworkCache.load(it.bannerPath) }
        }
    }
    val visible = remember(items, filter, query) {
        val source = when (filter) {
            LibraryFilter.All -> items
            LibraryFilter.Favorites -> items.filter { it.favorite }
            LibraryFilter.Recent -> items.asReversed()
            LibraryFilter.Steam -> items.filter { it.store == "steam" }
            LibraryFilter.Epic -> items.filter { it.store == "epic" }
        }
        if (query.isBlank()) source else source.filter { it.name.contains(query, true) }
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity, landscape) {
        activity?.setBottomNavigationVisible(!landscape)
        activity?.setMainToolbarVisible(!landscape)
        onDispose {
            // Only back in portrait: in landscape the next screen keeps them hidden, and showing
            // them here made the bottom bar flash and the next screen jump on every switch.
            if (activity?.resources?.configuration?.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                activity?.setBottomNavigationVisible(true)
                activity?.setMainToolbarVisible(true)
            }
        }
    }

    if (landscape && visible.isNotEmpty() && !grid) {
        var menu by remember { mutableStateOf<LibraryItem?>(null) }
        Box(Modifier.fillMaxSize()) {
        LandscapePagerCore(
            items = visible,
            selectedShortcutPath = selectedShortcutPath,
            callbacks = cb,
            header = {
                LibraryLandscapeHeader(
                    activity = activity,
                    grid = grid,
                    onArtwork = true,
                    onGridViewChanged = cb::onGridViewChanged
                )
                Spacer(Modifier.height(7.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    libraryFilters(items).forEach { option ->
                        LibraryFilterChip(filterLabel(option), option == filter) { filterName = option.name }
                    }
                }
            },
            footerActions = { item ->
                IconButton(onClick = { menu = item }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.l_lib_more_options), tint = Color.White) }
            }
        )
        AddGamesFab(activity, Modifier.align(Alignment.BottomEnd))
        }
        menu?.let { LibraryItemMenuCompat(it, cb) { menu = null } }
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Landscape: header and chips in the same place as over the artwork (26 dp in, 12 dp down), so
    // switching between the two views doesn't move them.
    Column(Modifier.fillMaxSize().then(if (landscape) Modifier.padding(start = 26.dp, end = 26.dp, top = 12.dp) else Modifier.padding(horizontal = 14.dp))) {
        if (landscape) {
            LibraryLandscapeHeader(
                activity = activity,
                grid = grid,
                onArtwork = false,
                onGridViewChanged = cb::onGridViewChanged
            )
            Spacer(Modifier.height(7.dp))
        }
        Row(Modifier.padding(if (landscape) PaddingValues(bottom = 9.dp) else PaddingValues(vertical = 12.dp)).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(if (landscape) 7.dp else 8.dp)) {
            libraryFilters(items).forEach { option ->
                LibraryFilterChip(filterLabel(option), option == filter) { filterName = option.name }
            }
        }
        if (visible.isEmpty()) {
            if (query.isNotBlank() || (filter != LibraryFilter.All && items.isNotEmpty())) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) uiStr(R.string.l_lib_no_games_match_your_search) else uiStr(R.string.l_lib_no_games_in_this_section),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Like the containers tab: a message here, adding is the + button below.
                Box(Modifier.fillMaxSize().padding(bottom = 72.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(if (landscape) 62.dp else 76.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.SportsEsports, null, modifier = Modifier.size(if (landscape) 30.dp else 38.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(uiStr(R.string.l_lib_no_games_yet), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            uiStr(R.string.l_lib_add_games_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // Each section starts at its top. One shared scroll state kept the first game on
            // screen across a switch: in "Recent" (the list reversed) that game is last, so the
            // section opened scrolled to its end.
            val gridState = remember(filter) { LazyGridState() }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (grid) 172.dp else 360.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.id }) { item ->
                    if (grid) CoverArtworkCard(item, cb) else CompactArtworkCard(item, cb)
                }
            }
        }
    }
    // Adding games is a floating button, as on the containers tab (both orientations).
    AddGamesFab(activity, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun AddGamesFab(activity: MainActivity?, modifier: Modifier) {
    FloatingActionButton(
        onClick = { activity?.navigateToMainDestination(R.id.main_menu_file_manager) },
        modifier = modifier.padding(18.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) { Icon(Icons.Outlined.Add, uiStr(R.string.l_lib_add_games)) }
}

@Composable
private fun LibraryLandscapeHeader(
    activity: MainActivity?,
    grid: Boolean,
    onArtwork: Boolean,
    onGridViewChanged: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            uiStr(R.string.l_lib_library),
            color = if (onArtwork) Color.White else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        LibraryTopIcon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, false) {
            onGridViewChanged(!grid)
        }
        // The tabs are the bar on the right (LandscapeNavRail); adding games is the + button below.
    }
}

@Composable
private fun LibraryOrientationMenu(activity: MainActivity?) {
    var expanded by remember { mutableStateOf(false) }
    var orientationRevision by remember { mutableStateOf(0) }
    val orientationState = remember(activity, orientationRevision) {
        Triple(
            activity?.isOrientationLocked ?: false,
            activity?.isVerticalModeEnabled ?: false,
            activity?.isHorizontalModeEnabled ?: false
        )
    }

    Box {
        LibraryTopIcon(Icons.Outlined.MoreVert, false) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OrientationToggleMenuItem(uiStr(R.string.l_lib_lock_screen_orientation), orientationState.first) {
                activity?.toggleOrientationLock()
                orientationRevision++
            }
            OrientationToggleMenuItem(uiStr(R.string.l_lib_vertical_mode), orientationState.second) {
                activity?.toggleVerticalMode()
                orientationRevision++
            }
            OrientationToggleMenuItem(uiStr(R.string.l_lib_horizontal_mode), orientationState.third) {
                activity?.toggleHorizontalMode()
                orientationRevision++
            }
        }
    }
}

@Composable
private fun OrientationToggleMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
        onClick = onClick
    )
}

@Composable
private fun LibraryTopIcon(icon: ImageVector, selected: Boolean, click: () -> Unit) {
    val whiteTheme = MaterialTheme.colorScheme.background.luminance() > .65f
    val background = if (whiteTheme) Color.Black.copy(if (selected) .90f else .78f)
    else if (selected) Color.White.copy(.16f) else Color.Transparent
    val content = if (whiteTheme) Color.White else Color.White.copy(if (selected) 1f else .68f)
    Surface(
        onClick = click,
        modifier = Modifier.padding(horizontal = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = content
    ) { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(23.dp)) } }
}

/** All / Favorites / Recent, and a chip for each store that has games here. */
private fun libraryFilters(items: List<LibraryItem>): List<LibraryFilter> = LibraryFilter.values().filter { f ->
    when (f) {
        LibraryFilter.Steam -> items.any { it.store == "steam" }
        LibraryFilter.Epic -> items.any { it.store == "epic" }
        else -> true
    }
}

private fun filterLabel(f: LibraryFilter): String = when (f) {
    LibraryFilter.All -> uiStr(R.string.l_lib_all)
    LibraryFilter.Favorites -> uiStr(R.string.l_lib_favorites)
    LibraryFilter.Recent -> uiStr(R.string.l_lib_recent)
    LibraryFilter.Steam -> "Steam"
    LibraryFilter.Epic -> "Epic"
}

@Composable
private fun LibraryFilterChip(label: String, selected: Boolean, click: () -> Unit) {
    Surface(
        onClick = click,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Text(label, Modifier.padding(horizontal = 15.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().height(92.dp).combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            ArtworkCompat(
                item.bannerPath ?: item.coverPath ?: item.iconPath,
                item.fallbackIcon,
                Modifier.fillMaxSize().alpha(.52f)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(.38f),
                            MaterialTheme.colorScheme.surface.copy(.82f),
                            MaterialTheme.colorScheme.surface.copy(.96f)
                        )
                    )
                )
            )
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ArtworkCompat(
                        item.coverPath ?: item.bannerPath ?: item.iconPath,
                        item.fallbackIcon,
                        Modifier.size(68.dp).clip(RoundedCornerShape(11.dp))
                    )
                    com.winlator.cmod.store.StoreBadge(item.store, Modifier.align(Alignment.BottomEnd).padding(3.dp), 18.dp)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.containerName, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                PlayCompat(item, cb, false)
                MenuButtonCompat(item, cb, false)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(Modifier.aspectRatio(.72f)) {
            ArtworkCompat(item.coverPath ?: item.bannerPath ?: item.iconPath, item.fallbackIcon, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 50.dp, bottom = 13.dp)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.containerName, color = Color.White.copy(.72f), style = MaterialTheme.typography.bodySmall)
            }
            com.winlator.cmod.store.StoreBadge(item.store, Modifier.align(Alignment.TopStart).padding(10.dp), 24.dp)
            Box(Modifier.align(Alignment.TopEnd)) { MenuButtonCompat(item, cb, true) }
            Box(Modifier.align(Alignment.BottomEnd).padding(9.dp)) { PlayCompat(item, cb, true) }
        }
    }
}

@Composable
private fun RequestArtworkCompat(item: LibraryItem, cb: LibraryCallbacks) {
    LaunchedEffect(item.id, item.coverPath, item.bannerPath) {
        if (item.coverPath == null) cb.onArtworkNeeded(item.shortcutPath, "cover")
        if (item.bannerPath == null) cb.onArtworkNeeded(item.shortcutPath, "banner")
    }
}

@Composable
private fun ArtworkCompat(path: String?, fallback: Bitmap?, modifier: Modifier) {
    // Decoded artwork is kept in memory: switching sections or grid / list showed each card's
    // small icon for a moment while its picture was read from storage again.
    val cached = remember(path) { ArtworkCache.get(path) }
    val bitmap by produceState(cached ?: fallback, path) {
        if (cached != null) return@produceState
        value = withContext(Dispatchers.IO) { ArtworkCache.load(path) ?: fallback }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun PlayCompat(item: LibraryItem, cb: LibraryCallbacks, overlay: Boolean) {
    Surface(
        onClick = { cb.onRun(item.shortcutPath) },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        // The theme's accent, on artwork and on plain cards alike.
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, uiStr(R.string.l_lib_play)) } }
}

@Composable
private fun MenuButtonCompat(item: LibraryItem, cb: LibraryCallbacks, light: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, uiStr(R.string.l_lib_more_options), tint = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) LibraryItemMenuCompat(item, cb) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryItemMenuCompat(item: LibraryItem, cb: LibraryCallbacks, close: () -> Unit) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    MotionSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface, dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
            ) {}
        }) { _ ->
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = if (landscape) 920.dp else 760.dp)
                .padding(horizontal = if (landscape) 22.dp else 16.dp)
                .padding(bottom = if (landscape) 10.dp else 24.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            Text(
                item.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
            )
            val favoriteLabel = if (item.favorite) uiStr(R.string.l_lib_unfavorite) else uiStr(R.string.l_lib_favorite)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(
                    if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    favoriteLabel,
                    Modifier.weight(1f),
                    horizontal = landscape
                ) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_FAVORITE)
                }
                LibraryActionTileCompat(Icons.Outlined.Settings, uiStr(R.string.l_lib_configure), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS)
                }
                LibraryActionTileCompat(Icons.Outlined.Photo, uiStr(R.string.l_lib_artwork), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_ICON)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(Icons.Outlined.Home, uiStr(R.string.l_lib_home_screen), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_HOME)
                }
                LibraryActionTileCompat(Icons.Outlined.ContentCopy, uiStr(R.string.l_set_clone), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_CLONE)
                }
                LibraryActionTileCompat(Icons.Outlined.FileUpload, uiStr(R.string.l_ic_export), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_EXPORT)
                }
            }
            LibraryActionTileCompat(
                Icons.Outlined.AdminPanelSettings,
                uiStr(R.string.l_gd_run_admin),
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_RUN_ADMIN)
            }
            LibraryActionTileCompat(
                Icons.Outlined.Public,
                uiStr(R.string.l_lib_community_presets),
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_PRESETS)
            }
            LibraryActionTileCompat(
                Icons.Outlined.DeleteOutline,
                uiStr(R.string.l_lib_remove_from_library),
                Modifier.fillMaxWidth().padding(top = 8.dp),
                destructive = true,
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_REMOVE)
            }
        }
    }
}

@Composable
private fun LibraryActionTileCompat(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    destructive: Boolean = false,
    horizontal: Boolean = false,
    click: () -> Unit
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = click,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (destructive)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = .22f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        border = BorderStroke(
            1.dp,
            if (destructive) MaterialTheme.colorScheme.error.copy(alpha = .46f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .70f)
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (horizontal) 13.dp else 12.dp,
                vertical = if (horizontal) 9.dp else 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(if (horizontal) 21.dp else 23.dp), tint = tint)
            Spacer(Modifier.width(9.dp))
            Text(
                label,
                style = if (horizontal) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Decoded library artwork, by file (and its last change, so new artwork is read again). */
internal object ArtworkCache {
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun key(path: String) = path + "@" + File(path).lastModified()
    fun get(path: String?): Bitmap? = path?.let { cache.get(key(it)) }
    fun put(path: String, bitmap: Bitmap) { cache.put(key(path), bitmap) }

    /**
     * Reads [path] (at most ~2048 px a side: a 4K hero art would take 19 MB) and keeps it. Off the
     * UI thread. Null when there's no such picture.
     */
    fun load(path: String?): Bitmap? {
        path ?: return null
        get(path)?.let { return it }
        val f = File(path)
        if (!f.isFile) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        put(path, bmp)
        return bmp
    }
}
