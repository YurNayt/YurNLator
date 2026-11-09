package com.winlator.cmod.session;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

/**
 * The graphics side of a launch, before the game starts: DXVK / WineD3D settings (DXVK's own FPS
 * limiter off: the host limits), the Vulkan wrapper ICD, the graphics runtime, wrapper and OpenGL
 * files, the Turnip driver through adrenotools (and the Wayland game driver), the wrapper / BCn
 * environment with its BCn layer, and vkBasalt. Moved out of the game screen (base migration).
 */
public final class GraphicsSetup {
    private static final String TAG = "GraphicsSetup";

    private GraphicsSetup() {}

    public static void prepare(Activity activity, SharedPreferences preferences, ImageFs imageFs, EnvVars envVars,
                               Container container, String graphicsDriver, java.util.Map<String, String> graphicsDriverConfig,
                               String dxwrapper, KeyValueSet dxwrapperConfig, String vkbasaltConfig,
                               boolean firstTimeBoot, boolean waylandMode, String winePath, boolean rgbaSurface) {
        String driverId = graphicsDriverConfig.get("version");
        Log.d(TAG, "Adrenotools DriverID: " + driverId);
        File rootDir = imageFs.getRootDir();

        if (dxwrapper.contains("dxvk")) {
            // The FPS limit is the host's (FpsLimit), changeable during play: DXVK's own limiter
            // reads it once at start and would keep capping at the start value.
            KeyValueSet dxvkConfig = new KeyValueSet(dxwrapperConfig.toString());
            dxvkConfig.put("framerate", "0");
            DXVKConfigDialog.setEnvVars(activity, dxvkConfig, envVars);
            String version = dxwrapperConfig.get("version");
            if (version != null && version.contains("sarek")) {
                Log.d(TAG, "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass");
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        } else {
            WineD3DConfigDialog.setEnvVars(activity, dxwrapperConfig, envVars);
        }

        if (!preferences.getBoolean("use_dri3", true)) envVars.put("MESA_VK_WSI_DEBUG", "sw");
        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");

        GraphicsDriverFiles.installRuntime(activity, rootDir, firstTimeBoot, graphicsDriverConfig.get("wrapper"));
        GraphicsDriverFiles.installOpenGL(activity, rootDir, container, graphicsDriver, firstTimeBoot);

        if (!"System".equals(driverId)) new AdrenotoolsManager(activity).setDriverById(envVars, imageFs, driverId);
        if (waylandMode) Log.i(TAG, "wayland: " + WaylandSupport.applyGameDriver(activity, envVars, driverId, winePath));

        WrapperEnv.Result wenv = WrapperEnv.apply(activity, graphicsDriverConfig, dxwrapperConfig, envVars, driverId, rgbaSurface);
        if (wenv.bcnLayer) GraphicsDriverFiles.installBcnLayer(activity, rootDir, wenv.bcnLegacy);

        if (vkbasaltConfig != null && !vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1");
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
        }
    }
}
