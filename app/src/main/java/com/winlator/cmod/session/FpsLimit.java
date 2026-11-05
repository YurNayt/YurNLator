package com.winlator.cmod.session;

import com.winlator.cmod.container.Container;

/**
 * YurNLator base, part 9: the host FPS limit. Saved in the container as the exact value
 * (graphicsFpsLimit) and, for older builds and presets, as the index of the nearest legacy choice
 * (graphicsFpsPreset: Off / 30 / 60 / 90 / 120).
 */
public final class FpsLimit {
    public static final int[] LEGACY = {0, 30, 60, 90, 120};

    private FpsLimit() {}

    /** The saved limit in FPS (0 = off): the exact value first, else the legacy index. */
    public static int saved(Container container) {
        if (container == null) return 0;
        String v = container.getExtra("graphicsFpsLimit", "");
        if (!v.isEmpty()) {
            try { return Math.max(0, Integer.parseInt(v.trim())); } catch (Exception ignored) {}
        }
        try {
            int i = Integer.parseInt(container.getExtra("graphicsFpsPreset", "0").trim());
            return i >= 0 && i < LEGACY.length ? LEGACY[i] : 0;
        } catch (Exception e) { return 0; }
    }

    /** Index of the legacy choice equal to [fps] (0 = off when none matches). */
    public static int legacyIndex(int fps) {
        for (int i = 0; i < LEGACY.length; i++) if (LEGACY[i] == fps) return i;
        return 0;
    }

    /** The Wayland compositor holds each released buffer to the limit's cadence. */
    public static void applyWayland(int fps) {
        try { com.winlator.cmod.wayland.WaylandCompositor.nativeSetFpsLimit(Math.max(0, fps)); }
        catch (Throwable t) { android.util.Log.w("FpsLimit", "wayland limit: " + t); }
    }

    public static void save(Container container, int fps) {
        if (container == null) return;
        container.putExtra("graphicsFpsPreset", String.valueOf(legacyIndex(fps)));
        container.putExtra("graphicsFpsLimit", String.valueOf(fps));
        container.saveData();
    }
}
