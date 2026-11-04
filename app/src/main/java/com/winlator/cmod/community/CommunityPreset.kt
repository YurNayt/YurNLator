package com.winlator.cmod.community

import android.content.Context
import android.os.Build
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.contents.DriverOrigins
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.WineInfo
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A game's settings, shared with other YurNLator users (community presets).
 *
 * A preset is the game's effective shortcut settings (the shortcut's own value, else the
 * container's) plus what they need: the DXVK / VKD3D / Box64 / FEXCore versions, the GPU driver
 * and the Wine build. Device-only things (CPU core list, controls profile, the compositor's driver,
 * cover art, credentials in env vars) never leave the phone.
 *
 * File format "yurnlator-preset" v1:
 * {format, version, id, game, exe, steam_appid, title, notes, author, created,
 *  device{model, soc, gpu, android}, app{version}, requires{wine, wine_label, dxvk, vkd3d, box64,
 *  fexcore, driver{id, label, url}}, settings{key: value}, owner_hash}
 */
data class CommunityPreset(
    val id: String,
    val game: String,
    val exe: String,
    val title: String,
    val notes: String,
    val author: String,
    val created: Long,
    val deviceModel: String,
    val soc: String,
    val gpu: String,
    val android: Int,
    val appVersion: String,
    val requires: Requires,
    val settings: Map<String, String>,
    val ownerHash: String,
    // From the index, not the file.
    val issue: Int = 0,
    val likes: Int = 0,
    val comments: Int = 0,
) {
    data class Requires(
        val wine: String = "",
        val wineLabel: String = "",
        val dxvk: String = "",
        val vkd3d: String = "",
        val box64: String = "",
        val fexcore: String = "",
        val driverId: String = "",
        val driverLabel: String = "",
        val driverUrl: String = "",
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", 1)
        put("id", id)
        put("game", game)
        put("exe", exe)
        put("title", title)
        put("notes", notes)
        put("author", author)
        put("created", created)
        put("device", JSONObject().put("model", deviceModel).put("soc", soc).put("gpu", gpu).put("android", android))
        put("app", JSONObject().put("version", appVersion))
        put("requires", JSONObject().apply {
            put("wine", requires.wine); put("wine_label", requires.wineLabel)
            put("dxvk", requires.dxvk); put("vkd3d", requires.vkd3d)
            put("box64", requires.box64); put("fexcore", requires.fexcore)
            put("driver", JSONObject().put("id", requires.driverId).put("label", requires.driverLabel).put("url", requires.driverUrl))
        })
        put("settings", JSONObject().apply { settings.toSortedMap().forEach { (k, v) -> put(k, v) } })
        put("owner_hash", ownerHash)
    }

    /** Short "Adreno 830 · SM8750" style label. */
    val hardware: String get() = listOf(gpu, soc).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    companion object {
        const val FORMAT = "yurnlator-preset"

        /** Settings that are the game's (portable), read from the shortcut, falling back to the container. */
        private val CONTAINER_BACKED = listOf(
            "screenSize", "graphicsDriver", "graphicsDriverConfig", "audioDriver", "dxwrapper", "dxwrapperConfig",
            "emulator", "fexcoreVersion", "box64Version", "fexcorePreset", "box64Preset", "inputType",
            "exclusiveXInput", "startupSelection", "lc_all", "envVars", "wincomponents", "syncCpuTopology",
        )

        /** Shortcut settings that fall back to a container extra of the same name. */
        private val EXTRA_BACKED = listOf(
            "frameGen", "frameGenEngine", "frameGenMultiplier", "frameGenPreset", "frameGenFlowScale", "frameGenTarget",
            "oboeProfile", "oboeApi", "oboeAdaptive", "oboeExclusive", "builtinDecoder", "softwareDecoding",
        )

        /** Settings that only exist on the shortcut. */
        private val SHORTCUT_ONLY = listOf(
            "displayBackend", "fullscreenStretched", "disableXinput", "enableRelativeMouse", "disableMouse",
            "simTouchScreen", "sharpnessEffect", "sharpnessLevel", "sharpnessDenoise", "autoMesaGlVersionOverride",
            "useDisplayX", "graphicsFpsPreset", "graphicsPostFXMode", "graphicsSharpness", "graphicsFilterMode",
            "mouseWarpOverride",
        )

        /** Renderer settings read through the shortcut's getters (they have their own container fallbacks). */
        private val RENDERER = listOf(
            "rendererNative", "trueDisplayX", "surfaceFormat", "displayXPerformanceMode",
            "displayXPresentAtRefreshRate", "rendererPresentMode", "rendererFilterMode", "rendererSwapRB",
        )

        /** Every key a preset may set on a shortcut. Anything else in a downloaded file is ignored. */
        val ALLOWED_KEYS: Set<String> = (CONTAINER_BACKED + EXTRA_BACKED + SHORTCUT_ONLY + RENDERER + "execArgs").toSet()

        /** Env vars that must never leave the device (tokens, account ids, passwords). */
        private val SECRET_ENV = Regex("(TOKEN|SECRET|PASSW|PASSWD|API_?KEY|AUTH|COOKIE|SESSION|STEAM_?ID|STEAM_?USER|ACCOUNT|EMAIL|LOGIN)", RegexOption.IGNORE_CASE)

        fun newToken(): String {
            val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }

        fun newId(): String {
            val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** "The Last of Us Part I" / "tlou-i.exe" → "thelastofuspart1"-like key used to match games across repacks. */
        fun gameKey(name: String): String = name.lowercase()
            .replace(Regex("\\.(exe|lnk|desktop)$"), "")
            .replace(Regex("[^a-z0-9]"), "")

        fun exeOf(shortcut: Shortcut): String =
            shortcut.path.replace('\\', '/').substringAfterLast('/').trim().trim('"').lowercase()

        fun scrubEnv(env: String): String = env.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.filterNot { token ->
            val name = token.substringBefore('=')
            SECRET_ENV.containsMatchIn(name)
        }.joinToString(" ")

        /** The shortcut's current value for [key], as the game would run with it. */
        fun effective(shortcut: Shortcut, key: String): String? {
            val c = shortcut.container
            fun own(): String? = shortcut.getExtra(key, "").takeIf { it.isNotEmpty() }
            return when (key) {
                in CONTAINER_BACKED -> own() ?: when (key) {
                    "screenSize" -> c.getScreenSize()
                    "graphicsDriver" -> c.getGraphicsDriver()
                    "graphicsDriverConfig" -> c.getGraphicsDriverConfig()
                    "audioDriver" -> c.getAudioDriver()
                    "dxwrapper" -> c.getDXWrapper()
                    "dxwrapperConfig" -> c.getDXWrapperConfig()
                    "emulator" -> c.getEmulator()
                    "fexcoreVersion" -> c.getFEXCoreVersion()
                    "box64Version" -> c.getBox64Version()
                    "fexcorePreset" -> c.getFEXCorePreset()
                    "box64Preset" -> c.getBox64Preset()
                    "inputType" -> c.getInputType().toString()
                    "exclusiveXInput" -> if (c.isExclusiveXInput()) "1" else "0"
                    "startupSelection" -> c.getStartupSelection().toString()
                    "lc_all" -> c.getLC_ALL()
                    "envVars" -> c.getEnvVars()
                    "wincomponents" -> c.getWinComponents()
                    "syncCpuTopology" -> if (c.isSyncCpuTopology()) "1" else "0"
                    else -> null
                }
                in EXTRA_BACKED -> own() ?: c.getExtra(key, "").takeIf { it.isNotEmpty() }
                "rendererNative" -> if (shortcut.getRendererNative()) "1" else "0"
                "trueDisplayX" -> if (shortcut.getTrueDisplayX()) "1" else "0"
                "surfaceFormat" -> shortcut.getSurfaceFormat()
                "displayXPerformanceMode" -> if (shortcut.getDisplayXPerformanceMode()) "1" else "0"
                "displayXPresentAtRefreshRate" -> if (shortcut.getDisplayXPresentAtRefreshRate()) "1" else "0"
                "rendererPresentMode" -> shortcut.getRendererPresentMode()
                "rendererFilterMode" -> shortcut.getRendererFilterMode().toString()
                "rendererSwapRB" -> if (shortcut.getRendererSwapRB()) "1" else "0"
                else -> own()
            }?.takeIf { it.isNotEmpty() }
        }

        fun readConfig(config: String?, key: String, separator: Char): String {
            config.orEmpty().split(separator).forEach { token ->
                val i = token.indexOf('=')
                if (i > 0 && token.substring(0, i).trim() == key) return token.substring(i + 1).trim()
            }
            return ""
        }

        fun writeConfig(config: String?, key: String, value: String, separator: Char): String {
            val out = ArrayList<String>()
            var found = false
            config.orEmpty().split(separator).filter { it.isNotBlank() }.forEach { token ->
                val i = token.indexOf('=')
                if (i > 0 && token.substring(0, i).trim() == key) { out += "$key=$value"; found = true } else out += token.trim()
            }
            if (!found) out += "$key=$value"
            return out.joinToString(separator.toString())
        }

        fun gpuName(context: Context): String = runCatching {
            GPUInformation.getRenderer(null, context)
        }.getOrNull().orEmpty().let { name ->
            name.replace(Regex("(?i)\\((tm|r)\\)"), " ")
                .replace(Regex("\\s*\\([^)]*\\)"), " ")
                .replace(Regex("(?i)\\b(qualcomm|arm|turnip|freedreno|panvk|panfrost|mesa|wrapper|vulkan|driver)\\b"), " ")
                .replace(Regex("(?i)\\bv?\\d+\\.\\d+[\\w.\\-]*"), " ")
                .replace(Regex("\\s+"), " ").trim().ifEmpty { name.trim() }
        }

        fun soc(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else Build.HARDWARE.orEmpty()

        /** Builds a preset from [shortcut]'s current settings. Blocking (reads installed contents). */
        fun capture(
            context: Context, shortcut: Shortcut, title: String, notes: String, author: String,
            includeArgs: Boolean, appVersion: String, token: String,
        ): CommunityPreset {
            val settings = LinkedHashMap<String, String>()
            for (key in ALLOWED_KEYS) {
                if (key == "execArgs" && !includeArgs) continue
                val v = effective(shortcut, key) ?: continue
                settings[key] = if (key == "envVars") scrubEnv(v) else v
            }
            settings["envVars"]?.let { if (it.isBlank()) settings.remove("envVars") }

            val wrapperCfg = settings["dxwrapperConfig"].orEmpty()
            val driverCfg = settings["graphicsDriverConfig"].orEmpty()
            val driverId = readConfig(driverCfg, "version", ';')
            var driverLabel = driverId
            var driverUrl = ""
            if (driverId.isNotBlank()) runCatching {
                val adreno = AdrenotoolsManager(context)
                if (adreno.enumarateInstalledDrivers().any { it.equals(driverId, true) }) {
                    driverLabel = listOf(adreno.getDriverName(driverId), adreno.getDriverVersion(driverId))
                        .filter { it.isNotBlank() }.joinToString(" ").ifBlank { driverId }
                }
                driverUrl = DriverOrigins.urlFor(context, driverId).orEmpty()
            }

            val wine = shortcut.container.getWineVersion().orEmpty()
            val wineLabel = runCatching {
                val manager = ContentsManager(context).apply { syncContents() }
                val info = WineInfo.fromIdentifier(context, manager, wine)
                ("proton".equals(info.type, true).let { if (it) "Proton " else "Wine " }) + info.fullVersion() + " " + info.arch
            }.getOrDefault(wine)

            return CommunityPreset(
                id = newId(),
                game = shortcut.name,
                exe = exeOf(shortcut),
                title = title.trim().take(80),
                notes = notes.trim().take(1500),
                author = author.trim().take(40),
                created = System.currentTimeMillis() / 1000,
                deviceModel = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
                soc = soc(),
                gpu = gpuName(context),
                android = Build.VERSION.SDK_INT,
                appVersion = appVersion,
                requires = Requires(
                    wine = wine, wineLabel = wineLabel,
                    dxvk = readConfig(wrapperCfg, "version", ','),
                    vkd3d = readConfig(wrapperCfg, "vkd3dVersion", ','),
                    box64 = settings["box64Version"].orEmpty(),
                    fexcore = settings["fexcoreVersion"].orEmpty(),
                    driverId = driverId, driverLabel = driverLabel, driverUrl = driverUrl,
                ),
                settings = settings,
                ownerHash = sha256(token),
            )
        }

        /** Reads a preset file; null when it isn't one. Unknown setting keys are dropped. */
        fun parse(json: JSONObject, issue: Int = 0, likes: Int = 0, comments: Int = 0): CommunityPreset? {
            if (json.optString("format") != FORMAT) return null
            val dev = json.optJSONObject("device") ?: JSONObject()
            val req = json.optJSONObject("requires") ?: JSONObject()
            val drv = req.optJSONObject("driver") ?: JSONObject()
            val s = json.optJSONObject("settings") ?: JSONObject()
            val settings = LinkedHashMap<String, String>()
            s.keys().forEach { k -> if (k in ALLOWED_KEYS) settings[k] = s.optString(k).take(4000) }
            return CommunityPreset(
                id = json.optString("id").filter { it.isLetterOrDigit() }.take(32),
                game = json.optString("game").take(120),
                exe = json.optString("exe").take(120),
                title = json.optString("title").take(80),
                notes = json.optString("notes").take(1500),
                author = json.optString("author").take(40),
                created = json.optLong("created"),
                deviceModel = dev.optString("model"),
                soc = dev.optString("soc"),
                gpu = dev.optString("gpu"),
                android = dev.optInt("android"),
                appVersion = json.optJSONObject("app")?.optString("version").orEmpty(),
                requires = Requires(
                    wine = req.optString("wine"), wineLabel = req.optString("wine_label"),
                    dxvk = req.optString("dxvk"), vkd3d = req.optString("vkd3d"),
                    box64 = req.optString("box64"), fexcore = req.optString("fexcore"),
                    driverId = drv.optString("id"), driverLabel = drv.optString("label"), driverUrl = drv.optString("url"),
                ),
                settings = settings,
                ownerHash = json.optString("owner_hash"),
                issue = issue, likes = likes, comments = comments,
            )
        }

        /** Human names for the settings shown in a preset's details. */
        fun label(key: String): String = when (key) {
            "screenSize" -> "Screen size"
            "graphicsDriver" -> "Graphics driver"
            "graphicsDriverConfig" -> "Driver settings"
            "audioDriver" -> "Audio"
            "dxwrapper" -> "DX wrapper"
            "dxwrapperConfig" -> "DXVK / VKD3D settings"
            "emulator" -> "x86 emulator"
            "fexcoreVersion" -> "FEXCore"
            "box64Version" -> "Box64"
            "fexcorePreset" -> "FEXCore preset"
            "box64Preset" -> "Box64 preset"
            "inputType" -> "Input type"
            "exclusiveXInput" -> "Exclusive XInput"
            "startupSelection" -> "Startup"
            "lc_all" -> "Locale"
            "envVars" -> "Environment variables"
            "wincomponents" -> "Windows components"
            "syncCpuTopology" -> "Sync CPU topology"
            "execArgs" -> "Launch arguments"
            "frameGen" -> "Frame generation"
            "frameGenEngine" -> "Frame-gen engine"
            "displayBackend" -> "Display backend"
            "rendererNative" -> "Native rendering (EGL)"
            "useDisplayX" -> "DisplayX"
            "rendererPresentMode" -> "Present mode"
            "rendererSwapRB" -> "Swap colors"
            "fullscreenStretched" -> "Stretch to fullscreen"
            "enableRelativeMouse" -> "Relative mouse"
            else -> key
        }
    }
}
