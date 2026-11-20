package com.winlator.cmod.ui.settings

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Test-build tuning of the "new version" banner: where it comes from and rests, its width, picture,
 * text, see-through and corners, with a button that shows it right away. Russian only: it goes
 * once the final look is chosen.
 */
@Composable
fun UpdateBannerTuningCard() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var look by remember { mutableStateOf(UpdateNotice.Look.load(context)) }
    fun set(l: UpdateNotice.Look) { look = l; l.save(context) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Вид уведомления об обновлении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text("Тест: настрой и нажми «Показать»", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (!open) return@Column

            Spacer(Modifier.size(8.dp))
            Choice("Выезжает", look.from, listOf("top" to "Сверху", "bottom" to "Снизу", "left" to "Слева", "right" to "Справа")) {
                set(look.copy(from = it))
            }
            Choice("Где стоит", look.place, listOf("top" to "Вверху", "center" to "По центру", "bottom" to "Внизу")) {
                set(look.copy(place = it))
            }
            Tune("Отступ от края", look.marginDp, 0, 200, "dp") { set(look.copy(marginDp = it)) }
            Tune("Ширина", look.widthPercent, 40, 100, "%") { set(look.copy(widthPercent = it)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Картинка на всю высоту", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(look.pictureFull, { set(look.copy(pictureFull = it)) })
            }
            Tune(if (look.pictureFull) "Картинка, не меньше" else "Размер картинки", look.pictureDp, 24, 200, "dp") {
                set(look.copy(pictureDp = it))
            }
            Tune("Размер текста", look.textPercent, 60, 200, "%") { set(look.copy(textPercent = it)) }
            Tune("Непрозрачность", look.opacityPercent, 10, 100, "%") { set(look.copy(opacityPercent = it)) }
            Tune("Скругление углов", look.cornerDp, 0, 40, "dp") { set(look.copy(cornerDp = it)) }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { set(UpdateNotice.Look.DEFAULT) }) { Text("Сбросить") }
                Button(onClick = { context.activity()?.let { UpdateNotice.preview(it) } }) { Text("Показать") }
            }
            Text(
                "Сейчас: выезжает ${look.from}, стоит ${look.place}, отступ ${look.marginDp} dp, ширина ${look.widthPercent}%, " +
                    "картинка ${look.pictureDp} dp${if (look.pictureFull) " (на всю высоту)" else ""}, текст ${look.textPercent}%, " +
                    "непрозрачность ${look.opacityPercent}%, углы ${look.cornerDp} dp",
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, text) in options) FilterChip(selected = value == id, onClick = { onPick(id) }, label = { Text(text) })
    }
}

@Composable
private fun Tune(label: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${v.roundToInt()} $unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v.roundToInt()) },
        valueRange = min.toFloat()..max.toFloat())
}

private fun android.content.Context.activity(): Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
