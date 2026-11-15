package com.winlator.cmod.store.steam

import android.content.Context
import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.WineRegistryEditor
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import java.util.Locale

/**
 * What the Steam client does once before a game's first start: its installscript.vdf. Here the
 * "Registry" part, which many games and their launchers need (Far Cry 3's updater: "Cannot find
 * essential information in the registry"): keys under HKEY_LOCAL_MACHINE / HKEY_CURRENT_USER with
 * %INSTALLDIR% and per-language values. Written before every launch of a game from the Steam
 * library (cheap, and survives a reset prefix). HKLM keys also go to the 32-bit view
 * (Wow6432Node), where Steam's own 32-bit writes land on a 64-bit Windows.
 */
object SteamInstallScript {
    private const val TAG = "SteamInstallScript"

    @JvmStatic
    fun beforeLaunch(context: Context, container: Container?, shortcut: Shortcut?) {
        if (container == null || shortcut == null) return
        val appId = shortcut.getExtra("steamAppId").toIntOrNull() ?: return
        SteamInstaller.load(context)
        val dir = SteamInstaller.installed.value[appId]?.dir?.let(::File) ?: return
        val scripts = find(dir)
        if (scripts.isEmpty()) return
        val installDir = windowsPath(container, dir)
        val lang = com.winlator.cmod.session.GameLanguage.of(shortcut).ifEmpty {
            if (Locale.getDefault().language == "ru") "russian" else "english"
        }
        for (f in scripts) runCatching { apply(container, f, installDir, lang) }.onFailure { Log.w(TAG, f.name, it) }
    }

    /** installscript.vdf in the game folder (or one level down), as Steam depots ship it. */
    private fun find(dir: File): List<File> {
        val out = ArrayList<File>()
        fun scan(d: File) = d.listFiles()?.filter { it.isFile && it.name.lowercase(Locale.ROOT).let { n -> n.endsWith(".vdf") && n.contains("installscript") } }.orEmpty()
        out += scan(dir)
        if (out.isEmpty()) dir.listFiles()?.filter { it.isDirectory }?.forEach { out += scan(it) }
        return out
    }

    /** D:\…\Game for a folder on one of the container's drives, else Z:\… (the device root). */
    private fun windowsPath(container: Container, dir: File): String {
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        for (d in container.drivesIterator()) {
            val root = runCatching { File(d[1]).canonicalPath }.getOrNull() ?: continue
            if (path == root || path.startsWith("$root/")) {
                return d[0].trimEnd(':').uppercase(Locale.ROOT) + ":\\" + path.removePrefix(root).trimStart('/').replace('/', '\\')
            }
        }
        return "Z:" + path.replace('/', '\\')
    }

    private fun child(kv: KeyValue, name: String): KeyValue? = kv.children.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun apply(container: Container, file: File, installDir: String, lang: String) {
        val root = KeyValue.loadFromString(file.readText()) ?: return
        val registry = child(root, "Registry") ?: return
        val system = WineRegistryEditor(File(container.rootDir, ".wine/system.reg"))
        val user = WineRegistryEditor(File(container.rootDir, ".wine/user.reg"))
        var n = 0
        try {
            for (keyNode in registry.children) {
                // Steam writes "\\" for one backslash; the text reader may keep both.
                val full = keyNode.name?.replace("\\\\", "\\") ?: continue
                val hive = full.substringBefore('\\').uppercase(Locale.ROOT)
                val sub = full.substringAfter('\\', "").trim('\\')
                if (sub.isEmpty()) continue
                val targets: List<Pair<WineRegistryEditor, String>> = when (hive) {
                    "HKEY_LOCAL_MACHINE", "HKLM" -> {
                        val wow = if (sub.startsWith("Software\\", true) && !sub.contains("Wow6432Node", true))
                            "Software\\Wow6432Node\\" + sub.substringAfter('\\') else null
                        listOfNotNull(system to sub, wow?.let { system to it })
                    }
                    "HKEY_CURRENT_USER", "HKCU" -> listOf(user to sub)
                    else -> continue
                }
                for (typeNode in keyNode.children) {
                    val type = typeNode.name?.lowercase(Locale.ROOT) ?: continue
                    // Values, or language blocks ("english" { "Language" "English" }): this game's language, else English.
                    val values = ArrayList<KeyValue>()
                    val langBlocks = typeNode.children.filter { it.children.isNotEmpty() }
                    typeNode.children.filter { it.children.isEmpty() }.forEach { values += it }
                    if (langBlocks.isNotEmpty()) {
                        (langBlocks.firstOrNull { it.name.equals(lang, true) } ?: langBlocks.firstOrNull { it.name.equals("english", true) })
                            ?.children?.forEach { values += it }
                    }
                    for (v in values) {
                        val name = v.name ?: continue
                        val raw = (v.value ?: "").replace("\\\\", "\\").replace("%INSTALLDIR%", installDir, ignoreCase = true)
                        for ((reg, key) in targets) {
                            when (type) {
                                "string" -> reg.setStringValue(key, name, raw)
                                "dword" -> (if (raw.startsWith("0x", true)) raw.substring(2).toLongOrNull(16) else raw.toLongOrNull())
                                    ?.let { reg.setDwordValue(key, name, it.toInt()) }
                            }
                        }
                        n++
                    }
                }
            }
        } finally {
            runCatching { system.close() }
            runCatching { user.close() }
        }
        child(root, "Run Process")?.children?.forEach { p -> Log.i(TAG, "${file.name}: not run here: ${p.name}") }
        Log.i(TAG, "${file.parentFile?.name}/${file.name}: $n registry values (${installDir})")
    }
}
