package com.winlator.cmod.xserver;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counters for Wine's offscreen Vulkan path (a game's surface redirected away from the screen,
 * its frames copied onto the window by Wine): how many frames went where in the last interval.
 * Printed with the session log's time marks, only when something happened.
 */
public final class OffscreenStats {
    public static final AtomicInteger presentsOffscreen = new AtomicInteger();   // Present to an unmapped / redirected window
    public static final AtomicInteger presentsCpuCopy = new AtomicInteger();     // Present copied on the CPU (no direct buffer)
    public static final AtomicInteger copyAreaDirect = new AtomicInteger();      // CopyArea that forwarded the direct buffer
    public static final AtomicInteger copyAreaDirectHidden = new AtomicInteger(); // ... into a window that isn't viewable
    public static final AtomicInteger getImageDirect = new AtomicInteger();      // GetImage read from the direct buffer
    public static final AtomicInteger getImagePlain = new AtomicInteger();       // GetImage of a window without a direct buffer
    public static final AtomicInteger putImageWindow = new AtomicInteger();      // PutImage / ShmPutImage into a window

    private OffscreenStats() {}

    /** "x11 offscreen: ..." for the last interval and reset, or null when nothing happened. */
    public static String drain() {
        int a = presentsOffscreen.getAndSet(0), b = presentsCpuCopy.getAndSet(0), c = copyAreaDirect.getAndSet(0),
            d = copyAreaDirectHidden.getAndSet(0), e = getImageDirect.getAndSet(0), f = getImagePlain.getAndSet(0),
            g = putImageWindow.getAndSet(0);
        if (a + b + c + d + e + f == 0) return null;
        return "[YurNLator] x11 offscreen: presents " + a + " (cpu copy " + b + "), copyArea direct " + c
                + " (to hidden " + d + "), getImage direct " + e + " / plain " + f + ", putImage to windows " + g;
    }
}
