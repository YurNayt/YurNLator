package com.winlator.cmod.session;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.RendererDriverGuard;
import com.winlator.cmod.widget.VulkanXServerView;

/**
 * The Vulkan driver the X11 screen renderer loads into the app's own process: the renderer driver
 * set for the game / container, else the game's graphics driver. The system driver instead on
 * non-Adreno GPUs (a custom driver such as PanVK took the whole app down there) and for a driver
 * that crashed the app in the renderer before ({@link RendererDriverGuard}); the game keeps its
 * own driver either way. Moved out of the game screen (base migration).
 */
public final class RendererDriver {
    private static final String TAG = "RendererDriver";

    private RendererDriver() {}

    public static void apply(Activity activity, VulkanXServerView renderer, Container container, Shortcut shortcut,
                             String graphicsDriverVersion) {
        String id = shortcut != null ? shortcut.getRendererDriverId()
                : (container != null ? container.getRendererDriverId() : "");
        if (id == null || id.isEmpty()) id = graphicsDriverVersion;
        if (custom(id) && !GPUInformation.isAdrenoGPU(activity)) {
            Log.d(TAG, "Renderer driver " + id + " ignored on non-Adreno GPU: system driver");
            id = null;
        }
        if (custom(id) && RendererDriverGuard.blocked(activity, id)) {
            Log.w(TAG, "Renderer driver " + id + " crashed the app before: system driver");
            Toast.makeText(activity, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.l_rd_blocked, id), Toast.LENGTH_LONG).show();
            id = null;
        }
        if (!custom(id)) return;
        RendererDriverGuard.arm(activity, id);
        try {
            String path = activity.getFilesDir().getAbsolutePath() + "/contents/adrenotools/" + id + "/";
            String library = new AdrenotoolsManager(activity).getLibraryName(id);
            if (!library.isEmpty()) renderer.setDriverInfo(path, library, AppUtils.getNativeLibDir(activity));
        } catch (Exception e) {
            Log.w(TAG, "renderer driver " + id, e);
        }
    }

    private static boolean custom(String id) {
        return id != null && !id.isEmpty() && !id.equalsIgnoreCase("system");
    }
}
