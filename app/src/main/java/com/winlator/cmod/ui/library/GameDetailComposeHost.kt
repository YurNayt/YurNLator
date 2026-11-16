package com.winlator.cmod.ui.library

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.KeepLandscapeChromeHidden
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface GameDetailCallbacks {
    fun onPlay()
    /** This launch only, as administrator (session/AdminRun). */
    fun onPlayAdmin() {}
    fun onConfigure()
    fun onArguments()
    fun onGameFolder()
    fun onFavorite(favorite: Boolean)
    fun onRemove()
    fun onPresets() {}
    fun onComponents() {}
}

/**
 * The game's page, laid out like Winlator Ludashi 5.0's: the artwork with back / favorite on it,
 * tiles with time played, launches, last launch, size and store, then Play (with a
 * run-as-administrator button) and the game's actions.
 */
object GameDetailComposeHost {
    @JvmStatic
    fun create(context: Context, title: String, subtitle: String, artworkPath: String?, fallback: Bitmap?, favorite: Boolean,
               shortcut: Shortcut?, callbacks: GameDetailCallbacks): ComposeView {
        applyAppFullscreen(context as? MainActivity)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { GameDetailScreen(title, subtitle, artworkPath, fallback, favorite, shortcut, callbacks) } }
        }
    }
}

@Composable
private fun GameDetailScreen(title: String, subtitle: String, artworkPath: String?, fallback: Bitmap?, initialFavorite: Boolean,
                             shortcut: Shortcut?, callbacks: GameDetailCallbacks) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val activity = LocalContext.current as? MainActivity
    val context = LocalContext.current.applicationContext
    if (landscape) KeepLandscapeChromeHidden(activity, restoreChromeOnPortrait = false)
    // Landscape only: in portrait the page is a layer over the top bar (GameDetailFragment).
    DisposableEffect(activity, landscape) {
        if (landscape) {
            activity?.setBottomNavigationVisible(false)
            activity?.setMainToolbarVisible(false)
        }
        onDispose { }
    }
    val artwork by produceState<Bitmap?>(fallback, artworkPath, fallback) {
        value = withContext(Dispatchers.IO) { artworkPath?.takeIf { File(it).isFile }?.let(BitmapFactory::decodeFile) ?: fallback }
    }
    // Counted again when the page comes back from a game.
    var resumed by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val stats by produceState(GameStats(), shortcut?.file?.path, resumed) {
        if (shortcut == null) return@produceState
        val basic = withContext(Dispatchers.IO) { GameStatsLoader.load(context, shortcut) }
        value = basic
        value = basic.copy(sizeText = withContext(Dispatchers.IO) { runCatching { GameStatsLoader.size(context, shortcut) }.getOrNull() })
    }
    var favorite by remember(initialFavorite) { mutableStateOf(initialFavorite) }
    val toggle = { favorite = !favorite; callbacks.onFavorite(favorite) }
    if (landscape) LandscapeDetail(title, subtitle, artwork, favorite, stats, callbacks, toggle)
    else PortraitDetail(title, subtitle, artwork, favorite, stats, callbacks, toggle)
}

@Composable
private fun Artwork(artwork: Bitmap?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        if (artwork != null) Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.22f), Color.Transparent, Color.Black.copy(.88f)))))
    }
}

@Composable
private fun ArtControls(favorite: Boolean, toggle: () -> Unit) {
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        ArtButton({ activity?.onBackPressedDispatcher?.onBackPressed() }) { Icon(Icons.Outlined.ArrowBack, uiStr(R.string.l_lib_back)) }
        ArtButton(toggle) { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, uiStr(R.string.l_lib_favorite)) }
    }
}

@Composable
private fun ArtButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick, Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(.55f), contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(.18f))) { Box(contentAlignment = Alignment.Center) { content() } }
}

@Composable
private fun LandscapeDetail(title: String, subtitle: String, artwork: Bitmap?, favorite: Boolean, stats: GameStats,
                            callbacks: GameDetailCallbacks, toggle: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Artwork(artwork, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(.55f), Color.Transparent, Color.Black.copy(.45f)))))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            ArtControls(favorite, toggle)
            Column(Modifier.align(Alignment.TopStart).padding(start = 76.dp, end = 76.dp, top = 18.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.White.copy(.75f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            val compact = maxWidth < 700.dp || maxHeight < 360.dp
            val available = (maxWidth - 54.dp).coerceAtLeast(200.dp)
            val panelWidth = minOf(available * .55f, if (compact) 360.dp else 440.dp)
            val statsWidth = (available - panelWidth).coerceAtLeast(100.dp)
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(18.dp).width(panelWidth).heightIn(max = (maxHeight - 116.dp).coerceAtLeast(100.dp)),
                shape = RoundedCornerShape(22.dp), color = Color.Black.copy(.48f), border = BorderStroke(1.dp, Color.White.copy(.18f))
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayRow(callbacks, onArtwork = true)
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TileAction(Icons.Outlined.Settings, uiStr(R.string.l_lib_configure), Modifier.weight(1f), callbacks::onConfigure)
                        TileAction(Icons.Outlined.Dns, uiStr(R.string.l_gd_container), Modifier.weight(1f), callbacks::onArguments)
                        TileAction(Icons.Outlined.Public, uiStr(R.string.l_gd_presets), Modifier.weight(1f), callbacks::onPresets)
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TileAction(Icons.Outlined.Extension, uiStr(R.string.l_gd_components), Modifier.weight(1f), callbacks::onComponents)
                        TileAction(Icons.Outlined.Folder, uiStr(R.string.l_lib_game_folder), Modifier.weight(1f), callbacks::onGameFolder)
                        TileAction(Icons.Outlined.DeleteOutline, uiStr(R.string.l_ctr_remove), Modifier.weight(1f), callbacks::onRemove, true)
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).padding(18.dp).width(minOf(statsWidth, 440.dp))
                    .heightIn(max = (maxHeight - 128.dp).coerceAtLeast(80.dp)).verticalScroll(rememberScrollState())
            ) { StatsGrid(stats, Modifier.fillMaxWidth(), onArtwork = true) }
        }
    }
}

@Composable
private fun PortraitDetail(title: String, subtitle: String, artwork: Bitmap?, favorite: Boolean, stats: GameStats,
                           callbacks: GameDetailCallbacks, toggle: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.28f)) {
            Artwork(artwork, Modifier.fillMaxSize())
            ArtControls(favorite, toggle)
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.White.copy(.75f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsGrid(stats, Modifier.fillMaxWidth())
            PlayRow(callbacks, onArtwork = false)
            DetailAction(Icons.Outlined.Settings, uiStr(R.string.l_lib_configure), callbacks::onConfigure)
            DetailAction(Icons.Outlined.Dns, uiStr(R.string.l_lib_enter_container), callbacks::onArguments)
            DetailAction(Icons.Outlined.Public, uiStr(R.string.l_lib_community_presets), callbacks::onPresets)
            DetailAction(Icons.Outlined.Extension, uiStr(R.string.l_lib_windows_components), callbacks::onComponents)
            DetailAction(Icons.Outlined.Folder, uiStr(R.string.l_lib_game_folder), callbacks::onGameFolder)
            DetailAction(Icons.Outlined.DeleteOutline, uiStr(R.string.l_ctr_remove), callbacks::onRemove, true)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Play, and next to it the same launch as administrator. */
@Composable
private fun PlayRow(callbacks: GameDetailCallbacks, onArtwork: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(callbacks::onPlay, Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp),
            colors = if (onArtwork) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black) else ButtonDefaults.buttonColors()) {
            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp))
            Text(uiStr(R.string.l_lib_play), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Surface(callbacks::onPlayAdmin, Modifier.size(52.dp), shape = RoundedCornerShape(12.dp),
            color = if (onArtwork) Color.White.copy(.10f) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (onArtwork) Color.White else MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, if (onArtwork) Color.White.copy(.16f) else MaterialTheme.colorScheme.outlineVariant)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AdminPanelSettings, uiStr(R.string.l_gd_run_admin)) }
        }
    }
}

@Composable
private fun TileAction(icon: ImageVector, label: String, modifier: Modifier, click: () -> Unit, destructive: Boolean = false) {
    Surface(click, modifier.fillMaxHeight(), shape = RoundedCornerShape(12.dp), color = Color.White.copy(.10f),
        contentColor = if (destructive) MaterialTheme.colorScheme.error else Color.White, border = BorderStroke(1.dp, Color.White.copy(.16f))) {
        Column(Modifier.padding(horizontal = 3.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(23.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, click: () -> Unit, destructive: Boolean = false) {
    Surface(click, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Stat(val icon: ImageVector, val label: String, val value: String)

@Composable
private fun StatsGrid(stats: GameStats, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    val entries = buildList {
        add(Stat(Icons.Outlined.Schedule, uiStr(R.string.l_gd_playtime), if (stats.playtimeMillis > 0) formatPlaytime(stats.playtimeMillis) else "—"))
        add(Stat(Icons.Outlined.SportsEsports, uiStr(R.string.l_gd_launches), stats.playCount.toString()))
        add(Stat(Icons.Outlined.History, uiStr(R.string.l_gd_last_played), if (stats.lastPlayedMillis > 0) formatLastPlayed(stats.lastPlayedMillis) else "—"))
        stats.sizeText?.let { add(Stat(Icons.Outlined.Storage, uiStr(R.string.l_gd_size), it)) }
        when (stats.store) {
            "steam" -> add(Stat(Icons.Outlined.Storefront, uiStr(R.string.l_gd_store), "Steam"))
            "epic" -> add(Stat(Icons.Outlined.Storefront, uiStr(R.string.l_gd_store), "Epic Games"))
        }
    }
    BoxWithConstraints(modifier) {
        val columns = if (maxWidth >= (if (onArtwork) 200.dp else 280.dp) && LocalConfiguration.current.fontScale <= 1.3f) 2 else 1
        val showIcons = maxWidth >= 280.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { e ->
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                            color = if (onArtwork) Color.Black.copy(.58f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (onArtwork) Color.White else MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, if (onArtwork) Color.White.copy(.16f) else MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (showIcons) { Icon(e.icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(e.label, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(.7f), maxLines = 1)
                                    Text(e.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
