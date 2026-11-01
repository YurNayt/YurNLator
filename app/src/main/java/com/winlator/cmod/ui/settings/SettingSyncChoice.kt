package com.winlator.cmod.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.components.ChoiceOption
import com.winlator.cmod.ui.components.ChoiceSheet
import java.io.File

/**
 * How Wine synchronizes the game's threads, stored as environment variables:
 *   esync      WINEESYNC=1 (Winlator's default, fast, works on every phone)
 *   ntsync     WINEESYNC=1 + WINENTSYNC=1 / PROTON_USE_NTSYNC=1 (fastest; needs /dev/ntsync in the
 *              phone's kernel, Wine falls back to esync without it)
 *   wineserver WINEESYNC=0 (slowest, most compatible: for games that hang with esync)
 * fsync is not offered: it needs futex_waitv, which Android doesn't allow apps to use.
 */
internal object SyncMode {
    const val ESYNC = "esync"
    const val NTSYNC = "ntsync"
    const val WINESERVER = "wineserver"

    private val KEYS = listOf("WINEESYNC", "WINENTSYNC", "PROTON_USE_NTSYNC", "PROTON_NO_ESYNC", "WINEFSYNC")

    /** True when the phone's kernel exposes the ntsync driver. */
    val ntsyncSupported: Boolean by lazy { runCatching { File("/dev/ntsync").exists() }.getOrDefault(false) }

    fun of(env: String): String = when {
        envValue(env, "WINENTSYNC") == "1" || envValue(env, "PROTON_USE_NTSYNC") == "1" -> NTSYNC
        envValue(env, "WINEESYNC") == "0" -> WINESERVER
        else -> ESYNC
    }

    fun apply(env: String, mode: String): String {
        var out = env
        KEYS.forEach { out = envPut(out, it, null) }
        return when (mode) {
            NTSYNC -> envPut(envPut(envPut(out, "WINEESYNC", "1"), "WINENTSYNC", "1"), "PROTON_USE_NTSYNC", "1")
            WINESERVER -> envPut(envPut(out, "WINEESYNC", "0"), "PROTON_NO_ESYNC", "1")
            else -> envPut(out, "WINEESYNC", "1")
        }
    }
}

@Composable
internal fun SettingSyncChoice(env: String, note: String? = null, onChanged: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = SyncMode.of(env)
    Surface(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sync", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                val hint = when {
                    current == SyncMode.NTSYNC && !SyncMode.ntsyncSupported -> "This phone's kernel has no ntsync: Wine uses esync"
                    else -> note
                }
                if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.KeyboardArrowDown, null)
        }
    }
    if (open) {
        val options = buildList {
            add(ChoiceOption(SyncMode.ESYNC, "esync", "Fast, works on every phone (default)"))
            if (SyncMode.ntsyncSupported || current == SyncMode.NTSYNC) {
                add(ChoiceOption(SyncMode.NTSYNC, "ntsync",
                    if (SyncMode.ntsyncSupported) "Fastest, uses the kernel driver (Wine / Proton 10+)"
                    else "Not supported by this phone's kernel"))
            }
            add(ChoiceOption(SyncMode.WINESERVER, "wineserver", "Slowest, most compatible: try it if a game hangs"))
        }
        ChoiceSheet(
            title = "Sync",
            options = options,
            selected = current,
            onPick = { onChanged(SyncMode.apply(env, it.value)) },
            onDismiss = { open = false }
        )
    }
}
