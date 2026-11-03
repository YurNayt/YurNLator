package com.winlator.cmod.community

import android.content.Context
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.ui.settings.DriverOption
import com.winlator.cmod.ui.settings.SettingsCatalog
import com.winlator.cmod.ui.settings.VersionCatalog
import com.winlator.cmod.ui.settings.installAdrenoDriver
import com.winlator.cmod.ui.settings.installRuntimeComponent
import com.winlator.cmod.ui.settings.loadSettingsCatalog
import org.json.JSONObject

/**
 * Checks what a preset needs on this phone and applies it to a shortcut.
 *
 * Missing DXVK / VKD3D / Box64 / FEXCore versions and GPU drivers are downloaded when the
 * component lists have them; what can't be had keeps this phone's current value. The shortcut's
 * previous values are kept in its "presetBackup" extra, so [undo] puts them back.
 */
object PresetApply {
    enum class State { INSTALLED, DOWNLOAD, MISSING, KEEP, INFO }

    data class Requirement(
        val what: String,
        val wanted: String,
        val state: State,
        val note: String = "",
        internal val type: String = "",
        internal val driver: DriverOption? = null,
    )

    private const val BACKUP = "presetBackup"
    private const val APPLIED = "presetApplied"
    private const val ABSENT = "\u0000absent"

    private fun arm64(wine: String) = wine.contains("arm64ec", true)

    private fun versionState(catalog: VersionCatalog, version: String): State = when {
        version.isBlank() -> State.INFO
        catalog.installed.any { it.equals(version, true) } -> State.INSTALLED
        catalog.all.any { it.equals(version, true) } -> State.DOWNLOAD
        else -> State.MISSING
    }

    private fun findDriver(catalog: SettingsCatalog, p: CommunityPreset): DriverOption? {
        val r = p.requires
        if (r.driverId.isBlank()) return null
        return catalog.drivers.firstOrNull { it.installed && it.id.equals(r.driverId, true) }
            ?: catalog.drivers.firstOrNull { r.driverUrl.isNotBlank() && it.remoteUrl == r.driverUrl }
            ?: catalog.drivers.firstOrNull { it.installed && r.driverLabel.isNotBlank() && it.label.equals(r.driverLabel, true) }
            ?: catalog.drivers.firstOrNull { !it.installed && r.driverLabel.isNotBlank() && it.label.equals(r.driverLabel, true) }
    }

    /** What the preset needs and whether this phone has it. Blocking-free (suspends on IO). */
    suspend fun check(context: Context, shortcut: Shortcut, p: CommunityPreset): List<Requirement> {
        val wine = shortcut.container.getWineVersion().orEmpty()
        val arm = arm64(wine)
        val sameArch = p.requires.wine.isBlank() || arm64(p.requires.wine) == arm
        val catalog = loadSettingsCatalog(context, arm)
        val r = p.requires
        val out = ArrayList<Requirement>()

        out += Requirement(
            "Wine", r.wineLabel.ifBlank { r.wine.ifBlank { "—" } }, State.INFO,
            when {
                r.wine.isBlank() -> ""
                r.wine.equals(wine, true) -> "same as your container"
                !sameArch -> "your container is " + (if (arm) "arm64ec" else "x86_64") + ": emulator settings are not taken"
                else -> "your container uses $wine"
            }
        )
        if (r.dxvk.isNotBlank()) out += Requirement("DXVK", r.dxvk, versionState(catalog.dxvk, r.dxvk), type = "DXVK")
        if (r.vkd3d.isNotBlank()) out += Requirement("VKD3D", r.vkd3d, versionState(catalog.vkd3d, r.vkd3d), type = "VKD3D")
        if (r.box64.isNotBlank()) out += if (!sameArch) Requirement("Box64", r.box64, State.KEEP, "other architecture")
            else Requirement(if (arm) "WOWBox64" else "Box64", r.box64, versionState(if (arm) catalog.wow else catalog.box, r.box64), type = if (arm) "WOWBox64" else "Box64")
        if (r.fexcore.isNotBlank() && arm) out += if (!sameArch) Requirement("FEXCore", r.fexcore, State.KEEP, "other architecture")
            else Requirement("FEXCore", r.fexcore, versionState(catalog.fex, r.fexcore), type = "FEXCore")
        if (r.driverId.isNotBlank()) {
            val label = r.driverLabel.ifBlank { r.driverId }
            out += if (!GPUInformation.isAdrenoGPU(context)) Requirement("GPU driver", label, State.KEEP, "not an Adreno GPU: your driver stays")
            else {
                val d = findDriver(catalog, p)
                when {
                    d == null -> Requirement("GPU driver", label, State.MISSING, "not in your driver lists: your driver stays")
                    d.installed -> Requirement("GPU driver", label, State.INSTALLED, driver = d)
                    else -> Requirement("GPU driver", label, State.DOWNLOAD, d.author?.let { "from $it" } ?: "", driver = d)
                }
            }
        }
        return out
    }

    /**
     * Downloads what's missing and writes the preset's settings to [shortcut].
     * Returns the lines to show the user (what was installed, what was kept).
     */
    suspend fun apply(context: Context, shortcut: Shortcut, p: CommunityPreset, progress: (String) -> Unit): List<String> {
        val reqs = check(context, shortcut, p)
        val report = ArrayList<String>()
        val settings = LinkedHashMap(p.settings)
        val wine = shortcut.container.getWineVersion().orEmpty()
        val sameArch = p.requires.wine.isBlank() || arm64(p.requires.wine) == arm64(wine)
        if (!sameArch) {
            listOf("emulator", "box64Version", "fexcoreVersion", "box64Preset", "fexcorePreset").forEach { settings.remove(it) }
        }

        // Versions that couldn't be had keep this phone's current value.
        fun keepCurrent(key: String, sub: String?, sep: Char) {
            if (sub == null) { settings.remove(key); return }
            val mine = CommunityPreset.readConfig(CommunityPreset.effective(shortcut, key), sub, sep)
            settings[key]?.let { settings[key] = CommunityPreset.writeConfig(it, sub, mine, sep) }
        }

        for (req in reqs) {
            when (req.state) {
                State.DOWNLOAD -> {
                    progress("Downloading ${req.what} ${req.wanted}…")
                    val installed = if (req.driver != null) installAdrenoDriver(context, req.driver)
                        else installRuntimeComponent(context, req.type, req.wanted)
                    if (installed == null) {
                        report += "${req.what} ${req.wanted}: download failed, yours is kept"
                        keepFor(req, ::keepCurrent)
                    } else {
                        report += "${req.what} ${req.wanted}: installed"
                        if (req.driver != null) settings["graphicsDriverConfig"]?.let {
                            settings["graphicsDriverConfig"] = CommunityPreset.writeConfig(it, "version", installed, ';')
                        }
                    }
                }
                State.INSTALLED -> if (req.driver != null) settings["graphicsDriverConfig"]?.let {
                    settings["graphicsDriverConfig"] = CommunityPreset.writeConfig(it, "version", req.driver.id, ';')
                }
                State.MISSING, State.KEEP -> {
                    report += "${req.what} ${req.wanted}: " + req.note.ifBlank { "not available, yours is kept" }
                    keepFor(req, ::keepCurrent)
                }
                State.INFO -> if (req.note.isNotBlank() && req.what == "Wine") report += "Wine: preset made with ${req.wanted} (${req.note})"
            }
        }

        progress("Applying…")
        // Backup of what the preset changes, once per apply (an earlier backup is kept on re-apply).
        if (shortcut.getExtra(BACKUP).isEmpty()) {
            val backup = JSONObject()
            for (key in CommunityPreset.ALLOWED_KEYS) backup.put(key, shortcut.getExtra(key, ABSENT))
            shortcut.putExtra(BACKUP, backup.toString())
        }
        settings.forEach { (k, v) -> if (k in CommunityPreset.ALLOWED_KEYS) shortcut.putExtra(k, v) }
        shortcut.putExtra(APPLIED, (p.title.ifBlank { p.id }).take(80) + " · " + p.author.ifBlank { "anonymous" })
        shortcut.saveData()
        report.add(0, "Applied ${settings.size} settings")
        return report
    }

    private fun keepFor(req: Requirement, keep: (String, String?, Char) -> Unit) {
        when (req.what) {
            "DXVK" -> keep("dxwrapperConfig", "version", ',')
            "VKD3D" -> keep("dxwrapperConfig", "vkd3dVersion", ',')
            "Box64", "WOWBox64" -> keep("box64Version", null, ',')
            "FEXCore" -> keep("fexcoreVersion", null, ',')
            "GPU driver" -> keep("graphicsDriverConfig", "version", ';')
        }
    }

    fun appliedLabel(shortcut: Shortcut): String = shortcut.getExtra(APPLIED)

    fun canUndo(shortcut: Shortcut): Boolean = shortcut.getExtra(BACKUP).isNotEmpty()

    /** Puts back the settings the shortcut had before the first preset was applied. */
    fun undo(shortcut: Shortcut): Boolean {
        val raw = shortcut.getExtra(BACKUP).ifEmpty { return false }
        val backup = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        backup.keys().forEach { k ->
            val v = backup.optString(k)
            shortcut.putExtra(k, if (v == ABSENT) null else v)
        }
        shortcut.putExtra(BACKUP, null)
        shortcut.putExtra(APPLIED, null)
        shortcut.saveData()
        return true
    }
}
