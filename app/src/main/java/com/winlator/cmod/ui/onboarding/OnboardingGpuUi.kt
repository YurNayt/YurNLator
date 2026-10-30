package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.GpuProfileManager
import com.winlator.cmod.ui.gpu.DetectedGpuCard
import com.winlator.cmod.ui.gpu.GpuProfileOptions

/**
 * First-launch step: shows the detected GPU and pre-selects the matching profile.
 * The choice is saved on Continue, so leaving the app midway keeps the detection.
 */
@Composable
internal fun OnboardingGpuScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val detected = remember { GpuProfileManager.detect(context) }
    var selected by remember { mutableStateOf(GpuProfileManager.current(context)) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    val confirm = {
        GpuProfileManager.set(context, selected)
        onContinue()
    }

    val header: @Composable () -> Unit = {
        Text("Your graphics chip", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "We picked settings that suit your GPU. You can change the profile later in Settings.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DetectedGpuCard(detected)
            GpuProfileOptions(detected, selected) { selected = it }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                Button(onClick = confirm, modifier = Modifier.weight(1f)) { Text("Continue") }
            }
        }
    }

    if (landscape) {
        Row(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 42.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(0.8f)) { header() }
            Column(Modifier.weight(1.2f).widthIn(max = 620.dp)) { body() }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.widthIn(max = 620.dp)) {
                header()
                Spacer(Modifier.height(20.dp))
                body()
            }
        }
    }
}
