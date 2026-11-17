package com.winlator.cmod.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme

/**
 * Landscape: the bottom bar's tabs as a vertical bar on the right edge of the screen (same icons,
 * names and accent), drawn by MainActivity beside the screens so it stays put on a tab switch.
 */
object LandscapeNavRail {
    /** The selected tab (a main_menu_* id), set by MainActivity with the bottom bar's. */
    private val selected = mutableIntStateOf(0)
    private val bench = mutableStateOf(false)

    @JvmStatic fun select(menuItemId: Int) { selected.intValue = menuItemId }
    @JvmStatic fun setBenchVisible(visible: Boolean) { bench.value = visible }

    @JvmStatic
    fun install(activity: MainActivity, view: ComposeView) {
        view.setContent { WinZTheme { Rail(activity) } }
    }

    private class Tab(val id: Int, @DrawableRes val icon: Int, val label: Int)

    @Composable
    private fun Rail(activity: MainActivity) {
        val tabs = buildList {
            add(Tab(R.id.main_menu_stores, R.drawable.ui_nav_store, R.string.l_st_stores))
            add(Tab(R.id.main_menu_shortcuts, R.drawable.ui_nav_home, R.string.library))
            add(Tab(R.id.main_menu_containers, R.drawable.ui_nav_grid, R.string.containers))
            add(Tab(R.id.main_menu_settings, R.drawable.ui_nav_settings, R.string.settings))
            if (bench.value) add(Tab(R.id.main_menu_bench, R.drawable.ui_nav_bench, R.string.l_nav_benchmark))
        }
        Column(
            Modifier.fillMaxHeight().width(88.dp).background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (tab in tabs) {
                val on = selected.intValue == tab.id
                val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { if (!on) activity.navigateToMainDestination(tab.id) }
                        .padding(horizontal = 2.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.width(56.dp).height(32.dp).background(
                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(16.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) { Icon(painterResource(tab.icon), null, Modifier.size(23.dp), tint = color) }
                    Spacer(Modifier.height(3.dp))
                    Text(uiStr(tab.label), color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
