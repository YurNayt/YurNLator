package com.winlator.cmod.store.steam

import android.content.Context
import android.util.Log
import com.winlator.cmod.core.TarCompressorUtils
import com.winlator.cmod.store.StoreNet
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * The Goldberg Steam emulator (gbe_fork) for installed Steam games, so a game that needs a running
 * Steam client starts without one: the game's steam_api(64).dll is swapped for Goldberg's (the
 * original is kept as <dll>.bak) and steam_settings/ tells it the app id, the account and the
 * language. It doesn't make online-only games reach their servers.
 *
 * The emulator comes from Bannerlator's winlator-contents release (goldberg.tzst, ~40 MB), downloaded
 * once into files/goldberg. Patch logic after Bannerlator's GoldbergPatcher (GPL-3.0), "regular" tier.
 */
object Goldberg {
    private const val TAG = "YurnGoldberg"
    private const val CATALOG = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/goldberg.json"
    private const val FALLBACK_URL = "https://github.com/The412Banner/winlator-contents/releases/download/goldberg-v2/goldberg.tzst"
    private const val FALLBACK_MD5 = "4D916BBF5207F3E01D88A102B2A465DE"
    private const val MARK = "; YurNLator"

    private val INTERFACE_PREFIXES = listOf(
        "SteamClient", "SteamGameServerStats", "SteamGameServer", "SteamUserStats",
        "SteamUser", "SteamFriends", "SteamUtils", "SteamMatchMakingServers",
        "SteamMatchMaking", "SteamApps", "SteamNetworkingUtils", "SteamNetworkingSockets",
        "SteamNetworkingMessages", "SteamNetworking", "SteamRemoteStorage",
        "SteamScreenshots", "SteamHTTP", "SteamController", "SteamUGC", "SteamAppList",
        "SteamMusicRemote", "SteamMusic", "SteamInventory", "SteamVideo",
        "SteamParentalSettings", "SteamInput", "SteamParties", "SteamRemotePlay",
        "SteamHTMLSurface", "SteamMasterServerUpdater", "SteamGameCoordinator",
    )

    private data class Target(val dll: File, val x64: Boolean) {
        val bak: File get() = File(dll.parentFile, dll.name + ".bak")
        val dir: File get() = dll.parentFile!!
    }

    fun dir(context: Context) = File(context.filesDir, "goldberg")

    fun downloaded(context: Context) = File(dir(context), "regular/x64/steam_api64.dll").isFile

    /** Downloads and unpacks the emulator if needed (blocking). */
    fun ensure(context: Context, progress: (Float) -> Unit = {}): Boolean {
        if (downloaded(context)) return true
        var url = FALLBACK_URL
        var md5 = FALLBACK_MD5
        runCatching {
            StoreNet.client.newCall(Request.Builder().url(CATALOG).build()).execute().use { r ->
                if (r.isSuccessful) {
                    val o = JSONObject(r.body?.string() ?: "")
                    o.optString("url").takeIf { it.startsWith("https://") }?.let { url = it; md5 = o.optString("file_checksum") }
                }
            }
        }
        val tmp = File(context.cacheDir, "goldberg.tzst")
        try {
            StoreNet.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return false
                val body = r.body ?: return false
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("MD5")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) progress(done.toFloat() / total)
                        }
                    }
                }
                val got = digest.digest().joinToString("") { "%02X".format(it) }
                if (md5.isNotBlank() && !got.equals(md5, true)) { Log.w(TAG, "md5 mismatch $got"); return false }
            }
            val out = dir(context)
            out.deleteRecursively()
            out.mkdirs()
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, tmp, out)) return false
            return downloaded(context)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            return false
        } finally {
            tmp.delete()
        }
    }

    /** steam_api dlls found per game folder (a big game on shared storage takes a while to walk). */
    private val found = java.util.concurrent.ConcurrentHashMap<String, List<Target>>()

    private val SKIP_DIRS = setOf("_commonredist", "redist", "redistributables", "directx", "vcredist", "dotnet", "support",
        "steam_settings", "__installer", "installers", "movies", "videos", "localization")

    private fun targets(gameDir: File): List<Target> = found.getOrPut(gameDir.absolutePath) {
        val out = ArrayList<Target>()
        fun walk(d: File, depth: Int) {
            d.listFiles()?.forEach { f ->
                if (Files.isSymbolicLink(f.toPath())) return@forEach
                if (f.isDirectory) {
                    if (depth < 6 && f.name.lowercase(Locale.ROOT) !in SKIP_DIRS) walk(f, depth + 1)
                } else {
                    val n = f.name.lowercase(Locale.ROOT)
                    if (n == "steam_api.dll" || n == "steam_api64.dll") out += Target(f, peX64(f) ?: (n == "steam_api64.dll"))
                }
            }
        }
        walk(gameDir, 0)
        out
    }

    /** (uses Steam's API, emulator applied) with one walk of the folder. */
    fun state(inst: SteamInstaller.Installed): Pair<Boolean, Boolean> {
        val ts = targets(File(inst.dir))
        return ts.isNotEmpty() to ts.any { it.bak.isFile }
    }

    fun forget(dir: String) { found.remove(File(dir).absolutePath) }

    /** True for a 64-bit PE, false for 32-bit, null when it isn't a PE. */
    private fun peX64(f: File): Boolean? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(0x3C)
            val b = ByteArray(4); raf.readFully(b)
            val off = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
            raf.seek(off.toLong())
            val sig = ByteArray(6); raf.readFully(sig)
            if (sig[0] != 'P'.code.toByte() || sig[1] != 'E'.code.toByte()) return@use null
            when ((sig[4].toInt() and 0xFF) or ((sig[5].toInt() and 0xFF) shl 8)) {
                0x8664 -> true
                0x014c -> false
                else -> null
            }
        }
    }.getOrNull()

    /** The game has a steam_api dll (otherwise it doesn't use Steam and needs no emulator). */
    fun usesSteam(inst: SteamInstaller.Installed) = targets(File(inst.dir)).isNotEmpty()

    fun applied(inst: SteamInstaller.Installed) = targets(File(inst.dir)).any { it.bak.isFile }

    private fun looksLikeGoldberg(dll: File) = runCatching {
        val t = String(dll.readBytes(), Charsets.ISO_8859_1)
        t.contains("steam_settings") || t.contains("gbe_fork") || t.contains("Goldberg")
    }.getOrDefault(false)

    private fun copy(src: File, dst: File): Boolean {
        val tmp = File(dst.parentFile, dst.name + ".yurn_tmp")
        return try {
            src.copyTo(tmp, overwrite = true)
            if (tmp.length() != src.length()) { tmp.delete(); false }
            else { Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING); true }
        } catch (t: Throwable) {
            Log.w(TAG, "copy ${src.name} -> ${dst.path}", t)
            tmp.delete()
            false
        }
    }

    private fun language(): String = when (Locale.getDefault().language) {
        "ru" -> "russian"; "uk" -> "ukrainian"; "de" -> "german"; "fr" -> "french"; "es" -> "spanish"
        "it" -> "italian"; "pl" -> "polish"; "pt" -> "brazilian"; "tr" -> "turkish"; "ja" -> "japanese"
        "ko" -> "koreana"; "zh" -> "schinese"; else -> "english"
    }

    enum class Result { APPLIED, NO_STEAM_API, NO_EMULATOR, FAILED }

    /** Puts Goldberg into the game (blocking). */
    fun apply(context: Context, inst: SteamInstaller.Installed): Result {
        val ts = targets(File(inst.dir))
        if (ts.isEmpty()) return Result.NO_STEAM_API
        if (!downloaded(context)) return Result.NO_EMULATOR
        for (t in ts) {
            if (!t.bak.isFile) {
                if (looksLikeGoldberg(t.dll)) continue
                if (!copy(t.dll, t.bak)) return Result.FAILED
            }
            val settings = File(t.dir, "steam_settings").apply { mkdirs() }
            val interfaces = runCatching {
                val text = String(t.bak.readBytes(), Charsets.ISO_8859_1)
                val found = LinkedHashSet<String>()
                INTERFACE_PREFIXES.forEach { p -> Regex(Regex.escape(p) + "\\d{3}").findAll(text).forEach { found += it.value } }
                found.toList()
            }.getOrDefault(emptyList())
            runCatching {
                if (interfaces.isNotEmpty()) File(settings, "steam_interfaces.txt").writeText(interfaces.joinToString("\n") + "\n")
                File(settings, "steam_appid.txt").writeText(inst.appId.toString())
                File(t.dir, "steam_appid.txt").writeText(inst.appId.toString())
                val user = File(settings, "configs.user.ini")
                if (!user.exists() || user.readText().startsWith(MARK)) {
                    val name = SteamSession.account.ifBlank { "Player" }
                    val id = SteamSession.steamId
                    user.writeText(buildString {
                        append(MARK).append('\n')
                        append("[user::general]\n")
                        append("account_name=").append(name).append('\n')
                        if (id != 0L) append("account_steamid=").append(id).append('\n')
                        append("language=").append(language()).append('\n')
                    })
                }
            }
            val src = File(dir(context), "regular/" + (if (t.x64) "x64/steam_api64.dll" else "x86/steam_api.dll"))
            if (!src.isFile || !copy(src, t.dll)) { restore(inst); return Result.FAILED }
        }
        return Result.APPLIED
    }

    /** Puts the game's own steam_api dlls back and removes what Goldberg added. */
    fun restore(inst: SteamInstaller.Installed) {
        for (t in targets(File(inst.dir))) {
            if (t.bak.isFile && t.bak.length() > 0 && copy(t.bak, t.dll)) t.bak.delete()
            File(t.dir, "steam_settings").takeIf { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }?.deleteRecursively()
        }
    }
}
