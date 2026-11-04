package com.winlator.cmod.components

import android.content.Context
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.WineRegistryEditor
import java.io.File

/**
 * The part of Bannerlator's ComponentExecInstaller the offline installer needs (setting the Windows
 * version). Running a component's own setup program inside a container session isn't ported yet:
 * such components are listed as not installable here.
 */
object ComponentExecInstaller {
    fun pendingComponentName(context: Context): String? = null

    fun launchUninstaller(context: Context, container: Container, name: String, products: List<String>) {
        throw UnsupportedOperationException("$name was installed by its own setup: remove it from Windows' Programs (Control Panel) in the container")
    }

    private data class WinVer(
        val version: String, val build: String, val csd: String,
        val product: String, val spLevel: Int, val major: Int, val minor: Int,
    )

    // winetricks/winecfg-aligned values for the versions used by the system-library set.
    private val WIN_VERSIONS = mapOf(
        "win20" to WinVer("4.0", "1381", "Service Pack 6a", "Microsoft Windows NT", 6, 4, 0),
        "winxp" to WinVer("5.1", "2600", "Service Pack 3", "Microsoft Windows XP", 3, 5, 1),
        "win2k" to WinVer("5.0", "2195", "Service Pack 4", "Microsoft Windows 2000", 4, 5, 0),
        "win2k3" to WinVer("5.2", "3790", "Service Pack 2", "Microsoft Windows 2003", 2, 5, 2),
        "vista" to WinVer("6.0", "6002", "Service Pack 2", "Microsoft Windows Vista", 2, 6, 0),
        "win7" to WinVer("6.1", "7601", "Service Pack 1", "Microsoft Windows 7", 1, 6, 1),
        "win8" to WinVer("6.2", "9200", "", "Microsoft Windows 8", 0, 6, 2),
        "win81" to WinVer("6.3", "9600", "", "Microsoft Windows 8.1", 0, 6, 3),
        "win10" to WinVer("10.0", "19043", "", "Microsoft Windows 10", 0, 10, 0),
        "win11" to WinVer("10.0", "22000", "", "Microsoft Windows 11", 0, 10, 0),
    )

    /** Set the prefix's reported Windows version (system.reg), mirroring winecfg's version dropdown. */
    internal fun setWindowsVersion(systemReg: File, version: String) {
        val v = WIN_VERSIONS[version] ?: return
        if (!systemReg.exists()) return
        WineRegistryEditor(systemReg).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            for (base in listOf(
                "Software\\Microsoft\\Windows NT\\CurrentVersion",
                "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion",
            )) {
                reg.setStringValue(base, "CurrentVersion", v.version)
                reg.setStringValue(base, "CurrentBuildNumber", v.build)
                reg.setStringValue(base, "CSDVersion", v.csd)
                reg.setStringValue(base, "ProductName", v.product)
                if (v.major >= 10) {
                    reg.setDwordValue(base, "CurrentMajorVersionNumber", v.major)
                    reg.setDwordValue(base, "CurrentMinorVersionNumber", v.minor)
                } else {
                    reg.removeValue(base, "CurrentMajorVersionNumber")
                    reg.removeValue(base, "CurrentMinorVersionNumber")
                }
            }
            reg.setStringValue("System\\CurrentControlSet\\Control\\ProductOptions", "ProductType", "WinNT")
            reg.setDwordValue("System\\CurrentControlSet\\Control\\Windows", "CSDVersion", v.spLevel shl 8)
            // Drop any per-app version override so the global version takes effect.
            reg.removeValue("Software\\Wine\\AppDefaults", "Version")
        }
    }
}
