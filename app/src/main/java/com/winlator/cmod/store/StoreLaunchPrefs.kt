package com.winlator.cmod.store

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Per game: whether the store takes part in its launches (Steam: cloud saves and achievements before
 * and after; Epic: the sign-in code). On by default; off plays the game as is, offline.
 */
object StoreLaunchPrefs {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("yurn_store_launch", Context.MODE_PRIVATE)
    fun enabled(c: Context, key: String) = prefs(c).getBoolean(key, true)
    fun set(c: Context, key: String, on: Boolean) = prefs(c).edit().putBoolean(key, on).apply()
    fun steam(appId: Int) = "steam:$appId"
    fun epic(app: String) = "epic:$app"
}

/** The switch of [StoreLaunchPrefs] in a game card. */
@androidx.compose.runtime.Composable
fun StoreLaunchSwitch(key: String, title: String, hint: String, onChange: (Boolean) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableStateOf(StoreLaunchPrefs.enabled(context, key)) }
    androidx.compose.material3.Surface(
        shape = LocalStoreStyle.current?.cardShape ?: androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(14.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = androidx.compose.ui.Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; StoreLaunchPrefs.set(context, key, it); onChange(it) })
            }
            androidx.compose.material3.Text(hint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
