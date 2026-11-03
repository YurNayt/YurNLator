package com.winlator.cmod.core;

import android.content.Context;

import androidx.preference.PreferenceManager;

/**
 * Frame generation settings. Two engines run inside the Vulkan renderer
 * (cpp/winlator/renderer/vulkan/VulkanRendererFrameGen.cpp):
 *  - win-fg: Bannerlator's clean-room engine (MIT, https://github.com/The412Banner/win-fg), built in;
 *  - LSFG: Lossless Scaling's frame generation, from the user's own Lossless.dll (Steam app 993090),
 *    imported in Settings and translated to SPIR-V on the device ({@link LsfgNative}).
 *
 * Container / game extras: frameGen = off | on, frameGenEngine = winfg | lsfg (empty = the app
 * default from Settings), frameGenMultiplier 2-4, frameGenPreset 0 Quality / 1 Balanced /
 * 2 Performance (win-fg), frameGenFlowScale 0.25-1.
 */
public final class WinFrameGen {
    public static final String OFF = "off";
    public static final String ON = "on";
    public static final String ENGINE_WINFG = "winfg";
    public static final String ENGINE_LSFG = "lsfg";
    private static final String PREF_ENGINE = "frame_gen_engine";

    private WinFrameGen() {}

    /** The engine used when a container / game doesn't choose one. */
    public static String defaultEngine(Context context) {
        String v = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_ENGINE, ENGINE_WINFG);
        return ENGINE_LSFG.equals(v) ? ENGINE_LSFG : ENGINE_WINFG;
    }

    public static void setDefaultEngine(Context context, String engine) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_ENGINE, engine).apply();
    }

    public static String engineLabel(String engine) {
        return ENGINE_LSFG.equals(engine) ? "LSFG" : "Win-FG";
    }
}
