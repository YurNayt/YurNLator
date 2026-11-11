package com.winlator.cmod.session;

import com.winlator.cmod.ui.UiStrings;
import com.winlator.cmod.R;
import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.wayland.WaylandCompositor;

import java.util.Locale;

/**
 * What the Wayland compositor is set up with before it starts: the Turnip driver it renders on
 * (the game's own, when the compositor can share frames with it) and the switches from the
 * container's / game's environment variables and settings. Moved out of the game screen (base
 * migration); the session itself is {@link WaylandSession}.
 */
public final class WaylandLaunch {
    private static final String TAG = "WaylandLaunch";

    /** The compositor's driver: null path / library = the system driver. */
    public static final class Driver {
        public final String path, library;
        Driver(String path, String library) { this.path = path; this.library = library; }
    }

    private WaylandLaunch() {}

    public static Driver driver(Activity activity, String driverId) {
        if (WaylandSupport.adapterUnusableReason(activity, driverId) == null) {
            AdrenotoolsManager atm = new AdrenotoolsManager(activity);
            return new Driver(atm.getDriverPath(driverId), atm.getLibraryName(driverId));
        }
        if (GPUInformation.isAdrenoGPU(activity))
            Toast.makeText(activity, UiStrings.get(R.string.l_xs_wayland_on_adreno_needs_a_turnip), Toast.LENGTH_LONG).show();
        return new Driver(null, null);
    }

    /**
     * Compositor switches, read before it starts:
     * BANNER_WAYLAND_ASYNC_COPY=0 (wait for every screen blit), BANNER_WAYLAND_UBWC=0,
     * BANNER_WAYLAND_ZC_CLIENT_FENCE=0, and zero-copy presentation ({@link WaylandZeroCopy}).
     */
    public static void applySwitches(Container container, Shortcut shortcut) {
        EnvVars user = new EnvVars(container != null ? container.getEnvVars() : "");
        if (shortcut != null) user.putAll(new EnvVars(shortcut.getExtra("envVars", "")));
        try {
            WaylandCompositor.nativeSetAsyncCopy(!off(user, "BANNER_WAYLAND_ASYNC_COPY"));
            WaylandCompositor.nativeSetUbwc(!off(user, "BANNER_WAYLAND_UBWC"));
            WaylandCompositor.nativeSetZeroCopy(WaylandZeroCopy.enabled(container, shortcut));
            if (off(user, "BANNER_WAYLAND_ZC_CLIENT_FENCE")) WaylandCompositor.nativeSetZeroCopyClientFence(false);
        } catch (Throwable t) {
            Log.w(TAG, "wayland switches", t);
        }
    }

    private static boolean off(EnvVars env, String name) {
        String v = env.get(name).trim().toLowerCase(Locale.US);
        return v.equals("0") || v.equals("false") || v.equals("off");
    }

    /**
     * YurNLator base, part 16: starts the compositor right above the X server's view, with this
     * launch's driver and switches. Its frames feed our HUD and close the loading screen (at the
     * game's window as on X11, not only at its first rendered frame).
     */
    public static WaylandSession start(Activity activity, com.winlator.cmod.xserver.XServer xServer,
                                       android.widget.FrameLayout root, android.view.View xServerView,
                                       String driverVersion, Container container, Shortcut shortcut,
                                       boolean stretched, com.winlator.cmod.core.PreloaderDialog loading,
                                       HudController hud) {
        Driver driver = driver(activity, driverVersion);
        applySwitches(container, shortcut);
        WaylandSession session = new WaylandSession(activity, xServer);
        session.start(root, root.indexOfChild(xServerView) + 1, driver.path, driver.library, stretched,
                shortcut != null, new WaylandSession.Listener() {
            private boolean shown = false;
            @Override public void onFirstFrame() { loading.closeOnUiThread(); }
            @Override public void onGameFrame() {
                // Also a game frame closes the loading screen (in case the first-frame signal is missed).
                if (!shown) { shown = true; loading.closeOnUiThread(); }
                com.winlator.cmod.widget.WinlatorHUD m = hud.modern();
                if (m != null) m.onFrame();
            }
            @Override public void onGameSurface(String window, String gpuName) {
                if (window != null) loading.closeOnUiThread();
                com.winlator.cmod.widget.WinlatorHUD m = hud.modern();
                if (window != null && gpuName != null && m != null) {
                    m.setGpuName(gpuName);
                    activity.runOnUiThread(() -> m.onRendererDetected("Wayland"));
                }
            }
        });
        return session;
    }
}
