package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.MaliCompat;

import java.util.Map;

/**
 * YurNLator base, part 2: the Vulkan wrapper's environment for a launch (WRAPPER_*, Mesa WSI,
 * BCn emulation), from the graphics driver config. Pure: reads settings, writes env vars, and
 * says whether the BCn texture layer is needed (the caller installs it).
 * Moved out of XServerDisplayActivity.extractGraphicsDriverFiles unchanged.
 */
public final class WrapperEnv {
    private WrapperEnv() {}

    public static final class Result {
        /** The BCn transcoding layer must be installed (non-Adreno, compute emulation). */
        public boolean bcnLayer;
        /** ...in its legacy (September) build. */
        public boolean bcnLegacy;
    }

    public static Result apply(Context context, Map<String, String> graphicsDriverConfig, KeyValueSet dxwrapperConfig,
                               EnvVars envVars, String adrenoToolsDriverId, boolean rgba8Surface) {
        Result result = new Result();
        String vulkanVersion = graphicsDriverConfig.get("vulkanVersion");
        String vulkanVersionPatch = GPUInformation.getVulkanVersion(adrenoToolsDriverId, context).split("\\.")[2];
        vulkanVersion = vulkanVersion + "." + vulkanVersionPatch;
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion);

        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String gpuName = graphicsDriverConfig.get("gpuName");
        String dxvkVersion = dxwrapperConfig.get("version");
        if (!gpuName.equals("Device") && (dxvkVersion == null || !dxvkVersion.contains("sarek"))) {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName);
            envVars.put("WRAPPER_DEVICE_ID", WineD3DConfigDialog.getDeviceIdFromGPUName(context, gpuName));
            envVars.put("WRAPPER_VENDOR_ID", WineD3DConfigDialog.getVendorIdFromGPUName(context, gpuName));
        }

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);

        String presentMode = graphicsDriverConfig.get("presentMode");
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String resourceType = graphicsDriverConfig.get("resourceType");
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);

        String syncFrame = graphicsDriverConfig.get("syncFrame");
        if (syncFrame.equals("1"))
            envVars.put("MESA_VK_WSI_DEBUG", "forcesync");

        String disablePresentWait = graphicsDriverConfig.get("disablePresentWait");
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

        String bcnEmulation = graphicsDriverConfig.get("bcnEmulation");
        String bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType");
        if (bcnEmulation == null) bcnEmulation = "auto";
        if (bcnEmulationType == null || bcnEmulationType.isEmpty()) bcnEmulationType = "compute";

        switch (bcnEmulation) {
            case "auto" -> envVars.put("WRAPPER_EMULATE_BCN", "3");
            case "full" -> envVars.put("WRAPPER_EMULATE_BCN", "2");
            case "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0");
            default -> envVars.put("WRAPPER_EMULATE_BCN", "1");
        }

        String bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache");
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache != null && !bcnEmulationCache.isEmpty() ? bcnEmulationCache : "1");

        // BCn texture layer from Winlator Mali 1.2.1 (leegao's bcn_layer, modified by WinMali-Dev):
        // Mali / non-Adreno GPUs have no BC1-BC7 in hardware, the layer transcodes them (ASTC /
        // ETC2). Adreno decodes BCn natively, so the layer is never loaded there.
        boolean bcnLayer = !"none".equals(bcnEmulation) && "compute".equals(bcnEmulationType)
                && GPUInformation.getVendorID(null, null) != 0x5143;
        if (bcnLayer) {
            boolean legacy = "legacy".equals(graphicsDriverConfig.get("bcnVersion"));
            // Benchmark-named builds (antutu…): the Mali driver answers differently there and the
            // Winlator Mali wrappers fail to create the device when the current layer asks for
            // ASTC HDR (winevulkan assert). The legacy layer doesn't ask for it.
            String wrapperId = graphicsDriverConfig.get("wrapper");
            if (!legacy && MaliCompat.forcesLegacyBcn(context, wrapperId)) {
                Log.d("BCn", "Spoofed package + Winlator Mali wrapper " + wrapperId + ": legacy BCn layer");
                legacy = true;
            }
            result.bcnLayer = true;
            result.bcnLegacy = legacy;
            envVars.put("ENABLE_BCN_COMPUTE", "1");
            if (legacy) {
                // September build: one target format at a quality preset.
                envVars.put("BCN_COMPUTE_AUTO", "1");
                String transcode = graphicsDriverConfig.get("bcnTranscode");
                envVars.put("etc2".equals(transcode) ? "BCN_TRANSCODE_TO_ETC2"
                        : "etc1".equals(transcode) ? "BCN_TRANSCODE_TO_ETC1" : "BCN_TRANSCODE_TO_ASTC", "1");
                String quality = graphicsDriverConfig.get("bcnQuality");
                if (quality != null && !quality.isEmpty() && !"auto".equals(quality)) envVars.put("BCN_QUALITY_PRESET", quality);
            } else {
                // Current build: the layer picks per texture; "full" emulation forces compute.
                envVars.put("BCN_COMPUTE_AUTO", "full".equals(bcnEmulation) ? "0" : "1");
            }
        } else {
            envVars.put("DISABLE_BCN_COMPUTE", "1");
        }

        if (rgba8Surface) {
            envVars.put("WRAPPER_SURFACE_FORMAT", "rgba8");
        }

        return result;
    }
}
