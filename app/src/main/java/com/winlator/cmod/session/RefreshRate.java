package com.winlator.cmod.session;

import android.app.Activity;
import android.util.Log;

import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.extensions.RandrExtension;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Refresh rates of a launch: the game screen runs at the panel's highest rate, Wine's RandR sees
 * every rate the panel has, and a Wine built with XRandR emulates the mode list / mode set so
 * games that insist on their saved 120 / 144 Hz mode find it. Moved out of the game screen
 * (base migration).
 */
public final class RefreshRate {
    private static final String TAG = "RefreshRate";
    private static final HashMap<String, Boolean> WINE_XRANDR_SUPPORT_CACHE = new HashMap<>();

    private RefreshRate() {}

    /** Tells Wine (RandR) every refresh rate the panel has and the current one. */
    public static void advertise(Activity activity, XServer xServer) {
        RandrExtension randr = xServer.getExtension(RandrExtension.MAJOR_OPCODE);
        if (randr == null) return;

        android.view.Display display = activity.getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) return;

        java.util.TreeSet<Short> distinct =
                new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (android.view.Display.Mode mode : modes) {
            short hz = (short)Math.round(mode.getRefreshRate());
            if (hz > 0) distinct.add(hz);
        }
        if (distinct.isEmpty()) return;

        short[] rates = new short[distinct.size()];
        int index = 0;
        for (short hz : distinct) rates[index++] = hz;

        short activeRate = (short)Math.round(display.getRefreshRate());
        randr.setRefreshRates(rates, activeRate);
        Log.d(TAG, "RandR advertising refresh rates "
                + java.util.Arrays.toString(rates) + ", active=" + activeRate);
    }

    /** The panel's highest refresh rate (the game screen asks Android for it). */
    public static float highest(Activity activity) {
        android.view.Display display = activity.getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();

        float maxRefresh = 0f;

        for (android.view.Display.Mode mode : modes) {
            if (mode.getRefreshRate() > maxRefresh)
                maxRefresh = mode.getRefreshRate();
        }

        Log.d(TAG, "Picking refresh rate " + maxRefresh);

        return maxRefresh;
    }

    /** Wine's mode-list / mode-set emulation on for a Wine built with XRandR, so games see and set the panel's rates. */
    public static void applyWineModeEmulation(ImageFs imageFs, WineInfo wineInfo) {
        final String x11DriverKey = "Software\\Wine\\X11 Driver";
        final boolean xrandrCapable = xrandrCapable(wineInfo);
        File userRegFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (xrandrCapable) {
                registryEditor.setStringValue(x11DriverKey, "EmulateModelist", "Y");
                registryEditor.setStringValue(x11DriverKey, "EmulateModeset", "Y");
            } else {
                registryEditor.removeValue(x11DriverKey, "EmulateModelist");
                registryEditor.removeValue(x11DriverKey, "EmulateModeset");
            }
        }

        Log.d(TAG, "RandR Wine mode emulation: "
                + (xrandrCapable ? "disabled" : "unchanged (layer has no XRandR)"));
    }

    private static boolean xrandrCapable(WineInfo wineInfo) {
        if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return false;

        String cacheKey = wineInfo.identifier() + "@" + wineInfo.path;
        synchronized (WINE_XRANDR_SUPPORT_CACHE) {
            Boolean cached = WINE_XRANDR_SUPPORT_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }

        File winex11 = new File(wineInfo.path, "lib/wine/aarch64-unix/winex11.so");
        if (!winex11.isFile()) {
            winex11 = new File(wineInfo.path, "lib/wine/x86_64-unix/winex11.so");
        }

        boolean capable = winex11.isFile()
                && fileContainsAny(winex11, "libXrandr.so", "RRQueryVersion")
                && !fileContainsAny(winex11, "XRandR support not compiled in.");

        synchronized (WINE_XRANDR_SUPPORT_CACHE) {
            WINE_XRANDR_SUPPORT_CACHE.put(cacheKey, capable);
        }
        return capable;
    }

    private static boolean fileContainsAny(File file, String... markers) {
        byte[][] needles = new byte[markers.length][];
        int maxNeedle = 0;
        for (int i = 0; i < markers.length; i++) {
            needles[i] = markers[i].getBytes(StandardCharsets.US_ASCII);
            maxNeedle = Math.max(maxNeedle, needles[i].length);
        }

        final int chunkSize = 64 * 1024;
        byte[] buffer = new byte[chunkSize + maxNeedle];
        try (FileInputStream input = new FileInputStream(file)) {
            int carry = 0;
            int read;
            while ((read = input.read(buffer, carry, chunkSize)) != -1) {
                int length = carry + read;
                for (byte[] needle : needles) {
                    if (indexOfBytes(buffer, length, needle) >= 0) return true;
                }

                carry = Math.min(length, maxNeedle - 1);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static int indexOfBytes(byte[] haystack, int haystackLength, byte[] needle) {
        if (needle.length == 0 || needle.length > haystackLength) return -1;

        outer:
        for (int i = 0; i <= haystackLength - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
