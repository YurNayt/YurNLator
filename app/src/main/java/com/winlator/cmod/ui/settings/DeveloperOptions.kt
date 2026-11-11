package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.preference.PreferenceManager
import com.winlator.cmod.contents.RemoteDriverCatalog
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Hidden developer options: unlocked by tapping the version row in Settings five times.
 * For now: saving GPU drivers from every driver author to the phone's storage
 * (Download/YurNLator/drivers), for tools that load custom drivers themselves (a modded 3DMark…).
 */
object DeveloperOptions {
    private const val KEY = "yurn_developer_options"
    const val TAPS = 5

    fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()

    private const val KEY_BUNDLES = "yurn_bundle_finder"

    /** The bundle finder tab (rightmost in the bottom bar). */
    @JvmStatic
    fun bundleFinder(context: Context): Boolean =
        enabled(context) && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_BUNDLES, false)

    fun setBundleFinder(context: Context, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY_BUNDLES, on).apply()

    const val FOLDER = "Download/YurNLator/drivers"

    /** Streams [url] into Download/YurNLator/drivers/[name]; progress 0..1 (-1 = unknown size). Null on success, else why not. */
    fun saveDriver(context: Context, url: String, name: String, progress: (Float) -> Unit): String? {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body ?: return "empty response"
                if (!resp.isSuccessful) return "HTTP ${resp.code}"
                val total = body.contentLength()
                val (out, path) = open(context, name) ?: return "can't write to Download"
                out.use { o ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            progress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
                path
            }
        } catch (e: Exception) {
            "failed: " + (e.message ?: e.javaClass.simpleName)
        }.let { result ->
            if (result.startsWith("Download/")) { lastSaved = result; null } else result
        }
    }

    @Volatile var lastSaved: String = ""

    private fun open(context: Context, name: String): Pair<OutputStream, String>? {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = context.contentResolver.openOutputStream(uri) ?: return null
            out to "$FOLDER/$safe"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator/drivers").apply { mkdirs() }
            File(dir, safe).outputStream() to "$FOLDER/$safe"
        }
    }
}

/** Every downloadable driver, grouped by author, saved as a .zip to the phone's Download folder. */
@Composable
internal fun DriverSaverDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<RemoteDriverCatalog.Entry>?>(null) }
    var author by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val saved = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { runCatching { RemoteDriverCatalog.load(context, true) }.getOrDefault(emptyList()) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KeepDialogFullscreen()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, uiStr(R.string.l_lib_back)) }
                    Column(Modifier.weight(1f)) {
                        Text(uiStr(R.string.l_dev_save_gpu_drivers), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(uiStr(R.string.l_dev_to_1_s_not_installed, DeveloperOptions.FOLDER), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.5f))
                val list = entries
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                if (list.isEmpty()) {
                    Text(uiStr(R.string.l_dev_couldn_t_load_the_driver_lists), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                val authors = remember(list) { list.map { it.repository }.distinct() }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = author == null, onClick = { author = null }, label = { Text(uiStr(R.string.l_dev_all_1_s, list.size)) }) }
                    items(authors) { a -> FilterChip(selected = author == a, onClick = { author = a }, label = { Text(a) }) }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Turnip 26, v805, Mesa…") })
                val shown = list.filter { (author == null || it.repository == author) && (query.isBlank() || it.name.contains(query, true)) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.url }) { e ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(e.repository + " · " + e.url.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    when {
                                        e.url in progress -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                        saved[e.url] == true -> Text(uiStr(R.string.l_dev_saved), color = MaterialTheme.colorScheme.primary)
                                        else -> OutlinedButton(onClick = {
                                            progress[e.url] = 0f
                                            scope.launch {
                                                val fileName = e.url.substringAfterLast('/').substringBefore('?').ifBlank { e.name }
                                                val err = withContext(Dispatchers.IO) {
                                                    DeveloperOptions.saveDriver(context, e.url, fileName) { f ->
                                                        scope.launch { if (e.url in progress) progress[e.url] = f }
                                                    }
                                                }
                                                progress.remove(e.url)
                                                if (err == null) {
                                                    saved[e.url] = true
                                                    Toast.makeText(context, uiStr(R.string.l_dev_saved_1_s, DeveloperOptions.lastSaved), Toast.LENGTH_SHORT).show()
                                                } else Toast.makeText(context, "${e.name}: $err", Toast.LENGTH_LONG).show()
                                            }
                                        }) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiStr(R.string.l_set_save)) }
                                    }
                                }
                                progress[e.url]?.let { f ->
                                    Spacer(Modifier.height(8.dp))
                                    if (f < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
