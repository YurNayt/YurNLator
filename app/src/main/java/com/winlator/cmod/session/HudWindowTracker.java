package com.winlator.cmod.session;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;

import java.util.HashMap;
import java.util.Map;

/**
 * Which X window the HUD counts frames of. The driver announces itself on the window it renders
 * to (_MESA_DRV_* properties); when it doesn't (some drivers / wrappers), or the game moves on
 * from a launcher / splash window, the window updated most often wins. Moved out of the game
 * screen (base migration).
 */
public final class HudWindowTracker {
    public interface Host {
        WinlatorHUD modernHud();
        FrameRating classicHud();
        XServerRendererView renderer();
        void runOnUi(Runnable r);
    }

    private final Host host;
    /** The window the HUD counts (-1: none yet). */
    private volatile int hudWindowId = -1;
    /** The window the driver announced itself on (-1: none). */
    private volatile int rendererWindowId = -1;
    private volatile String rendererName = null;
    private final HashMap<Integer, Integer> updates = new HashMap<>();
    private long checkMs = 0;

    public HudWindowTracker(Host host) { this.host = host; }

    public int hudWindowId() { return hudWindowId; }
    public int rendererWindowId() { return rendererWindowId; }
    public String rendererName() { return rendererName; }

    private boolean hudOn() {
        WinlatorHUD m = host.modernHud();
        FrameRating c = host.classicHud();
        return (m != null && m.isUserEnabled()) || (c != null && c.getVisibility() == View.VISIBLE);
    }

    private void point(int id) {
        hudWindowId = id;
        XServerRendererView r = host.renderer();
        if (r != null) r.setFpsWindowId(id);
    }

    /**
     * A HUD was just created or switched: count the renderer's window if one is known.
     * True when it was (the caller then shows the renderer name / first numbers).
     */
    public boolean adoptRendererWindow() {
        if (rendererWindowId == -1) return false;
        point(rendererWindowId);
        return true;
    }

    /** X window content changed (a frame through the X server). */
    public void onWindowContent(Window window) {
        track(window, false);
        WinlatorHUD m = host.modernHud();
        FrameRating c = host.classicHud();
        if (hudWindowId == window.id) {
            if (c != null) c.update();
            if (m != null) m.onFrame();
        } else if (hudWindowId == -1 && rendererName != null && window.isApplicationWindow() && hudOn()) {
            rendererWindowId = window.id;
            point(window.id);
            if (c != null) c.update();
            if (m != null) m.onFrame();
        }
    }

    /** Direct (DRI3) frames come from the game's own rendering window, which is often a child
     *  window (Wine's Vulkan / GL client window), not a top-level "application" window. */
    public void onDirectFrame(Window window) { track(window, true); }

    /** A window property changed ([property] set) or the window was destroyed (null). */
    public void onWindowProperty(Window window, Property property) {
        String propName = property != null ? property.nameAsString() : null;

        if (property != null) {
            if (rendererWindowId == -1 && propName.contains("_MESA_DRV")) rendererWindowId = window.id;
            if (propName.contains("_MESA_DRV_ENGINE_NAME") && (rendererWindowId == -1 || window.id == rendererWindowId))
                rendererName = property.toString();
        } else if (rendererWindowId != -1 && window.id == rendererWindowId) {
            rendererWindowId = -1;
            rendererName = null;
        }

        WinlatorHUD m = host.modernHud();
        FrameRating c = host.classicHud();
        if (c == null && m == null) return;

        if (property != null) {
            // The window the driver announced itself on renders the game: prefer it over a window
            // picked earlier (a launcher / splash window when the HUD was on from the start).
            if (propName.contains("_MESA_DRV") && window.id == rendererWindowId && hudWindowId != window.id) hudWindowId = -1;
            if (hudWindowId == -1 && propName.contains("_MESA_DRV")) {
                point(window.id);
                Log.d("HudWindowTracker", "Showing hud for Window " + window.getName());
                if (c != null) c.update();
                if (m != null) {
                    final String name = rendererName;
                    host.runOnUi(() -> m.onRendererDetected(name));
                }
            }
            if (propName.contains("_MESA_DRV_ENGINE_NAME") && window.id == hudWindowId) {
                String name = property.toString();
                if (c != null) host.runOnUi(() -> c.setRenderer(name));
                if (m != null) host.runOnUi(() -> m.setRenderer(name));
            }
            if (propName.contains("_MESA_DRV_GPU_NAME") && window.id == hudWindowId) {
                String gpu = property.toString();
                if (c != null) host.runOnUi(() -> c.setGpuName(gpu));
                if (m != null) host.runOnUi(() -> m.setGpuName(gpu));
            }
        } else if (hudWindowId != -1 && window.id == hudWindowId) {
            point(-1);
            Log.d("HudWindowTracker", "Hiding hud for Window " + window.getName());
            if (c != null) host.runOnUi(() -> { c.setVisibility(View.GONE); c.reset(); });
            if (m != null) host.runOnUi(m::onRendererGone);
        }
    }

    // FPS follows the window that actually renders: games often draw into a new window after a
    // launcher / splash one, and the HUD kept counting the old window (low or frozen FPS).
    private synchronized void track(Window window, boolean direct) {
        if (!direct && !window.isApplicationWindow()) return;
        if (!hudOn()) return;
        Integer n = updates.get(window.id);
        updates.put(window.id, n == null ? 1 : n + 1);
        long now = SystemClock.uptimeMillis();
        if (checkMs == 0) checkMs = now;
        if (now - checkMs < 1000) return;
        int busiest = -1, busiestCount = 0;
        for (Map.Entry<Integer, Integer> e : updates.entrySet()) {
            if (e.getValue() > busiestCount) { busiest = e.getKey(); busiestCount = e.getValue(); }
        }
        Integer cur = updates.get(hudWindowId);
        int current = cur == null ? 0 : cur;
        if (busiest != -1 && busiest != hudWindowId && busiestCount >= 10 && current * 2 < busiestCount) {
            // Also the fallback when the driver never announced itself (_MESA_DRV properties are
            // missing with some drivers / wrappers): the HUD used to stay hidden then.
            boolean wasUnset = hudWindowId == -1;
            if (rendererWindowId == -1 || !wasUnset) rendererWindowId = busiest;
            point(busiest);
            if (wasUnset) {
                final String name = rendererName;
                WinlatorHUD m = host.modernHud();
                FrameRating c = host.classicHud();
                if (m != null) host.runOnUi(() -> m.onRendererDetected(name));
                if (c != null) host.runOnUi(c::update);
            }
        }
        updates.clear();
        checkMs = now;
    }
}
