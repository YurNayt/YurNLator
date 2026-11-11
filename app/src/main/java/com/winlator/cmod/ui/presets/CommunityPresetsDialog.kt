package com.winlator.cmod.ui.presets

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.winlator.cmod.community.CommunityPreset
import com.winlator.cmod.community.PresetApply
import com.winlator.cmod.community.PresetHub
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Community presets for one game: browse what others shared, apply it (missing components are
 * downloaded), undo, and share this game's own settings with everyone.
 */
object CommunityPresetsDialog {
    @JvmStatic
    fun show(context: Context, shortcut: Shortcut) {
        val dialog = ComponentDialog(context)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            decorView.setPadding(0, 0, 0, 0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        dialog.setContentView(ComposeView(context).apply {
            setContent { WinZTheme { PresetsScreen(shortcut, dialog::dismiss) } }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private sealed class Page {
    object List : Page()
    data class Detail(val entry: PresetHub.IndexEntry?, val local: CommunityPreset?) : Page()
    object Share : Page()
}

private fun date(sec: Long): String =
    if (sec <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(sec * 1000))

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, uiStr(R.string.l_pre_no_browser_to_open_the_link), Toast.LENGTH_LONG).show() }
}

private fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, uiStr(R.string.l_pre_share_preset)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun PresetsScreen(shortcut: Shortcut, close: () -> Unit) {
    KeepDialogFullscreen()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Page>(Page.List) }
    var all by remember { mutableStateOf<List<PresetHub.IndexEntry>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val soc = remember { CommunityPreset.soc() }
    val gpu = remember { mutableStateOf("") }
    var social by remember { mutableStateOf<Map<String, PresetHub.Social>>(emptyMap()) }

    LaunchedEffect(reload) {
        loading = true
        val list = withContext(Dispatchers.IO) {
            if (gpu.value.isEmpty()) gpu.value = CommunityPreset.gpuName(context)
            PresetHub.index(context, refresh = reload > 0)
        }
        offline = list == null
        all = list ?: all ?: emptyList()
        loading = false
        all?.takeIf { it.isNotEmpty() }?.let { entries ->
            social = withContext(Dispatchers.IO) { PresetHub.social(context, entries.map { it.id }) } ?: social
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                    .getOrNull()?.let(PresetHub::importText)
            }
            if (p == null) Toast.makeText(context, uiStr(R.string.l_pre_that_file_isn_t_a_yurnlator), Toast.LENGTH_LONG).show()
            else page = Page.Detail(null, p)
        }
    }

    BackHandler { if (page is Page.List) close() else page = Page.List }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page is Page.List) close() else page = Page.List }) { Icon(Icons.Outlined.ArrowBack, uiStr(R.string.l_lib_back)) }
            Column(Modifier.weight(1f)) {
                Text(
                    when (page) { is Page.Share -> uiStr(R.string.l_pre_share_my_settings); is Page.Detail -> uiStr(R.string.l_pre_preset); else -> uiStr(R.string.l_lib_community_presets) },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
                )
                Text(shortcut.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (page is Page.List) {
                IconButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Outlined.FileOpen, uiStr(R.string.l_pre_import_file)) }
                IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.l_pre_refresh)) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.5f))
        Box(Modifier.fillMaxSize()) {
            when (val p = page) {
                is Page.List -> PresetList(shortcut, all, social, loading, offline, soc, gpu.value,
                    open = { page = Page.Detail(it, null) }, share = { page = Page.Share })
                is Page.Detail -> PresetDetail(shortcut, p.entry, p.local, p.entry?.let { social[it.id] },
                    onSocial = { id, v -> social = social + (id to v) }, done = { page = Page.List; reload++ })
                is Page.Share -> ShareForm(shortcut, done = { page = Page.List; reload++ })
            }
        }
    }
}

@Composable
private fun PresetList(
    shortcut: Shortcut, all: List<PresetHub.IndexEntry>?, social: Map<String, PresetHub.Social>, loading: Boolean, offline: Boolean,
    soc: String, gpu: String, open: (PresetHub.IndexEntry) -> Unit, share: () -> Unit,
) {
    val context = LocalContext.current
    var everything by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var undoState by remember { mutableIntStateOf(0) }
    val mine = remember(all) { PresetHub.myIds(context) }
    val exe = remember { CommunityPreset.exeOf(shortcut) }
    val list = remember(all, everything, query, gpu) {
        val src = all.orEmpty()
        val base = if (everything) src.filter {
            query.isBlank() || it.game.contains(query, true) || it.title.contains(query, true) || it.exe.contains(query, true)
        } else PresetHub.forGame(src, shortcut.name, exe)
        PresetHub.rank(base, soc, gpu)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(onClick = share, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(uiStr(R.string.l_pre_share_my_settings_for_this_game), fontWeight = FontWeight.SemiBold)
            }
        }
        if (undoState >= 0 && PresetApply.canUndo(shortcut)) item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(.35f)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiStr(R.string.l_pre_preset_applied), fontWeight = FontWeight.SemiBold)
                        Text(PresetApply.appliedLabel(shortcut), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        if (PresetApply.undo(shortcut)) Toast.makeText(context, uiStr(R.string.l_pre_your_previous_settings_are_back), Toast.LENGTH_SHORT).show()
                        undoState++
                    }) { Text(uiStr(R.string.l_pre_undo)) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !everything, onClick = { everything = false }, label = { Text(uiStr(R.string.l_pre_this_game)) })
                FilterChip(selected = everything, onClick = { everything = true }, label = { Text(uiStr(R.string.l_pre_all_games)) })
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        if (everything) item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text(uiStr(R.string.l_pre_game_preset_or_exe)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
        }
        if (offline) item {
            Text(uiStr(if (!all.isNullOrEmpty()) R.string.l_pre_couldn_t_reach_the_library_showing else R.string.l_pre_offline_nocopy),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!loading && list.isEmpty()) item {
            Text(
                if (everything) uiStr(R.string.l_pre_nothing_found) else uiStr(R.string.l_pre_no_presets_for_this_game_yet),
                Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(list, key = { it.id }) { e ->
            PresetCard(e, sameChip = soc.isNotBlank() && e.soc.equals(soc, true), sameGpu = gpu.isNotBlank() && e.gpu.equals(gpu, true),
                mine = e.id in mine, showGame = everything, social = social[e.id]) { open(e) }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(.18f), contentColor = color) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PresetCard(e: PresetHub.IndexEntry, sameChip: Boolean, sameGpu: Boolean, mine: Boolean, showGame: Boolean, social: PresetHub.Social?, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.title.ifBlank { uiStr(R.string.l_pre_preset) }, Modifier.weight(1f, false), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sameChip) Badge(uiStr(R.string.l_pre_your_chip), MaterialTheme.colorScheme.primary)
                else if (sameGpu) Badge(uiStr(R.string.l_pre_your_gpu), MaterialTheme.colorScheme.primary)
                if (mine) Badge(uiStr(R.string.l_pre_mine), MaterialTheme.colorScheme.tertiary)
            }
            if (showGame) Text(e.game, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(e.author.ifBlank { "anonymous" }, e.device, e.hardware).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (social?.liked == true) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null, Modifier.size(14.dp),
                    tint = if (social?.liked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" ${e.likes + (social?.likes ?: 0)}   ", style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" ${e.comments + (social?.comments ?: 0)}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(listOf(e.appVersion, date(e.created)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PresetDetail(
    shortcut: Shortcut, entry: PresetHub.IndexEntry?, local: CommunityPreset?, social: PresetHub.Social?,
    onSocial: (String, PresetHub.Social) -> Unit, done: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(local) }
    var failed by remember { mutableStateOf(false) }
    var reqs by remember { mutableStateOf<List<PresetApply.Requirement>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<List<String>?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var hasService by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<List<PresetHub.Comment>?>(null) }

    LaunchedEffect(entry) {
        if (entry != null) {
            hasService = withContext(Dispatchers.IO) { PresetHub.serviceUrl(context) } != null
            if (hasService) comments = withContext(Dispatchers.IO) { PresetHub.comments(context, entry.id) }
        }
        if (preset == null && entry != null) {
            preset = withContext(Dispatchers.IO) { PresetHub.fetch(context, entry) }
            failed = preset == null
        }
        preset?.let { p -> reqs = runCatching { PresetApply.check(context, shortcut, p) }.getOrDefault(emptyList()) }
    }

    val p = preset
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) Text(uiStr(R.string.l_pre_couldn_t_download_this_preset), color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator()
        }
        return
    }
    val otherGame = CommunityPreset.gameKey(p.game) != CommunityPreset.gameKey(shortcut.name) &&
        (p.exe.isBlank() || p.exe != CommunityPreset.exeOf(shortcut))
    val mine = PresetHub.myToken(context, p.id) != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.title.ifBlank { uiStr(R.string.l_pre_preset) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(p.game + if (p.exe.isNotBlank()) " · ${p.exe}" else "", style = MaterialTheme.typography.bodyMedium)
            Text(listOf(uiStr(R.string.l_pre_by_1_s, p.author.ifBlank { uiStr(R.string.l_pre_anonymous) }), p.deviceModel, p.hardware, "Android API ${p.android}".takeIf { p.android > 0 }, p.appVersion, date(p.created))
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (otherGame) Text(uiStr(R.string.l_pre_made_for_another_game_apply_it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (p.notes.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(p.notes, Modifier.fillMaxWidth().padding(12.dp))
        }

        Text(uiStr(R.string.l_pre_what_it_needs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val r = reqs
        if (r == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else r.forEach { q ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${q.what}: ${q.wanted}", style = MaterialTheme.typography.bodyMedium)
                    if (q.note.isNotBlank()) Text(q.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (q.state) {
                    PresetApply.State.INSTALLED -> Badge(uiStr(R.string.l_pre_installed), Color(0xFF3FB950))
                    PresetApply.State.DOWNLOAD -> Badge(uiStr(R.string.l_pre_will_download), MaterialTheme.colorScheme.primary)
                    PresetApply.State.MISSING -> Badge(uiStr(R.string.l_pre_not_available), MaterialTheme.colorScheme.error)
                    PresetApply.State.KEEP -> Badge(uiStr(R.string.l_pre_yours_kept), MaterialTheme.colorScheme.onSurfaceVariant)
                    PresetApply.State.INFO -> {}
                }
            }
        }

        busy?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(msg)
            }
        }
        result?.let { lines ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(.35f)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) { lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = busy == null && r != null, onClick = {
                scope.launch {
                    busy = uiStr(R.string.l_pre_preparing)
                    result = runCatching {
                        PresetApply.apply(context, shortcut, p) { msg -> scope.launch { busy = msg } }
                    }.getOrElse { listOf(uiStr(R.string.l_pre_failed) + (it.message ?: it.javaClass.simpleName)) }
                    busy = null
                    reqs = runCatching { PresetApply.check(context, shortcut, p) }.getOrNull() ?: reqs
                    Toast.makeText(context, uiStr(R.string.l_pre_preset_applied_to_1_s, shortcut.name), Toast.LENGTH_SHORT).show()
                }
            }) { Text(if (result == null) uiStr(R.string.l_pre_apply_to_this_game) else uiStr(R.string.l_pre_apply_again)) }
            if (entry != null && hasService) {
                val liked = social?.liked == true
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { PresetHub.toggleLike(context, entry.id) }
                        if (r == null) Toast.makeText(context, uiStr(R.string.l_pre_no_connection), Toast.LENGTH_SHORT).show()
                        else onSocial(entry.id, PresetHub.Social(r.first, social?.comments ?: comments?.size ?: 0, r.second))
                    }
                }) {
                    Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${entry.likes + (social?.likes ?: 0)}")
                }
            } else if (p.issue > 0) OutlinedButton(onClick = { openUrl(context, PresetHub.issueUrl(p.issue)) }) {
                Icon(Icons.Outlined.ThumbUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(uiStr(R.string.l_pre_like_comment))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { shareText(context, "YurNLator preset: ${p.game}", PresetHub.exportText(p)) }) {
                Icon(Icons.Outlined.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(uiStr(R.string.l_pre_send))
            }
            if (mine && entry != null) TextButton(enabled = busy == null, onClick = {
                scope.launch {
                    busy = uiStr(R.string.l_pre_removing)
                    val res = withContext(Dispatchers.IO) { PresetHub.delete(context, p.id) }
                    busy = null
                    when (res) {
                        is PresetHub.Publish.Sent -> { Toast.makeText(context, uiStr(R.string.l_pre_removal_requested_gone_within_a_few), Toast.LENGTH_LONG).show(); done() }
                        is PresetHub.Publish.OpenBrowser -> { openUrl(context, res.url); Toast.makeText(context, uiStr(R.string.l_pre_submit_the_issue_on_github_to), Toast.LENGTH_LONG).show() }
                        is PresetHub.Publish.Failed -> Toast.makeText(context, res.reason, Toast.LENGTH_LONG).show()
                    }
                }
            }) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                Text(uiStr(R.string.l_lib_remove_from_library), color = MaterialTheme.colorScheme.error)
            }
        }

        if (entry != null && hasService) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.5f))
            CommentsSection(entry.id, comments, onAdded = { c ->
                val list = comments.orEmpty() + c
                comments = list
                onSocial(entry.id, PresetHub.Social(social?.likes ?: 0, list.size, social?.liked == true))
            })
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.5f))
        Row(Modifier.fillMaxWidth().clickable { showAll = !showAll }, verticalAlignment = Alignment.CenterVertically) {
            Text(uiStr(R.string.l_pre_settings_1_s, p.settings.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (showAll) uiStr(R.string.l_bench_hide) else uiStr(R.string.l_bench_show), color = MaterialTheme.colorScheme.primary)
        }
        if (showAll) p.settings.toSortedMap(compareBy<String>({ CommunityPreset.label(it) }, { it })).forEach { (k, v) ->
            Column {
                Text(CommunityPreset.label(k), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ShareForm(shortcut: Shortcut, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var author by remember { mutableStateOf(PresetHub.savedAuthor(context)) }
    var includeArgs by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var built by remember { mutableStateOf<Pair<CommunityPreset, String>?>(null) }
    val hasArgs = remember { shortcut.getExtra("execArgs").isNotBlank() }

    suspend fun build(): Pair<CommunityPreset, String> = withContext(Dispatchers.IO) {
        val token = CommunityPreset.newToken()
        CommunityPreset.capture(context, shortcut, title, notes, author, includeArgs, (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""), token) to token
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val b = built ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(PresetHub.exportText(b.first).toByteArray()) } }.isSuccess
            withContext(Dispatchers.Main) { Toast.makeText(context, if (ok) uiStr(R.string.l_pre_saved) else uiStr(R.string.l_pre_couldn_t_save), Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiStr(R.string.l_pre_everyone_with_yurnlator_will_be_able, shortcut.name),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(uiStr(R.string.l_pre_preset_name)) }, placeholder = { Text(uiStr(R.string.l_pre_e_g_stable_60_fps_medium)) })
        OutlinedTextField(notes, { notes = it.take(1500) }, Modifier.fillMaxWidth().height(150.dp),
            label = { Text(uiStr(R.string.l_pre_notes)) }, placeholder = { Text(uiStr(R.string.l_pre_fps_you_get_in_game_settings)) })
        OutlinedTextField(author, { author = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(uiStr(R.string.l_pre_your_name_optional)) })
        if (hasArgs) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeArgs = !includeArgs }) {
            Checkbox(includeArgs, { includeArgs = it })
            Text(uiStr(R.string.l_pre_include_launch_arguments_1_s, shortcut.getExtra("execArgs").take(60)), style = MaterialTheme.typography.bodyMedium)
        }

        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), onClick = {
            scope.launch {
                busy = true
                PresetHub.saveAuthor(context, author)
                val b = build()
                built = b
                val res = withContext(Dispatchers.IO) { PresetHub.publish(context, b.first, b.second) }
                busy = false
                when (res) {
                    is PresetHub.Publish.Sent -> {
                        Toast.makeText(context, uiStr(R.string.l_pre_published_it_appears_in_the_library), Toast.LENGTH_LONG).show()
                        done()
                    }
                    is PresetHub.Publish.OpenBrowser -> {
                        Toast.makeText(context, uiStr(R.string.l_pre_github_opens_tap_submit_new_issue), Toast.LENGTH_LONG).show()
                        openUrl(context, res.url)
                        done()
                    }
                    is PresetHub.Publish.Failed -> Toast.makeText(context, res.reason, Toast.LENGTH_LONG).show()
                }
            }
        }) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else { Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.l_pre_publish), fontWeight = FontWeight.SemiBold) }
        }
        Text(uiStr(R.string.l_pre_publishing_uses_github_github_com_1, PresetHub.REPO),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    val b = build(); built = b
                    saver.launch(shortcut.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_") + ".yurnpreset.json")
                }
            }) { Text(uiStr(R.string.l_pre_save_to_file)) }
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    val b = build(); built = b
                    shareText(context, "YurNLator preset: ${shortcut.name}", PresetHub.exportText(b.first))
                }
            }) { Icon(Icons.Outlined.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(uiStr(R.string.l_pre_send)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CommentsSection(id: String, comments: List<PresetHub.Comment>?, onAdded: (PresetHub.Comment) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val hw = remember { listOf(android.os.Build.MODEL, CommunityPreset.soc()).filter { it.isNotBlank() }.joinToString(" · ") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(uiStr(R.string.l_pre_comments) + (comments?.let { " (${it.size})" } ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when {
            comments == null -> Text(uiStr(R.string.l_pre_couldn_t_load_comments), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            comments.isEmpty() -> Text(uiStr(R.string.l_pre_no_comments_yet_tried_it_say),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> comments.forEach { c ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (c.mine) MaterialTheme.colorScheme.primary.copy(.5f) else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(listOf(c.name + if (c.mine) uiStr(R.string.l_pre_you) else "", c.hw, date(c.created)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        OutlinedTextField(text, { text = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text(uiStr(R.string.l_pre_your_comment)) },
            placeholder = { Text(uiStr(R.string.l_pre_fps_what_works_what_to_change)) }, minLines = 2)
        Button(enabled = !sending && text.trim().length >= 2, onClick = {
            scope.launch {
                sending = true
                val (c, err) = withContext(Dispatchers.IO) {
                    PresetHub.postComment(context, id, PresetHub.savedAuthor(context).ifBlank { "anonymous" }, text, hw)
                }
                sending = false
                if (c != null) { onAdded(c); text = "" }
                else Toast.makeText(context, err ?: uiStr(R.string.l_pre_not_sent), Toast.LENGTH_SHORT).show()
            }
        }) { Text(if (sending) uiStr(R.string.l_pre_sending) else uiStr(R.string.l_pre_send_comment)) }
        Text(uiStr(R.string.l_pre_shown_as_1_s_the_name, PresetHub.savedAuthor(context).ifBlank { uiStr(R.string.l_pre_anonymous) }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
