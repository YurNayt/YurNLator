package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.ScreenInfo;

import java.io.File;

/**
 * YurNLator base, part 5: getting the Wine prefix ready for a launch, in one place (it was a chain
 * of methods inside the game screen). In order:
 *  1. display driver: X11, or Wayland (winex11.drv set aside so Wine can't fall back to it);
 *  2. image update: the common container pattern and system tweaks when the image changed;
 *  3. DirectX wrapper DLLs (DxWrapperFiles), checked on disk;
 *  4. Windows components (WinComponentFiles);
 *  5. desktop theme, start menu, drive letters (dosdevices), controller registry keys;
 *  6. Windows services for the startup selection.
 * The container's record of what's installed is saved once at the end.
 */
public final class PrefixSetup {
    private final Context context;
    private final ImageFs imageFs;
    private final WineInfo wineInfo;
    private final Container container;
    private final LaunchConfig launch;
    private final ScreenInfo screen;
    private final boolean wayland;
    private final boolean firstTimeBoot;
    private final OnExtractFileListener listener;
    private final DxWrapperFiles dx;

    public PrefixSetup(Context context, ImageFs imageFs, WineInfo wineInfo, Container container, LaunchConfig launch,
                       ScreenInfo screen, boolean wayland, boolean firstTimeBoot, OnExtractFileListener listener,
                       DxWrapperFiles dx) {
        this.dx = dx;
        this.context = context;
        this.imageFs = imageFs;
        this.wineInfo = wineInfo;
        this.container = container;
        this.launch = launch;
        this.screen = screen;
        this.wayland = wayland;
        this.firstTimeBoot = firstTimeBoot;
        this.listener = listener;
    }

    /**
     * Runs every step. [wrapperId] is DxWrapperFiles.wrapperId(...) of this launch; [env] gets what
     * the steps need at run time (a ddraw wrapper's config file).
     */
    public void run(String wrapperId, EnvVars env) {
        boolean changed = false;
        setDisplayDriver();

        String imgVersion = String.valueOf(imageFs.getVersion());
        if (!container.getExtra("imgVersion").equals(imgVersion)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "container_pattern_common.tzst", imageFs.getRootDir());
            WineUtils.applySystemTweaks(context, wineInfo);
            container.putExtra("graphicsDriver", null);
            container.putExtra("desktopTheme", null);
            container.putExtra("imgVersion", imgVersion);
            changed = true;
        }

        if (dx.filesMissing(wrapperId) || !wrapperId.equals(container.getExtra("dxwrapper"))) {
            dx.install(wrapperId, env);
            container.putExtra("dxwrapper", wrapperId);
            changed = true;
        }

        String wincomponents = launch.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            WinComponentFiles.apply(context, imageFs, container, wincomponents,
                    container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS), firstTimeBoot, listener,
                    dx::restoreOriginal);
            container.putExtra("wincomponents", wincomponents);
            changed = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme + "," + screen).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(context, new WineThemeManager.ThemeInfo(desktopTheme), screen);
            container.putExtra("desktopTheme", desktopTheme + "," + screen);
            changed = true;
        }

        WineStartMenuCreator.create(context, container);
        WineUtils.createDosdevicesSymlinks(container);

        int inputType = launch.getInputType();
        boolean dinput = (inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT;
        WineUtils.setJoystickRegistryKeys(container, dinput, launch.getExclusiveXInput());

        String startup = launch.getStartupSelection();
        if (!startup.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, startup);
            container.putExtra("startupSelection", startup);
            changed = true;
        }
        if (changed) container.saveData();
    }

    /** X11, or Wayland with winex11.drv set aside (renamed back when X11 is used again). */
    private void setDisplayDriver() {
        File userReg = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(userReg)) {
            if (wayland) {
                reg.setStringValue("Software\\Wine\\Drivers", "Graphics", "wayland");
                reg.setStringValue("Software\\Wine\\Explorer", "Desktop", "shell");
                reg.setStringValue("Software\\Wine\\Explorer\\Desktops", "shell", String.valueOf(screen));
            } else if ("wayland".equals(reg.getStringValue("Software\\Wine\\Drivers", "Graphics", ""))) {
                reg.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11");
                if ("shell".equals(reg.getStringValue("Software\\Wine\\Explorer", "Desktop", ""))) {
                    reg.removeValue("Software\\Wine\\Explorer", "Desktop");
                    reg.removeValue("Software\\Wine\\Explorer\\Desktops", "shell");
                }
            }
        } catch (Exception e) {
            Log.e("PrefixSetup", "wayland: registry", e);
        }
        if (wineInfo == null || wineInfo.path == null) return;
        for (String arch : new String[]{"aarch64-windows", "i386-windows", "x86_64-windows"}) {
            File drv = new File(wineInfo.path, "lib/wine/" + arch + "/winex11.drv");
            File bak = new File(wineInfo.path, "lib/wine/" + arch + "/winex11.drv.bak");
            if (wayland) { if (drv.exists() && !bak.exists()) drv.renameTo(bak); }
            else if (bak.exists() && !drv.exists()) bak.renameTo(drv);
        }
    }
}
