package com.winlator.cmod.session;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;

/**
 * Wayland zero-copy presentation: a fullscreen game's own buffers go to an Android layer, with no
 * compositor blit queued behind the game's GPU work (on a busy GPU that wait was 15-50 ms a frame
 * and the picture stuttered). On by default; the compositor itself falls back to the copy path
 * while an effect / upscaling is on or the scene isn't one fullscreen window, and for games whose
 * driver can't present that way. Per game (shortcut) first, then the container;
 * BANNER_WAYLAND_ZERO_COPY=0/1 in the environment variables still wins for A/B.
 */
public final class WaylandZeroCopy {
    private static final String KEY = "waylandZeroCopy";

    private WaylandZeroCopy() {}

    public static boolean enabled(Container container, Shortcut shortcut) {
        EnvVars env = new EnvVars(container != null ? container.getEnvVars() : "");
        if (shortcut != null) env.putAll(new EnvVars(shortcut.getExtra("envVars", "")));
        String e = env.get("BANNER_WAYLAND_ZERO_COPY").trim().toLowerCase(java.util.Locale.US);
        if (e.equals("1") || e.equals("true") || e.equals("on")) return true;
        if (e.equals("0") || e.equals("false") || e.equals("off")) return false;
        String v = shortcut != null ? shortcut.getExtra(KEY, "") : "";
        if (v.isEmpty() && container != null) v = container.getExtra(KEY, "");
        return !"0".equals(v);
    }

    public static void save(Container container, Shortcut shortcut, boolean on) {
        String v = on ? "1" : "0";
        if (shortcut != null) { shortcut.putExtra(KEY, v); shortcut.saveData(); }
        else if (container != null) { container.putExtra(KEY, v); container.saveData(); }
    }
}
