package com.winlator.cmod.ui.quickmenu

/** A choosable item (controls profile, …). */
data class QmOption(val id: Int, val name: String)

/** One "label: value" line of the session info card. */
data class QmInfo(val label: String, val value: String)

/** A running Windows process. */
data class QmProcess(val pid: Int, val name: String, val memory: String, val wow64: Boolean)

/**
 * Gyroscope aiming settings. target: 0 mouse, 1 right stick, 2 left stick;
 * activation: 0 always, 1 hold a button, 2 toggle with a button; button: gamepad button index.
 */
data class QmGyro(
    val available: Boolean,
    val enabled: Boolean,
    val target: Int,
    val sensitivity: Float,
    val invertX: Boolean,
    val invertY: Boolean,
    val fast: Boolean,
    val activation: Int,
    val button: Int,
    /** 0 auto, 1 Windows input (games), 2 X pointer (desktop). */
    val mouseMode: Int = 0
)

/**
 * Frame generation (win-fg in the renderer). loaded: available (Vulkan renderer), changes apply live;
 * multiplier 2-4; preset 0 Quality / 1 Balanced / 2 Performance; flowScale 0.2-1.
 */
/** [engine] "winfg" | "lsfg"; [lsfgImported] the user's Lossless.dll is in Settings. */
data class QmFrameGen(val loaded: Boolean, val on: Boolean, val multiplier: Int, val preset: Int, val flowScale: Float,
                      val engine: String = "winfg", val lsfgImported: Boolean = false,
                      /** Adaptive target output FPS (LSFG / WFG); 0 = fixed multiplier. */
                      val target: Int = 0)

/** Live task manager numbers. */
data class QmTasks(val cpuPercent: Int, val memory: String, val processes: List<QmProcess>)

/**
 * What the in-game quick menu can read and do. Implemented by XServerDisplayActivity, which
 * owns the renderer, HUD, input controls and Wine; the menu itself is only UI.
 */
interface QuickMenuBridge {
    // HUD
    /** 0 off, 1 classic, 2 modern. */
    fun hudMode(): Int
    fun setHudMode(mode: Int)
    /** Modern HUD element: 0 FPS, 2 GPU, 3 CPU, 7 RAM, 4 battery / temperature, 6 renderer. */
    fun hudElement(index: Int): Boolean
    fun setHudElement(index: Int, on: Boolean)
    /** 0.5 … 2.0 */
    fun hudScale(): Float
    fun setHudScale(scale: Float)
    /** 0 … 1 */
    fun hudAlpha(): Float
    fun setHudAlpha(alpha: Float)
    fun resetHud()

    /** 0 = no limit. */
    fun fpsLimit(): Int
    fun setFpsLimit(fps: Int)

    // Screen
    /** Wayland zero-copy presentation: -1 = not a Wayland session, 0 off, 1 on. */
    fun zeroCopy(): Int = -1
    fun setZeroCopy(on: Boolean) {}
    fun isVulkanRenderer(): Boolean
    /** -1 off, 0 SGSR, 1 FSR. */
    fun upscaler(): Int
    fun setUpscaler(mode: Int)
    /** 0 … 100: upscaler sharpness / shader strength. */
    fun sharpness(): Int
    fun setSharpness(value: Int, save: Boolean)
    /** 0 none, 1 DLS, 2 CRT, 3 HDR, 4 Natural, 10 … 20 ReShade-style effects. */
    fun shader(): Int
    fun setShader(id: Int)
    /** Picture brightness / contrast in -100…100 (0 = unchanged), gamma 0.5…2.5 (1 = unchanged). */
    fun brightness(): Int
    fun contrast(): Int
    fun gamma(): Float
    fun setColorAdjust(brightness: Int, contrast: Int, gamma: Float, save: Boolean)

    fun frameGen(): QmFrameGen
    fun setFrameGen(fg: QmFrameGen)

    /** 0 fit, 1 fullscreen, 2 stretch. */
    fun scaling(): Int
    fun setScaling(mode: Int)
    fun openMagnifier()
    fun enterPip()

    // Controls
    fun controlProfiles(): List<QmOption>
    /** 0 = none. */
    fun controlProfile(): Int
    fun setControlProfile(id: Int)
    fun editControls()
    fun touchControls(): Boolean
    fun setTouchControls(on: Boolean)
    fun touchTimeout(): Boolean
    fun setTouchTimeout(on: Boolean)
    fun touchHaptics(): Boolean
    fun setTouchHaptics(on: Boolean)
    /** 0 … 1 */
    fun controlsOpacity(): Float
    fun setControlsOpacity(value: Float)
    fun relativeMouse(): Boolean
    fun setRelativeMouse(on: Boolean)
    /** Touch -> mouse speed (also the on-screen trackpad), 0.1 … 5.0; saved for all games. */
    fun mouseSpeed(): Float
    fun setMouseSpeed(speed: Float)
    fun mouseDisabled(): Boolean
    fun setMouseDisabled(on: Boolean)
    fun gyro(): QmGyro
    fun setGyro(gyro: QmGyro)
    fun showKeyboard()
    fun vibrationSettings()

    // Tasks
    fun sessionInfo(): List<QmInfo>
    fun startTasks(listener: (QmTasks) -> Unit)
    fun stopTasks()
    fun bringToFront(name: String)
    fun endProcess(name: String)
    fun processAffinity(pid: Int)
    fun newTask()
    fun logsAvailable(): Boolean
    fun showLogs()

    // Session
    fun isPaused(): Boolean
    fun setPaused(paused: Boolean)
    fun exitGame()
    fun close()
}
