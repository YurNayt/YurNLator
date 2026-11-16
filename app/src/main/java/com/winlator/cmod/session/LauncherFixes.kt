package com.winlator.cmod.session

import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.WineRegistryEditor
import java.io.File

/**
 * Known fixes for particular programs, written into the prefix before each launch (the start of
 * the game fixes database).
 *
 * Launchers and updaters: some load Direct3D only to read system information and unload it right
 * away. DXVK keeps worker threads running, so after FreeLibrary they run freed code and the
 * program crashes (Far Cry 3's FC3UpdaterSteam: "page fault on execute access" ~10 s in). These
 * programs get Wine's own Direct3D (Wine's per-program DllOverrides); the game they start keeps DXVK.
 *
 * Ubisoft Connect: on some setups its Chromium turns on Text Services Framework (msctf) when a text
 * field gets focus; Wine's msctf is mostly stubs (SetInputScope, TextStoreACPSink_OnLayoutChange)
 * and the page's renderer process then stops on a Chromium check a moment later, so the login
 * field can't be used. Without msctf Chromium types through IMM32 instead.
 */
object LauncherFixes {
    private const val TAG = "LauncherFixes"

    /** Exe names (any case) that should use Wine's Direct3D instead of DXVK. */
    private val WINED3D_LAUNCHERS = listOf(
        "FC3UpdaterSteam.exe", "FC3Updater.exe",                      // Far Cry 3 (Steam / retail) updater
    )
    /** Were on the list (build 279-280): Ubisoft Connect's own programs keep DXVK after all. */
    private val NO_LONGER = listOf("UbisoftConnect.exe", "upc.exe", "UplayWebCore.exe", "Uplay.exe", "UbisoftGameLauncher.exe", "UbisoftGameLauncher64.exe")
    private val D3D_DLLS = listOf("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi")
    /** Exe names whose Chromium should not use Text Services Framework: msctf disabled ("" in Wine). */
    private val NO_TSF = listOf("upc.exe", "UplayWebCore.exe", "UbisoftConnect.exe")

    /** Before Wine starts, ahead of the game's own per-program choices (which win). */
    @JvmStatic
    fun apply(container: Container) {
        runCatching {
            WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { reg ->
                for (exe in NO_LONGER) for (dll in D3D_DLLS) reg.removeValue("Software\\Wine\\AppDefaults\\$exe\\DllOverrides", dll)
                for (exe in WINED3D_LAUNCHERS) {
                    val key = "Software\\Wine\\AppDefaults\\$exe\\DllOverrides"
                    for (dll in D3D_DLLS) reg.setStringValue(key, dll, "builtin")
                }
                for (exe in NO_TSF) reg.setStringValue("Software\\Wine\\AppDefaults\\$exe\\DllOverrides", "msctf", "")
            }
        }.onFailure { Log.w(TAG, "launcher overrides", it) }
    }
}
