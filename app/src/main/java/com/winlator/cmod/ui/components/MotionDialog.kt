package com.winlator.cmod.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A dialog that looks like Material's AlertDialog but is drawn inside the current window (see
 * InWindowOverlay): its content can grow (a list opening) smoothly downwards, fully grown it is
 * centred, without a window being resized frame by frame behind it or re-centred. In a small
 * dialog window it falls back to an AlertDialog.
 */
@Composable
fun MotionDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    /** How much taller the content can grow (a list opening): the dialog is placed so that, fully
     *  grown, it sits in the middle of the screen, and it grows downwards from there. */
    growth: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val root = rememberOverlayRoot()
    if (root == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = { Text(title) },
            text = { Column(content = content) }
        )
        return
    }
    InWindowOverlay(root) {
        val shown = remember { Animatable(0f) }
        LaunchedEffect(Unit) { shown.animateTo(1f, tween(160)) }
        BackHandler { onDismissRequest() }
        val density = LocalDensity.current
        // Its top stays where its first (closed) height plus [growth] would be centred.
        var topPx by remember { mutableIntStateOf(-1) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenPx = constraints.maxHeight
            val growthPx = with(density) { growth.roundToPx() }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = shown.value }
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismissRequest() }
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { if (topPx < 0) topPx = ((screenPx - it.height - growthPx) / 2).coerceAtLeast(with(density) { 16.dp.roundToPx() }) }
                    .offset { IntOffset(0, topPx.coerceAtLeast(0)) }
                    .padding(start = 24.dp, end = 24.dp)
                    .imePadding()
                    .widthIn(min = 280.dp, max = 560.dp)
                    .graphicsLayer {
                        alpha = if (topPx < 0) 0f else shown.value
                        val s = 0.96f + 0.04f * shown.value
                        scaleX = s; scaleY = s
                    },
                shape = RoundedCornerShape(28.dp),
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
                    Spacer(Modifier.height(16.dp))
                    Column(content = content)
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        dismissButton(); confirmButton()
                    }
                }
            }
        }
    }
}
