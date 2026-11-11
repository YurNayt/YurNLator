package com.winlator.cmod.xserver;

import android.os.SystemClock;

/**
 * MangoHud's "toggle HUD" key without a key the game sees. MangoHud asks the X server which keys
 * are held (QueryKeymap) at every present and toggles when its toggle_hud keys are. Its config
 * binds toggle_hud to F24, which our keyboard never maps: it then looks at keycode 0, and only
 * this class sets that bit, for a moment. Wine ignores keycodes under 8.
 *
 * Showing / hiding this way needs no config reload: a reload ran in every Vulkan instance's
 * watcher thread at once (DXVK makes several) and they rebuilt the same row list together, so the
 * CPU / GPU rows came out twice.
 */
public final class MangoHudKeys {
    private MangoHudKeys() {}

    private static volatile long pressedUntil;
    private static volatile long lastQuery;

    /** Shown in the QueryKeymap reply's first byte (keycode 0). */
    static byte firstByte() {
        long now = SystemClock.uptimeMillis();
        lastQuery = now;
        return now < pressedUntil ? (byte) 1 : 0;
    }

    /** MangoHud asks for the keys (its key bindings work in this game). */
    public static boolean alive() {
        long q = lastQuery;
        return q != 0 && SystemClock.uptimeMillis() - q < 3000;
    }

    /** One toggle: held shorter than MangoHud's 400 ms repeat guard, long enough for a few presents. */
    public static void pressToggle() {
        pressedUntil = SystemClock.uptimeMillis() + 250;
    }
}
