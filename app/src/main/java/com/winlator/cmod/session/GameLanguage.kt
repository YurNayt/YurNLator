package com.winlator.cmod.session

import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.WineRegistryEditor
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * "Game language" of a shortcut (extra gameLang, a Steam language name; empty = leave the game
 * alone). Games pick their language in different places, so at each launch it is set in all of
 * the usual ones:
 *  - the Steam emulator's config next to the game (steam_emu.ini, Goldberg's steam_settings,
 *    SmartSteamEmu, ALI213, 3DM, CreamAPI, OnlineFix…): its "Language" line (backed up once);
 *  - HKCU\Software\Valve\Steam "Language", which some games and cracks read;
 *  - Unreal Engine games: "-culture=<code>" on the command line (this launch only);
 *  - Wine's locale (LC_ALL), when neither the game nor its container chose one.
 */
object GameLanguage {
    private const val TAG = "GameLanguage"
    const val KEY = "gameLang"

    /** Steam name -> (ISO code for -culture, LC_ALL locale, own name). */
    val LANGUAGES: LinkedHashMap<String, Triple<String, String, String>> = linkedMapOf(
        "russian" to Triple("ru", "ru_RU.UTF-8", "Русский"),
        "english" to Triple("en", "en_US.UTF-8", "English"),
        "ukrainian" to Triple("uk", "uk_UA.UTF-8", "Українська"),
        "german" to Triple("de", "de_DE.UTF-8", "Deutsch"),
        "french" to Triple("fr", "fr_FR.UTF-8", "Français"),
        "spanish" to Triple("es", "es_ES.UTF-8", "Español"),
        "italian" to Triple("it", "it_IT.UTF-8", "Italiano"),
        "polish" to Triple("pl", "pl_PL.UTF-8", "Polski"),
        "portuguese" to Triple("pt", "pt_PT.UTF-8", "Português"),
        "brazilian" to Triple("pt-BR", "pt_BR.UTF-8", "Português (Brasil)"),
        "japanese" to Triple("ja", "ja_JP.UTF-8", "日本語"),
        "koreana" to Triple("ko", "ko_KR.UTF-8", "한국어"),
        "schinese" to Triple("zh-Hans", "zh_CN.UTF-8", "简体中文"),
    )

    /** Steam emulator configs with a "Language=" line (lower-case names). */
    private val CONFIGS = setOf(
        "steam_emu.ini", "smartsteamemu.ini", "steamconfig.ini", "3dmgame.ini", "cream_api.ini",
        "onlinefix.ini", "configs.user.ini", "valve.ini", "cpy.ini", "codex.ini", "rune.ini", "empress.ini",
        "steam_api.ini", "ds.ini", "hlm.ini", "skidrow.ini", "steamclient.ini",
    )
    private val SKIP_DIRS = setOf("content", "paks", "movies", "videos", "__installer", "_commonredist", "redist",
        "redistributables", "directx", "vcredist", "dotnet", "saved", "logs", "shadercache")

    @JvmStatic
    fun of(shortcut: Shortcut?): String = shortcut?.getExtra(KEY).orEmpty().takeIf { it in LANGUAGES } ?: ""

    /** LC_ALL for the game's language ("" when none is set). */
    @JvmStatic
    fun locale(shortcut: Shortcut?): String = LANGUAGES[of(shortcut)]?.second ?: ""

    /** The exe on this device, including paths on the container's other drives (D:\…). */
    private fun exe(container: Container, shortcut: Shortcut): File? {
        LaunchConfig.exeFile(shortcut.path)?.let { return it }
        var p = shortcut.path.trim().removePrefix("\"").substringBefore('"')
        if (p.length < 3 || p[1] != ':') return null
        val letter = p.substring(0, 1).uppercase(Locale.ROOT)
        for (d in container.drivesIterator()) {
            if (d[0].equals(letter, ignoreCase = true)) { p = d[1].trimEnd('/') + "/" + p.substring(2).replace('\\', '/').trimStart('/'); return File(p) }
        }
        return null
    }

    /** The folder the game was installed to (above Binaries/Win64 for Unreal games). */
    private fun gameRoot(exe: File): File {
        val dir = exe.parentFile ?: return exe
        val path = dir.path.replace('\\', '/')
        if (Regex("/Binaries/Win(64|32)$", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
            return dir.parentFile?.parentFile?.parentFile ?: dir
        }
        return dir
    }

    private fun isUnreal(exe: File): Boolean {
        val dir = exe.parentFile ?: return false
        if (Regex("/Binaries/Win(64|32)$", RegexOption.IGNORE_CASE).containsMatchIn(dir.path.replace('\\', '/'))) return true
        return File(dir, "Engine").isDirectory && dir.listFiles()?.any { it.isDirectory && File(it, "Content/Paks").isDirectory } == true
    }

    /**
     * Before Wine starts (user.reg is ours then). [shortcut]'s execArgs get "-culture" for this
     * launch only (the shortcut isn't saved).
     */
    @JvmStatic
    fun beforeLaunch(container: Container?, shortcut: Shortcut?) {
        if (container == null || shortcut == null) return
        val lang = of(shortcut).ifEmpty { return }
        val (iso, _, _) = LANGUAGES.getValue(lang)
        runCatching {
            WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { it.setStringValue("Software\\Valve\\Steam", "Language", lang) }
        }.onFailure { Log.w(TAG, "registry", it) }

        val exe = runCatching { exe(container, shortcut) }.getOrNull()
        if (exe != null && exe.isFile) {
            var changed = 0
            runCatching { changed = emulatorConfigs(gameRoot(exe), lang) }.onFailure { Log.w(TAG, "configs", it) }
            if (isUnreal(exe)) {
                val own = shortcut.getExtra("execArgs", "")
                if (!own.contains("-culture=", ignoreCase = true)) shortcut.putExtra("execArgs", ("$own -culture=$iso").trim())
            }
            Log.i(TAG, "${exe.name}: $lang ($changed emulator configs set${if (isUnreal(exe)) ", -culture=$iso" else ""})")
        }
        // Goldberg's settings for every game of the container.
        val roaming = File(container.rootDir, ".wine/drive_c/users/${com.winlator.cmod.xenvironment.ImageFs.USER}/AppData/Roaming")
        File(roaming, "Goldberg SteamEmu Saves/settings").takeIf { it.isDirectory }?.let { runCatching { File(it, "language.txt").writeText(lang) } }
        File(roaming, "GSE Saves/settings/configs.user.ini").takeIf { it.isFile }?.let { runCatching { setLanguageLine(it, lang) } }
    }

    /** Sets the language in the emulator configs under [root]; returns how many were changed. */
    private fun emulatorConfigs(root: File, lang: String): Int {
        var n = 0
        var visited = 0
        fun walk(d: File, depth: Int) {
            val list = d.listFiles() ?: return
            for (f in list) {
                if (++visited > 6000) return
                if (Files.isSymbolicLink(f.toPath())) continue
                val name = f.name.lowercase(Locale.ROOT)
                if (f.isDirectory) {
                    if (name == "steam_settings") {
                        // Goldberg: force_language.txt (older builds) and configs.user.ini (gbe_fork).
                        runCatching { File(f, "force_language.txt").takeIf { it.isFile }?.let { backup(it); it.writeText(lang); n++ } }
                        runCatching { File(f, "configs.user.ini").takeIf { it.isFile }?.let { if (setLanguageLine(it, lang)) n++ } }
                        runCatching { File(f, "language.txt").takeIf { it.isFile }?.let { backup(it); it.writeText(lang); n++ } }
                    } else if (depth < 6 && name !in SKIP_DIRS) walk(f, depth + 1)
                } else if (name in CONFIGS && f.length() < 512 * 1024) {
                    if (runCatching { setLanguageLine(f, lang) }.getOrDefault(false)) n++
                }
            }
        }
        walk(root, 0)
        return n
    }

    /** "Language = x" lines (any case, any spacing) -> [lang]; the file is backed up once. */
    private fun setLanguageLine(f: File, lang: String): Boolean {
        val bytes = f.readBytes()
        val utf16 = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
        val text = if (utf16) String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF") else String(bytes, Charsets.UTF_8)
        val re = Regex("(?im)^([ \\t]*language[ \\t]*=[ \\t]*)([^\\r\\n;#]*)")
        if (!re.containsMatchIn(text)) return false
        val out = re.replace(text) { m -> m.groupValues[1] + lang }
        if (out == text) return true
        backup(f)
        if (utf16) f.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + out.toByteArray(Charsets.UTF_16LE)) else f.writeText(out)
        return true
    }

    private fun backup(f: File) {
        val b = File(f.parentFile, f.name + ".yurn_bak")
        if (!b.exists()) runCatching { f.copyTo(b) }
    }
}
