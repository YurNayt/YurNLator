package com.winlator.cmod.session;

import android.content.SharedPreferences;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;

/**
 * YurNLator base, part 6: what a launch runs and with which environment, in the order the game
 * screen applied it:
 *  1. [base]: locale, prefix, WINEDEBUG (from Settings › Debug);
 *  2. [user]: the container's variables, then the game's on top;
 *  3. (the OpenGL driver's, in the game screen);
 *  4. [defaults]: CPU topology, esync, fast yield, codecs, only where the user set nothing;
 *  5. one-off overrides (e.g. the bundle finder), then the audio server's.
 * [startCommand] is what Wine runs: the game through winhandler.exe, or the file manager.
 */
public final class LaunchEnv {
    public static final String DEFAULT_DEBUG_CHANNELS = "warn,err,fixme";

    private LaunchEnv() {}

    public static void base(EnvVars env, SharedPreferences prefs, String lcAll, String wineprefix) {
        env.put("LC_ALL", lcAll);
        env.put("WINEPREFIX", wineprefix);
        boolean debug = prefs.getBoolean("enable_wine_debug", false);
        String channels = prefs.getString("wine_debug_channels", DEFAULT_DEBUG_CHANNELS);
        env.put("WINEDEBUG", debug && channels != null && !channels.isEmpty() ? "+" + channels.replace(",", ",+") : "-all");
    }

    public static void user(EnvVars env, Container container, Shortcut shortcut) {
        env.putAll(container.getEnvVars());
        if (shortcut != null) env.putAll(shortcut.getExtra("envVars"));
    }

    public static void defaults(EnvVars env, Container container, Shortcut shortcut, String cpuTopology) {
        if (cpuTopology != null && !cpuTopology.isEmpty()) env.put("WINE_CPU_TOPOLOGY", cpuTopology);
        if (!env.has("WINEESYNC")) env.put("WINEESYNC", "1");
        MediaDecoder.applyEnv(env, container, shortcut);
        if (!env.has("WINE_FAST_YIELD")) env.put("WINE_FAST_YIELD", "1");
    }

    /** "wine explorer /desktop=shell,WxH <start command>". */
    public static String guestExecutable(Object screenInfo, String startCommand) {
        return "wine explorer /desktop=shell," + screenInfo + " " + startCommand;
    }

    /**
     * The game through winhandler.exe (/dir = its folder, quoted name, launch arguments), or the
     * file manager when there is no game. [overrides] may carry EXTRA_EXEC_ARGS (taken out here).
     */
    public static String startCommand(Shortcut shortcut, EnvVars overrides) {
        String args = "";
        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";
            if (shortcut.path.endsWith(".lnk")) {
                args += "\"" + shortcut.path + "\"" + execArgs;
            } else {
                String fullPath = shortcut.path.replace("\"", "");
                String exeDir, filename;
                if (fullPath.contains("\\")) {
                    int lastSlash = fullPath.lastIndexOf("\\");
                    if (lastSlash != -1) {
                        exeDir = fullPath.substring(0, lastSlash);
                        filename = fullPath.substring(lastSlash + 1);
                    } else {
                        exeDir = "D:\\";
                        filename = fullPath;
                    }
                } else {
                    exeDir = FileUtils.getDirname(fullPath);
                    filename = FileUtils.getName(fullPath);
                }
                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = dotIndex != -1 ? filename.indexOf(" ", dotIndex) : -1;
                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }
                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else if (overrides.has("EXTRA_EXEC_ARGS")) {
            args += " " + overrides.get("EXTRA_EXEC_ARGS");
            overrides.remove("EXTRA_EXEC_ARGS");
        } else {
            args += "\"wfm.exe\"";
        }
        return "winhandler.exe " + args;
    }
}
