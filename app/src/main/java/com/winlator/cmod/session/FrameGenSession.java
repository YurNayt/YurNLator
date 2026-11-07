package com.winlator.cmod.session;

import android.app.Activity;
import android.widget.Toast;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.LsfgNative;
import com.winlator.cmod.core.WinFrameGen;
import com.winlator.cmod.wayland.WaylandCompositor;
import com.winlator.cmod.widget.VulkanXServerView;

import java.util.concurrent.Executors;

/**
 * YurNLator base, part 8: frame generation in the host Vulkan renderer (Win-FG, WFG or LSFG),
 * from the game's settings, then the container's. Switched live from the quick menu.
 */
public final class FrameGenSession {
    private final Shortcut shortcut;
    private final Container container;

    public FrameGenSession(Shortcut shortcut, Container container) {
        this.shortcut = shortcut;
        this.container = container;
    }

    public String setting(String key, String fallback) {
        String v = shortcut != null ? shortcut.getExtra(key, "") : "";
        if (v.isEmpty() && container != null) v = container.getExtra(key, "");
        return v.isEmpty() ? fallback : v;
    }

    public boolean on() { return WinFrameGen.ON.equals(setting("frameGen", WinFrameGen.OFF)); }
    public int multiplier() { return parseInt(setting("frameGenMultiplier", "2"), 2); }
    public int preset() { return parseInt(setting("frameGenPreset", "1"), 1); }
    public int target() { return parseInt(setting("frameGenTarget", "0"), 0); }

    public float flowScale() {
        try { return Float.parseFloat(setting("frameGenFlowScale", "0.6")); } catch (Exception e) { return 0.6f; }
    }

    public String engine(Activity activity) {
        String e = setting("frameGenEngine", "");
        return e.isEmpty() ? WinFrameGen.defaultEngine(activity) : e;
    }

    public void apply(Activity activity, VulkanXServerView view) {
        if (view == null) return;
        final int mult = multiplier(), preset = preset();
        final float flow = flowScale();
        boolean on = on();
        view.setFrameGenTarget(target());
        String engine = engine(activity);
        if (on && WinFrameGen.ENGINE_LSFG.equals(engine)) {
            if (!LsfgNative.isDllAvailable(activity)) {
                Toast.makeText(activity, "LSFG: import your Lossless.dll in Settings › Frame generation. Using Win-FG.", Toast.LENGTH_LONG).show();
                view.setFrameGenEngine(0, null);
                view.setFrameGen(true, mult, preset, flow);
                return;
            }
            // The shader cache is built at import; checked (and rebuilt if the DLL changed) off the UI thread.
            Executors.newSingleThreadExecutor().execute(() -> {
                int status = LsfgNative.ensureCache(activity, true);
                activity.runOnUiThread(() -> {
                    if (status == LsfgNative.STATUS_OK) {
                        view.setFrameGenEngine(1, LsfgNative.cacheFile(activity).getAbsolutePath());
                    } else {
                        Toast.makeText(activity, "LSFG: " + LsfgNative.explain(status) + ". Using Win-FG.", Toast.LENGTH_LONG).show();
                        view.setFrameGenEngine(0, null);
                    }
                    view.setFrameGen(true, mult, preset, flow);
                    // The engine starts on the next frame: report a device that can't run it.
                    view.postDelayed(() -> {
                        String why = view.lsfgProblem();
                        if (why != null) Toast.makeText(activity, "LSFG can't run: " + why, Toast.LENGTH_LONG).show();
                    }, 2500);
                });
            });
            return;
        }
        view.setFrameGenEngine(WinFrameGen.ENGINE_WFG.equals(engine) ? 2 : 0, null);
        view.setFrameGen(on, mult, preset, flow);
    }

    /**
     * Wayland: the compositor presents the game, so frame generation runs there (its LSFG /
     * Win-FG engines on the compositor's Turnip device); the X11 renderer's engines never saw
     * the game's frames.
     */
    public void applyWayland(Activity activity) {
        final int mult = Math.max(2, Math.min(4, multiplier()));
        final boolean on = on();
        float refresh = 60f;
        try { refresh = activity.getWindowManager().getDefaultDisplay().getRefreshRate(); } catch (Throwable ignored) {}
        final float hz = refresh;
        try {
            if (!on) { WaylandCompositor.nativeSetFrameGenArmed(false, mult); return; }
            WaylandCompositor.nativeSetFrameGenTuning(Math.max(0.25f, Math.min(1f, flowScale())), hz);
            if (WinFrameGen.ENGINE_LSFG.equals(engine(activity))) {
                if (!LsfgNative.isDllAvailable(activity)) {
                    Toast.makeText(activity, "LSFG: import your Lossless.dll in Settings › Frame generation. Using Win-FG.", Toast.LENGTH_LONG).show();
                    armWinFg(mult);
                    return;
                }
                Executors.newSingleThreadExecutor().execute(() -> {
                    int status = LsfgNative.ensureCache(activity, true);
                    activity.runOnUiThread(() -> {
                        try {
                            if (status == LsfgNative.STATUS_OK) {
                                WaylandCompositor.nativeSetLsfgCachePath(LsfgNative.cacheFile(activity).getAbsolutePath());
                                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_LSFG);
                                WaylandCompositor.nativeSetFrameGenArmed(true, mult);
                            } else {
                                Toast.makeText(activity, "LSFG: " + LsfgNative.explain(status) + ". Using Win-FG.", Toast.LENGTH_LONG).show();
                                armWinFg(mult);
                            }
                        } catch (Throwable t) { android.util.Log.w("FrameGen", "wayland lsfg: " + t); }
                    });
                });
                return;
            }
            if (WinFrameGen.ENGINE_WFG.equals(engine(activity))) {
                // WFG runs in the compositor too (kind 2): flow resolution from the preset.
                WaylandCompositor.nativeSetWinFgTuning(4, Math.max(0, Math.min(2, preset())));
                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_WFG);
                WaylandCompositor.nativeSetFrameGenArmed(true, mult);
                return;
            }
            armWinFg(mult);
        } catch (Throwable t) {
            android.util.Log.w("FrameGen", "wayland: " + t);
        }
    }

    private void armWinFg(int mult) {
        WaylandCompositor.nativeSetWinFgTuning(4, Math.max(0, Math.min(2, preset())));
        WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_WINFG);
        WaylandCompositor.nativeSetFrameGenArmed(true, mult);
    }

    /** A few seconds after arming: says why the compositor's engine doesn't run, if it doesn't
     *  (the device lacks a feature, or the engine failed to start). */
    public static void reportWaylandProblem(Activity activity, java.util.function.Consumer<String> log) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                int p;
                try { p = WaylandCompositor.nativeFrameGenProblem(); } catch (Throwable t) { return; }
                if (p == -1 && ++tries < 10) { h.postDelayed(this, 3000); return; }   // compositor device not up yet
                String why = null;
                if (p == 1) {
                    String r = "";
                    try { r = WaylandCompositor.nativeFrameGenCapsReason(); } catch (Throwable ignored) {}
                    why = "this GPU / driver lacks what it needs" + (r == null || r.isEmpty() ? "" : ": " + r);
                } else if (p == 2) {
                    why = "the engine failed to start (details in Download/Wayland-logs)";
                } else if (p == -1) {
                    why = "the compositor's GPU device didn't start";
                }
                String line = "[YurNLator] Wayland frame generation: " + (why == null ? "running" : "not running, " + why);
                if (log != null) log.accept(line);
                if (why != null) Toast.makeText(activity, "Frame generation (Wayland) not running: " + why, Toast.LENGTH_LONG).show();
            }
        }, 4000);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
}
