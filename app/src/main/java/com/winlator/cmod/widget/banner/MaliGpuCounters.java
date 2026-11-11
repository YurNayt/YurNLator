package com.winlator.cmod.widget.banner;

import android.os.SystemClock;
import android.util.Log;

import com.winlator.cmod.core.GPUInformation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mali GPU load and clock from the GPU's own hardware counters (/dev/mali0), for phones whose sysfs
 * GPU nodes are closed to apps (most MediaTek phones without root). From WinlatorMali
 * (GunaCharanTeja, MIT) via Bannerlator (GPL-3.0); the reader is cpp/gpuclock/mali_counters.cpp.
 *
 * HudMetrics asks for it after every sysfs path failed. Only a CSF-generation Mali opens the device:
 * a three-digit Mali-G (G310 / G510 / G610 / G615 / G710 / G715 / G720 / G925…) or an Immortalis.
 * Job-manager parts (G31…G78, two digits — G52, G57) are never touched: on old Bifrost kernels the
 * counter or time-info ioctls rebooted the phone (WinlatorMali, Helio G95). The native side checks
 * the CSF interface version as well.
 *
 * One reader for the process (several HUD views each own a HudMetrics); samples closer than
 * {@link #MIN_INTERVAL_MS} return the last one. Thread-safe.
 */
final class MaliGpuCounters {
    private static final String TAG = "MaliGpuCounters";
    private static final long MIN_INTERVAL_MS = 500;
    private static final Pattern CSF_MODEL =
            Pattern.compile("(mali[- ]?g[1-9]\\d\\d(?!\\d))|immortalis", Pattern.CASE_INSENSITIVE);

    private static final Object LOCK = new Object();
    private static Boolean eligible;
    private static boolean libraryLoaded;
    private static long handle;
    private static boolean unavailable;
    private static long lastSampleMs;
    private static final int[] last = {-1, -1};
    private static final int[] sample = new int[2];

    private MaliGpuCounters() {}

    /** True for a renderer name of a CSF-generation Mali (three-digit Mali-G or Immortalis). */
    static boolean isCsfMaliName(String renderer) {
        return renderer != null && CSF_MODEL.matcher(renderer.toLowerCase(Locale.US)).find();
    }

    /** GPU load 0..100, or null when the counters can't be read here. */
    static Integer usagePercent() {
        int v = read()[0];
        return v >= 0 ? v : null;
    }

    /** Current GPU clock in MHz, or null when unknown. */
    static Integer clockMhz() {
        int v = read()[1];
        return v > 0 ? v : null;
    }

    private static int[] read() {
        synchronized (LOCK) {
            if (unavailable) return new int[]{-1, -1};
            long now = SystemClock.elapsedRealtime();
            if (handle != 0 && now - lastSampleMs < MIN_INTERVAL_MS) return last.clone();
            try {
                if (handle == 0) {
                    if (!isEligible()) {
                        unavailable = true;
                        return new int[]{-1, -1};
                    }
                    if (!libraryLoaded) {
                        System.loadLibrary("gpuclock");
                        libraryLoaded = true;
                    }
                    handle = nativeOpen();
                    if (handle == 0) {
                        unavailable = true;
                        Log.i(TAG, "hardware counters not available on this GPU / kernel");
                        return new int[]{-1, -1};
                    }
                    Log.i(TAG, "reading Mali GPU load / clock from hardware counters");
                }
                if (!nativeSample(handle, sample)) {
                    nativeClose(handle);
                    handle = 0;
                    unavailable = true;
                    return new int[]{-1, -1};
                }
                lastSampleMs = now;
                last[0] = sample[0];
                last[1] = sample[1];
                return last.clone();
            } catch (Throwable t) {
                unavailable = true;
                Log.w(TAG, "counter reader failed", t);
                return new int[]{-1, -1};
            }
        }
    }

    private static boolean isEligible() {
        if (eligible == null) {
            boolean ok = false;
            try {
                ok = isCsfMaliName(GPUInformation.getRenderer(null, null));
            } catch (Throwable ignored) {}
            eligible = ok;
            Log.i(TAG, "CSF Mali: " + ok);
        }
        return eligible;
    }

    private static native long nativeOpen();
    private static native boolean nativeSample(long handle, int[] out);
    private static native void nativeClose(long handle);
}
