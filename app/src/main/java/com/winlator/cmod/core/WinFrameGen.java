package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.Locale;

/**
 * Frame generation with win-fg, Bannerlator's clean-room engine (MIT, FSR3-family optical flow +
 * own synthesis; https://github.com/The412Banner/win-fg). It runs as a Vulkan layer inside the
 * game: loaded when the game starts with WIN_FG_ENABLE=1, then turned on/off and tuned live
 * through HOME/.config/win-fg/conf.toml, which the layer re-reads.
 *
 * Container extras: frameGen = off | ready (layer loaded, generation off) | on,
 * frameGenMultiplier 2-4, frameGenPreset 0 Quality / 1 Balanced / 2 Performance, frameGenFlowScale 0.2-1.
 */
public final class WinFrameGen {
    public static final String OFF = "off";
    public static final String READY = "ready";
    public static final String ON = "on";
    private static final String ASSET_SO = "win-fg/libwin_fg.so";
    private static final String ASSET_JSON = "win-fg/VkLayer_win_framegen.json";

    private WinFrameGen() {}

    /** Puts the layer into the image (idempotent: copies only when the bundled build changed). */
    public static void install(Context context, ImageFs imageFs) {
        try {
            File so = new File(imageFs.getLibDir(), "libwin_fg.so");
            long size = FileUtils.getSize(context, ASSET_SO);
            if (!so.isFile() || so.length() != size) {
                File tmp = new File(so.getParentFile(), "libwin_fg.so.staging");
                FileUtils.copy(context, ASSET_SO, tmp);
                if (!tmp.renameTo(so)) {
                    tmp.delete();
                    Log.e("WinFrameGen", "Could not install the win-fg layer");
                    return;
                }
            }
            File manifestDir = new File(imageFs.getRootDir(), "usr/share/vulkan/implicit_layer.d");
            manifestDir.mkdirs();
            FileUtils.copy(context, ASSET_JSON, new File(manifestDir, "VkLayer_win_framegen.json"));
        } catch (Exception e) {
            Log.e("WinFrameGen", "Failed to install the win-fg layer", e);
        }
    }

    /** Writes the live settings the layer reads. */
    public static void writeConfig(ImageFs imageFs, boolean on, int multiplier, int preset, float flowScale) {
        try {
            File dir = new File(imageFs.home_path, ".config/win-fg");
            dir.mkdirs();
            String toml = "# Written by YurNLator (frame generation)\n"
                    + "enabled = " + (on ? "1" : "0") + "\n"
                    + "multiplier = " + Math.max(2, Math.min(4, multiplier)) + "\n"
                    + "flowScale = " + String.format(Locale.US, "%.2f", Math.max(0.2f, Math.min(1f, flowScale))) + "\n"
                    + "model = 3\n"
                    + "perf_preset = " + Math.max(0, Math.min(2, preset)) + "\n"
                    + "capture = off\n";
            FileUtils.writeString(new File(dir, "conf.toml"), toml);
        } catch (Exception e) {
            Log.e("WinFrameGen", "Failed to write win-fg conf.toml", e);
        }
    }
}
