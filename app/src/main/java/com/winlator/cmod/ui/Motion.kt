package com.winlator.cmod.ui

import android.content.Context
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween as composeTween
import androidx.compose.runtime.mutableStateMapOf
import androidx.preference.PreferenceManager
import com.winlator.cmod.R

/** A curve as on cubic-bezier.com (x1, y1, x2, y2) and how long it runs. */
data class MotionCurve(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Int) {
    fun easing() = CubicBezierEasing(x1, y1, x2, y2)
    fun interpolator(): Interpolator = PathInterpolator(x1.coerceIn(0f, 1f), y1, x2.coerceIn(0f, 1f), y2)
    fun encode() = "$x1,$y1,$x2,$y2,$ms"

    companion object {
        fun decode(s: String?): MotionCurve? = runCatching {
            val p = s!!.split(',')
            MotionCurve(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toInt())
        }.getOrNull()
    }
}

/** The kinds of motion that can be tuned, each with the curve it ships with. */
enum class MotionKind(val key: String, val titleRes: Int, val default: MotionCurve) {
    /** The new tab's screen fading in and rising (the old one fades out in TAB_OUT). */
    TAB_IN("tab_in", R.string.motion_tab_in, MotionCurve(0f, 0f, 0.58f, 1f, 200)),
    TAB_OUT("tab_out", R.string.motion_tab_out, MotionCurve(0.42f, 0f, 1f, 1f, 70)),
    /** Picker windows (drivers, Proton, DXVK…) sliding up and away. */
    SHEET("sheet", R.string.motion_sheet, MotionCurve(0.4f, 0f, 0.2f, 1f, 300)),
    /** Lists and groups opening and closing on a tap, and their arrows. */
    EXPAND("expand", R.string.motion_expand, MotionCurve(0f, 0f, 0.58f, 1f, 500)),
    /** A game's page sliding in over the library and back out. */
    PAGE("page", R.string.motion_page, MotionCurve(0.32f, 0.72f, 0f, 1f, 260)),
    /** The known-variables list in the add-variable window. Until set, the picker windows' curve, 1.1 × as long. */
    VAR_LIST("var_list", R.string.motion_var_list, MotionCurve(0.4f, 0f, 0.2f, 1f, 330));
}

/**
 * The app's motion: one curve and length per kind, the same everywhere that kind appears. The
 * values can be changed while the app runs (the floating motion editor) and are kept; Compose
 * reads them as state, so a change applies to the next animation without a restart.
 */
object Motion {
    private const val PREFIX = "motion_"
    private val curves = mutableStateMapOf<MotionKind, MotionCurve>()
    private var prefs: android.content.SharedPreferences? = null

    @JvmStatic
    fun init(context: Context) {
        if (prefs != null) return
        val p = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs = p
        MotionKind.entries.forEach { k -> MotionCurve.decode(p.getString(PREFIX + k.key, null))?.let { curves[k] = it } }
    }

    @JvmStatic
    fun curve(kind: MotionKind): MotionCurve = curves[kind] ?: when (kind) {
        MotionKind.VAR_LIST -> curve(MotionKind.SHEET).let { it.copy(ms = Math.round(it.ms * 1.1f)) }
        else -> kind.default
    }

    @JvmStatic
    fun set(kind: MotionKind, curve: MotionCurve) {
        curves[kind] = curve
        prefs?.edit()?.putString(PREFIX + kind.key, curve.encode())?.apply()
    }

    @JvmStatic
    fun reset(kind: MotionKind) {
        curves.remove(kind)
        prefs?.edit()?.remove(PREFIX + kind.key)?.apply()
    }

    /** For View animations (Java). */
    @JvmStatic
    fun interpolator(kind: MotionKind): Interpolator = curve(kind).interpolator()

    @JvmStatic
    fun duration(kind: MotionKind): Long = curve(kind).ms.toLong()

    fun <T> tween(kind: MotionKind): TweenSpec<T> = curve(kind).let { composeTween(it.ms, easing = it.easing()) }

    /** Lists and groups opening on a tap. */
    fun <T> expand(): TweenSpec<T> = tween(MotionKind.EXPAND)

    /** Kept for callers of the first version. */
    val EaseOut get() = curve(MotionKind.EXPAND).easing()
}
