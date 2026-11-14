package com.winlator.cmod.store

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which store a library game came from: "steam", "epic" or "" (added by hand). */
object StoreOrigin {
    @JvmStatic
    fun of(shortcut: Shortcut?): String = when {
        shortcut == null -> ""
        shortcut.getExtra("steamAppId").isNotEmpty() -> "steam"
        shortcut.getExtra("epicApp").isNotEmpty() -> "epic"
        else -> ""
    }
}

/** The store's icon on a small tile, for a corner of a library cover. Nothing for other games. */
@Composable
fun StoreBadge(origin: String, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val store = when (origin) { "steam" -> Store.STEAM; "epic" -> Store.EPIC; else -> return }
    val context = LocalContext.current
    val bitmap by produceState(StoreIcons.cached(store), store) {
        if (value == null) value = withContext(Dispatchers.IO) { StoreIcons.load(context, store) }
    }
    val b = bitmap ?: return
    Box(modifier.size(size).background(Color.White.copy(alpha = .92f), RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        Image(b.asImageBitmap(), store.title, Modifier.size(size * 0.78f), contentScale = ContentScale.Fit)
    }
}
