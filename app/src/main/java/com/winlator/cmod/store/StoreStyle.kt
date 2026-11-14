package com.winlator.cmod.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The look of each store inside the app, close to the real one: Steam's dark blue with a green
 * "install" button and nearly square buttons, Epic's near-black with its light blue button and gray
 * secondary buttons.
 */
data class StoreStyle(
    val background: Brush,
    val buttonShape: Shape,
    val cardShape: Shape,
    /** Main action (install, add, sign in). */
    val positive: Color,
    val onPositive: Color,
    /** Other actions. */
    val accent: Color,
    val onAccent: Color,
    val secondary: Color,
    val onSecondary: Color,
    /** The store's own name style in headers. */
    val title: Color,
)

val LocalStoreStyle = staticCompositionLocalOf<StoreStyle?> { null }

private val steamStyle = StoreStyle(
    background = Brush.verticalGradient(0f to Color(0xFF2A475E), 0.32f to Color(0xFF1B2838), 1f to Color(0xFF171A21)),
    buttonShape = RoundedCornerShape(3.dp),
    cardShape = RoundedCornerShape(4.dp),
    positive = Color(0xFF5C9E2E), onPositive = Color(0xFFE8F5D2),
    accent = Color(0xFF1A9FFF), onAccent = Color.White,
    secondary = Color(0x3367C1F5), onSecondary = Color(0xFF67C1F5),
    title = Color(0xFFC7D5E0),
)

private val epicStyle = StoreStyle(
    background = Brush.verticalGradient(listOf(Color(0xFF18181C), Color(0xFF101014))),
    buttonShape = RoundedCornerShape(10.dp),
    cardShape = RoundedCornerShape(12.dp),
    positive = Color(0xFF26BBFF), onPositive = Color(0xFF101014),
    accent = Color(0xFF26BBFF), onAccent = Color(0xFF101014),
    secondary = Color(0xFF343437), onSecondary = Color.White,
    title = Color.White,
)

fun styleOf(store: Store) = if (store == Store.STEAM) steamStyle else epicStyle

/** Wraps a store's screens: its colors for every Material component, and its background. */
@Composable
fun StoreTheme(store: Store, content: @Composable () -> Unit) {
    val b = MaterialTheme.colorScheme
    val scheme = if (store == Store.STEAM) b.copy(
        primary = Color(0xFF66C0F4), onPrimary = Color(0xFF0B1520),
        primaryContainer = Color(0xFF2A475E), onPrimaryContainer = Color(0xFFC7D5E0),
        secondary = Color(0xFF66C0F4), onSecondary = Color(0xFF0B1520),
        secondaryContainer = Color(0xFF2A475E), onSecondaryContainer = Color(0xFFE5F2FA),
        tertiary = Color(0xFFA4D007), onTertiary = Color(0xFF1B2838),
        background = Color(0xFF1B2838), onBackground = Color(0xFFC7D5E0),
        surface = Color(0xFF1B2838), onSurface = Color(0xFFDCE6EF),
        surfaceVariant = Color(0xFF233447), onSurfaceVariant = Color(0xFF8F98A0),
        surfaceTint = Color(0xFF66C0F4),
        surfaceBright = Color(0xFF2A3F55), surfaceDim = Color(0xFF131A22),
        surfaceContainerLowest = Color(0xFF131A22), surfaceContainerLow = Color(0xFF1B2838),
        surfaceContainer = Color(0xFF1F2E40), surfaceContainerHigh = Color(0xFF233447), surfaceContainerHighest = Color(0xFF2A3F55),
        outline = Color(0xFF4B6B8A), outlineVariant = Color(0xFF2E4258),
        error = Color(0xFFE5554F), onError = Color.White,
    ) else b.copy(
        primary = Color(0xFF26BBFF), onPrimary = Color(0xFF101014),
        primaryContainer = Color(0xFF26BBFF), onPrimaryContainer = Color(0xFF101014),
        secondary = Color(0xFFFFFFFF), onSecondary = Color(0xFF101014),
        secondaryContainer = Color(0xFF343437), onSecondaryContainer = Color.White,
        tertiary = Color(0xFF26BBFF), onTertiary = Color(0xFF101014),
        background = Color(0xFF101014), onBackground = Color.White,
        surface = Color(0xFF18181C), onSurface = Color.White,
        surfaceVariant = Color(0xFF202024), onSurfaceVariant = Color(0xFFA7A7A7),
        surfaceTint = Color(0xFF26BBFF),
        surfaceBright = Color(0xFF2A2A2E), surfaceDim = Color(0xFF0C0C0F),
        surfaceContainerLowest = Color(0xFF0C0C0F), surfaceContainerLow = Color(0xFF18181C),
        surfaceContainer = Color(0xFF202024), surfaceContainerHigh = Color(0xFF26262A), surfaceContainerHighest = Color(0xFF2E2E33),
        outline = Color(0xFF55555A), outlineVariant = Color(0xFF343437),
        error = Color(0xFFFF6B6B), onError = Color(0xFF101014),
    )
    val style = styleOf(store)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalStoreStyle provides style) {
            Box(Modifier.fillMaxSize().background(style.background)) { content() }
        }
    }
}

/** A store's button: [positive] for the main action, otherwise its plain accent button. */
@Composable
fun StoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, positive: Boolean = false,
                content: @Composable RowScope.() -> Unit) {
    val s = LocalStoreStyle.current
    if (s == null) { Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content); return }
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = s.buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (positive) s.positive else s.accent,
            contentColor = if (positive) s.onPositive else s.onAccent,
            disabledContainerColor = s.secondary.copy(alpha = .35f),
            disabledContentColor = s.onSecondary.copy(alpha = .5f),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), content = content)
}

/** A store's secondary button (pause, cancel, remove…). */
@Composable
fun StoreSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                         content: @Composable RowScope.() -> Unit) {
    val s = LocalStoreStyle.current
    if (s == null) { Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content); return }
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = s.buttonShape,
        colors = ButtonDefaults.buttonColors(containerColor = s.secondary, contentColor = s.onSecondary),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), content = content)
}
