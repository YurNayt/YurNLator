package com.winlator.cmod.xserver;

import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Auto-fit for in-game resolution changes (YurNLator).
 *
 * Wine runs in a virtual desktop the size of the container screen. When a game switches to a
 * smaller display mode (960x544, 1024x768, 1280x720 on a 1920x1080 container…), Wine shrinks the
 * desktop window to that size in the top-left corner, and the game used to sit in a corner of a
 * black screen. This tracks the desktop window's size; the renderer and the touch mapping then
 * scale that area to fill the view, keeping its aspect ratio.
 */
public final class DisplayModeTracker implements WindowManager.OnWindowModificationListener {
    private static final String TAG = "DisplayModeTracker";

    public interface Listener { void onDisplayModeChanged(); }

    private final ScreenInfo screen;
    private final WindowManager windowManager;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile short width, height;   // 0 = the whole screen
    private volatile boolean enabled = true;
    private Window desktop;

    public DisplayModeTracker(ScreenInfo screen, WindowManager windowManager) {
        this.screen = screen;
        this.windowManager = windowManager;
        windowManager.addOnWindowModificationListener(this);
    }

    /** The area the game sees as its screen: (0,0)-(width,height). */
    public short getWidth() { return enabled && width > 0 ? width : screen.width; }
    public short getHeight() { return enabled && height > 0 ? height : screen.height; }
    public boolean isActive() { return enabled && width > 0 && (width != screen.width || height != screen.height); }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        notifyListeners();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyListeners() { for (Listener l : listeners) l.onDisplayModeChanged(); }

    private boolean isWineDesktop(Window w) {
        if (w == null || w.getParent() != windowManager.rootWindow) return false;
        String cls = w.getClassName().toLowerCase();
        String name = w.getName().toLowerCase();
        return name.contains("wine desktop") || cls.contains("explorer");
    }

    private void update(Window w) {
        if (!isWineDesktop(w)) return;
        desktop = w;
        short nw = 0, nh = 0;
        if (w.attributes.isMapped() && w.getX() == 0 && w.getY() == 0
                && w.getWidth() >= 320 && w.getHeight() >= 200
                && w.getWidth() <= screen.width && w.getHeight() <= screen.height
                && (w.getWidth() < screen.width || w.getHeight() < screen.height)) {
            nw = w.getWidth();
            nh = w.getHeight();
        }
        if (nw != width || nh != height) {
            width = nw;
            height = nh;
            Log.i(TAG, nw > 0 ? "game display mode " + nw + "x" + nh + " on a " + screen + " screen: scaled to fit"
                    : "game display mode = screen size");
            notifyListeners();
        }
    }

    @Override public void onMapWindow(Window window) { update(window); }
    @Override public void onUpdateWindowGeometry(Window window, boolean resized) { if (resized) update(window); }
    @Override public void onModifyWindowProperty(Window window, Property property) { update(window); }

    @Override public void onUnmapWindow(Window window) {
        if (window == desktop && width > 0) { width = height = 0; notifyListeners(); }
    }

    @Override public void onDestroyWindow(Window window) {
        if (window == desktop) {
            desktop = null;
            if (width > 0) { width = height = 0; notifyListeners(); }
        }
    }
}
