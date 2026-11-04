package com.winlator.cmod.session;

import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.WineRegistryEditor;

import java.io.File;

/**
 * Codecs (ported from Bannerlator's MediaDecoder, GPL-3.0): which backend Wine's Media Foundation
 * plays cutscenes with, and whether video decoders may hand the game GPU (DXGI) frames.
 *
 * Builtin decoder: Wine's MF byte-stream handlers read HKCU\Software\Wine\MediaFoundation
 * "DisableGstByteStreamHandler": DWORD 0 = winegstreamer (GStreamer), 1 = winedmo (FFmpeg).
 * FFmpeg also sets WINE_USE_DMO=1; it needs a layer whose winedmo was built with FFmpeg (newer
 * arm64ec Proton builds), others fall back to GStreamer. DirectShow always uses GStreamer.
 *
 * Software decoding: WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 makes MF decoders output system-memory
 * frames (fixes black video with sound in some games).
 *
 * Stored as container extras, overridden per game by the shortcut's extras of the same name.
 */
public final class MediaDecoder {
    public static final String KEY_BACKEND = "builtinDecoder";
    public static final String KEY_SOFTWARE_DECODING = "softwareDecoding";
    public static final String BACKEND_GSTREAMER = "gstreamer";
    public static final String BACKEND_FFMPEG = "ffmpeg";

    private static final String REG_KEY = "Software\\Wine\\MediaFoundation";
    private static final String REG_VALUE = "DisableGstByteStreamHandler";

    private MediaDecoder() {}

    private static String value(Container container, Shortcut shortcut, String key, String fallback) {
        String v = shortcut != null ? shortcut.getExtra(key, "") : "";
        if (v.isEmpty() && container != null) v = container.getExtra(key, "");
        return v.isEmpty() ? fallback : v;
    }

    public static boolean ffmpeg(Container container, Shortcut shortcut) {
        return BACKEND_FFMPEG.equals(value(container, shortcut, KEY_BACKEND, BACKEND_GSTREAMER));
    }

    public static boolean softwareDecoding(Container container, Shortcut shortcut) {
        return "1".equals(value(container, shortcut, KEY_SOFTWARE_DECODING, "0"));
    }

    /** The registry half; written on every launch (idempotent, survives a prefix update). */
    public static void applyRegistry(File userReg, Container container, Shortcut shortcut) {
        boolean ffmpeg = ffmpeg(container, shortcut);
        try (WineRegistryEditor reg = new WineRegistryEditor(userReg)) {
            reg.setDwordValue(REG_KEY, REG_VALUE, ffmpeg ? 1 : 0);
        } catch (Exception e) {
            Log.w("MediaDecoder", "registry", e);
        }
        Log.d("MediaDecoder", "Media decoder: " + (ffmpeg ? "FFmpeg (winedmo)" : "GStreamer")
                + ", software decoding " + softwareDecoding(container, shortcut));
    }

    /** The env half; only added, so the user's own value in the env string wins. */
    public static void applyEnv(EnvVars env, Container container, Shortcut shortcut) {
        if (ffmpeg(container, shortcut) && !env.has("WINE_USE_DMO")) env.put("WINE_USE_DMO", "1");
        if (softwareDecoding(container, shortcut) && !env.has("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER"))
            env.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");
    }
}
