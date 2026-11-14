package com.winlator.cmod.session

import android.os.Handler
import android.os.Looper
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.winhandler.WinHandler
import java.io.File

/**
 * Which Windows gamepad API a game is shown. Wine always offers the pad through XInput; the
 * DirectInput pad ("Generic HID Gamepad N") is shown or hidden with a Wine registry key.
 * Games that read both see two pads when both are shown, so XInput alone is the default, and
 * DirectInput is added for games that need it (Unity reads gamepads only through DirectInput).
 *
 * The choice is stored as the "padApi" extra of the shortcut ("" = as the container) or the
 * container ("" = auto):
 *  - auto:   XInput; DirectInput too for Unity games or when the old input switches ask for it;
 *  - xinput: XInput only;
 *  - both:   XInput and DirectInput.
 */
object GamepadApi {
    const val KEY = "padApi"
    const val AUTO = "auto"
    const val XINPUT = "xinput"
    const val BOTH = "both"

    /** The stored choice, shortcut first: "auto", "xinput" or "both". */
    @JvmStatic
    fun mode(container: Container, shortcut: Shortcut?): String {
        val own = shortcut?.getExtra(KEY).orEmpty()
        if (own.isNotEmpty()) return own
        return container.getExtra(KEY, "").ifEmpty { AUTO }
    }

    /** Container value for the editors: "" (auto), "xinput" or "both"; old switches map onto it. */
    @JvmStatic
    fun containerChoice(container: Container): String {
        val v = container.getExtra(KEY, "")
        if (v.isNotEmpty()) return if (v == AUTO) "" else v
        return if (legacyDinput(container.getInputType(), container.isExclusiveXInput())) BOTH else ""
    }

    /** Old Winlator switches: DirectInput on, or "exclusive" off (both APIs). */
    @JvmStatic
    fun legacyDinput(inputType: Int, exclusive: Boolean): Boolean =
        !exclusive || (inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0

    /** Whether this launch shows the DirectInput pad. */
    @JvmStatic
    fun dinputShown(lc: LaunchConfig): Boolean = when (lc.padApi) {
        XINPUT -> false
        BOTH -> true
        else -> legacyDinput(lc.inputType, lc.exclusiveXInput) || lc.isUnityGame
    }

    /** What [mode] would show for this game once saved with [save] (old per-game switches gone). */
    @JvmStatic
    fun dinputShownFor(mode: String, container: Container, unity: Boolean): Boolean = when (mode) {
        XINPUT -> false
        BOTH -> true
        else -> legacyDinput(container.getInputType(), container.isExclusiveXInput()) || unity
    }

    /** Saves the choice for the game (its shortcut, else the container). [mode] "" = as the container. */
    @JvmStatic
    fun save(container: Container, shortcut: Shortcut?, mode: String) {
        if (shortcut != null) {
            shortcut.putExtra(KEY, mode.ifEmpty { null })
            // The old per-game switches would otherwise keep mixing in.
            if (mode.isNotEmpty()) {
                shortcut.putExtra("inputType", null)
                shortcut.putExtra("exclusiveXInput", null)
            }
            shortcut.saveData()
        } else {
            saveContainer(container, if (mode == AUTO) "" else mode)
            container.saveData()
        }
    }

    /** Writes the container choice ("" auto, xinput, both) and keeps the old switches in step. */
    @JvmStatic
    fun saveContainer(container: Container, choice: String) {
        container.putExtra(KEY, choice.ifEmpty { null })
        val x = WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        val d = WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        if (choice == BOTH) {
            container.setInputType(x or d); container.setExclusiveXInput(false)
        } else {
            container.setInputType(x); container.setExclusiveXInput(true)
        }
    }

    /**
     * Shows or hides the DirectInput pad in the running game: the registry is changed through
     * regedit inside Wine (user.reg is Wine's own while it runs), then the pads are plugged in
     * again so the game looks for them anew. Games that only look once need a restart.
     */
    @JvmStatic
    fun applyLive(container: Container, winHandler: WinHandler?, dinput: Boolean) {
        if (winHandler == null) return
        runCatching {
            val temp = File(container.rootDir, ".wine/drive_c/windows/temp").apply { mkdirs() }
            val value = if (dinput) "override" else "disabled"
            val sb = StringBuilder("REGEDIT4\r\n\r\n[HKEY_CURRENT_USER\\Software\\Wine\\DirectInput\\Joysticks]\r\n")
            for (i in 0 until 4) {
                sb.append("\"Generic HID Gamepad ").append(i).append("\"=\"").append(value).append("\"\r\n")
                sb.append("\"ric HID Gamepad ").append(i).append("\"=\"").append(value).append("\"\r\n")
            }
            File(temp, "yurn_pad.reg").writeText(sb.toString())
            winHandler.exec("regedit /S C:\\windows\\temp\\yurn_pad.reg")
            Handler(Looper.getMainLooper()).postDelayed({ winHandler.reconnectGamepads() }, 1500)
        }
    }
}
