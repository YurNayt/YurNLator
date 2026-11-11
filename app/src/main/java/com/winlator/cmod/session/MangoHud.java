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
        prepareLaunch(envVars, imageFsRoot, log, true);
    }

    /** [visible] false: loaded hidden ("keep MangoHud ready"), shown later from the quick menu. */
    public static void prepareLaunch(EnvVars envVars, File imageFsRoot, boolean log, boolean visible) {
        sensorRows = com.winlator.cmod.core.HudSensorFeed.INSTANCE.getRunning();
        write(imageFsRoot, visible, log);
        envVars.put("YURN_MANGOHUD_CONFIGFILE", configFile(imageFsRoot).getPath());
        // MangoHud's own messages (errors, what it cannot read here) go to the session log.
        if (!envVars.has("MANGOHUD_LOG_LEVEL")) envVars.put("MANGOHUD_LOG_LEVEL", "info");
    }

    /** What MangoHud shows now (as last set). */
    private static volatile boolean shown;

    /**
     * During the session: show or hide. By MangoHud's own toggle key when it reads keys here
     * (xserver/MangoHudKeys: no reload, so no doubled rows); otherwise the config file is
     * rewritten and MangoHud reloads it.
     */
    /** Logging on / off during the session: the config is rewritten (MangoHud reloads it). */
    public static void setLogging(File imageFsRoot, boolean log) { write(imageFsRoot, shown, log); }

    public static void setVisible(File imageFsRoot, boolean visible, boolean log) {
        if (visible == shown) return;
        if (com.winlator.cmod.xserver.MangoHudKeys.alive()) {
            com.winlator.cmod.xserver.MangoHudKeys.pressToggle();
            shown = visible;
            return;
        }
        write(imageFsRoot, visible, log);
    }

    /** Precise sensors on (root / ADB): CPU and GPU rows read from the feed's files. */
    private static volatile boolean sensorRows;

    private static void write(File root, boolean visible, boolean log) {
        File f = configFile(root);
        try {
            f.getParentFile().mkdirs();
            if (log) logFolder().mkdirs();
            StringBuilder c = new StringBuilder();
            if (!visible) c.append("no_display\n");
            // Show / hide is our key (F24 -> keycode 0, xserver/MangoHudKeys). The other bindings go
            // to Escape, which our keymap never reports held: they can't fire by accident.
            c.append("toggle_hud=F24\n")
             .append("toggle_logging=Escape\ntoggle_fps_limit=Escape\nreload_cfg=Escape\n")
             .append("toggle_preset=Escape\ntoggle_hud_position=Escape\nreset_fps_metrics=Escape\n")
             .append("upload_log=Escape\nupload_logs=Escape\n");
            // Only what MangoHud can measure here (GPU, VRAM, CPU and battery read as 0 or are
            // closed to apps on Android): FPS, the 1% / 0.1% lows, frame time graph and RAM, in a
            // small box instead of a strip across the screen. Battery must stay out: listing
            // /sys/class/power_supply is denied and MangoHud's uncaught error kills the game.
            c.append("legacy_layout=0\n")
             .append("hud_compact\n")
             .append("table_columns=3\n")
             .append("fps\n")
             .append("fps_metrics=0.01,0.001\n")
             .append("frametime\n")
             .append("frame_timing=1\n")
             .append("ram\n");
            if (sensorRows) {
                // MangoHud can't open these files here: our privileged reader writes them once a
                // second and MangoHud prints them (exec runs in its one long-lived sh).
                File dir = com.winlator.cmod.core.HudSensorFeed.INSTANCE.mirrorDir(root);
                c.append("custom_text=CPU\nexec=cat ").append(new File(dir, "cpu").getPath()).append('\n')
                 .append("custom_text=GPU\nexec=cat ").append(new File(dir, "gpu").getPath()).append('\n');
            }
            c.append("fps_sampling_period=1000\n")
             .append("position=top-left\n")
             .append("offset_x=16\noffset_y=16\n")
             .append("width=240\n")
             .append("background_alpha=0.45\n")
             .append("round_corners=10\n")
             .append("font_size=20\n")
             .append("text_outline=0\n")
             .append("fps_color_change\n")
             .append("fps_value=30,55\n")
             .append("fps_color=E04B4B,F2C14E,5BD08A\n");
            if (log) {
                c.append("output_folder=").append(logFolder().getPath()).append('\n')
                 .append("autostart_log=1\n")
                 .append("log_interval=250\n");
            }
            try (FileWriter w = new FileWriter(f, false)) { w.write(c.toString()); }
            shown = visible;
        } catch (Exception e) {
            Log.w(TAG, "config " + f, e);
        }
    }
}
