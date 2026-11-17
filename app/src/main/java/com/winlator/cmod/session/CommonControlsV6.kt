package com.winlator.cmod.session

import android.util.Log
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts the Microsoft.Windows.Common-Controls 6.0 assembly (comctl32 v6) into the prefix's winsxs
 * on every launch, from the selected Wine build's own files. Ported from GameNative
 * (utkarshdalal/GameNative, utils/protonFixes/CommonControlsV6.kt, GPL-3.0).
 *
 * Windows keeps two comctl32 versions: v5 in system32 and v6 as a side-by-side assembly in
 * winsxs, which a program gets when its manifest asks for it (most modern programs do).
 *  - Wine 11 / Proton 11 split them: comctl32.dll is v5 only, and TaskDialog / TaskDialogIndirect
 *    (ordinals 344/345) are empty slots in it; the code is in comctl32_v6.dll, reachable only
 *    through the assembly. An arm64ec prefix gets an empty assembly (setupapi looks for the DLL in
 *    arm64ec-windows, the build ships aarch64-windows), so a program that imports them dies with
 *    "unimplemented function COMCTL32.dll.345".
 *  - Proton 9 / 10 have one comctl32.dll that already is v6, but the assembly is still needed: a
 *    program whose manifest names Common-Controls 6.0 loses its whole activation context without
 *    it (and with it the compatibility section: GetVersionEx says Windows 8).
 *  - An assembly left by another build (the container pattern's x86_64 DLL under amd64_, which an
 *    arm64ec process can't load) is replaced.
 * ntdll matches the manifest by `<arch>_<name>_<key>_6.0.*.*`, so the exact version never matters.
 */
object CommonControlsV6 {
    private const val TAG = "CommonControlsV6"
    private const val SRC_DLL = "comctl32_v6.dll"
    private const val DST_DLL = "comctl32.dll"
    private const val TMP_SUFFIX = "yurnnew"
    private const val TMP_DLL = "$DST_DLL.$TMP_SUFFIX"
    private const val ASSEMBLY = "microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef"
    private val ALL_ARCHES = listOf("amd64", "arm64", "x86")

    /** Window classes user32 sends to the v6 DLL when the activation context lists them (Wine's own list). */
    private val WINDOW_CLASSES = listOf(
        "Button", "ButtonListBox", "ComboBoxEx32", "ComboLBox", "ComboBox", "Edit", "ListBox",
        "NativeFontCtl", "ReBarWindow32", "ScrollBar", "Static", "SysAnimate32", "SysDateTimePick32",
        "SysHeader32", "SysIPAddress32", "SysLink", "SysListView32", "SysMonthCal32", "SysPager",
        "SysTabControl32", "SysTreeView32", "ToolbarWindow32", "msctls_hotkey32", "msctls_progress32",
        "msctls_statusbar32", "msctls_trackbar32", "msctls_updown32", "tooltips_class32",
    )

    /** Returns a line for the session log, or null when nothing changed. */
    @JvmStatic
    fun ensure(imageFs: ImageFs, wineInfo: WineInfo?): String? = runCatching {
        wineInfo ?: return null
        val libDir = File(wineInfo.path ?: File(imageFs.rootDir, "opt/wine").path, "lib/wine")
        // No Wine files to take the DLL from: leave the prefix alone rather than prune it.
        if (!libDir.isDirectory) return null
        val winsxs = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/winsxs")
        if (!winsxs.parentFile!!.isDirectory) return null

        val arches = targetArches(wineInfo)
        // Per build, not per arch: on a split build comctl32.dll is v5 and must never become the assembly.
        val split = arches.any { (_, pe) -> File(File(libDir, pe), SRC_DLL).isFile }
        val srcName = if (split) SRC_DLL else DST_DLL
        val sources = LinkedHashMap<String, File>()
        for ((arch, pe) in arches) {
            val src = File(File(libDir, pe), srcName)
            if (src.isFile) sources[arch] = src
        }
        if (sources.isEmpty()) return null

        val changed = ArrayList<String>()
        for ((arch, src) in sources) {
            val name = "${arch}_$ASSEMBLY"
            val dst = File(File(winsxs, name), DST_DLL)
            if (!isCurrentBuild(dst, src)) {
                if (!linkOrCopy(src, dst)) { Log.w(TAG, "could not install $name from $src"); continue }
                changed += arch
            }
            // The manifest makes the directory discoverable: repaired even when the DLL was in place.
            writeManifest(File(winsxs, "manifests/$name.manifest"), arch)
        }
        // Arches this build has no v6 DLL for: another build's assembly would win the lookup.
        for (arch in ALL_ARCHES) if (!sources.containsKey(arch) && prune(winsxs, "${arch}_$ASSEMBLY")) changed += "-$arch"

        if (changed.isEmpty()) null
        else ("Common-Controls 6.0: " + (if (split) "comctl32_v6.dll" else "comctl32.dll") + " → winsxs (" + changed.joinToString(", ") + ")")
            .also { Log.i(TAG, it) }
    }.getOrElse { Log.w(TAG, "failed", it); null }

    /**
     * (winsxs arch, Wine PE folder). arm64ec needs both amd64 and arm64: setupapi names the folder
     * amd64_, ntdll resolves "*" to arm64.
     */
    private fun targetArches(w: WineInfo): List<Pair<String, String>> = when {
        w.isArm64EC -> listOf("amd64" to "aarch64-windows", "arm64" to "aarch64-windows", "x86" to "i386-windows")
        w.arch == "x86_64" -> listOf("amd64" to "x86_64-windows", "x86" to "i386-windows")
        else -> listOf("x86" to "i386-windows")
    }

    private fun isCurrentBuild(dst: File, src: File): Boolean {
        if (!dst.isFile || dst.length() != src.length()) return false
        return try { Files.isSameFile(dst.toPath(), src.toPath()) || FileUtils.contentEquals(src, dst) } catch (_: Exception) { false }
    }

    /** Removes an assembly that holds a DLL (another build's) or only a leftover temp file. The manifest goes with it. */
    private fun prune(winsxs: File, name: String): Boolean {
        val dir = File(winsxs, name)
        if (!dir.isDirectory) return false
        val entries = dir.list() ?: return false
        val dll = File(dir, DST_DLL)
        if (!dll.isFile && !(entries.isEmpty() || (entries.size == 1 && entries[0] == TMP_DLL))) return false
        if (!FileUtils.delete(dir)) return false
        File(winsxs, "manifests/$name.manifest").delete()
        return true
    }

    /** Made beside the target and moved in, so a failed copy never leaves the manifest pointing at nothing. */
    private fun linkOrCopy(src: File, dst: File): Boolean {
        val dir = dst.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, TMP_DLL)
        tmp.delete()
        val created = try { Files.createLink(tmp.toPath(), src.toPath()); true } catch (_: Throwable) {
            try { src.copyTo(tmp, overwrite = true); true } catch (_: Exception) { false }
        }
        if (!created) { tmp.delete(); return false }
        return try {
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); true
        } catch (_: Exception) { tmp.delete(); false }
    }

    private fun writeManifest(file: File, arch: String): Boolean {
        val xml = manifestXml(arch)
        if (runCatching { file.isFile && file.readText() == xml }.getOrDefault(false)) return true
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, "${file.name}.$TMP_SUFFIX")
        return try {
            tmp.writeText(xml)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); true
        } catch (_: Exception) { tmp.delete(); false }
    }

    private fun manifestXml(arch: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\n")
        append("  <assemblyIdentity type=\"win32\" name=\"Microsoft.Windows.Common-Controls\" ")
        append("version=\"6.0.2600.2982\" processorArchitecture=\"$arch\" publicKeyToken=\"6595b64144ccf1df\"/>\n")
        append("  <file name=\"$DST_DLL\">\n")
        for (c in WINDOW_CLASSES) append("    <windowClass>$c</windowClass>\n")
        append("  </file>\n")
        append("</assembly>\n")
    }
}
