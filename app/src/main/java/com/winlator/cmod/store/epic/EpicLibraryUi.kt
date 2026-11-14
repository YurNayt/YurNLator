package com.winlator.cmod.store.epic

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.store.LocalStoreStyle
import com.winlator.cmod.store.RemoteImage
import com.winlator.cmod.store.StoreButton
import com.winlator.cmod.store.StoreNet
import com.winlator.cmod.store.StoreSecondaryButton
import com.winlator.cmod.store.StoreShortcuts
import com.winlator.cmod.store.steam.JobLine
import com.winlator.cmod.store.steam.SteamInstaller
import com.winlator.cmod.store.steam.freeBytes
import com.winlator.cmod.store.steam.size
import okhttp3.Request
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Epic's "Library" tab: sign-in on Epic's page, then the account's games. Installing comes next. */
@Composable
fun EpicLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name by EpicAccount.name.collectAsState()
    LaunchedEffect(Unit) {
        EpicAccount.load(context)
        withContext(Dispatchers.IO) { EpicLibrary.loadCached(context) }
    }
    Box(modifier.fillMaxSize()) {
        if (name == null) SignIn() else Library(name!!)
    }
}

@Composable
private fun SignIn() {
    var web by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiStr(R.string.l_ste_sign_in_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(uiStr(R.string.l_ste_sign_in_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StoreButton(onClick = { web = true }, modifier = Modifier.fillMaxWidth(), positive = true) { Text(uiStr(R.string.l_ste_sign_in)) }
    }
    if (web) LoginDialog { web = false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KeepDialogFullscreen()
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, null) }
                Text(uiStr(R.string.l_ste_sign_in_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = EpicAccount.USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (busy || url == null || !url.startsWith(EpicAccount.REDIRECT)) return
                            busy = true
                            // The redirect page is JSON: {"authorizationCode": "...", ...}
                            view.evaluateJavascript("(function(){return document.body.innerText;})()") { raw ->
                                val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw ?: ""
                                val code = runCatching { JSONObject(text).optString("authorizationCode") }.getOrNull().orEmpty()
                                if (code.isEmpty()) { busy = false; view.loadUrl(EpicAccount.LOGIN_URL); return@evaluateJavascript }
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { EpicAccount.signIn(context, code) }
                                    busy = false
                                    if (ok) {
                                        onClose()
                                        launch(Dispatchers.IO) { EpicLibrary.refresh(context) }
                                    } else {
                                        Toast.makeText(context, uiStr(R.string.l_ste_failed), Toast.LENGTH_LONG).show()
                                        view.loadUrl(EpicAccount.LOGIN_URL)
                                    }
                                }
                            }
                        }
                    }
                    loadUrl(EpicAccount.LOGIN_URL)
                }
            })
        }
    }
}

@Composable
private fun Library(name: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val games by EpicLibrary.games.collectAsState()
    val loading by EpicLibrary.loading.collectAsState()
    val jobs by EpicInstaller.jobs.collectAsState()
    val installed by EpicInstaller.installed.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { EpicInstaller.load(context) } }
    var filter by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<EpicGame?>(null) }
    var askSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(name) { if (EpicLibrary.games.value.isNullOrEmpty()) withContext(Dispatchers.IO) { EpicLibrary.refresh(context) } }
    val shown = games.orEmpty().filter { filter.isBlank() || it.title.contains(filter.trim(), true) }
    val card = LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(12.dp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    games?.let { Text(uiStr(R.string.l_sts_count, it.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = { scope.launch(Dispatchers.IO) { EpicLibrary.refresh(context) } }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, uiStr(R.string.l_sts_refresh))
                }
                IconButton(onClick = { askSignOut = true }) { Icon(Icons.Outlined.Logout, uiStr(R.string.l_sts_sign_out)) }
            }
        }
        item {
            OutlinedTextField(filter, { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(uiStr(R.string.l_sts_filter)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, null) } })
        }
        when {
            games == null && loading -> item { Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(uiStr(R.string.l_sts_loading))
            } }
            games == null && EpicLibrary.lastError != null -> item {
                Text(uiStr(R.string.l_sts_load_failed, EpicLibrary.lastError ?: ""), color = MaterialTheme.colorScheme.error)
            }
            games != null && shown.isEmpty() -> item { Text(uiStr(R.string.l_sts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(shown, key = { it.appName }) { g ->
                Row(Modifier.fillMaxWidth().clip(card).clickable { open = g }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(g.wide ?: g.tall, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(card))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val j = jobs[g.appName]
                        when {
                            j != null && j.phase != SteamInstaller.Phase.DONE -> JobLine(j)
                            installed.containsKey(g.appName) -> Text(uiStr(R.string.l_sts_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            g.developer.isNotBlank() -> Text(g.developer, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    open?.let { GameSheet(it) { open = null } }
    if (askSignOut) AlertDialog(
        onDismissRequest = { askSignOut = false },
        title = { Text(uiStr(R.string.l_sts_sign_out)) },
        confirmButton = { TextButton(onClick = {
            askSignOut = false
            EpicAccount.signOut(context)
            EpicLibrary.clear(context)
        }) { Text(uiStr(R.string.l_sts_sign_out)) } },
        dismissButton = { TextButton(onClick = { askSignOut = false }) { Text(uiStr(R.string.l_sts_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameSheet(g: EpicGame, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by EpicInstaller.jobs.collectAsState()
    val installed by EpicInstaller.installed.collectAsState()
    val job = jobs[g.appName]
    val inst = installed[g.appName]
    var pickContainer by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    val free = remember { freeBytes(EpicInstaller.root()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        KeepDialogFullscreen()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteImage(g.wide ?: g.tall, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(14.dp)))
            Text(g.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (g.developer.isNotBlank()) Text(g.developer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                job?.active == true -> {
                    JobLine(job)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoreButton(onClick = { EpicInstaller.pause(g.appName) }) { Text(uiStr(R.string.l_sts_pause)) }
                        StoreSecondaryButton(onClick = { EpicInstaller.cancel(g) }) { Text(uiStr(R.string.l_sts_cancel_dl)) }
                    }
                }
                inst != null -> {
                    Text(uiStr(R.string.l_sts_folder, inst.dir), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StoreButton(positive = true, onClick = {
                        if (StoreShortcuts.containers(context).isEmpty()) Toast.makeText(context, uiStr(R.string.l_sts_no_containers), Toast.LENGTH_LONG).show()
                        else pickContainer = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_add_to_library)) }
                    StoreSecondaryButton(onClick = { askRemove = true }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_uninstall)) }
                    com.winlator.cmod.store.StoreLaunchSwitch(com.winlator.cmod.store.StoreLaunchPrefs.epic(g.appName),
                        uiStr(R.string.l_ste_launch_sign_in), uiStr(R.string.l_ste_launch_sign_in_hint))
                    val cloud by androidx.compose.runtime.produceState<String?>(null, g.appName) {
                        value = withContext(Dispatchers.IO) { EpicSync.cloudFolder(context, g.appName) }
                    }
                    cloud?.let { f ->
                        Text(if (f.isEmpty()) uiStr(R.string.l_ste_cloud_none) else uiStr(R.string.l_ste_cloud_folder, f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    if (job != null) JobLine(job)
                    if (free >= 0) Text(uiStr(R.string.l_sts_free_space, size(free)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StoreButton(positive = true, onClick = { EpicInstaller.install(context, g) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (job != null && job.phase != SteamInstaller.Phase.DONE) uiStr(R.string.l_sts_resume) else uiStr(R.string.l_sts_install))
                    }
                    if (job != null && job.phase != SteamInstaller.Phase.DONE)
                        StoreSecondaryButton(onClick = { EpicInstaller.cancel(g) }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_cancel_dl)) }
                    Text(uiStr(R.string.l_sts_folder, EpicInstaller.dirFor(g).path), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (pickContainer) {
        val list = remember { StoreShortcuts.containers(context) }
        AlertDialog(
            onDismissRequest = { pickContainer = false },
            title = { Text(uiStr(R.string.l_sts_pick_container)) },
            text = {
                Column {
                    list.forEach { c ->
                        Text(c.name, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            pickContainer = false
                            scope.launch {
                                val exe = withContext(Dispatchers.IO) {
                                    val e = EpicInstaller.addShortcut(context, c, g.appName)
                                    if (e != null) (g.tall ?: g.wide)?.let { url ->
                                        runCatching {
                                            StoreNet.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                                if (r.isSuccessful) r.body?.bytes()?.let { StoreShortcuts.saveCover(g.title, it) }
                                            }
                                        }
                                    }
                                    e
                                }
                                if (exe == null) Toast.makeText(context, uiStr(R.string.l_sts_no_exe), Toast.LENGTH_LONG).show() else {
                                    Toast.makeText(context, uiStr(R.string.l_sts_added, c.name), Toast.LENGTH_LONG).show()
                                    (context as? android.app.Activity)?.let { act ->
                                        runCatching { com.winlator.cmod.ui.components.WindowsComponentsDialog.recommendAfterAdding(act, c, exe) }
                                    }
                                }
                            }
                        }.padding(vertical = 12.dp, horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickContainer = false }) { Text(uiStr(R.string.l_sts_cancel)) } },
        )
    }
    if (askRemove && inst != null) AlertDialog(
        onDismissRequest = { askRemove = false },
        title = { Text(uiStr(R.string.l_sts_uninstall)) },
        text = { Text(uiStr(R.string.l_ste_uninstall_q, g.title)) },
        confirmButton = {
            TextButton(onClick = {
                askRemove = false
                scope.launch { withContext(Dispatchers.IO) { EpicInstaller.uninstall(context, g.appName) }; onDismiss() }
            }) { Text(uiStr(R.string.l_sts_uninstall), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { askRemove = false }) { Text(uiStr(R.string.l_sts_cancel)) } },
    )
}
