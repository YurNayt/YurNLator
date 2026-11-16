package com.winlator.cmod.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * The app's motion for things that open and close on a tap (a driver's versions in a picker,
 * a list under a field, the arrow that turns with it): one curve and one length everywhere, so
 * they read as one family. CSS "ease-out", cubic-bezier(0, 0, 0.58, 1), over 0.5 s.
 */
object Motion {
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    const val EXPAND_MS = 500

    fun <T> expand(): TweenSpec<T> = tween(EXPAND_MS, easing = EaseOut)
}
