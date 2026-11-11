package com.winlator.cmod.session;

import android.content.Context;

import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xserver.ScreenInfo;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * YurNLator base, part 12: the game's own process: the launcher component (what Wine starts, the
 * container's drives, the Box64 / FEXCore presets, the Wayland runtime folder) and the environment
 * the launch adds (the game's fixes, the user's variables, OpenGL and the defaults). Moved out of
 * the game screen's setupXEnvironment.
 */
public final class GuestLaunch {
    private GuestLaunch() {}

    /** The launcher component; [log] gets what was launched (the session log's first lines). */
    public static GuestProgramLauncherComponent create(android.app.Activity context, ContentsManager contents, Container container,
                                                       Shortcut shortcut, WineInfo wineInfo, LaunchConfig lc,
                                                       ScreenInfo screen, EnvVars overrides, boolean wayland,
                                                       Consumer<String> log) {
        GuestProgramLauncherComponent launcher = new GuestProgramLauncherComponent(
                contents, contents.getProfileByEntryName(container.getWineVersion()), shortcut);
        launcher.setContainer(container);
        launcher.setWineInfo(wineInfo);

        String guestExecutable = LaunchEnv.guestExecutable(screen, LaunchEnv.startCommand(shortcut, overrides));
        launcher.setGuestExecutable(guestExecutable);
        // First line of the log: what was launched (and the shortcut's path), so a game that never
        // starts shows why (wrong folder, mangled path...).
        String ver = "";
        try { ver = " " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        log.accept("[YurNLator" + ver + "] launch: " + guestExecutable);
        if (shortcut != null) log.accept("[YurNLator] shortcut path: " + shortcut.path);

        ArrayList<String> bindingPaths = new ArrayList<>();
        for (String[] drive : container.drivesIterator()) bindingPaths.add(drive[1]);
        launcher.setBindingPaths(bindingPaths.toArray(new String[0]));
        launcher.setBox64Preset(lc.getBox64Preset());
        launcher.setFEXCorePreset(lc.getFexcorePreset());
        launcher.setWaylandRuntimeDir(wayland ? WaylandSession.runtimeDir(context).getPath() : null);
        return launcher;
    }

    /** The variables the launch adds: game fixes, the user's own, OpenGL, then the defaults. */
    public static void environment(Context context, Container container, Shortcut shortcut, EnvVars envVars,
                                   String graphicsDriver, String cpuTopology, Consumer<String> log) {
        // Game-specific fixes in the game's own files (TLOU's address-space reservation).
        GameFixes.apply(context, container, shortcut, envVars, log);
        LaunchEnv.user(envVars, container, shortcut);
        OpenGLEnv.apply(envVars, graphicsDriver, container, shortcut);
        LaunchEnv.defaults(envVars, container, shortcut, cpuTopology);
    }
}
