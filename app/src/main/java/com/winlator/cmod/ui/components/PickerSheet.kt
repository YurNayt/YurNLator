package com.winlator.cmod.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An action card at the top of the sheet ("Install from file", "Download …"). */
data class PickerAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val busy: Boolean = false,
    val onClick: () -> Unit
)

/** One choosable entry (an installed driver or wrapper). */
data class PickerOption(val id: String, val title: String, val subtitle: String?)

/**
 * Full-height bottom sheet for choosing a driver or wrapper, in the same card style
 * as the component type list: title, action cards, then the installed options with
 * the current one checked.
 */
@Composable
fun PickerSheet(
    title: String,
    subtitle: String,
    icon: ImageVector,
    actions: List<PickerAction>,
    options: List<PickerOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        KeepDialogFullscreen()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    IconBox(icon, highlighted = true)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(actions, key = { "action:" + it.title }) { action ->
                SheetCard(onClick = { if (!action.busy) action.onClick() }) {
                    if (action.busy) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        }
                    } else IconBox(action.icon, highlighted = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(action.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(action.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "installed-title") {
                Text(
                    "INSTALLED",
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(options, key = { "option:" + it.id }) { option ->
                val selected = option.id.equals(selectedId, ignoreCase = true)
                SheetCard(selected = selected, onClick = { onSelect(option.id) }) {
                    Icon(
                        if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!option.subtitle.isNullOrBlank()) {
                            Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetCard(selected: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun IconBox(icon: ImageVector, highlighted: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(42.dp).background(
            if (highlighted) scheme.primary.copy(alpha = 0.15f) else scheme.surfaceVariant,
            RoundedCornerShape(10.dp)
        ),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = if (highlighted) scheme.primary else scheme.onSurface) }
}

/**
 * One entry of a [ChoiceSheet]. Not installed entries show "Download" and call onPick without closing.
 * Consecutive entries with the same [group] are shown as one row (the first entry) with an
 * arrow that reveals the others, e.g. one "Proton arm64ec" row with older builds inside.
 */
data class ChoiceOption(
    val value: String,
    val title: String = value,
    val subtitle: String? = null,
    val installed: Boolean = true,
    val busy: Boolean = false,
    val group: String? = null
)

/**
 * Compact bottom sheet for any setting with a list of values (memory size, present mode,
 * DXVK version…), in the GPU driver sheet's style but denser. Short lists only take the
 * height they need; long ones grow up to the same full height as the driver sheet.
 * [onPick] gets the chosen option; the sheet closes itself for installed options.
 */
@Composable
fun ChoiceSheet(
    title: String,
    options: List<ChoiceOption>,
    selected: String,
    onPick: (ChoiceOption) -> Unit,
    onDismiss: () -> Unit,
    actions: List<PickerAction> = emptyList()
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    // Consecutive options of one group -> one block; ungrouped options are blocks of one.
    val blocks = remember(options) {
        val out = mutableListOf<List<ChoiceOption>>()
        for (o in options) {
            val last = out.lastOrNull()
            if (o.group != null && last != null && last.first().group == o.group) out[out.size - 1] = last + o
            else out += listOf(o)
        }
        out
    }
    // Groups start open when they hold the current choice (and it isn't their top row).
    var open by remember(blocks) {
        mutableStateOf(
            blocks.filter { b -> b.size > 1 && b.drop(1).any { it.installed && it.value.equals(selected, ignoreCase = true) } }
                .mapNotNull { it.first().group }.toSet()
        )
    }
    val pick: (ChoiceOption) -> Unit = { option ->
        if (!option.busy) {
            onPick(option)
            if (option.installed) onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        KeepDialogFullscreen()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "title") {
                Text(
                    title,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            items(actions, key = { "choice-action:" + it.title }) { action ->
                Surface(
                    onClick = { if (!action.busy) action.onClick() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surface,
                    border = BorderStroke(1.dp, scheme.outlineVariant)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (action.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(action.icon, null, Modifier.size(20.dp), tint = scheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                            Text(action.subtitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            blocks.forEach { block ->
                val head = block.first()
                val rest = block.drop(1)
                val group = head.group
                val isOpen = group != null && group in open
                item(key = "choice:" + head.value) {
                    ChoiceRow(
                        option = head,
                        selected = selected,
                        more = rest.size,
                        moreInstalled = rest.count { it.installed },
                        expanded = isOpen,
                        onToggle = if (rest.isEmpty() || group == null) null else {
                            { open = if (isOpen) open - group else open + group }
                        },
                        onClick = { pick(head) }
                    )
                }
                if (isOpen) items(rest, key = { "choice:" + it.value }) { option ->
                    Box(Modifier.padding(start = 18.dp)) {
                        ChoiceRow(option = option, selected = selected, onClick = { pick(option) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    option: ChoiceOption,
    selected: String,
    onClick: () -> Unit,
    more: Int = 0,
    moreInstalled: Int = 0,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val isSelected = option.installed && option.value.equals(selected, ignoreCase = true)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) scheme.secondaryContainer else scheme.surface,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) scheme.primary else scheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = if (onToggle != null) 2.dp else 12.dp,
                top = if (option.subtitle == null && more == 0) 11.dp else 8.dp,
                bottom = if (option.subtitle == null && more == 0) 11.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                option.busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                !option.installed -> Icon(Icons.Outlined.CloudDownload, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                else -> Icon(
                    if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null, Modifier.size(20.dp),
                    tint = if (isSelected) scheme.primary else scheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (option.installed) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = when {
                    option.busy -> "Downloading…"
                    !option.installed -> listOfNotNull(option.subtitle, "Download").joinToString(" · ")
                    else -> option.subtitle
                }
                if (!sub.isNullOrBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                if (more > 0) {
                    val inside = if (moreInstalled > 0) " · $moreInstalled installed" else ""
                    Text(
                        com.winlator.cmod.core.VersionGroups.moreLabel(more) + inside,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary
                    )
                }
            }
            if (onToggle != null) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide versions" else "Show versions",
                        tint = scheme.primary
                    )
                }
            }
        }
    }
}
