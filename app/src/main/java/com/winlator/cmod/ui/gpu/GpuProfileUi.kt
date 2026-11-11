package com.winlator.cmod.ui.gpu

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.winlator.cmod.core.DetectedGpu
import com.winlator.cmod.core.GpuProfile
import com.winlator.cmod.core.GpuProfileManager

/** Card showing what the device reports: "ARM Mali · Mali-G57 MC2". */
@Composable
fun DetectedGpuCard(detected: DetectedGpu, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.DeveloperBoard, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(uiStr(R.string.l_gpu_detected_gpu), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detected.renderer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detected.vendor.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The three profile options. Picking one that doesn't match the hardware asks for
 * confirmation first; [onSelected] fires only for confirmed choices.
 */
@Composable
fun GpuProfileOptions(
    detected: DetectedGpu,
    selected: GpuProfile,
    onSelected: (GpuProfile) -> Unit
) {
    var pendingMismatch by remember { mutableStateOf<GpuProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GpuProfile.values().forEach { profile ->
            ProfileOption(
                profile = profile,
                selected = profile == selected,
                recommended = profile == detected.recommendedProfile,
                onClick = {
                    if (profile == selected) return@ProfileOption
                    if (profile != detected.recommendedProfile) pendingMismatch = profile
                    else onSelected(profile)
                }
            )
        }
    }

    pendingMismatch?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingMismatch = null },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(uiStr(R.string.l_gpu_use_the_1_s_profile, profile.title)) },
            text = {
                Text(
                    uiStr(R.string.l_gpu_your_device_has_1_s_2, detected.vendor.displayName, detected.renderer, profile.title)
                )
            },
            confirmButton = {
                TextButton(onClick = { onSelected(profile); pendingMismatch = null }) { Text(uiStr(R.string.l_gpu_use_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMismatch = null }) { Text(uiStr(R.string.l_qm_cancel)) }
            }
        )
    }
}

@Composable
private fun ProfileOption(profile: GpuProfile, selected: Boolean, recommended: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            uiStr(R.string.l_gpu_recommended),
                            modifier = Modifier
                                .background(scheme.primary.copy(alpha = 0.14f), RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary
                        )
                    }
                }
                Text(
                    profile.summary,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Settings row: shows the active profile, opens the chooser in a dialog. */
@Composable
fun GpuProfilePreferenceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val detected = remember { GpuProfileManager.detect(context) }
    var current by remember { mutableStateOf(GpuProfileManager.current(context)) }
    var open by remember { mutableStateOf(false) }
    val mismatch = current != detected.recommendedProfile

    Surface(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.DeveloperBoard, null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.l_gpu_gpu_profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${current.title} · ${detected.renderer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (mismatch) {
                    Text(
                        uiStr(R.string.l_gpu_doesn_t_match_your_gpu_games),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(uiStr(R.string.l_gpu_gpu_profile)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetectedGpuCard(detected)
                    Text(
                        uiStr(R.string.l_gpu_applies_to_containers_you_create_from),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GpuProfileOptions(detected, current) {
                        GpuProfileManager.set(context, it)
                        current = it
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(uiStr(R.string.l_ed_done)) } }
        )
    }
}
