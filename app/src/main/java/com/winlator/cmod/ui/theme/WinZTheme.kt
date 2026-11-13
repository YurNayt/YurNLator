package com.winlator.cmod.ui.theme

import android.R as AndroidR
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.winlator.cmod.R

enum class WinlatorThemeType(
    val id: String,
    val displayName: String,
    val description: String
) {
    WHITE("white", "White", "Bright surfaces with dark text"),
    BLACK("black", "Black", "Balanced dark theme · Default"),
    AMOLED("amoled", "AMOLED", "Pure black background for OLED displays"),
    BLUE("blue", "Blue", "Dark interface with cool blue accents"),
    RED("red", "Red", "Dark interface with warm red accents"),
    PURPLE("purple", "Purple", "Dark interface with rich purple accents"),
    GREEN("green", "Green", "Dark interface with soft green accents");

    companion object {
        fun fromId(id: String?): WinlatorThemeType = values().firstOrNull { it.id == id } ?: BLACK
    }
}

object WinlatorThemeManager {
    private const val PREF_KEY = "winlator_ui_theme"
    private const val PREF_ACCENT = "winlator_ui_accent"
    private const val PREF_TINT = "winlator_ui_tint"
    private val currentState = mutableStateOf(WinlatorThemeType.BLACK)
    /** Accent colour (ARGB) over the theme, 0 = the theme's own. */
    private val accentState = androidx.compose.runtime.mutableIntStateOf(0)
    /** Colour the whole interface is tinted with (ARGB), 0 = the theme's own. */
    private val tintState = androidx.compose.runtime.mutableIntStateOf(0)
    private var initialized = false

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        currentState.value = WinlatorThemeType.fromId(prefs.getString(PREF_KEY, WinlatorThemeType.BLACK.id))
        accentState.intValue = prefs.getInt(PREF_ACCENT, 0)
        tintState.intValue = prefs.getInt(PREF_TINT, 0)
        initialized = true
    }

    @Composable
    fun currentTheme(): WinlatorThemeType {
        val context = LocalContext.current
        ensureInitialized(context)
        return currentState.value
    }

    fun currentTheme(context: Context): WinlatorThemeType {
        ensureInitialized(context)
        return currentState.value
    }

    /** The accent colour; read in composition, so changing it recolours the UI at once. */
    val accent: Int get() = accentState.intValue

    fun accent(context: Context): Int { ensureInitialized(context); return accentState.intValue }

    fun setAccent(context: Context, argb: Int) {
        ensureInitialized(context)
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit().putInt(PREF_ACCENT, argb).apply()
        accentState.intValue = argb
    }

    /** The interface colour; read in composition like [accent]. */
    val tint: Int get() = tintState.intValue

    fun setTint(context: Context, argb: Int) {
        ensureInitialized(context)
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit().putInt(PREF_TINT, argb).apply()
        tintState.intValue = argb
    }

    fun setTheme(context: Context, theme: WinlatorThemeType) {
        ensureInitialized(context)
        if (currentState.value == theme) return
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putString(PREF_KEY, theme.id)
            .apply()
        currentState.value = theme
    }
}

private val BlackColors = darkColorScheme(
    primary = Color(0xFFF4F4F6), onPrimary = Color(0xFF090A0D),
    primaryContainer = Color(0xFF303138), onPrimaryContainer = Color(0xFFF7F7F8),
    secondary = Color(0xFFC7C8CF), onSecondary = Color(0xFF111217),
    secondaryContainer = Color(0xFF24252B), onSecondaryContainer = Color(0xFFE7E7EA),
    background = Color(0xFF06070A), onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF101116), onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1D23), onSurfaceVariant = Color(0xFFA8A9B1),
    outline = Color(0xFF41434D), outlineVariant = Color(0xFF2A2B32),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val AmoledColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF191919), onPrimaryContainer = Color.White,
    secondary = Color(0xFFD0D0D0), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF101010), onSecondaryContainer = Color(0xFFECECEC),
    background = Color.Black, onBackground = Color(0xFFF7F7F7),
    surface = Color(0xFF050505), onSurface = Color(0xFFF7F7F7),
    surfaceVariant = Color(0xFF0D0D0D), onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF383838), outlineVariant = Color(0xFF202020),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val WhiteColors = lightColorScheme(
    primary = Color(0xFF23252B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E3E8), onPrimaryContainer = Color(0xFF17191E),
    secondary = Color(0xFF555861), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E8EC), onSecondaryContainer = Color(0xFF26282E),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF18191D),
    surface = Color.White, onSurface = Color(0xFF18191D),
    surfaceVariant = Color(0xFFE8E9ED), onSurfaceVariant = Color(0xFF60636B),
    outline = Color(0xFF92959D), outlineVariant = Color(0xFFD1D3D8),
    error = Color(0xFFBA1A1A), onError = Color.White
)

private val BlueColors = darkColorScheme(
    primary = Color(0xFF82B8FF), onPrimary = Color(0xFF001B3A),
    primaryContainer = Color(0xFF173A63), onPrimaryContainer = Color(0xFFD5E7FF),
    secondary = Color(0xFFAFC9EA), onSecondary = Color(0xFF102033),
    secondaryContainer = Color(0xFF17283D), onSecondaryContainer = Color(0xFFD3E5FF),
    background = Color(0xFF050A12), onBackground = Color(0xFFF2F6FC),
    surface = Color(0xFF0C1320), onSurface = Color(0xFFF2F6FC),
    surfaceVariant = Color(0xFF121D2D), onSurfaceVariant = Color(0xFFA9B9CD),
    outline = Color(0xFF45617F), outlineVariant = Color(0xFF25384F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val RedColors = darkColorScheme(
    primary = Color(0xFFFF8A8F), onPrimary = Color(0xFF3C0006),
    primaryContainer = Color(0xFF662128), onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFE7B7BA), onSecondary = Color(0xFF301416),
    secondaryContainer = Color(0xFF432326), onSecondaryContainer = Color(0xFFFFDADB),
    background = Color(0xFF0C0607), onBackground = Color(0xFFFFF2F2),
    surface = Color(0xFF160B0D), onSurface = Color(0xFFFFF2F2),
    surfaceVariant = Color(0xFF241214), onSurfaceVariant = Color(0xFFCDB0B2),
    outline = Color(0xFF74454A), outlineVariant = Color(0xFF45292C),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val PurpleColors = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF0E0A13), onBackground = Color(0xFFE9E1EC),
    surface = Color(0xFF17111E), onSurface = Color(0xFFE9E1EC),
    surfaceVariant = Color(0xFF241C2D), onSurfaceVariant = Color(0xFFCCC2DC),
    outline = Color(0xFF958DA0), outlineVariant = Color(0xFF494151),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val GreenColors = darkColorScheme(
    primary = Color(0xFF9DC9A8), onPrimary = Color(0xFF0B2414),
    primaryContainer = Color(0xFF234430), onPrimaryContainer = Color(0xFFD3EBD9),
    secondary = Color(0xFFB4CCB9), onSecondary = Color(0xFF15241A),
    secondaryContainer = Color(0xFF1F3125), onSecondaryContainer = Color(0xFFD0E6D5),
    background = Color(0xFF060B08), onBackground = Color(0xFFF0F5F1),
    surface = Color(0xFF0D1510), onSurface = Color(0xFFF0F5F1),
    surfaceVariant = Color(0xFF15201A), onSurfaceVariant = Color(0xFFAEC2B3),
    outline = Color(0xFF4A6352), outlineVariant = Color(0xFF26362C),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

/**
 * The theme's colours, tinted with the interface colour and then given the accent colour
 * (Settings > Appearance). With no accent of its own, the interface colour is the accent too.
 */
internal fun winlatorColorScheme(theme: WinlatorThemeType): ColorScheme {
    val light = theme == WinlatorThemeType.WHITE
    val tint = WinlatorThemeManager.tint
    val accent = WinlatorThemeManager.accent.takeIf { it != 0 } ?: tint
    return baseColorScheme(theme).withTint(tint, light, theme == WinlatorThemeType.AMOLED).withAccent(accent, light)
}

/** Interface colour swatches (the accent swatches without the neutral white). */
val WINLATOR_TINTS: List<Int> get() = WINLATOR_ACCENTS.dropLast(1)

/**
 * Backgrounds, cards, outlines and secondary text take on the interface colour; text stays
 * readable since only a small share of the colour is mixed into each tone.
 */
private fun ColorScheme.withTint(argb: Int, light: Boolean, amoled: Boolean): ColorScheme {
    if (argb == 0) return this
    val c = Color(argb)
    fun mix(base: Color, k: Float) = androidx.compose.ui.graphics.lerp(base, c, k)
    val s = if (light) 0.75f else 1f
    return copy(
        background = if (amoled) background else mix(background, 0.07f * s),
        surface = mix(surface, (if (amoled) 0.05f else 0.10f) * s),
        surfaceVariant = mix(surfaceVariant, 0.14f * s),
        surfaceContainerLowest = mix(background, 0.05f * s),
        surfaceContainerLow = mix(surface, 0.08f * s),
        surfaceContainer = mix(surface, 0.11f * s),
        surfaceContainerHigh = mix(surfaceVariant, 0.13f * s),
        surfaceContainerHighest = mix(surfaceVariant, 0.17f * s),
        surfaceBright = mix(surfaceVariant, 0.18f * s),
        surfaceDim = mix(background, 0.06f * s),
        inverseSurface = mix(inverseSurface, 0.10f),
        secondaryContainer = mix(secondaryContainer, 0.20f * s),
        onSecondaryContainer = mix(onSecondaryContainer, 0.15f),
        tertiary = mix(tertiary, 0.5f),
        tertiaryContainer = mix(tertiaryContainer, 0.25f * s),
        onBackground = mix(onBackground, 0.05f),
        onSurface = mix(onSurface, 0.05f),
        onSurfaceVariant = mix(onSurfaceVariant, 0.28f),
        outline = mix(outline, 0.32f),
        outlineVariant = mix(outlineVariant, 0.22f * s)
    )
}

/** Accent swatches of the palette (dark-theme shades; a light theme gets them darker). */
val WINLATOR_ACCENTS: List<Int> = listOf(
    0xFF82B8FF, 0xFF7FD3F5, 0xFF6FD6C4, 0xFF9DC9A8, 0xFFB5D46A, 0xFFF2D06B,
    0xFFFFB074, 0xFFFF8A8F, 0xFFF48FB1, 0xFFD0BCFF, 0xFFB9B4F5, 0xFFF4F4F6
).map { it.toInt() }

internal fun accentFor(argb: Int, light: Boolean): Color =
    if (light) androidx.compose.ui.graphics.lerp(Color(argb), Color.Black, 0.45f) else Color(argb)

private fun ColorScheme.withAccent(argb: Int, light: Boolean): ColorScheme {
    if (argb == 0) return this
    val a = accentFor(argb, light)
    val onA = if (a.luminance() > 0.45f) Color(0xFF111215) else Color.White
    val container = androidx.compose.ui.graphics.lerp(a, if (light) Color.White else Color.Black, 0.6f)
    return copy(
        primary = a, onPrimary = onA,
        primaryContainer = container, onPrimaryContainer = if (light) Color(0xFF15161A) else Color(0xFFF2F3F5),
        secondary = androidx.compose.ui.graphics.lerp(secondary, a, 0.35f)
    )
}

private fun baseColorScheme(theme: WinlatorThemeType): ColorScheme = when (theme) {
    WinlatorThemeType.WHITE -> WhiteColors
    WinlatorThemeType.BLACK -> BlackColors
    WinlatorThemeType.AMOLED -> AmoledColors
    WinlatorThemeType.BLUE -> BlueColors
    WinlatorThemeType.RED -> RedColors
    WinlatorThemeType.PURPLE -> PurpleColors
    WinlatorThemeType.GREEN -> GreenColors
}

private val WinlatorTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
)

private val WinlatorShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val theme = WinlatorThemeManager.currentTheme()
    val colors = winlatorColorScheme(theme)
    HideSystemBars(theme)
    ApplyLegacyChrome(colors)
    MaterialTheme(colorScheme = colors, typography = WinlatorTypography, shapes = WinlatorShapes) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}

@Composable
fun WinZTheme(content: @Composable () -> Unit) = WinlatorTheme(content)

@Composable
private fun HideSystemBars(theme: WinlatorThemeType) {
    val activity = LocalContext.current.findActivity()
    val colors = winlatorColorScheme(theme)
    DisposableEffect(activity, theme) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val light = theme == WinlatorThemeType.WHITE
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        onDispose { }
    }
}

@Composable
private fun ApplyLegacyChrome(colors: ColorScheme) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, colors) {
        activity?.let { host ->
            val background = colors.background.toArgb()
            val surface = colors.surface.toArgb()
            val onSurface = colors.onSurface.toArgb()

            host.window.decorView.setBackgroundColor(background)
            host.findViewById<DrawerLayout>(R.id.DrawerLayout)?.let { drawerLayout ->
                drawerLayout.setBackgroundColor(background)
                if (drawerLayout.childCount > 0) {
                    drawerLayout.getChildAt(0)?.setBackgroundColor(background)
                }
            }
            host.findViewById<View>(R.id.FLFragmentContainer)?.setBackgroundColor(background)

            val toolbar = host.findViewById<Toolbar>(R.id.Toolbar)
            toolbar?.setBackgroundColor(surface)
            toolbar?.setTitleTextColor(onSurface)
            toolbar?.navigationIcon = toolbar?.navigationIcon?.mutate()?.apply { setTint(onSurface) }
            toolbar?.menu?.let { menu ->
                for (i in 0 until menu.size()) {
                    menu.getItem(i).icon?.mutate()?.setTint(onSurface)
                }
            }

            val selectedStates = arrayOf(
                intArrayOf(AndroidR.attr.state_checked),
                intArrayOf()
            )
            val navColors = intArrayOf(colors.primary.toArgb(), colors.onSurfaceVariant.toArgb())
            val tint = ColorStateList(selectedStates, navColors)

            host.findViewById<BottomNavigationView>(R.id.BottomNavigation)?.let { bottom ->
                ViewCompat.setBackgroundTintList(bottom, ColorStateList.valueOf(surface))
                bottom.itemIconTintList = tint
                bottom.itemTextColor = tint
                bottom.itemRippleColor = ColorStateList.valueOf(colors.primary.copy(alpha = 0.14f).toArgb())
            }

            host.findViewById<NavigationView>(R.id.NavigationView)?.let { drawer ->
                drawer.setBackgroundColor(surface)
                drawer.itemIconTintList = tint
                drawer.itemTextColor = ColorStateList.valueOf(onSurface)
            }
        }
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
