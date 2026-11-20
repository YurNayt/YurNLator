package com.winlator.cmod.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Sheets and full-screen dialogs open in their own window, which by default shows the
 * status / navigation bars while the app runs fullscreen. The bars appearing made the
 * screen behind jump. Call this inside the sheet / dialog content to keep it fullscreen.
 */
@Composable
fun KeepDialogFullscreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        var v: Any? = view
        var window: android.view.Window? = null
        while (v != null && window == null) {
            window = (v as? DialogWindowProvider)?.window
            v = (v as? View)?.parent
        }
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            // A dialog window stops below the status bar by default. Once the bars come back (the
            // keyboard opens, a swipe) the screen beneath showed through that strip (the
            // container's title over the Windows components list): reach the top edge instead;
            // the content keeps itself clear of the bars with its own padding.
            w.attributes = w.attributes.also { a ->
                if (android.os.Build.VERSION.SDK_INT >= 30) a.fitInsetsTypes = 0
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    a.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose { }
    }
}
