package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Wayland display backend, launch side (ported from Bannerlator: WineWaylandSupport,
 * WaylandAdapter, WaylandGameDriver; GPL-3.0).
 *
 *  - A Wine / Proton layer can run on Wayland when it ships winewayland.so (Bannerlator's v8
 *    Proton 11 layers do). Otherwise the launch stays on X11.
 *  - The game renders through the "adapter": the bionic Vulkan wrapper rebuilt with the Wayland
 *    WSI (assets/wayland/adapter), handed to the Proton as its ICD (BANNER_WAYLAND_VK_ICD). It
 *    loads the container's AdrenoTools driver underneath (Turnip on Adreno, PanVK on Mali), so
 *    one driver pick feeds the compositor and the game.
 *  - System / Qualcomm-blob drivers can't share frames with the compositor (no dma-buf
 *    modifiers): on Adreno the layer's bundled Wayland Turnip is used instead.
 */
public final class WaylandSupport {
    private WaylandSupport() {}

    private static final String TAG = "WaylandSupport";
    public static final String ASSET_LIB = "wayland/adapter/libvulkan_wrapper.so";
    public static final String ENV_ICD = "BANNER_WAYLAND_VK_ICD";

    /** The layer at [winePath] has the Wayland driver. */
    public static boolean isLayerCapable(String winePath) {
        if (winePath == null || winePath.isEmpty()) return false;
        return new File(winePath, "lib/wine/aarch64-unix/winewayland.so").isFile()
                || new File(winePath, "lib/wine/x86_64-unix/winewayland.so").isFile();
    }

    /** The layer bundles its own Wayland Turnip (Adreno fallback when the adapter can't be used). */
    public static boolean layerHasWaylandTurnip(String winePath) {
        return winePath != null && new File(winePath, "lib/libvulkan_freedreno_wayland.so").isFile();
    }

    /** Why the adapter can't sit on [driverId], or null when it can (needs a Mesa adrenotools driver). */
    public static String adapterUnusableReason(Context context, String driverId) {
        if (driverId == null || driverId.isEmpty() || driverId.equalsIgnoreCase("System"))
            return "the graphics driver is System";
        AdrenotoolsManager atm = new AdrenotoolsManager(context);
        String lib = atm.getLibraryName(driverId);
        if (lib == null || lib.isEmpty()) return "the graphics driver " + driverId + " is not installed";
        String name = (atm.getDriverName(driverId) + " " + lib).toLowerCase();
        if (name.contains("qualcomm") || lib.contains("libvulkan_adreno"))
            return "the graphics driver is the proprietary Qualcomm driver";
        return null;
    }

    /**
     * Sets the game's Wayland Vulkan driver for this launch: the adapter on a Mesa driver, else the
     * layer's bundled Wayland Turnip (Adreno). Returns a line for the log.
     */
    public static String applyGameDriver(Context context, com.winlator.cmod.core.EnvVars envVars, String driverId, String winePath) {
        String why = adapterUnusableReason(context, driverId);
        boolean mali = !GPUInformation.isAdrenoGPU(context);
        if (why == null || (mali && "the graphics driver is System".equals(why))) {
            String icd = ensureAdapterInstalled(context);
            if (icd != null) {
                envVars.put(ENV_ICD, icd);
                return "game driver: Wayland adapter on " + (driverId == null ? "System" : driverId)
                        + (why != null ? " (experimental: system driver)" : "");
            }
            return "game driver: adapter missing from the APK, layer default";
        }
        return "game driver: " + (layerHasWaylandTurnip(winePath) ? "layer's Wayland Turnip" : "layer default")
                + " (" + why + ")";
    }

    private static String cachedIcd;

    /** Installs the bundled adapter (files/wayland_adapter/<hash>/) and returns its icd.json path. */
    public static synchronized String ensureAdapterInstalled(Context context) {
        if (cachedIcd != null && new File(cachedIcd).isFile()) return cachedIcd;
        File root = new File(context.getFilesDir(), "wayland_adapter");
        try {
            String id = assetId(context);
            if (id == null) return null;
            File dir = new File(root, id);
            File lib = new File(dir, "libvulkan_wrapper.so");
            File icd = new File(dir, "icd.json");
            if (!lib.isFile() || !icd.isFile()) {
                FileUtils.delete(dir);
                if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
                try (InputStream in = context.getAssets().open(ASSET_LIB);
                     OutputStream out = new FileOutputStream(lib)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                lib.setReadable(true, false);
                lib.setExecutable(true, false);
                JSONObject body = new JSONObject();
                body.put("library_path", lib.getAbsolutePath());
                body.put("api_version", "1.3.0");
                JSONObject manifest = new JSONObject();
                manifest.put("file_format_version", "1.0.0");
                manifest.put("ICD", body);
                // Plain slashes: winewayland's pin_icd_library can't read "\/".
                FileUtils.writeString(icd, manifest.toString(2).replace("\\/", "/"));
                Log.i(TAG, "installed the Wayland adapter " + id);
            }
            File[] old = root.listFiles();
            if (old != null) for (File f : old) if (!f.getName().equals(id)) FileUtils.delete(f);
            cachedIcd = icd.getAbsolutePath();
            return cachedIcd;
        } catch (Exception e) {
            Log.e(TAG, "could not install the Wayland adapter", e);
            return null;
        }
    }

    private static String assetId(Context context) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(ASSET_LIB)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        } catch (IOException e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        byte[] d = md.digest();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));
        return sb.toString();
    }
}
