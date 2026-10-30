package com.winlator.cmod.core

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Which family of default settings the app uses: chosen automatically from the GPU
 * on first launch, changeable by the user later (with a warning when it doesn't match).
 *
 * The profile decides *defaults* (graphics driver for new containers, and later the
 * Mali-specific layers). Hardware workarounds that depend on the real chip keep
 * checking the real GPU, not the profile.
 */
enum class GpuProfile(val id: String, val title: String, val summary: String) {
    ADRENO(
        "adreno",
        "Adreno",
        "Snapdragon devices. Turnip and Adreno-tuned drivers, standard DXVK / VKD3D."
    ),
    MALI(
        "mali",
        "Mali",
        "MediaTek Dimensity, Exynos (older), Unisoc, Kirin. Vulkan wrapper with Mali-friendly defaults."
    ),
    UNIVERSAL(
        "universal",
        "Universal",
        "Xclipse, PowerVR and other GPUs. Safe defaults through the system Vulkan driver."
    );

    companion object {
        fun fromId(id: String?): GpuProfile? = values().firstOrNull { it.id == id }
    }
}

/** What the hardware reports, read once per process. */
data class DetectedGpu(val vendor: GpuVendor, val renderer: String) {
    val recommendedProfile: GpuProfile
        get() = when (vendor) {
            GpuVendor.QUALCOMM -> GpuProfile.ADRENO
            GpuVendor.ARM -> GpuProfile.MALI
            else -> GpuProfile.UNIVERSAL
        }
}

enum class GpuVendor(val displayName: String) {
    QUALCOMM("Qualcomm Adreno"),
    ARM("ARM Mali"),
    SAMSUNG("Samsung Xclipse"),
    IMAGINATION("Imagination PowerVR"),
    UNKNOWN("Unknown");

    companion object {
        // Vulkan vendor IDs (VkPhysicalDeviceProperties.vendorID)
        fun fromVulkanId(id: Int): GpuVendor = when (id) {
            0x5143 -> QUALCOMM
            0x13B5 -> ARM
            0x144D -> SAMSUNG
            0x1010 -> IMAGINATION
            else -> UNKNOWN
        }

        /** Fallback when the vendor ID is unavailable: guess from the renderer string. */
        fun fromRenderer(renderer: String): GpuVendor {
            val r = renderer.lowercase()
            return when {
                "adreno" in r -> QUALCOMM
                "mali" in r || "immortalis" in r -> ARM
                "xclipse" in r || "samsung" in r -> SAMSUNG
                "powervr" in r || "img " in r -> IMAGINATION
                else -> UNKNOWN
            }
        }
    }
}

object GpuProfileManager {
    private const val KEY_PROFILE = "yurn_gpu_profile"
    private const val KEY_CONFIRMED = "yurn_gpu_profile_confirmed"

    @Volatile private var cachedDetection: DetectedGpu? = null

    /** Reads the GPU through the system Vulkan driver. Never throws. */
    @JvmStatic
    fun detect(context: Context): DetectedGpu {
        cachedDetection?.let { return it }
        val renderer = runCatching { GPUInformation.getRenderer(null, context) }
            .getOrNull()?.trim().orEmpty()
        val vendorId = runCatching { GPUInformation.getVendorID(null, context) }.getOrDefault(0)
        var vendor = GpuVendor.fromVulkanId(vendorId)
        if (vendor == GpuVendor.UNKNOWN) vendor = GpuVendor.fromRenderer(renderer)
        return DetectedGpu(vendor, renderer.ifBlank { "Unknown GPU" }).also { cachedDetection = it }
    }

    /** The active profile: the user's choice if made, otherwise the detected one. */
    @JvmStatic
    fun current(context: Context): GpuProfile {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return GpuProfile.fromId(prefs.getString(KEY_PROFILE, null))
            ?: detect(context).recommendedProfile
    }

    @JvmStatic
    fun set(context: Context, profile: GpuProfile) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_PROFILE, profile.id)
            .putBoolean(KEY_CONFIRMED, true)
            .apply()
    }

    /** True once the user went through the GPU step (or changed it in Settings). */
    @JvmStatic
    fun isConfirmed(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_CONFIRMED, false)

    /** The profile doesn't match the hardware: show the "may not work" warning. */
    @JvmStatic
    fun isMismatch(context: Context, profile: GpuProfile): Boolean =
        profile != detect(context).recommendedProfile

    /** Default graphics driver for new containers under the active profile. */
    @JvmStatic
    fun defaultGraphicsDriverVersion(context: Context): String = when (current(context)) {
        GpuProfile.ADRENO -> {
            val realAdreno = detect(context).vendor == GpuVendor.QUALCOMM
            when {
                // Real Adreno: keep Ludashi's check (some older Adreno can't load this wrapper)
                realAdreno && !runCatching {
                    GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)
                }.getOrDefault(false) -> DefaultVersion.WRAPPER
                // Forced on other hardware: the user was warned that it may not work
                else -> DefaultVersion.WRAPPER_ADRENO
            }
        }
        GpuProfile.MALI, GpuProfile.UNIVERSAL -> DefaultVersion.WRAPPER
    }
}
