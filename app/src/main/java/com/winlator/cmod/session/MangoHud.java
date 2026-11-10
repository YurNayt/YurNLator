package com.winlator.cmod.session;

import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.core.EnvVars;

import java.io.File;
import java.io.FileWriter;

/**
 * MangoHud: the real one, drawn inside the game's own frames by its Vulkan layer (imagefs ships
 * MangoHud 0.8.1 built for bionic, with Adreno kgsl stats). HUD style 3. It loads only when the
 * session starts with it (MANGOHUD=1 + our config file); while the session runs, the config file
 * is rewritten and MangoHud reloads it (shown / hidden). Logs (MangoHud's CSV) go to
 * Download/YurNLator/MangoHud when logging is on.
 */
public final class MangoHud {
    public static final int HUD_MODE = 3;
    private static final String TAG = "MangoHud";

    private MangoHud() {}

    public static File configFile(File imageFsRoot) { return new File(imageFsRoot, "usr/etc/mangohud/yurnlator.conf"); }

    public static File logFolder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator/MangoHud");
    }

    /** At launch: the variables that load the layer with our config (read by the guest launcher). */
    public static void prepareLaunch(EnvVars envVars, File imageFsRoot, boolean log) {
        write(imageFsRoot, true, log);
        envVars.put("YURN_MANGOHUD_CONFIGFILE", configFile(imageFsRoot).getPath());
        // Diagnostics while MangoHud is new here: the Vulkan loader says whether it found and
        // enabled the layer, MangoHud logs what it reads (both to the session log).
        if (!envVars.has("VK_LOADER_DEBUG")) envVars.put("VK_LOADER_DEBUG", "error,warn,layer");
        if (!envVars.has("MANGOHUD_LOG_LEVEL")) envVars.put("MANGOHUD_LOG_LEVEL", "debug");
    }

    /** During the session: show or hide (MangoHud reloads its config file when it changes). */
    public static void setVisible(File imageFsRoot, boolean visible, boolean log) { write(imageFsRoot, visible, log); }

    private static void write(File root, boolean visible, boolean log) {
        File f = configFile(root);
        try {
            f.getParentFile().mkdirs();
            if (log) logFolder().mkdirs();
            StringBuilder c = new StringBuilder();
            if (!visible) c.append("no_display\n");
            c.append("legacy_layout=0\n")
             .append("horizontal\n")
             .append("hud_compact\n")
             .append("fps\n")
             .append("fps_metrics=avg,0.01,0.001\n")
             .append("frametime\n")
             .append("frame_timing=1\n")
             .append("gpu_stats\ngpu_temp\ngpu_core_clock\n")
             .append("cpu_stats\ncpu_temp\ncpu_mhz\n")
             .append("ram\nvram\n")
             .append("battery\nbattery_watt\n")
             .append("position=top-left\n")
             .append("background_alpha=0.35\n")
             .append("font_size=18\n")
             .append("round_corners=8\n");
            if (log) {
                c.append("output_folder=").append(logFolder().getPath()).append('\n')
                 .append("autostart_log=1\n")
                 .append("log_interval=100\n");
            }
            try (FileWriter w = new FileWriter(f, false)) { w.write(c.toString()); }
        } catch (Exception e) {
            Log.w(TAG, "config " + f, e);
        }
    }
}
