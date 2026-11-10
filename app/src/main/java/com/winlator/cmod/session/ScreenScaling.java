package com.winlator.cmod.session;

import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.XServerRendererView;

import java.util.function.Supplier;

/**
 * How the game's picture fills the screen: 0 fit (keeps the aspect), 1 fullscreen, 2 soft
 * stretch (fullscreen with the Vulkan renderer's smooth stretch). The touchpad follows the
 * renderer so touches land where the picture is; on Wayland the compositor scales instead.
 * Moved out of the game screen, its drawer and the quick menu (base migration).
 */
public final class ScreenScaling {
    private final Supplier<XServerRendererView> renderer;
    private final Supplier<TouchpadView> touchpad;
    private final Supplier<WaylandSession> wayland;
    private boolean softStretch;

    public ScreenScaling(Supplier<XServerRendererView> renderer, Supplier<TouchpadView> touchpad, Supplier<WaylandSession> wayland) {
        this.renderer = renderer;
        this.touchpad = touchpad;
        this.wayland = wayland;
    }

    public int mode() {
        if (softStretch) return 2;
        XServerRendererView r = renderer.get();
        return r != null && r.isFullscreen() ? 1 : 0;
    }

    public void setMode(int mode) {
        XServerRendererView r = renderer.get();
        if (r == null) return;
        setFullscreen(r, mode >= 1);
        softStretch = mode == 2;
        WaylandSession w = wayland.get();
        if (w != null) w.setStretch(mode >= 1);
        if (r instanceof VulkanXServerView) ((VulkanXServerView) r).setStretchMode(softStretch ? 1 : 0);
    }

    /** The drawer's fullscreen button. */
    public void toggleFullscreen() {
        XServerRendererView r = renderer.get();
        if (r != null) setFullscreen(r, !r.isFullscreen());
    }

    /** The drawer's soft stretch button; true when it is on now. */
    public boolean toggleSoftStretch() {
        setMode(softStretch ? (mode() == 2 ? 1 : mode()) : 2);
        return softStretch;
    }

    private void setFullscreen(XServerRendererView r, boolean full) {
        if (r.isFullscreen() == full) return;
        r.toggleFullscreen();
        TouchpadView t = touchpad.get();
        if (t != null) t.toggleFullscreen();
    }
}
