package com.winlator.cmod.ui.quickmenu

/** A choosable item (controls profile, …). */
data class QmOption(val id: Int, val name: String)

/** One "label: value" line of the session info card. */
data class QmInfo(val label: String, val value: String)

/** A running Windows process. */
data class QmProcess(val pid: Int, val name: String, val memory: String, val wow64: Boolean)

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
    fun mouseDisabled(): Boolean
    fun setMouseDisabled(on: Boolean)
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
