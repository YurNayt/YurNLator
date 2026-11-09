package com.winlator.cmod.session;

import android.util.Log;

import com.winlator.cmod.wayland.WaylandCompositor;

/**
 * YurNLator base, part 14: the quick menu's picture settings on Wayland. The game is drawn by
 * the compositor there, above the X11 renderer, so the renderer's upscaler, shaders and colour
 * settings didn't reach it. The compositor has its own pass chain (effects_chain.c); the quick
 * menu's values are mapped onto it. The effect presets (10+) are built from the passes it has
 * (sharpen, colour, CRT / NTSC, FXAA, toon, deband), so they look close to, not exactly like, X11.
 */
public final class WaylandEffects {
    private static final String TAG = "WaylandEffects";

    private WaylandEffects() {}

    /**
     * @param upscaler -1 off, 0 SGSR, 1 FSR (the quick menu's values)
     * @param sharp 0..100
     * @param shader 0 off, 1 DLS, 2 CRT, 3 HDR, 4 Natural, 10..20 effect presets
     * @param brightness -100..100
     * @param contrast -100..100
     * @param gamma 0.5..2.5
     */
    public static void apply(int upscaler, int sharp, int shader, int brightness, int contrast, float gamma) {
        // A slider left a step or two off zero (a container saved brightness/contrast +1) changes
        // nothing visible but turned the colour pass on, and every pass frame is waited for on the
        // compositor thread: 15-25 ms per frame on a busy GPU, the picture stuttered at 20-40 fps
        // with every effect "off" (NFS Payback, 2026-11-09). Near-neutral values count as neutral.
        brightness = snap(brightness);
        contrast = snap(contrast);
        if (Math.abs(gamma - 1f) < 0.03f) gamma = 1f;
        int scaler = upscaler == 0 ? 3 : upscaler == 1 ? 4 : 0;
        boolean cas = false, hdr = false, fxaa = false, toon = false, crt = false, ntsc = false, deband = false;
        int db = 0, dc = 0, saturation = 100;
        switch (shader) {
            case 1: cas = true; break;                                   // DLS sharpen
            case 2: crt = true; break;                                   // CRT
            case 3: hdr = true; break;                                   // HDR
            case 4: saturation = 110; dc = 5; break;                     // Natural
            case 10: cas = true; saturation = 105; dc = 5; break;        // Game Clarity
            case 11: dc = 12; saturation = 90; db = -3; break;           // Cinematic
            case 12: saturation = 135; dc = 8; break;                    // Vivid
            case 13: cas = true; dc = 15; db = 5; saturation = 95; break; // Competitive
            case 14: cas = true; break;                                  // Adaptive Sharpen
            case 15: dc = 8; saturation = 92; deband = true; break;      // Filmic
            case 16: crt = true; saturation = 115; break;                // Arcade
            case 17: crt = true; ntsc = true; break;                     // Retro CRT
            case 18: scaler = 4; cas = true; break;                      // Upscale Sharp
            case 19: fxaa = true; deband = true; break;                  // Pixel Clean
            case 20: toon = true; cas = true; break;                     // Anime Edge
            default: break;
        }
        try {
            WaylandCompositor.nativeSetUpscaler(scaler);
            WaylandCompositor.nativeSetUpscaleSharpness(sharp);
            WaylandCompositor.nativeSetCas(cas, cas ? Math.max(1, sharp) : 0);
            WaylandCompositor.nativeSetHdr(hdr);
            WaylandCompositor.nativeSetDeband(deband, 100);
            WaylandCompositor.nativeSetScreenEffects(
                    clamp(brightness + db, -100, 100), clamp(contrast + dc, -100, 100),
                    Math.max(0.5f, Math.min(3f, gamma)), saturation, fxaa, toon, crt, ntsc);
        } catch (Throwable t) {
            Log.w(TAG, "compositor effects: " + t);
        }
    }

    private static int snap(int v) { return Math.abs(v) <= 2 ? 0 : v; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
