package com.winlator.cmod.session

import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut

/**
 * YurNLator base, part 1: everything a game launch needs, resolved once.
 *
 * Each setting comes from the game's shortcut when it has its own value, otherwise from the
 * container. Before this, the game screen (XServerDisplayActivity, 3600 lines) looked up
 * `shortcut.getExtra(key, container.getX())` in ~35 places, with small differences between
 * them; the new game screen reads only this object.
 */
data class LaunchConfig(
    val graphicsDriver: String,
    val graphicsDriverConfig: String,
    val audioDriver: String,
    val emulator: String,
    val dxwrapper: String,
    val dxwrapperConfig: String,
    val screenSize: String,
    val lcAll: String,
    /** WinHandler input-type flags (DInput / XInput). */
    val inputType: Int,
    val exclusiveXInput: Boolean,
    val xinputDisabled: Boolean,
    val cpuList: String,
    /** The shortcut sets its own CPU list: 32-bit processes use it too. */
    val cpuListFromShortcut: Boolean,
    val syncCpuTopology: Boolean,
    val box64Preset: String,
    val fexcorePreset: String,
    val winComponents: String,
    val startupSelection: String,
    /** Container env vars, then the shortcut's on top ("A=1 B=2" form). */
    val envVars: List<String>,
    val execArgs: String,
    val fullscreenStretched: Boolean,
    /** Controls profile id to show at start; 0 = none. */
    val controlsProfileId: Int,
    val simulateTouchScreen: Boolean,
    val relativeMouse: Boolean,
    val mouseDisabled: Boolean,
    val wmClass: String,
    /** vkBasalt config from the shortcut's sharpness effect; empty = none. */
    val vkbasaltConfig: String,
    /** "x11" (default) or "wayland" (Bannerlator's embedded compositor, experimental). */
    val displayBackend: String,
) {
    val wayland: Boolean get() = displayBackend == "wayland"

    companion object {
        @JvmStatic
        fun resolve(container: Container, shortcut: Shortcut?): LaunchConfig {
            fun s(key: String, fallback: String): String = shortcut?.getExtra(key, fallback) ?: fallback
            /** Shortcut value only when set (empty = use the container). */
            fun own(key: String): String? = shortcut?.getExtra(key)?.takeIf { it.isNotEmpty() }

            val containerCpus = container.getCPUList(true).orEmpty()
            val cpus = s("cpuList", containerCpus)

            val sharpness = s("sharpnessEffect", "None")
            val vkbasalt = if (shortcut != null && sharpness != "None") {
                val level = (s("sharpnessLevel", "100").toDoubleOrNull() ?: 100.0) / 100
                val denoise = (s("sharpnessDenoise", "100").toDoubleOrNull() ?: 100.0) / 100
                "effects=${sharpness.lowercase()};casSharpness=$level;dlsSharpness=$level;dlsDenoise=$denoise;enableOnLaunch=True"
            } else ""

            return LaunchConfig(
                graphicsDriver = s("graphicsDriver", container.getGraphicsDriver()),
                graphicsDriverConfig = s("graphicsDriverConfig", container.getGraphicsDriverConfig()),
                audioDriver = Container.normalizeAudioDriver(s("audioDriver", container.getAudioDriver())),
                emulator = s("emulator", container.getEmulator()),
                dxwrapper = s("dxwrapper", container.getDXWrapper()),
                dxwrapperConfig = s("dxwrapperConfig", container.getDXWrapperConfig()),
                screenSize = s("screenSize", container.getScreenSize()),
                lcAll = s("lc_all", container.getLC_ALL()),
                inputType = own("inputType")?.toIntOrNull() ?: container.getInputType(),
                exclusiveXInput = own("exclusiveXInput")?.let { it == "1" } ?: container.isExclusiveXInput(),
                xinputDisabled = s("disableXinput", "false").let { it == "1" || it.equals("true", ignoreCase = true) },
                cpuList = cpus,
                cpuListFromShortcut = shortcut != null,
                syncCpuTopology = shortcut?.getExtra("syncCpuTopology", if (container.isSyncCpuTopology()) "1" else "")
                    ?.equals("1") ?: container.isSyncCpuTopology(),
                box64Preset = s("box64Preset", container.getBox64Preset()),
                fexcorePreset = s("fexcorePreset", container.getFEXCorePreset()),
                winComponents = s("wincomponents", container.getWinComponents()),
                startupSelection = s("startupSelection", container.getStartupSelection().toString()),
                envVars = listOfNotNull(container.getEnvVars(), own("envVars")),
                execArgs = shortcut?.getExtra("execArgs").orEmpty(),
                fullscreenStretched = own("fullscreenStretched")?.let { it == "1" } ?: container.isFullscreenStretched(),
                controlsProfileId = own("controlsProfile")?.toIntOrNull() ?: 0,
                simulateTouchScreen = shortcut?.getExtra("simTouchScreen") == "1",
                relativeMouse = shortcut?.getExtra("enableRelativeMouse") == "1",
                mouseDisabled = shortcut?.getExtra("disableMouse") == "1",
                wmClass = shortcut?.getExtra("wmClass", "").orEmpty(),
                vkbasaltConfig = vkbasalt,
                displayBackend = (own("displayBackend") ?: container.getExtra("displayBackend", "x11")).ifEmpty { "x11" },
            )
        }
    }

    /** "cores:list" for WINE_CPU_TOPOLOGY, or empty. */
    val wineCpuTopology: String
        get() = if (syncCpuTopology && cpuList.isNotEmpty()) "${cpuList.split(",").size}:$cpuList" else ""
}
