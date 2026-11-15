package com.winlator.cmod.session

import android.content.Context
import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.WineRegistryEditor
import com.winlator.cmod.store.steam.SteamInstallScript
import com.winlator.cmod.store.steam.SteamInstaller
import java.io.File

/**
 * Ubisoft games installed from Steam (or copied from elsewhere), shown to Ubisoft Connect as
 * installed: what its "Locate installed game" writes, plus the registry value its product config
 * reads the game's exe from. Without them Ubisoft Connect, when it can't reach a Steam client (we
 * have none), treats the game as its own copy, finds nothing installed and tries to download it
 * ("Couldn't start the download"). Works for an account that owns the game in Ubisoft Connect
 * (bought there, or a Steam purchase linked to the Ubisoft account).
 *
 * One entry per game, from a working setup (Far Cry 3: launcher_log "Failed to read executable path
 * from registry key HKEY_CURRENT_USER\SOFTWARE\UBISOFT\Far Cry 3\ExecutableName"; the value is
 * relative to the install folder).
 */
object UbisoftGames {
    private const val TAG = "UbisoftGames"
    private const val INSTALLS = "Software\\Wow6432Node\\Ubisoft\\Launcher\\Installs\\"

    private class Game(
        val name: String,
        val uplayId: Int,
        /** A file (relative to the game folder) that tells this game's folder apart. */
        val marker: String,
        /** HKCU key + value name Ubisoft Connect reads the exe from, and that exe relative to the folder. */
        val exeKey: String, val exeValue: String, val exe: String,
    )

    private val GAMES = listOf(
        Game("Far Cry 3", 46, "bin/farcry3_d3d11.exe",
            "Software\\Ubisoft\\Far Cry 3", "ExecutableName", "bin\\farcry3_d3d11.exe"),
    )

    /** Before Wine starts. */
    @JvmStatic
    fun beforeLaunch(context: Context, container: Container?, shortcut: Shortcut?) {
        if (container == null || shortcut == null) return
        val dirs = candidates(context, container, shortcut)
        for (g in GAMES) {
            val dir = dirs.firstOrNull { File(it, g.marker).isFile } ?: continue
            runCatching { apply(container, g, dir) }.onFailure { Log.w(TAG, g.name, it) }
        }
    }

    /** The Steam install folder, and the exe's folder with up to two folders above it. */
    private fun candidates(context: Context, container: Container, shortcut: Shortcut): List<File> {
        val out = ArrayList<File>()
        shortcut.getExtra("steamAppId").toIntOrNull()?.let { id ->
            SteamInstaller.load(context)
            SteamInstaller.installed.value[id]?.dir?.let { out += File(it) }
        }
        var d = LaunchConfig.exeFile(shortcut.path)?.parentFile
        var up = 0
        while (d != null && up < 3) { out += d; d = d.parentFile; up++ }
        return out.distinctBy { it.path }
    }

    private fun apply(container: Container, g: Game, dir: File) {
        // Ubisoft writes forward slashes with a trailing one ("F:/Download/.../Far Cry 3/").
        val installDir = SteamInstallScript.windowsPath(container, dir).replace('\\', '/').trimEnd('/') + "/"
        WineRegistryEditor(File(container.rootDir, ".wine/system.reg")).use { reg ->
            val key = INSTALLS + g.uplayId
            if (reg.getStringValue(key, "InstallDir") != installDir) reg.setStringValue(key, "InstallDir", installDir)
        }
        WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { reg ->
            // Keep what the game wrote itself (its own launcher picks DX9 / DX11); fix a full path.
            val cur = reg.getStringValue(g.exeKey, g.exeValue, "")
            if (cur.isBlank() || cur.contains(':')) reg.setStringValue(g.exeKey, g.exeValue, g.exe)
        }
        Log.i(TAG, "${g.name}: Ubisoft Connect install $installDir (${g.uplayId})")
    }
}
