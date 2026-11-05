package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.contents.WrapperManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.File;

/**
 * YurNLator base, part 5: the graphics files in the image a launch needs, moved out of the game
 * screen. Each piece is extracted only when it changed (a marker file or the container's record):
 *  - the common graphics runtime (paired Vulkan wrapper, layers, extra libs);
 *  - the Vulkan wrapper the container / game chose ("wrapper=" in the driver config);
 *  - the OpenGL driver (Zink, or Freedreno on Adreno);
 *  - the BCn texture layer (normal or legacy build).
 * The environment for them stays with the launch (next base step).
 */
public final class GraphicsDriverFiles {
    private static final String TAG = "GraphicsDriverFiles";
    private static final String RUNTIME_MARKER = "usr/lib/.winlator-graphics-runtime-c7474f7e-25b50a11-v2";

    private GraphicsDriverFiles() {}

    /** The common runtime and the chosen wrapper. */
    public static void installRuntime(Context context, File rootDir, boolean firstTimeBoot, String wrapper) {
        File marker = new File(rootDir, RUNTIME_MARKER);
        boolean extracted = false;
        if (firstTimeBoot || !marker.isFile()) {
            extracted = true;
            Log.d(TAG, "Installing paired Pipetto wrapper and common graphics runtime");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/wrapper.tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "layers.tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/extra_libs.tzst", rootDir);
            FileUtils.writeString(marker, "wrapper=c7474f7e;extra_libs=25b50a11;layers=9d57736d;opengl=split-v1");
        }
        WrapperManager.applySelected(context, rootDir, wrapper, extracted);
    }

    /** "freedreno" for the Freedreno graphics driver choice, else "zink". */
    public static String openGLDriver(String graphicsDriver) {
        return "freedreno".equalsIgnoreCase(graphicsDriver) ? "freedreno" : "zink";
    }

    /** The OpenGL driver's files, when the container doesn't have [graphicsDriver]'s one yet. */
    public static void installOpenGL(Context context, File rootDir, Container container, String graphicsDriver, boolean firstTimeBoot) {
        String selected = openGLDriver(graphicsDriver);
        String installed = container.getExtra("installedOpenGLDriver", "");
        if (!firstTimeBoot && selected.equals(installed)) return;
        Log.d(TAG, "Installing OpenGL driver " + selected + " (installed: '" + installed + "')");
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/opengl_" + selected + ".tzst", rootDir)) {
            Log.e(TAG, "Failed to extract OpenGL driver " + selected);
            return;
        }
        container.putExtra("installedOpenGLDriver", selected);
        container.saveData();
    }

    /** The chosen BCn layer build (only when it changed since last time). */
    public static void installBcnLayer(Context context, File rootDir, boolean legacy) {
        String asset = legacy ? "graphics_driver/leegao_bcn_legacy.tzst" : "graphics_driver/leegao_bcn.tzst";
        File marker = new File(rootDir, "usr/lib/.yurn-bcn-layer");
        String want = asset + ":" + FileUtils.getSize(context, asset);
        String have = marker.isFile() ? FileUtils.readString(marker).trim() : "";
        if (want.equals(have) && new File(rootDir, "usr/lib/libbcn_layer.so").isFile()) return;
        // version.txt at the root would overwrite the graphics driver's own: skip it.
        OnExtractFileListener skipVersion = (file, size) ->
                file.getName().equals("version.txt") && rootDir.equals(file.getParentFile()) ? null : file;
        if (TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, asset, rootDir, skipVersion)) {
            FileUtils.writeString(marker, want);
        }
    }
}
