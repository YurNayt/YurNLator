package com.winlator.cmod.core

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * Which component versions make sense under the Mali GPU profile.
 *
 * Mali runs games through the system Vulkan driver (plus a wrapper), so:
 *  - GPU drivers (adrenotools: Turnip, Qualcomm builds) load only on Adreno -> hidden;
 *    PanVK builds (open Mesa driver for Mali) stay.
 *  - DXVK: 1.x only (1.10.3, async, Sarek 1.11 / 1.12, d8vk). 2.x needs Vulkan 1.3 and
 *    features the Mali driver lacks; testers saw it fail even on Mali-G720 (Dimensity 8400).
 *  - VKD3D-Proton up to 2.14 (Winlator Mali ships 2.8 and 2.14.1); 2.8 max on Vulkan < 1.3.
 *  - Wine / Proton, Box64, FEX are CPU-side and fit any GPU; only builds marked for
 *    Adreno / Turnip are hidden.
 * Builds named for Mali always fit. The user can still show everything ([showAll]).
 */
object MaliCompat {
    private const val KEY_SHOW_ALL = "yurn_show_adreno_only"

    /** Observed by the lists, so the toggle applies at once. */
    val showAllState = mutableStateOf<Boolean?>(null)

    @Volatile private var vulkan13: Boolean? = null

    @JvmStatic
    fun showAll(context: Context): Boolean =
        showAllState.value ?: PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_SHOW_ALL, false).also { showAllState.value = it }

    @JvmStatic
    fun setShowAll(context: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY_SHOW_ALL, on).apply()
        showAllState.value = on
    }

    /** The Mali profile is active (filtering applies, whether or not it's switched off). */
    @JvmStatic
    fun active(context: Context): Boolean = GpuProfileManager.current(context) == GpuProfile.MALI

    /** Filtering is on right now. */
    @JvmStatic
    fun filtering(context: Context): Boolean = active(context) && !showAll(context)

    /** System Vulkan driver reports 1.3+. Unknown -> assume yes (don't hide too much). */
    @JvmStatic
    fun hasVulkan13(context: Context): Boolean {
        vulkan13?.let { return it }
        val v = runCatching { GPUInformation.getVulkanVersion(null, context) }.getOrNull().orEmpty()
        val parts = v.split('.').mapNotNull { it.trim().toIntOrNull() }
        val ok = if (parts.size >= 2) parts[0] > 1 || (parts[0] == 1 && parts[1] >= 3) else true
        return ok.also { vulkan13 = it }
    }

    private val adrenoOnly = Regex("turnip|adreno|qualcomm|freedreno|\\bqcom\\b|snapdragon|\\ba[67]\\d\\d\\b|8gen|8 ?elite")

    /**
     * Whether a component fits a Mali GPU. [type] is the content type ("DXVK", "dxvk",
     * "VKD3D", "AdrenoTools", "Wine", "Proton"…), [name] its version name.
     */
    @JvmStatic
    fun fits(context: Context, type: String, name: String): Boolean {
        val t = type.lowercase(Locale.ENGLISH)
        val n = name.lowercase(Locale.ENGLISH)
        if ("mali" in n) return true
        return when (t) {
            // Turnip / Qualcomm drivers load only on Adreno; PanVK (Mesa for Mali) is the exception.
            "adrenotools" -> n == "system" || Regex("panvk|panfrost|kbase|fourfect").containsMatchIn(n)
            "dxvk" -> {
                if ("d8vk" in n || "sarek" in n) return true
                if (adrenoOnly.containsMatchIn(n)) return false
                val v = lead(t, name) ?: return true
                // Testers: DXVK 2.x fails on Mali-G720 (Dimensity 8400 Ultra) too, so 1.x only
                // (1.10.3, async, Sarek 1.11 / 1.12) and d8vk for DX8.
                v[0] < 2
            }
            "vkd3d" -> {
                if (adrenoOnly.containsMatchIn(n)) return false
                val v = lead(t, name) ?: return true
                val maxMinor = if (hasVulkan13(context)) 14 else 8
                v[0] < 2 || (v[0] == 2L && v.getOrElse(1) { 0L } <= maxMinor)
            }
            "wrapper" -> true
            else -> !adrenoOnly.containsMatchIn(n)
        }
    }

    private fun lead(type: String, name: String): List<Long>? =
        VersionGroups.parse(type, name).lead.takeIf { it.isNotEmpty() }

    /** Short reason shown on hidden-type notices. */
    @JvmStatic
    fun hiddenLabel(count: Int): String =
        if (count == 1) "Show 1 Adreno-only version" else "Show $count Adreno-only versions"
}
