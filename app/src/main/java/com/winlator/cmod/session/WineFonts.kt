package com.winlator.cmod.session

import android.util.Log
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRegistryEditor
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File
import java.util.Locale

/**
 * Wine's own TrueType fonts (Tahoma, Marlett, Symbol…) copied into C:\windows\Fonts and listed
 * in HKLM\Software\Microsoft\Windows NT\CurrentVersion\Fonts.
 *
 * Wine's GDI loads them straight from <wine>/share/wine/fonts, but DirectWrite only knows the
 * fonts listed under that key. For a font outside every Wine drive (a downloaded Wine/Proton lives
 * in files/contents, Z: is the image's root) the entry is the bare file name, which DirectWrite
 * looks up in C:\windows\Fonts, so Tahoma, the system UI font, does not exist for DirectWrite.
 * Chromium-based launchers (Ubisoft Connect) then can't make their UI font, fall back to the
 * system font, which is Tahoma again, and recurse until the stack overflows. Our own entries (names
 * Wine's font code never touches) point at the copies.
 */
object WineFonts {
    private const val TAG = "WineFonts"
    private const val KEY = "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
    private val TYPES = setOf("ttf", "ttc", "otf")

    @JvmStatic
    fun ensure(imageFs: ImageFs, wineInfo: WineInfo?) {
        val path = wineInfo?.path ?: return
        val src = File(path, "share/wine/fonts").listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in TYPES }.orEmpty()
        if (src.isEmpty()) return
        val prefix = File(imageFs.rootDir, ImageFs.WINEPREFIX)
        val dst = File(prefix, "drive_c/windows/Fonts")
        if (!dst.isDirectory && !dst.mkdirs()) return
        val present = dst.list()?.associateBy { it.lowercase(Locale.ROOT) }.orEmpty()
        val copied = ArrayList<String>()
        val listed = ArrayList<String>()
        for (f in src) {
            val name = present[f.name.lowercase(Locale.ROOT)] ?: runCatching {
                f.copyTo(File(dst, f.name)); copied += f.name; f.name
            }.onFailure { Log.w(TAG, f.name, it) }.getOrNull() ?: continue
            listed += name
        }
        if (copied.isNotEmpty()) Log.i(TAG, "copied to C:\\windows\\Fonts: ${copied.joinToString()}")
        runCatching {
            WineRegistryEditor(File(prefix, "system.reg")).use { reg ->
                for (name in listed) {
                    val value = "C:\\windows\\Fonts\\$name"
                    val entry = "YurNLator ${name.substringBeforeLast('.')}"
                    if (reg.getStringValue(KEY, entry) != value) reg.setStringValue(KEY, entry, value)
                }
            }
        }.onFailure { Log.w(TAG, "registry", it) }
    }
}
