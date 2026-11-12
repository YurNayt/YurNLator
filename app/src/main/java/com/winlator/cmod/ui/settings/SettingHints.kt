package com.winlator.cmod.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.winlator.cmod.R
import com.winlator.cmod.ui.L10nDict
import com.winlator.cmod.ui.uiStr

/**
 * A short line under a setting: which GPU / CPU it is for (settings that only work on some
 * hardware), and the English name of settings whose translation is less known than the term.
 */
internal object SettingHints {
    private val hardware: Map<String, Int> = mapOf(
        "BCN Emulation" to R.string.l_hint_non_adreno,
        "BCN Emulation Type" to R.string.l_hint_non_adreno,
        "BCN Emulation Cache" to R.string.l_hint_non_adreno,
        "BCn layer (Winlator Mali)" to R.string.l_hint_mali,
        "BCn transcode to" to R.string.l_hint_mali,
        "BCn quality" to R.string.l_hint_mali,
        "Mali barrier tweaks" to R.string.l_hint_mali,
        "Rendering Mode" to R.string.l_hint_turnip,
        "Force SSBS (CPU speed fix, arm64ec)" to R.string.l_hint_ssbs,
        "Display backend" to R.string.l_hint_wayland,
        "Graphics Driver" to R.string.l_hint_freedreno,
        "Driver Version" to R.string.l_hint_vk_driver,
    )

    /** Shown with their English name when the app is in another language. */
    private val englishName = setOf("Exclusive Input")

    fun of(label: String): String? {
        val parts = ArrayList<String>(2)
        if (label in englishName && L10nDict.t(label) != label) parts += label
        hardware[label]?.let { parts += uiStr(it) }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}

@Composable
internal fun SettingHint(label: String) {
    val hint = SettingHints.of(label) ?: return
    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}
