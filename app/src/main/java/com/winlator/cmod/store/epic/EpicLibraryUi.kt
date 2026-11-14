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
                        if (g.developer.isNotBlank()) Text(g.developer, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        KeepDialogFullscreen()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteImage(g.wide ?: g.tall, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(14.dp)))
            Text(g.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (g.developer.isNotBlank()) Text(g.developer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(uiStr(R.string.l_ste_install_soon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
