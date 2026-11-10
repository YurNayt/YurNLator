package com.winlator.cmod.widget.banner

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.winlator.cmod.widget.banner.fusionhud.FusionHudView
import com.winlator.cmod.widget.banner.fusionhud.FusionSize
import com.winlator.cmod.widget.banner.perfhud.PerformanceHudView

/**
 * Bannerlator's HUD styles (ported from Bannerlator, GPL-3) shown for our HUD's "Bannerlator" style.
 * [com.winlator.cmod.widget.WinlatorHUD] stays the engine (frames, renderer detection, show / hide,
 * labels) and hosts this: the chosen view sits next to it in the game screen.
 *
 * A tap goes to the next look: Fusion Full → Tiles → Pill → Minimal → Mega → GameHub (vertical,
 * horizontal) → GameNative (list, compact) → Fusion Full. A drag moves it, a long press locks it.
 */
class BannerHud(private val context: Context) {
    /** Game frames: FPS, average and the 1% / 0.1% lows. */
    val fps = FpsCounter()

    private val prefs = context.getSharedPreferences("banner_hud", Context.MODE_PRIVATE)
    private var parent: ViewGroup? = null
    private var view: View? = null
    private var variant = prefs.getInt("variant", 0).coerceIn(0, VARIANTS - 1)
    private var visible = false
    private var engine = ""
    private var gpuModel = ""
    private var presented = 0f

    fun tick() = fps.tick()

    fun attach(parent: ViewGroup) {
        if (this.parent === parent && view != null) return
        detach()
        this.parent = parent
        build()
    }

    fun detach() {
        stopTicker()
        view?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        view = null
        parent = null
    }

    fun setVisible(on: Boolean) {
        visible = on
        view?.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun setEngine(label: String?) {
        val s = label ?: ""
        if (s == engine) return
        engine = s
        when (val v = view) {
            is FusionHudView -> v.setEngineLabel(s)
            is PerfHudView -> v.setEngineLabel(s)
            is PerformanceHudView -> v.setEngineLabel(s)
        }
    }

    fun setGpuModel(name: String?) {
        val s = shortGpu(name)
        if (s == gpuModel) return
        gpuModel = s
        when (val v = view) {
            is FusionHudView -> v.setGpuModel(s)
            is PerfHudView -> v.setGpuModel(s)
            is PerformanceHudView -> v.setGpuModel(s)
        }
    }

    /** What reaches the screen when frame generation adds frames (0 = same as the game). */
    fun setPresentedFps(value: Float) {
        presented = value
        when (val v = view) {
            is FusionHudView -> v.setPresentedFps(value)
            is PerfHudView -> v.setPresentedFps(value)
            is PerformanceHudView -> v.setPresentedFps(value)
        }
    }

    private fun locked() = prefs.getBoolean("locked", false)

    /** The quick menu's HUD size (1 = Bannerlator's sizes at [SCALE] %). */
    private var size: Float = prefs.getFloat("size", 1f)

    fun getSize(): Float = size

    fun setSize(value: Float) {
        val v = value.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(v - size) < 0.01f) return
        size = v
        prefs.edit().putFloat("size", v).apply()
        val root = parent ?: return
        view?.let { root.removeView(it) }
        view = null
        stopTicker()
        build()
    }

    private fun config(extra: String): String =
        "showFPS=1,showFPSGraph=1,showCPUUsage=1,showCPULoad=1,showGPULoad=1,showRAM=1,showVram=1," +
            "showPower=1,showTemp=1,showGpuTemp=1,showBattery=1,showEngine=1,showGpuModel=1,showLow001=1," +
            "hudScale=" + kotlin.math.round(SCALE * size).toInt() + ",hudLocked=" + (if (locked()) "1" else "0") + "," + extra

    private fun build() {
        val root = parent ?: return
        val v: View = when {
            variant < FUSION_COUNT -> FusionHudView(context).apply {
                applyConfig(config("hudSize=" + FusionSize.values()[variant].token))
                setOnSizeCycledListener { token ->
                    // Fusion cycles its own sizes; past Mega (back at Full) the next style comes.
                    if (token == FusionSize.FULL.token) post { select(FUSION_COUNT) }
                    else { variant = FusionSize.from(token).ordinal; save() }
                }
                setOnMovedListener { x, y -> savePos("fusion", x, y) }
                setOnLockChangedListener { l -> prefs.edit().putBoolean("locked", l).apply() }
            }
            variant < FUSION_COUNT + 2 -> PerfHudView(context).apply {
                applyConfig(config("hudMode=" + if (variant == FUSION_COUNT) "vertical" else "horizontal"))
                setVertical(variant == FUSION_COUNT)
                setOnTapListener { select(variant + 1) }
                setOnMovedListener { x, y -> savePos("gamehub", x, y) }
                setOnLockChangedListener { l -> prefs.edit().putBoolean("locked", l).apply() }
            }
            else -> PerformanceHudView(context).apply {
                val list = variant == FUSION_COUNT + 2
                applyConfig(config("hudMode=" + if (list) "vertical" else "horizontal"))
                setVertical(list)
                setOnTapListener { select(variant + 1) }
                setOnMovedListener { x, y -> savePos("gamenative", x, y) }
                setOnLockChangedListener { l -> prefs.edit().putBoolean("locked", l).apply() }
            }
        }
        when (v) {
            is FusionHudView -> { v.setFpsCounter(fps); v.setEngineLabel(engine); v.setGpuModel(gpuModel); v.setPresentedFps(presented) }
            is PerfHudView -> { v.setFpsCounter(fps); v.setEngineLabel(engine); v.setGpuModel(gpuModel); v.setPresentedFps(presented) }
            is PerformanceHudView -> { v.setFpsCounter(fps); v.setEngineLabel(engine); v.setGpuModel(gpuModel); v.setPresentedFps(presented) }
        }
        val fusion = v is FusionHudView
        v.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or if (fusion) Gravity.END else Gravity.START
        ).apply { topMargin = 10; leftMargin = 10; rightMargin = 10 }
        restorePos(v, when (v) { is FusionHudView -> "fusion"; is PerfHudView -> "gamehub"; else -> "gamenative" })
        // Keep it on screen when a tap makes it bigger (it grows from the edge it is anchored to).
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val p = view.parent as? View ?: return@addOnLayoutChangeListener
            if (view.width == 0 || view.height == 0) return@addOnLayoutChangeListener
            val x = view.x.coerceIn(0f, maxOf(0f, (p.width - view.width).toFloat()))
            val y = view.y.coerceIn(0f, maxOf(0f, (p.height - view.height).toFloat()))
            if (x != view.x) view.x = x
            if (y != view.y) view.y = y
        }
        v.visibility = if (visible) View.VISIBLE else View.GONE
        root.addView(v)
        view = v
        if (v is PerfHudView) startTicker(v) else stopTicker()
    }

    // GameHub's view only refreshes when told to (Bannerlator called it on every present): a small
    // thread does it twice a second, off the UI thread (it reads sysfs), so a paused game reads 0.
    @Volatile private var ticker: Thread? = null

    private fun startTicker(v: PerfHudView) {
        stopTicker()
        ticker = Thread({
            while (ticker === Thread.currentThread()) {
                try { v.update(); Thread.sleep(500) } catch (_: InterruptedException) { break } catch (_: Throwable) {}
            }
        }, "BannerHudTick").apply { isDaemon = true; start() }
    }

    private fun stopTicker() {
        val t = ticker
        ticker = null
        t?.interrupt()
    }

    /** Next look; rebuilt when it is another view, re-laid when only its arrangement changes. */
    private fun select(next: Int) {
        val n = ((next % VARIANTS) + VARIANTS) % VARIANTS
        val old = variant
        variant = n
        save()
        val v = view
        if (v is PerfHudView && n in FUSION_COUNT until FUSION_COUNT + 2 && old in FUSION_COUNT until FUSION_COUNT + 2) {
            v.setVertical(n == FUSION_COUNT); v.setOnTapListener { select(variant + 1) }; return
        }
        if (v is PerformanceHudView && n >= FUSION_COUNT + 2 && old >= FUSION_COUNT + 2) {
            v.setVertical(n == FUSION_COUNT + 2); v.setOnTapListener { select(variant + 1) }; return
        }
        val root = parent ?: return
        v?.let { root.removeView(it) }
        view = null
        stopTicker()
        build()
    }

    private fun save() = prefs.edit().putInt("variant", variant).apply()

    private fun savePos(key: String, x: Float, y: Float) =
        prefs.edit().putFloat("${key}_x", x).putFloat("${key}_y", y).apply()

    private fun restorePos(v: View, key: String) {
        if (!prefs.contains("${key}_x")) return
        val sx = prefs.getFloat("${key}_x", 0f)
        val sy = prefs.getFloat("${key}_y", 0f)
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (view.width == 0 || view.height == 0) return
                view.removeOnLayoutChangeListener(this)
                val p = view.parent as? View
                val maxX = if (p != null) maxOf(0f, (p.width - view.width).toFloat()) else sx
                val maxY = if (p != null) maxOf(0f, (p.height - view.height).toFloat()) else sy
                view.x = sx.coerceIn(0f, maxX)
                view.y = sy.coerceIn(0f, maxY)
            }
        })
    }

    companion object {
        /** Bannerlator's sizes at 82 %: they took a lot of a phone screen. */
        private const val SCALE = 82

        /** "Wrapper(Turnip Adreno (TM) 750)" -> "Adreno 750": the row is narrow. */
        fun shortGpu(name: String?): String {
            if (name.isNullOrBlank()) return ""
            var s = name.replace(Regex("(?i)\\((tm|r)\\)"), " ")
                .replace(Regex("(?i)\\b(wrapper|turnip|freedreno|mesa|qualcomm|arm|panvk|panfrost|vulkan|driver)\\b"), " ")
                .replace(Regex("(?i)\\bv?\\d+\\.\\d+[\\w.\\-]*"), " ")
                .replace(Regex("[()\\[\\]·|]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) s = name.trim()
            return if (s.length > 22) s.substring(0, 21).trim() + "…" else s
        }

        private val FUSION_COUNT = FusionSize.values().size
        /** Fusion's sizes, GameHub vertical / horizontal, GameNative list / compact. */
        val VARIANTS = FUSION_COUNT + 4
    }
}
