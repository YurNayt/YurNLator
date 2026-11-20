package com.winlator.cmod.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.inputcontrols.PcInput
import com.winlator.cmod.ui.uiStr
import kotlin.math.roundToInt

/**
 * The right stick's (mouse) speed for the "PC" gamepad mode, 0.2..5 in steps of 0.1.
 * [onCommit] runs when the finger lets go: save there.
 */
@Composable
fun PcStickSpeedSetting(value: Float, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(uiStr(R.string.l_pc_stick_speed), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(PcInput.format(v), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = v,
            onValueChange = { v = (it * 10f).roundToInt() / 10f },
            onValueChangeFinished = { onCommit(v) },
            valueRange = PcInput.MIN_SPEED..PcInput.MAX_SPEED,
            steps = ((PcInput.MAX_SPEED - PcInput.MIN_SPEED) * 10f).roundToInt() - 1
        )
        Text(uiStr(R.string.l_pc_stick_speed_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
