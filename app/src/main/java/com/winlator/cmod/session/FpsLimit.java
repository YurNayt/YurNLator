package com.winlator.cmod.session;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.KeyValueSet;

/**
 * YurNLator base, part 9: the FPS limit. One value for every control: "Frame Rate" of the DX
 * wrapper settings (framerate=), the game's when it has its own settings, else the container's.
 * The game settings editor, the sidebar and the quick menu all read and write it.
 *
 * It is applied by the host (X11: the game's Present idles are paced; Wayland: its buffer
 * releases), not by DXVK's own limiter: DXVK reads its limit once at start, so a limit raised
 * during play stayed capped at the start value.
 */
public final class FpsLimit {
    public static final int[] LEGACY = {0, 30, 60, 90, 120};

    private FpsLimit() {}

    /** The DX wrapper settings in effect for this game (its own, else the container's). */
    private static String config(Container container, Shortcut shortcut) {
        String fallback = container != null ? container.getDXWrapperConfig() : "";
        return shortcut != null ? shortcut.getExtra("dxwrapperConfig", fallback) : fallback;
    }

    /** The limit in FPS (0 = off). */
    public static int saved(Container container, Shortcut shortcut) {
        return parse(new KeyValueSet(config(container, shortcut)).get("framerate"));
    }

    public static int parse(String v) {
        try { return Math.max(0, Integer.parseInt(v == null ? "" : v.trim())); } catch (Exception e) { return 0; }
    }

    /** Index of the legacy choice equal to [fps] (0 = off when none matches). */
    public static int legacyIndex(int fps) {
        for (int i = 0; i < LEGACY.length; i++) if (LEGACY[i] == fps) return i;
        return 0;
    }

    /** Saved where the game settings editor shows it: the game's settings, or the container's without a game. */
    public static void save(Container container, Shortcut shortcut, int fps) {
        KeyValueSet cfg = new KeyValueSet(config(container, shortcut));
        cfg.put("framerate", String.valueOf(Math.max(0, fps)));
        if (shortcut != null) {
            shortcut.putExtra("dxwrapperConfig", cfg.toString());
            shortcut.saveData();
        } else if (container != null) {
            container.setDXWrapperConfig(cfg.toString());
            container.saveData();
        }
        if (container != null) {
            // Older builds' keys, so they don't come back with another value.
            container.putExtra("graphicsFpsLimit", null);
            container.putExtra("graphicsFpsPreset", null);
            container.putExtra("nativeFpsLimit", null);
            container.putExtra("nativeFpsLimiterEnabled", null);
            container.saveData();
        }
    }

    /** The Wayland compositor holds each released buffer to the limit's cadence. */
    public static void applyWayland(int fps) {
        try { com.winlator.cmod.wayland.WaylandCompositor.nativeSetFpsLimit(Math.max(0, fps)); }
        catch (Throwable t) { android.util.Log.w("FpsLimit", "wayland limit: " + t); }
    }
}
