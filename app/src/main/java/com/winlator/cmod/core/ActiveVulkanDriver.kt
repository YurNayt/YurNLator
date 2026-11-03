package com.winlator.cmod.core

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Which Vulkan driver the game really runs on, read from the memory maps of our own
 * processes (Wine runs as our uid, so /proc/<pid>/maps is readable).
 *
 * The settings can say "System" while a wrapper loads a driver of its own (testers: the
 * Winlator Mali wrapper started FristOneRR's PanVK), so the HUD and the task manager show
 * what is actually mapped:
 *  - a driver installed in the app ("contents/adrenotools/<id>/…") -> its name;
 *  - PanVK / Turnip libraries anywhere else -> "PanVK" / "Turnip";
 *  - the phone's own driver (/vendor … vulkan.*.so, libGLES_mali) -> "System";
 *  - nothing found yet (game not started) -> null.
 */
object ActiveVulkanDriver {
    private val adrenotools = Regex("/contents/adrenotools/([^/]+)/")

    @JvmStatic
    fun detect(context: Context): String? {
        val uid = Process.myUid()
        val self = Process.myPid()
        val procs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return null
        var system = false
        for (p in procs) {
            val pid = p.name.toIntOrNull() ?: continue
            if (pid == self) continue // the app's own compositor uses the system driver
            val status = runCatching { File(p, "status").readText() }.getOrNull() ?: continue
            val puid = Regex("Uid:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull()
            if (puid != uid) continue
            val maps = runCatching { File(p, "maps").readText() }.getOrNull() ?: continue
            if ("libvulkan_wrapper" !in maps && "vulkan" !in maps) continue
            adrenotools.find(maps)?.let { m ->
                val id = m.groupValues[1]
                return runCatching {
                    val mgr = com.winlator.cmod.contents.AdrenotoolsManager(context)
                    listOf(mgr.getDriverName(id), mgr.getDriverVersion(id)).filter { it.isNotBlank() }.joinToString(" ")
                }.getOrNull()?.ifBlank { null } ?: id
            }
            val lower = maps.lowercase()
            if ("panfrost" in lower || "panvk" in lower) return "PanVK"
            if ("freedreno" in lower || "turnip" in lower) return "Turnip"
            if ("libvulkan_wrapper" in maps &&
                (Regex("/vendor/lib64/(hw/vulkan|egl/libGLES_mali)").containsMatchIn(maps))) system = true
        }
        return if (system) "System" else null
    }
}
