package com.winlator.cmod.ui.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.core.EnvVars
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.ui.components.KeepDialogFullscreen
import org.json.JSONArray
import java.util.Locale

/** Name prefix of the per-container / per-game Custom presets. */
private const val CUSTOM_PREFIX = "Custom · "

private fun displayName(p: PresetInfo, slotName: String) = if (p.name == slotName) "Custom" else p.name

/** Which emulator's presets: FEXCore, or Box64 (also used for WOWBox64). */
enum class PresetKind(val prefix: String, val defaultId: String) {
    FEXCORE("fexcore", FEXCorePreset.INTERMEDIATE),
    BOX64("box64", Box64Preset.COMPATIBILITY)
}

private data class PresetInfo(val id: String, val name: String, val custom: Boolean)

private data class PresetVar(
    val name: String,
    val choices: List<String>,
    val toggle: Boolean,
    val text: Boolean,
    val default: String,
    val help: String?
)

/** Read / write presets of one kind through the existing managers. */
private class PresetStore(val context: Context, val kind: PresetKind) {
    fun list(): List<PresetInfo> = when (kind) {
        PresetKind.FEXCORE -> FEXCorePresetManager.getPresets(context).map { PresetInfo(it.id, it.name, it.id.startsWith(FEXCorePreset.CUSTOM)) }
        PresetKind.BOX64 -> Box64PresetManager.getPresets(kind.prefix, context).map { PresetInfo(it.id, it.name, it.isCustom) }
    }

    fun values(id: String): EnvVars = when (kind) {
        PresetKind.FEXCORE -> FEXCorePresetManager.getEnvVars(context, id)
        PresetKind.BOX64 -> Box64PresetManager.getEnvVars(kind.prefix, context, id)
    }

    fun nextId(): String = when (kind) {
        PresetKind.FEXCORE -> FEXCorePreset.CUSTOM + "-" + FEXCorePresetManager.getNextPresetId(context)
        PresetKind.BOX64 -> Box64Preset.CUSTOM + "-" + Box64PresetManager.getNextPresetId(context, kind.prefix)
    }

    /** id = null creates a new custom preset (its id is [nextId] taken just before). */
    fun save(id: String?, name: String, env: EnvVars) = when (kind) {
        PresetKind.FEXCORE -> FEXCorePresetManager.editPreset(context, id, name, env)
        PresetKind.BOX64 -> Box64PresetManager.editPreset(kind.prefix, context, id, name, env)
    }

    fun remove(id: String) = when (kind) {
        PresetKind.FEXCORE -> FEXCorePresetManager.removePreset(context, id)
        PresetKind.BOX64 -> Box64PresetManager.removePreset(kind.prefix, context, id)
    }

    fun variables(): List<PresetVar> = runCatching {
        val data = JSONArray(FileUtils.readString(context, "${kind.prefix}_env_vars.json"))
        (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            val name = o.getString("name")
            val arr = o.getJSONArray("values")
            val suffix = when (kind) {
                PresetKind.FEXCORE -> name.replace("FEX_", "")
                PresetKind.BOX64 -> name.replace("BOX64_", "")
            }.lowercase(Locale.ENGLISH)
            PresetVar(
                name = name,
                choices = (0 until arr.length()).map { arr.getString(it) },
                toggle = o.optBoolean("toggleSwitch", false),
                text = o.optBoolean("editText", false),
                default = o.optString("defaultValue"),
                help = StringUtils.getString(context, "${kind.prefix}_env_var_help__$suffix")
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Preset row in container / game settings. Opens a GameHub-style sheet: every preset
 * (built-in and your own) in a row on top, all of the chosen preset's settings below,
 * editable in place. Editing a built-in preset makes your own copy and selects it;
 * your own presets save as you change them.
 */
@Composable
fun SettingPresetChoice(
    label: String,
    kind: PresetKind,
    selectedId: String,
    /** Whose Custom preset this is: "container-3", "game-Doom"… (one Custom per container / game). */
    scope: String,
    onSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val store = remember(kind) { PresetStore(context, kind) }
    var revision by remember { mutableIntStateOf(0) }
    // "," and "|" separate stored presets, so they can't be part of the name.
    val slotName = remember(scope) { CUSTOM_PREFIX + scope.replace(Regex("[,|]+"), "") }
    // Other containers' / games' Custom presets are hidden here.
    val presets = remember(revision, slotName) {
        store.list().filter { !it.name.startsWith(CUSTOM_PREFIX) || it.name == slotName }
    }
    val current = presets.firstOrNull { it.id == selectedId }
    var open by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        current?.let { displayName(it, slotName) } ?: selectedId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (current?.custom == true) "Your preset · tap to edit" else "Tap to see and change its settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
    }

    if (open) {
        PresetSheet(label, store, presets, current?.id ?: kind.defaultId, slotName,
            onChanged = { revision++ },
            onSelected = onSelected,
            onDismiss = { open = false })
    }
}

@Composable
private fun PresetSheet(
    title: String,
    store: PresetStore,
    presets: List<PresetInfo>,
    selectedId: String,
    slotName: String,
    onChanged: () -> Unit,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val variables = remember { store.variables() }
    val preset = presets.firstOrNull { it.id == selectedId } ?: presets.firstOrNull()
    // Values of the shown preset: all known variables, the preset's value or the default.
    val values = remember(preset?.id, presets) {
        val env = preset?.let { store.values(it.id) }
        variables.associate { v -> v.name to (env?.takeIf { e -> e.has(v.name) }?.get(v.name) ?: v.default) }
    }
    var name by remember(preset?.id, presets) { mutableStateOf(preset?.name.orEmpty()) }
    var help by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun envOf(map: Map<String, String>) = EnvVars().apply { map.forEach { (k, v) -> put(k, v) } }

    /**
     * Apply one change. Your presets (and Custom) save directly. A built-in preset is
     * not touched: its values plus this change go to this container's / game's Custom
     * preset, which is then selected (GameHub style).
     */
    fun change(varName: String, value: String) {
        val p = preset ?: return
        val updated = values + (varName to value)
        if (p.custom) {
            store.save(p.id, if (p.name == slotName) slotName else name.ifBlank { p.name }, envOf(updated))
        } else {
            val slot = presets.firstOrNull { it.name == slotName }
            if (slot != null) {
                store.save(slot.id, slotName, envOf(updated))
                onSelected(slot.id)
            } else {
                val newId = store.nextId()
                store.save(null, slotName, envOf(updated))
                onSelected(newId)
            }
        }
        onChanged()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        KeepDialogFullscreen()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "title") {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            // Presets in a row: built-in first, then yours, then "+ New".
            item(key = "presets") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(presets, key = { it.id }) { p ->
                        val sel = p.id == preset?.id
                        Surface(
                            onClick = { onSelected(p.id) },
                            shape = RoundedCornerShape(50),
                            color = if (sel) scheme.primary else scheme.surface,
                            contentColor = if (sel) scheme.onPrimary else scheme.onSurface,
                            border = if (sel) null else BorderStroke(1.dp, scheme.outlineVariant)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (!p.custom) {
                                    Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(displayName(p, slotName), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                    item(key = "+new") {
                        Surface(
                            onClick = {
                                val newId = store.nextId()
                                store.save(null, "My preset", envOf(values))
                                onSelected(newId)
                                onChanged()
                            },
                            shape = RoundedCornerShape(50),
                            color = scheme.surface,
                            border = BorderStroke(1.dp, scheme.outlineVariant)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            item(key = "hint") {
                Text(
                    when {
                        preset?.name == slotName -> "Custom for this ${if (slotName.contains("game-")) "game" else "container"}: changes are saved right away."
                        preset?.custom == true -> "Your preset: changes are saved right away."
                        else -> "Built-in preset. Change any setting and you switch to Custom with this preset's values plus your change."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            if (preset?.custom == true && preset.name != slotName) {
                item(key = "name") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it.replace(Regex("[,|]+"), "")
                                if (name.isNotBlank()) { store.save(preset.id, name, envOf(values)); onChanged() }
                            },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete preset") }
                    }
                }
            }
            items(variables, key = { it.name }) { v ->
                VariableCard(v, values[v.name] ?: v.default, onHelp = { help = v.help }) { change(v.name, it) }
            }
        }
    }

    help?.let { text ->
        AlertDialog(onDismissRequest = { help = null }, confirmButton = { TextButton(onClick = { help = null }) { Text("OK") } }, text = { Text(text) })
    }
    if (confirmDelete && preset != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${displayName(preset, slotName)}?") },
            text = { Text("Containers and games using it switch to the default preset.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.remove(preset.id)
                    onSelected(store.kind.defaultId)
                    onChanged()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** One variable, compact: switch for on/off, value chips for a few choices, text for free input. */
@Composable
private fun VariableCard(v: PresetVar, value: String, onHelp: () -> Unit, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.name.removePrefix("FEX_").removePrefix("BOX64_"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!v.help.isNullOrBlank()) {
                    IconButton(onClick = onHelp, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.HelpOutline, "Help", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
                if (v.toggle) Switch(checked = value == "1", onCheckedChange = { onChange(if (it) "1" else "0") })
            }
            when {
                v.toggle -> Unit
                v.text -> {
                    var text by remember(value) { mutableStateOf(value) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; if (it.isNotBlank()) onChange(it.trim()) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 6.dp)
                ) {
                    items(v.choices, key = { it }) { c ->
                        val sel = c == value
                        Surface(
                            onClick = { if (!sel) onChange(c) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) scheme.primary else scheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = if (sel) scheme.onPrimary else scheme.onSurface
                        ) {
                            Text(c, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
