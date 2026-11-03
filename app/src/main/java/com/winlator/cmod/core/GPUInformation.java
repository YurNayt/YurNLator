package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Asks a Vulkan driver about itself by loading it into the app process.
 *
 * YurNLator: a custom driver (adrenotools: Turnip, PanVK…) can crash the whole app when loaded
 * here (testers: PanVK builds for another Mali generation -> native crash on opening the
 * graphics settings). So:
 *  - on non-Adreno GPUs only the system driver is probed (Turnip can't load there, PanVK may
 *    crash); questions about another driver get the system driver's answer;
 *  - a driver that crashed the app while being probed is remembered and never probed again.
 */
public abstract class GPUInformation {
    private static final String KEY_INFLIGHT = "yurn_driver_probe_inflight";
    private static final String KEY_BAD = "yurn_driver_probe_crashed";
    private static Boolean adreno;

    public static boolean isAdrenoGPU(Context context) {
        if (adreno == null) adreno = nGetRenderer(null, context).toLowerCase().contains("adreno");
        return adreno;
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !"System".equals(driverName))
            return false;

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }

    /** The driver to actually load for a question about [driverName]. */
    private static String safe(String driverName, Context context) {
        if (driverName == null || "System".equals(driverName) || driverName.isEmpty()) return driverName;
        if (context == null) return driverName;
        if (!isAdrenoGPU(context)) return "System";
        if (crashed(context).contains(driverName)) return "System";
        return driverName;
    }

    private static Set<String> crashed(Context context) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        return new HashSet<>(p.getStringSet(KEY_BAD, new HashSet<>()));
    }

    private static void begin(Context context, String driver) {
        if (context == null || driver == null || "System".equals(driver)) return;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY_INFLIGHT, driver).commit();
    }

    private static void end(Context context, String driver) {
        if (context == null || driver == null || "System".equals(driver)) return;
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(KEY_INFLIGHT).apply();
    }

    /** At app start: a probe that never finished crashed the app; don't load that driver again. */
    public static void recoverFromProbeCrash(Context context) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        String driver = p.getString(KEY_INFLIGHT, null);
        if (driver == null) return;
        Set<String> bad = crashed(context);
        bad.add(driver);
        p.edit().remove(KEY_INFLIGHT).putStringSet(KEY_BAD, bad).apply();
    }

    public static String getVulkanVersion(String driverName, Context context) {
        String d = safe(driverName, context);
        begin(context, d);
        try { return nGetVulkanVersion(d, context); } finally { end(context, d); }
    }

    public static int getVendorID(String driverName, Context context) {
        String d = safe(driverName, context);
        begin(context, d);
        try { return nGetVendorID(d, context); } finally { end(context, d); }
    }

    public static String getRenderer(String driverName, Context context) {
        String d = safe(driverName, context);
        begin(context, d);
        try { return nGetRenderer(d, context); } finally { end(context, d); }
    }

    public static String[] enumerateExtensions(String driverName, Context context) {
        String d = safe(driverName, context);
        begin(context, d);
        try { return nEnumerateExtensions(d, context); } finally { end(context, d); }
    }

    private native static String nGetVulkanVersion(String driverName, Context context);
    private native static int nGetVendorID(String driverName, Context context);
    private native static String nGetRenderer(String driverName, Context context);
    private native static String[] nEnumerateExtensions(String driverName, Context context);

    static {
        System.loadLibrary("winlator");
    }
}
