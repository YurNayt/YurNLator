package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.LsfgNative
import com.winlator.cmod.core.WinFrameGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings › Frame generation: the default engine (Win-FG built in, or LSFG) and the user's own
 * Lossless.dll. The DLL is never run: its 25 compute shaders are read as data and translated to
 * SPIR-V once, on import (a few seconds), then cached ([LsfgNative.cacheFile]).
 * Containers and games can still pick their engine in the in-game menu.
 */
@Composable
fun FrameGenPreferenceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(WinFrameGen.defaultEngine(context)) }
    var status by remember { mutableStateOf(lsfgStatusText(context)) }
    var busy by remember { mutableStateOf(false) }

    fun import(uri: Uri) {
        busy = true
        status = uiStr(R.string.l_fg_translating_the_shaders_for_this_gpu)
        scope.launch {
            val result = withContext(Dispatchers.IO) { importLossless(context, uri) }
            busy = false
            status = result
            // A working LSFG becomes the default engine (Win-FG stays one tap away).
            if (LsfgNative.cacheVariant(context) != LsfgNative.VARIANT_NONE) {
                WinFrameGen.setDefaultEngine(context, WinFrameGen.ENGINE_LSFG)
                engine = WinFrameGen.ENGINE_LSFG
            }
        }
    }

    fun autoFind() {
        busy = true
        status = uiStr(R.string.l_fg_looking_for_lossless_dll)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val found = findLossless(context)
                if (found == null) uiStr(R.string.l_fg_not_found_steam_folders_of_the)
                else importLosslessFrom(context) { found.inputStream() }.let { uiStr(R.string.l_fg_found_1_s_lossless_dll_2, found.parentFile?.name, it) }
            }
            busy = false
            status = result
            if (LsfgNative.cacheVariant(context) != LsfgNative.VARIANT_NONE) {
                WinFrameGen.setDefaultEngine(context, WinFrameGen.ENGINE_LSFG)
                engine = WinFrameGen.ENGINE_LSFG
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::import) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiStr(R.string.l_fg_default_engine), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(WinFrameGen.ENGINE_WINFG to "Win-FG", WinFrameGen.ENGINE_WFG to "WFG", WinFrameGen.ENGINE_LSFG to "LSFG").forEach { (id, label) ->
                    FilterChip(selected = engine == id, onClick = {
                        engine = id
                        WinFrameGen.setDefaultEngine(context, id)
                    }, label = { Text(label) })
                }
            }
            Text(
                uiStr(R.string.l_fg_win_fg_and_wfg_are_built),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Text(uiStr(R.string.l_fg_your_lsfg_lossless_dll), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                uiStr(R.string.l_fg_from_your_own_lossless_scaling_steam),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (LsfgNative.isDllAvailable(context) && !busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (LsfgNative.isDllAvailable(context)) uiStr(R.string.l_fg_replace) else uiStr(R.string.l_fg_import_lossless_dll))
                }
                if (!LsfgNative.isDllAvailable(context) && !busy) {
                    TextButton(onClick = { autoFind() }) { Text(uiStr(R.string.l_fg_find)) }
                }
                if (LsfgNative.isDllAvailable(context) && !busy) {
                    TextButton(onClick = {
                        LsfgNative.losslessDll(context).delete()
                        LsfgNative.cacheFile(context).delete()
                        status = lsfgStatusText(context)
                    }) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiStr(R.string.l_ctr_remove))
                    }
                }
            }
        }
    }
}

private fun lsfgStatusText(context: android.content.Context): String {
    if (!LsfgNative.isDllAvailable(context)) return uiStr(R.string.l_fg_not_imported)
    val variant = LsfgNative.cacheVariant(context)
    return if (variant != LsfgNative.VARIANT_NONE) uiStr(R.string.l_fg_ready_shaders) + LsfgNative.variantName(variant)
    else uiStr(R.string.l_fg_imported_shaders_are_prepared_at_the)
}

/**
 * Looks for Lossless.dll where it usually ends up: the containers' Steam folders (Lossless
 * Scaling installed through Steam in a container) and Download / Documents on the phone.
 */
private fun findLossless(context: android.content.Context): File? {
    val roots = mutableListOf<Pair<File, Int>>()
    val home = File(com.winlator.cmod.xenvironment.ImageFs.find(context).rootDir, "home")
    home.listFiles()?.forEach { user ->
        val c = File(user, ".wine/drive_c")
        roots += File(c, "Program Files (x86)/Steam/steamapps/common/Lossless Scaling") to 0
        roots += File(c, "Program Files/Steam/steamapps/common/Lossless Scaling") to 0
        roots += File(c, "Program Files (x86)/Steam/steamapps/common") to 2
    }
    val ext = android.os.Environment.getExternalStorageDirectory()
    listOf("Download", "Documents", "Lossless Scaling", "Steam", "SteamLibrary").forEach { roots += File(ext, it) to 4 }
    for ((dir, depth) in roots) search(dir, depth)?.let { return it }
    return null
}

private fun search(dir: File, depth: Int): File? {
    val direct = File(dir, "Lossless.dll")
    if (direct.isFile) return direct
    if (depth <= 0) return null
    val kids = runCatching { dir.listFiles() }.getOrNull() ?: return null
    for (k in kids) if (k.isDirectory && !k.name.startsWith(".")) search(k, depth - 1)?.let { return it }
    return null
}

/** Copies the picked file to a temp name, checks it, builds the cache, then makes it the active DLL. */
private fun importLossless(context: android.content.Context, uri: Uri): String =
    importLosslessFrom(context) { context.contentResolver.openInputStream(uri) }

private fun importLosslessFrom(context: android.content.Context, open: () -> java.io.InputStream?): String {
    val target = LsfgNative.losslessDll(context)
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "Lossless.dll.import")
    return try {
        open()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: return uiStr(R.string.l_fg_could_not_read_the_file)
        val valid = LsfgNative.validate(tmp)
        if (valid != LsfgNative.STATUS_OK) {
            tmp.delete()
            return LsfgNative.explain(valid)
        }
        LsfgNative.cacheFile(context).delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        val built = LsfgNative.ensureCache(context, true)
        if (built == LsfgNative.STATUS_OK) lsfgStatusText(context) else LsfgNative.explain(built)
    } catch (e: Exception) {
        tmp.delete()
        uiStr(R.string.l_fg_import_failed) + (e.message ?: e.javaClass.simpleName)
    }
}
