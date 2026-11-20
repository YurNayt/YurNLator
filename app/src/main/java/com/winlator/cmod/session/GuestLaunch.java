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
        log.accept("[YurNLator] gamepad: " + lc.getPadApi() + (lc.isUnityGame() ? ", Unity game" : "")
                + (GamepadApi.PC.equals(lc.getPadApi()) ? " -> PC (keyboard + mouse)" : " -> XInput" + (GamepadApi.dinputShown(lc) ? " + DirectInput" : " only")));
        // What actually runs the x86 code and with which settings: launched from the file manager
        // there is no shortcut, so only the container's emulator / preset / variables apply.
        try {
            log.accept("[YurNLator] " + emulatorLine(context, container, shortcut, wineInfo, lc));
            String cv = container.getEnvVars() != null ? container.getEnvVars().trim() : "";
            log.accept("[YurNLator] container variables: " + (cv.isEmpty() ? "none" : cv));
            if (shortcut != null) {
                String sv = shortcut.getExtra("envVars").trim();
                log.accept("[YurNLator] shortcut variables: " + (sv.isEmpty() ? "none" : sv));
            }
        } catch (Throwable t) { android.util.Log.w("GuestLaunch", "emulator line", t); }

        ArrayList<String> bindingPaths = new ArrayList<>();
        for (String[] drive : container.drivesIterator()) bindingPaths.add(drive[1]);
        launcher.setBindingPaths(bindingPaths.toArray(new String[0]));
        launcher.setBox64Preset(lc.getBox64Preset());
        launcher.setFEXCorePreset(lc.getFexcorePreset());
        launcher.setWaylandRuntimeDir(wayland ? WaylandSession.runtimeDir(context).getPath() : null);
        return launcher;
    }

    /** "32-bit emulator: FEXCore 2611, preset X (FEX_...=...)" or the Box64 equivalent. */
    private static String emulatorLine(Context context, Container container, Shortcut shortcut,
                                       WineInfo wineInfo, LaunchConfig lc) {
        if (wineInfo == null || !wineInfo.isArm64EC())
            return "emulator: Box64 (whole Wine), preset " + lc.getBox64Preset();
        if ("fexcore".equalsIgnoreCase(lc.getEmulator())) {
            String ver = shortcut != null ? shortcut.getExtra("fexcoreVersion", container.getFEXCoreVersion()) : container.getFEXCoreVersion();
            EnvVars preset = com.winlator.cmod.fexcore.FEXCorePresetManager.getEnvVars(context, lc.getFexcorePreset());
            return "x86 emulator (64-bit games: FEXCore arm64ec; 32-bit: FEXCore) " + ver + ", preset " + lc.getFexcorePreset() + " (" + preset + ")";
        }
        String ver = shortcut != null ? shortcut.getExtra("box64Version", container.getBox64Version()) : container.getBox64Version();
        return "x86 emulator (64-bit games: FEXCore arm64ec; 32-bit: WOWBox64 " + ver + ", preset " + lc.getBox64Preset() + ")";
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
