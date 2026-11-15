package com.winlator.cmod.session

import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.WineRegistryEditor
import java.io.File

/**
 * Running Windows programs as administrator, as Bannerlator does it: UAC off for the prefix
 * (HKLM\Software\Microsoft\Windows\CurrentVersion\Policies\System EnableLUA = 0), so every
 * process gets the full administrator token and installers / games that ask for it run without
 * permission errors. A container switch (on by default), and a one-time "run as administrator"
 * launch from the game's menu that sets it for that session only.
 */
object AdminRun {
    /** Container extra: "1" (default) or "0". */
    const val KEY = "runAsAdmin"
    /** Intent extra of a launch: run this time as administrator. */
    const val EXTRA = "run_as_admin"

    @JvmStatic
    fun containerOn(container: Container): Boolean = container.getExtra(KEY, "1") != "0"

    @JvmStatic
    fun setContainer(container: Container, on: Boolean) = container.putExtra(KEY, if (on) "1" else "0")

    /** Before Wine starts (system.reg is ours then). */
    @JvmStatic
    fun beforeLaunch(container: Container?, @Suppress("UNUSED_PARAMETER") shortcut: Shortcut?, once: Boolean) {
        if (container == null) return
        val admin = once || containerOn(container)
        runCatching {
            WineRegistryEditor(File(container.rootDir, ".wine/system.reg")).use {
                it.setDwordValue("Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System", "EnableLUA", if (admin) 0 else 1)
            }
            Log.i("AdminRun", "administrator: $admin${if (once) " (this launch)" else ""}")
        }.onFailure { Log.w("AdminRun", "registry", it) }
    }
}
