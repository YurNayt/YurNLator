package com.winlator.cmod.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.winlator.cmod.R
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Stores tab (left of Library): Steam and Epic Games Store, lite. Stage 1: the store's showcase,
 * search and a game card linking to its store page. Sign-in, library, install and remove come next.
 */
class StoresFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        ComposeView(requireContext()).apply { setContent { WinZTheme { StoresScreen() } } }

    override fun onResume() {
        super.onResume()
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = uiStr(R.string.l_st_stores)
    }
}

@Composable
private fun StoresScreen() {
    var store by rememberSaveable { mutableStateOf<String?>(null) }
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val activity = LocalContext.current as? com.winlator.cmod.MainActivity
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) com.winlator.cmod.ui.LandscapeMainNavigation(activity, R.id.main_menu_stores, uiStr(R.string.l_st_stores))
        val s = store?.let { runCatching { Store.valueOf(it) }.getOrNull() }
        if (s == null) StorePicker { store = it.name } else {
            BackHandler { store = null }
            StoreTheme(s) { StoreScreen(s) { store = null } }
        }
    }
}

@Composable
private fun StorePicker(onPick: (Store) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiStr(R.string.l_st_stores), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(uiStr(R.string.l_st_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StoreCard(Store.STEAM, "Steam", uiStr(R.string.l_st_steam_desc), Color(0xFF1B2838), Color(0xFF66C0F4)) { onPick(Store.STEAM) }
        StoreCard(Store.EPIC, "Epic Games Store", uiStr(R.string.l_st_epic_desc), Color(0xFF121212), Color(0xFFF5F5F5)) { onPick(Store.EPIC) }
    }
}

@Composable
private fun StoreCard(store: Store, title: String, desc: String, bg: Color, accent: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreIcon(store, 52.dp, accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(desc, color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun sectionTitle(key: String) = when (key) {
    "specials" -> uiStr(R.string.l_st_specials)
    "top_sellers" -> uiStr(R.string.l_st_top_sellers)
    "new_releases" -> uiStr(R.string.l_st_new_releases)
    "free_now" -> uiStr(R.string.l_st_free_now)
    "free_to_play" -> uiStr(R.string.l_st_free_to_play)
    else -> key
}

private fun priceLabel(p: String) = if (p == "Free") uiStr(R.string.l_st_free) else p

@Composable
private fun StoreScreen(store: Store, onBack: () -> Unit) {
    var query by rememberSaveable(store) { mutableStateOf("") }
    var submitted by rememberSaveable(store) { mutableStateOf("") }
    var tab by rememberSaveable(store) { mutableStateOf(0) }
    var open by remember { mutableStateOf<StoreItem?>(null) }
    val featured by produceState<List<Pair<String, List<StoreItem>>>?>(null, store) {
        value = withContext(Dispatchers.IO) { if (store == Store.STEAM) SteamCatalog.featured() else EpicCatalog.featured() }
    }
    val results by produceState<List<StoreItem>?>(emptyList(), store, submitted) {
        if (submitted.isBlank()) { value = emptyList(); return@produceState }
        value = null
        value = withContext(Dispatchers.IO) { if (store == Store.STEAM) SteamCatalog.search(submitted) else EpicCatalog.search(submitted) }
    }

    if (tab == 1) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { StoreHeader(store, tab, { tab = it }, onBack) }
            if (store == Store.STEAM) com.winlator.cmod.store.steam.SteamLibraryScreen(Modifier.weight(1f))
            else com.winlator.cmod.store.epic.EpicLibraryScreen(Modifier.weight(1f))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StoreHeader(store, tab, { tab = it }, onBack) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(uiStr(R.string.l_st_search, store.title)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = "" }) { Icon(Icons.Outlined.Close, null) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            )
        }
        if (submitted.isNotBlank()) {
            val r = results
            when {
                r == null -> item { CircularProgressIndicator(Modifier.size(28.dp)) }
                r.isEmpty() -> item { Text(uiStr(R.string.l_st_nothing_found), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> items(r, key = { it.store.name + it.id }) { ResultRow(it) { open = it } }
            }
        } else {
            val f = featured
            when {
                f == null -> item { CircularProgressIndicator(Modifier.size(28.dp)) }
                f.isEmpty() -> item { Text(uiStr(R.string.l_st_offline), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> f.forEach { (key, list) ->
                    item(key = "h:$key") { Text(sectionTitle(key), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    item(key = "r:$key") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(list, key = { it.id }) { ShowcaseCard(it) { open = it } }
                        }
                    }
                }
            }
        }
    }
    open?.let { GameSheet(it) { open = null } }
}

@Composable
private fun StoreHeader(store: Store, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) }
            StoreIcon(store, 30.dp, styleOf(store).title)
            Spacer(Modifier.width(10.dp))
            Text(if (store == Store.STEAM) "STEAM" else store.title, style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (store == Store.STEAM) FontWeight.Black else FontWeight.Bold,
                letterSpacing = if (store == Store.STEAM) 3.sp else 0.sp, color = styleOf(store).title)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { onTab(0) }, label = { Text(uiStr(R.string.l_st_shop)) })
            FilterChip(selected = tab == 1, onClick = { onTab(1) }, label = { Text(uiStr(R.string.l_st_library)) })
        }
    }
}

@Composable
private fun ShowcaseCard(item: StoreItem, onClick: () -> Unit) {
    val shape = LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(14.dp)
    Column(Modifier.width(220.dp).clip(shape).clickable(onClick = onClick)) {
        RemoteImage(item.image, Modifier.fillMaxWidth().aspectRatio(460f / 215f).clip(shape))
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        PriceLine(item)
    }
}

@Composable
private fun ResultRow(item: StoreItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(item.image, Modifier.width(132.dp).aspectRatio(460f / 215f).clip(LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            PriceLine(item)
        }
    }
}

@Composable
private fun PriceLine(item: StoreItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.discountPercent > 0) {
            Text("-${item.discountPercent}%", color = Color(0xFFBEEE11), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFF4C6B22), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (item.price.isNotBlank()) Text(priceLabel(item.price), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameSheet(item: StoreItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<StoreDetails?>(null, item) {
        value = if (item.store == Store.STEAM) withContext(Dispatchers.IO) { SteamCatalog.details(item.id) } else null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        KeepDialogFullscreen()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteImage(details?.header ?: item.image, Modifier.fillMaxWidth().aspectRatio(460f / 215f).clip(RoundedCornerShape(14.dp)))
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            PriceLine(item)
            details?.let { d ->
                if (d.description.isNotBlank()) Text(d.description, style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(
                    d.developer.takeIf { it.isNotBlank() }?.let { uiStr(R.string.l_st_developer, it) },
                    d.releaseDate.takeIf { it.isNotBlank() }?.let { uiStr(R.string.l_st_release, it) },
                    d.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!d.windows) Text(uiStr(R.string.l_st_not_windows), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            StoreButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_st_open_page, item.store.title)) }
            Text(uiStr(if (item.store == Store.STEAM) R.string.l_sts_install_from_library else R.string.l_st_install_soon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The stores' own icons, taken from their sites (the site icon, else Google's copy of it) and kept
 * in files/store_icons so they show offline too. Until then the store's first letter.
 */
private object StoreIcons {
    private val urls = mapOf(
        Store.STEAM to listOf("https://store.steampowered.com/apple-touch-icon.png",
            "https://www.google.com/s2/favicons?domain=store.steampowered.com&sz=128"),
        Store.EPIC to listOf("https://store.epicgames.com/apple-touch-icon.png",
            "https://www.google.com/s2/favicons?domain=epicgames.com&sz=128"),
    )
    private val memory = HashMap<Store, Bitmap>()

    fun cached(store: Store): Bitmap? = synchronized(memory) { memory[store] }

    fun load(context: android.content.Context, store: Store): Bitmap? {
        cached(store)?.let { return it }
        val file = java.io.File(context.filesDir, "store_icons/${store.name}.png")
        var bmp = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
        if (bmp == null) {
            var best: Pair<ByteArray, Bitmap>? = null
            for (u in urls[store].orEmpty()) {
                val bytes = runCatching {
                    StoreNet.client.newCall(Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                }.getOrNull() ?: continue
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (best == null || b.width > best.second.width) best = bytes to b
                if (b.width >= 96) break
            }
            best?.let { (bytes, b) ->
                runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                bmp = b
            }
        }
        bmp?.let { synchronized(memory) { memory[store] = it } }
        return bmp
    }
}

@Composable
private fun StoreIcon(store: Store, size: androidx.compose.ui.unit.Dp, accent: Color) {
    val context = LocalContext.current
    val bitmap by produceState(StoreIcons.cached(store), store) {
        if (value == null) value = withContext(Dispatchers.IO) { StoreIcons.load(context, store) }
    }
    val shape = RoundedCornerShape(size * 0.26f)
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), store.title, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).background(accent.copy(alpha = .16f), shape), contentAlignment = Alignment.Center) {
            Text(store.title.take(1), color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Pictures of the stores, cached in memory (a few dozen headers). */
internal object ImageCache {
    val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String): Bitmap? {
        memory.get(url)?.let { return it }
        val bytes = runCatching {
            StoreNet.client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull() ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 480) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        memory.put(url, bmp)
        return bmp
    }
}

@Composable
internal fun RemoteImage(url: String?, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(url?.let { ImageCache.memory.get(it) }, url) {
        if (url != null && value == null) value = withContext(Dispatchers.IO) { ImageCache.load(url) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
