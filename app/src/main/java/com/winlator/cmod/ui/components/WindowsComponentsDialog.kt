package com.winlator.cmod.ui.components

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.winlator.cmod.components.Component
import com.winlator.cmod.components.ComponentCatalog
import com.winlator.cmod.components.ComponentInstallRecords
import com.winlator.cmod.components.ComponentInstaller
import com.winlator.cmod.components.ComponentUninstaller
import com.winlator.cmod.components.DependencyDetector
import com.winlator.cmod.components.GameImports
import com.winlator.cmod.components.PrefixInstalledDetector
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Windows components to download into a container (Visual C++, .NET, DirectX parts, PhysX, XNA,
 * OpenAL…). Catalog and installers ported from Bannerlator (GPL-3.0): most components are laid
 * out straight into the prefix without running their setup, so the container stays closed.
 *
 * [recommendFor]: a game's exe; the dialog then opens on what that game needs (its imports and the
 * redistributables in its folder) and offers to install them together.
 */
object WindowsComponentsDialog {
    @JvmStatic
    fun show(context: Context, container: Container) = open(context, container, null, emptyList())

    /**
     * After a game is added: looks for the components it needs that the container lacks and, when
     * there are some, offers them. Does nothing (shows nothing) otherwise.
     */
    @JvmStatic
    fun recommendAfterAdding(context: Context, container: Container, exe: File) = check(context, container, exe, false)

    /** The game's exe on the phone: a Unix path as written by the file manager, or C:\… / D:\… mapped through the container. */
    @JvmStatic
    fun exeOf(shortcut: com.winlator.cmod.container.Shortcut): File? {
        val p = shortcut.path.trim().trim('"')
        if (p.startsWith("/")) return File(p)
        if (p.length < 3 || p[1] != ':') return null
        val letter = p[0].uppercaseChar()
        val rest = p.substring(3).replace('\\', '/')
        val root = if (letter == 'C') File(shortcut.container.rootDir, ".wine/drive_c")
        else shortcut.container.drivesIterator().firstOrNull { it[0].equals(letter.toString(), true) }?.let { File(it[1]) } ?: return null
        return File(root, rest)
    }

    /** The game page's button: what this game needs, installed or not. */
    @JvmStatic
    fun showForGame(context: Context, container: Container, exe: File?) {
        if (exe == null || !exe.isFile) { show(context, container); return }
        check(context, container, exe, true)
    }

    private fun check(context: Context, container: Container, exe: File, always: Boolean) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (always) Toast.makeText(context, uiStr(R.string.l_wc_checking_what_1_s_needs, exe.name), Toast.LENGTH_SHORT).show()
        Thread {
            val recs = runCatching { recommendations(context, container, exe, always) }.getOrNull()
            main.post {
                runCatching {
                    when {
                        recs == null -> if (always) open(context, container, null, emptyList())
                        recs.isNotEmpty() -> open(context, container, exe, recs)
                        always -> { Toast.makeText(context, uiStr(R.string.l_wc_1_s_no_extra_components_found, exe.name), Toast.LENGTH_LONG).show(); open(context, container, null, emptyList()) }
                        else -> Toast.makeText(context, uiStr(R.string.l_wc_windows_components_nothing_missing_for_1, exe.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    /** What [exe] needs (its imports, its folders' redistributables); null when the catalog can't be read. */
    private fun recommendations(context: Context, container: Container, exe: File, withInstalled: Boolean): List<DependencyDetector.Recommendation>? {
        val catalog = ComponentCatalog.load().associateBy { it.name }
        if (catalog.isEmpty()) return null
        val have = installedNames(context, container)
        val found = LinkedHashMap<String, DependencyDetector.Recommendation>()
        val folder = runCatching { DependencyDetector.detectForExe(exe) }.getOrDefault(emptyList())
        (runCatching { GameImports.detect(exe) }.getOrDefault(emptyList()) + folder)
            .forEach { r -> if (r.componentName !in found) found[r.componentName] = r }
        android.util.Log.i("WindowsComponents", "${exe.name}: found ${found.keys}, container has $have")
        return found.values.filter { r ->
            val c = catalog[r.componentName] ?: return@filter false
            (withInstalled || r.componentName !in have) && ComponentInstaller.isInstallable(c)
        }.sortedBy { DependencyDetector.orderOf(it.componentName) }
    }

    private fun installedNames(context: Context, container: Container): Set<String> =
        ComponentInstallRecords.verified(context, container) + PrefixInstalledDetector.detect(container)

    private fun open(context: Context, container: Container, exe: File?, recs: List<DependencyDetector.Recommendation>) {
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
            setContent { WinZTheme { ComponentsScreen(container, exe, recs, dialog::dismiss) } }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private class Job(val name: String) {
    var progress by mutableFloatStateOf(0f)
    var stage by mutableStateOf("")
}

@Composable
private fun ComponentsScreen(container: Container, exe: File?, recs: List<DependencyDetector.Recommendation>, close: () -> Unit) {
    KeepDialogFullscreen()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<Component>?>(null) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var onlyInstalled by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(recs.map { it.componentName }) } }

    suspend fun refreshInstalled() {
        installed = withContext(Dispatchers.IO) {
            ComponentInstallRecords.verified(context, container) + PrefixInstalledDetector.detect(container)
        }
    }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { runCatching { ComponentCatalog.load() }.getOrDefault(emptyList()) }
        failed = list.isEmpty()
        catalog = list
        refreshInstalled()
    }

    /** Installs [names] one after another; returns the lines to show. */
    suspend fun install(names: List<String>): List<String> {
        val byName = catalog.orEmpty().associateBy { it.name }
        val out = ArrayList<String>()
        for (name in names) {
            val c = byName[name] ?: continue
            val j = Job(name).also { job = it }
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    ComponentInstaller.install(context, container, c, { f -> j.progress = f }) { s -> j.stage = s }
                }.getOrElse { it.message ?: it.javaClass.simpleName }
            }
            if (error == null) {
                ComponentInstallRecords.add(context, container.id, name)
                out += uiStr(R.string.l_wc_1_s_installed, DependencyDetector.labelOf(name))
            } else out += "${DependencyDetector.labelOf(name)}: $error"
        }
        job = null
        refreshInstalled()
        return out
    }

    fun report(lines: List<String>) {
        if (lines.isNotEmpty()) Toast.makeText(context, lines.joinToString("\n"), Toast.LENGTH_LONG).show()
    }

    BackHandler(enabled = job == null) { close() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = job == null, onClick = close) { Icon(Icons.Outlined.ArrowBack, uiStr(R.string.l_lib_back)) }
            Column(Modifier.weight(1f)) {
                Text(if (exe != null) uiStr(R.string.l_wc_components_this_game_needs) else uiStr(R.string.l_lib_windows_components),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(container.name + (exe?.let { " · " + it.name } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.5f))
        job?.let { j ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(uiStr(R.string.l_wc_installing_1_s, DependencyDetector.labelOf(j.name)), fontWeight = FontWeight.SemiBold)
                if (j.stage.isNotBlank()) Text(j.stage, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(progress = { j.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }

        val list = catalog
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (failed) {
            Text(uiStr(R.string.l_wc_couldn_t_load_the_component_list), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recs.isNotEmpty()) {
                item {
                    Text(uiStr(R.string.l_wc_found_in_the_game_s_exe),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(recs, key = { "rec-" + it.componentName }) { r ->
                    val done = r.componentName in installed
                    Row(Modifier.fillMaxWidth().clickable(enabled = !done && job == null) {
                        if (r.componentName in selected) selected.remove(r.componentName) else selected.add(r.componentName)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = done || r.componentName in selected, enabled = !done && job == null,
                            onCheckedChange = { if (it) selected.add(r.componentName) else selected.remove(r.componentName) })
                        Column(Modifier.weight(1f)) {
                            Text(r.label, fontWeight = FontWeight.SemiBold)
                            Text(if (done) uiStr(R.string.l_wc_installed) else r.reason, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val todo = selected.filter { it !in installed }
                        Button(enabled = job == null && todo.isNotEmpty(), onClick = { scope.launch { report(install(todo)) } }) {
                            Text(uiStr(R.string.l_wc_install_selected_1_s, todo.size))
                        }
                        TextButton(enabled = job == null, onClick = close) { Text(uiStr(R.string.l_wc_not_now)) }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(.5f)) }
                item { Text(uiStr(R.string.l_wc_all_components), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            } else item {
                Text(uiStr(R.string.l_wc_installed_straight_into_this_container_without),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("vcredist, dotnet, d3dx9, physx…") })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyInstalled, onClick = { onlyInstalled = false }, label = { Text(uiStr(R.string.l_lib_all)) })
                    FilterChip(selected = onlyInstalled, onClick = { onlyInstalled = true }, label = { Text(uiStr(R.string.l_wc_installed_1_s, installed.size)) })
                }
            }
            val shown = list.filter { c ->
                (!onlyInstalled || c.name in installed) && (query.isBlank() || c.name.contains(query, true) ||
                    c.description.contains(query, true) || DependencyDetector.labelOf(c.name).contains(query, true))
            }.sortedWith(compareByDescending<Component> { it.name in installed }.thenBy { DependencyDetector.orderOf(it.name) }.thenBy { it.name.lowercase() })
            items(shown, key = { it.name }) { c ->
                ComponentRow(c, c.name in installed, job == null,
                    install = { scope.launch { report(install(listOf(c.name))) } },
                    remove = {
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    val plan = ComponentUninstaller.plan(context, container, c.name, installed, list.associateBy { it.name })
                                    ComponentUninstaller.uninstall(context, container, plan)
                                }.getOrElse { ComponentUninstaller.Outcome.Failed(it.message ?: "failed") }
                            }
                            when (outcome) {
                                is ComponentUninstaller.Outcome.Removed -> Toast.makeText(context, uiStr(R.string.l_wc_1_s_removed, c.name), Toast.LENGTH_SHORT).show()
                                is ComponentUninstaller.Outcome.Failed -> Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                else -> {}
                            }
                            refreshInstalled()
                        }
                    })
            }
        }
    }
}

@Composable
private fun ComponentRow(c: Component, installed: Boolean, idle: Boolean, install: () -> Unit, remove: () -> Unit) {
    val blocked = remember(c) { ComponentInstaller.blockedReason(c) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (installed) MaterialTheme.colorScheme.primary.copy(.45f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val label = DependencyDetector.labelOf(c.name)
                Text(if (label != c.name) "$label · ${c.name}" else c.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!installed && blocked != null) Text(if (blocked.contains("installer step")) uiStr(R.string.l_wc_needs_its_own_setup_not_yet) else blocked,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(8.dp))
            when {
                installed -> TextButton(enabled = idle, onClick = remove) { Text(uiStr(R.string.l_ctr_remove)) }
                blocked == null -> OutlinedButton(enabled = idle, onClick = install) { Text(uiStr(R.string.l_ob_install)) }
            }
        }
    }
}
