package com.winlator.cmod.store.steam

import com.winlator.cmod.ui.components.MotionSheet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.StatFs
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.winlator.cmod.R
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.store.RemoteImage
import com.winlator.cmod.store.StoreButton
import com.winlator.cmod.store.StoreSecondaryButton
import com.winlator.cmod.store.LocalStoreStyle
import com.winlator.cmod.store.StoreNet
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

internal fun size(b: Long) = StringUtils.formatBytes(b)

internal fun freeBytes(root: File = SteamInstaller.root()): Long = runCatching {
    val r = root.apply { mkdirs() }
    StatFs(r.absolutePath).availableBytes
}.getOrDefault(-1L)

/** Steam's "Library" tab: sign-in, then the account's games with install / pause / remove / add to a container. */
@Composable
fun SteamLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by SteamSession.status.collectAsState()
    val auth by SteamSession.auth.collectAsState()
    var signedIn by remember { mutableStateOf(SteamSession.signedIn(context)) }
    LaunchedEffect(Unit) {
        SteamSession.start(context)
        withContext(Dispatchers.IO) {
            SteamInstaller.load(context)
            SteamLibrary.loadCached(context)
        }
    }
    // A finished sign-in clears the auth state; a rejected token clears the saved one.
    LaunchedEffect(auth, status) { signedIn = SteamSession.signedIn(context) }

    Box(modifier.fillMaxSize()) {
        if (!signedIn) SignIn(auth) else Library(status) {
            SteamSession.signOut()
            SteamLibrary.clear(context)
            signedIn = false
        }
    }
}

@Composable
private fun SignIn(auth: SteamSession.Auth) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(0) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiStr(R.string.l_sts_sign_in_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(uiStr(R.string.l_sts_sign_in_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { SteamSession.cancelAuth(); mode = 0 }, label = { Text(uiStr(R.string.l_sts_qr)) })
            FilterChip(selected = mode == 1, onClick = { SteamSession.cancelAuth(); mode = 1 }, label = { Text(uiStr(R.string.l_sts_password)) })
        }
        when (auth) {
            is SteamSession.Auth.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(uiStr(R.string.l_sts_connecting))
            }
            is SteamSession.Auth.Qr -> {
                val bmp = remember(auth.url) { qr(auth.url) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(bmp.asImageBitmap(), null, Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
                }
                Text(uiStr(R.string.l_sts_qr_hint), style = MaterialTheme.typography.bodyMedium)
                StoreSecondaryButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(auth.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_open_in_app)) }
                TextButton(onClick = { SteamSession.cancelAuth() }) { Text(uiStr(R.string.l_sts_cancel)) }
            }
            is SteamSession.Auth.Code -> {
                Text(if (auth.email != null) uiStr(R.string.l_sts_code_email, auth.email) else uiStr(R.string.l_sts_code_app),
                    style = MaterialTheme.typography.bodyMedium)
                if (auth.wrong) Text(uiStr(R.string.l_sts_code_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(code, { code = it.uppercase() }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoreButton(onClick = { SteamSession.submitCode(code); code = "" }, enabled = code.length >= 4) { Text(uiStr(R.string.l_sts_send)) }
                    TextButton(onClick = { SteamSession.cancelAuth() }) { Text(uiStr(R.string.l_sts_cancel)) }
                }
            }
            is SteamSession.Auth.Confirm -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(uiStr(R.string.l_sts_confirm))
                }
                TextButton(onClick = { SteamSession.cancelAuth() }) { Text(uiStr(R.string.l_sts_cancel)) }
            }
            else -> {
                if (auth is SteamSession.Auth.Failed) Text(uiStr(R.string.l_sts_failed, auth.message), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                if (mode == 0) {
                    StoreButton(onClick = { SteamSession.startQr(context) }, modifier = Modifier.fillMaxWidth(), positive = true) { Text(uiStr(R.string.l_sts_show_qr)) }
                } else {
                    OutlinedTextField(user, { user = it.trim() }, label = { Text(uiStr(R.string.l_sts_account)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(uiStr(R.string.l_sts_password_field)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    StoreButton(onClick = { SteamSession.startPassword(context, user, pass); pass = "" }, enabled = user.isNotBlank() && pass.isNotEmpty(), positive = true,
                        modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_sign_in)) }
                }
            }
        }
    }
}

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

@Composable
private fun Library(status: SteamSession.Status, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by SteamLibrary.apps.collectAsState()
    val loading by SteamLibrary.loading.collectAsState()
    val jobs by SteamInstaller.jobs.collectAsState()
    val installed by SteamInstaller.installed.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    var onlyInstalled by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<SteamApp?>(null) }
    var askSignOut by remember { mutableStateOf(false) }
    val online = status.state == SteamSession.State.ONLINE
    LaunchedEffect(online) {
        if (online && SteamLibrary.apps.value.isNullOrEmpty()) withContext(Dispatchers.IO) { SteamLibrary.refresh(context) }
    }

    val shown = apps.orEmpty().filter { a ->
        (filter.isBlank() || a.name.contains(filter.trim(), ignoreCase = true)) &&
            (!onlyInstalled || installed.containsKey(a.id) || jobs.containsKey(a.id))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(when (status.state) {
                    SteamSession.State.ONLINE -> Color(0xFF5BC35B)
                    SteamSession.State.OFFLINE -> MaterialTheme.colorScheme.error
                    else -> Color(0xFFE0A030)
                }))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(SteamSession.account.ifBlank { "Steam" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(when (status.state) {
                        SteamSession.State.ONLINE -> uiStr(R.string.l_sts_online)
                        SteamSession.State.OFFLINE -> uiStr(R.string.l_sts_offline)
                        else -> uiStr(R.string.l_sts_connecting)
                    } + (apps?.let { " · " + uiStr(R.string.l_sts_count, it.size) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch(Dispatchers.IO) { SteamLibrary.refresh(context) } }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, uiStr(R.string.l_sts_refresh))
                }
                IconButton(onClick = { askSignOut = true }) { Icon(Icons.Outlined.Logout, uiStr(R.string.l_sts_sign_out)) }
            }
        }
        item {
            OutlinedTextField(filter, { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(uiStr(R.string.l_sts_filter)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, null) } })
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = onlyInstalled, onClick = { onlyInstalled = !onlyInstalled }, label = { Text(uiStr(R.string.l_sts_installed_only)) })
                Spacer(Modifier.weight(1f))
                var push by remember { mutableStateOf(SteamSync.pushAchievements(context)) }
                Text(uiStr(R.string.l_sts_push_ach), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Switch(checked = push, onCheckedChange = { push = it; SteamSync.setPushAchievements(context, it) })
            }
            Text(uiStr(R.string.l_sts_sync_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            apps == null && loading -> item { Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(uiStr(R.string.l_sts_loading))
            } }
            apps == null && SteamLibrary.lastError != null -> item {
                Text(uiStr(R.string.l_sts_load_failed, SteamLibrary.lastError ?: ""), color = MaterialTheme.colorScheme.error)
            }
            shown.isEmpty() && apps != null -> item { Text(uiStr(R.string.l_sts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(shown, key = { it.id }) { a -> GameRow(a, jobs[a.id], installed[a.id]) { open = a } }
        }
    }
    open?.let { a -> GameSheet(a, jobs[a.id], installed[a.id]) { open = null } }
    if (askSignOut) AlertDialog(
        onDismissRequest = { askSignOut = false },
        title = { Text(uiStr(R.string.l_sts_sign_out)) },
        confirmButton = { TextButton(onClick = { askSignOut = false; onSignOut() }) { Text(uiStr(R.string.l_sts_sign_out)) } },
        dismissButton = { TextButton(onClick = { askSignOut = false }) { Text(uiStr(R.string.l_sts_cancel)) } },
    )
}

@Composable
internal fun JobLine(j: SteamInstaller.Job) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (j.phase) {
            SteamInstaller.Phase.QUEUED -> Text(uiStr(R.string.l_sts_queued), style = MaterialTheme.typography.bodySmall)
            SteamInstaller.Phase.PREPARING -> {
                Text(uiStr(R.string.l_sts_preparing), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SteamInstaller.Phase.DOWNLOADING -> {
                if (j.total > 0) LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(uiStr(R.string.l_sts_progress, size(j.done), if (j.total > 0) size(j.total) else "?", size(j.speed)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SteamInstaller.Phase.PAUSED -> {
                if (j.total > 0) LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                Text(uiStr(R.string.l_sts_paused), style = MaterialTheme.typography.bodySmall)
            }
            SteamInstaller.Phase.RECONNECTING -> {
                if (j.total > 0) LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(uiStr(R.string.l_sts_reconnecting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SteamInstaller.Phase.FAILED -> Text(uiStr(R.string.l_sts_failed_dl, j.error ?: ""), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            SteamInstaller.Phase.DONE -> Text(uiStr(R.string.l_sts_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GameRow(a: SteamApp, job: SteamInstaller.Job?, inst: SteamInstaller.Installed?, onClick: () -> Unit) {
    val card = LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(card).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(a.header, Modifier.width(128.dp).aspectRatio(460f / 215f).clip(card))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            when {
                job != null && job.phase != SteamInstaller.Phase.DONE -> JobLine(job)
                inst != null -> Text(uiStr(R.string.l_sts_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                a.sizeBytes > 0 -> Text(size(a.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameSheet(a: SteamApp, job: SteamInstaller.Job?, inst: SteamInstaller.Installed?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickContainer by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    val free = remember { freeBytes() }
    MotionSheet(onDismissRequest = onDismiss) { _ ->
        KeepDialogFullscreen()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteImage(a.header, Modifier.fillMaxWidth().aspectRatio(460f / 215f).clip(LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(14.dp)))
            Text(a.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (a.sizeBytes > 0) Text(size(a.sizeBytes) + if (free >= 0) "  ·  " + uiStr(R.string.l_sts_free_space, size(free)) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val active = job?.active == true
            when {
                active -> {
                    JobLine(job!!)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoreButton(onClick = { SteamInstaller.pause(a.id) }) { Text(uiStr(R.string.l_sts_pause)) }
                        StoreSecondaryButton(onClick = { SteamInstaller.cancel(context, a) }) { Text(uiStr(R.string.l_sts_cancel_dl)) }
                    }
                }
                inst != null -> {
                    Text(uiStr(R.string.l_sts_folder, inst.dir), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StoreButton(positive = true, onClick = {
                        if (SteamInstaller.containers(context).isEmpty()) toast(context, uiStr(R.string.l_sts_no_containers))
                        else pickContainer = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_add_to_library)) }
                    StoreSecondaryButton(onClick = { askRemove = true }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_uninstall)) }
                    GoldbergRow(inst)
                    com.winlator.cmod.store.StoreLaunchSwitch(com.winlator.cmod.store.StoreLaunchPrefs.steam(a.id),
                        uiStr(R.string.l_sts_launch_sync), uiStr(R.string.l_sts_launch_sync_hint)) { on ->
                        if (!on) Thread { Goldberg.settingsDirs(inst).forEach { java.io.File(it, "achievements.json").delete() } }.start()
                    }
                }
                else -> {
                    if (job != null) JobLine(job)
                    val noSpace = a.sizeBytes > 0 && free in 0 until a.sizeBytes
                    if (noSpace) Text(uiStr(R.string.l_sts_no_space), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    StoreButton(onClick = { SteamInstaller.install(context, a) }, modifier = Modifier.fillMaxWidth(), enabled = !noSpace, positive = true) {
                        Text(when {
                            job != null && job.phase != SteamInstaller.Phase.DONE -> uiStr(R.string.l_sts_resume)
                            a.sizeBytes > 0 -> uiStr(R.string.l_sts_install_size, size(a.sizeBytes))
                            else -> uiStr(R.string.l_sts_install)
                        })
                    }
                    if (job != null && job.phase != SteamInstaller.Phase.DONE)
                        StoreSecondaryButton(onClick = { SteamInstaller.cancel(context, a) }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.l_sts_cancel_dl)) }
                    Text(uiStr(R.string.l_sts_folder, File(SteamInstaller.root(), a.installDir).path), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AchievementsSection(a.id)
        }
    }
    if (pickContainer) {
        val list = remember { SteamInstaller.containers(context) }
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
                                    val e = SteamInstaller.addShortcut(context, c, a.id)
                                    if (e != null) runCatching {
                                        StoreNet.client.newCall(Request.Builder().url(a.portrait).build()).execute().use { r ->
                                            if (r.isSuccessful) r.body?.bytes()?.let { SteamInstaller.saveCover(a.id, it) }
                                        }
                                    }
                                    e
                                }
                                if (exe == null) toast(context, uiStr(R.string.l_sts_no_exe)) else {
                                    toast(context, uiStr(R.string.l_sts_added, c.name))
                                    (context as? Activity)?.let { act ->
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
        text = { Text(uiStr(R.string.l_sts_uninstall_q, a.name, if (a.sizeBytes > 0) size(a.sizeBytes) else "?")) },
        confirmButton = {
            TextButton(onClick = {
                askRemove = false
                scope.launch { withContext(Dispatchers.IO) { SteamInstaller.uninstall(context, a.id) }; onDismiss() }
            }) { Text(uiStr(R.string.l_sts_uninstall), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { askRemove = false }) { Text(uiStr(R.string.l_sts_cancel)) } },
    )
}

/** The game's achievements and how many of them are earned on the profile. */
@Composable
private fun AchievementsSection(appId: Int) {
    val context = LocalContext.current
    val list by produceState<List<SteamSync.Achievement>?>(null, appId) { value = withContext(Dispatchers.IO) { SteamSync.achievements(context, appId) } ?: emptyList() }
    var all by remember(appId) { mutableStateOf(false) }
    val l = list
    Spacer(Modifier.height(6.dp))
    if (l == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(uiStr(R.string.l_sts_ach_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (l.isEmpty()) return
    val got = l.count { it.unlocked }
    Text(uiStr(R.string.l_sts_ach_title, got, l.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(progress = { got.toFloat() / l.size }, modifier = Modifier.fillMaxWidth())
    val fmt = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    (if (all) l else l.take(6)).forEach { ach ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(ach.icon, Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val secret = ach.hidden && !ach.unlocked
                Text(if (secret) uiStr(R.string.l_sts_ach_hidden) else ach.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, color = if (ach.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!secret && ach.description.isNotBlank()) Text(ach.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ach.unlocked && ach.time > 0) Text(uiStr(R.string.l_sts_ach_when, fmt.format(java.util.Date(ach.time * 1000))),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (l.size > 6) TextButton(onClick = { all = !all }) {
        Text(if (all) uiStr(R.string.l_sts_ach_less) else uiStr(R.string.l_sts_ach_all, l.size))
    }
}

/** The Steam emulator switch of an installed game. */
@Composable
private fun GoldbergRow(inst: SteamInstaller.Installed) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // null = still checking; first = uses Steam's API, second = emulator applied
    var state by remember(inst.appId) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var busy by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(inst.appId) { state = withContext(Dispatchers.IO) { runCatching { Goldberg.state(inst) }.getOrDefault(false to false) } }
    Surface(shape = LocalStoreStyle.current?.cardShape ?: RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiStr(R.string.l_sts_goldberg), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val st = state
                if (st == null || busy != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (st.first) Switch(checked = st.second, onCheckedChange = { on ->
                    busy = 0f
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            if (on) Goldberg.ensure(context) { p -> busy = p } && Goldberg.apply(context, inst) == Goldberg.Result.APPLIED
                            else { Goldberg.restore(inst); true }
                        }
                        if (!ok) toast(context, uiStr(R.string.l_sts_goldberg_failed))
                        state = withContext(Dispatchers.IO) { runCatching { Goldberg.state(inst) }.getOrDefault(false to false) }
                        busy = null
                    }
                })
            }
            val b = busy
            Text(when {
                b != null && b > 0f -> uiStr(R.string.l_sts_goldberg_dl, (b * 100).toInt())
                state?.first == false -> uiStr(R.string.l_sts_goldberg_none)
                else -> uiStr(R.string.l_sts_goldberg_hint)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
