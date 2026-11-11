package com.winlator.cmod.ui

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.theme.WinZTheme

object FileManagerLandscapeNavHost {
    @JvmStatic
    fun create(activity: MainActivity): ComposeView = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            WinZTheme {
                LandscapeMainNavigation(
                    activity = activity,
                    selected = activity.selectedDestination,
                    title = uiStr(R.string.l_set_file_manager)
                )
            }
        }
    }
}
