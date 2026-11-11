package com.winlator.cmod.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.winlator.cmod.R
import com.winlator.cmod.ui.UiStrings
import com.winlator.cmod.ui.components.ChoiceOption
import com.winlator.cmod.ui.components.ChoiceSheet

/** The app's own language (Android 13+ per-app language; AppCompat stores it on older phones). */
object AppLanguageSettings {
    /** "" follows the phone's language. */
    val supportedTags: List<String> = listOf("", "en", "ru")

    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        .substringBefore(',').substringBefore('-').let { if (it in supportedTags) it else "" }

    fun select(tag: String) {
        require(tag in supportedTags)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        UiStrings.invalidate()
    }
}

@Composable
fun AppLanguagePreferenceCard() {
    val configuration = LocalConfiguration.current
    var selected by remember(configuration) { mutableStateOf(AppLanguageSettings.current()) }
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        "" to stringResource(R.string.app_language_system),
        "en" to stringResource(R.string.app_language_english),
        "ru" to stringResource(R.string.app_language_russian)
    )
    Surface(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(labels[selected] ?: labels.getValue(""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        ChoiceSheet(
            title = stringResource(R.string.app_language),
            options = AppLanguageSettings.supportedTags.map { ChoiceOption(it, labels.getValue(it)) },
            selected = selected,
            onPick = {
                open = false
                if (it.value != selected) {
                    selected = it.value
                    AppLanguageSettings.select(it.value)
                }
            },
            onDismiss = { open = false }
        )
    }
}
