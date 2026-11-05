package com.winlator.cmod.session;

import android.app.Activity;
import android.widget.Toast;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.LsfgNative;
import com.winlator.cmod.core.WinFrameGen;
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

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
}
