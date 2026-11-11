package com.winlator.cmod.session;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.XServerRendererView;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * YurNLator base, part 11: the game's performance HUD. Its style is 0 off, 2 YurNLator, 4
 * Bannerlator's styles (drawn next to ours, which keeps counting) or 3 MangoHud (in the game itself,
 * only when the session started with it). Saved per game when the game has its own value, else per
 * container; the quick menu and the sidebar switch it through [apply]. Moved out of the game screen.
 */
public final class HudController {
    public static final int OFF = 0, YURNLATOR = 2, MANGOHUD = MangoHud.HUD_MODE, BANNERLATOR = 4;

    public interface Host {
        Activity activity();
        Container container();
        Shortcut shortcut();
        FrameLayout root();
        XServerRendererView renderer();
        boolean wayland();
        /** Wayland's frame generation rates (game / presented), for the FPS readout. */
        Supplier<float[]> compositorFrameGen();
        HudWindowTracker tracker();
        File imageFsRoot();
        boolean mangoHudLog();
        /** "Keep MangoHud ready": loaded hidden with every game, so it can be switched on any time. */
        boolean mangoHudPreload();
    }

    private final Host host;
    private volatile WinlatorHUD modern;
    private boolean mangoHudLaunched;

    public HudController(Host host) { this.host = host; }

    /** Our HUD (also the engine of the Bannerlator styles); null until shown once. */
    public WinlatorHUD modern() { return modern; }

    /** The session started with MangoHud's layer. */
    public boolean mangoHudLaunched() { return mangoHudLaunched; }

    /** The saved style: the game's own, else the container's (the classic HUD, 1, opens ours). */
    public int savedMode() {
        Container container = host.container();
        if (container == null) return OFF;
        Shortcut shortcut = host.shortcut();
        String extra = shortcut != null ? shortcut.getExtra("hudMode", "") : "";
        if (extra.isEmpty()) extra = container.getExtra("hudMode");
        int m;
        if (extra.isEmpty()) m = container.isShowFPS() ? YURNLATOR : OFF;
        else try { m = Integer.parseInt(extra.trim()); } catch (Exception e) { m = OFF; }
        return m == 1 ? YURNLATOR : m;
    }

    private void saveMode(int mode) {
        Container container = host.container();
        if (container == null) return;
        Shortcut shortcut = host.shortcut();
        // The game's own HUD setting wins at launch: change it there when it has one.
        if (shortcut != null && !shortcut.getExtra("hudMode", "").isEmpty()) {
            shortcut.putExtra("hudMode", String.valueOf(mode));
            shortcut.saveData();
            return;
        }
        container.putExtra("hudMode", String.valueOf(mode));
        container.setShowFPS(mode != OFF);
        container.saveData();
    }

    /** Before Wine starts: MangoHud's layer goes into the environment when it is the style. [log] gets one line. */
    public void prepareLaunch(EnvVars envVars, Consumer<String> log) {
        int mode = savedMode();
        File root = host.imageFsRoot();
        boolean writeLog = host.mangoHudLog();
        mangoHudLaunched = mode == MANGOHUD || host.mangoHudPreload();
        if (mangoHudLaunched) MangoHud.prepareLaunch(envVars, root, writeLog && mode == MANGOHUD, mode == MANGOHUD);
        if (log != null) log.accept("[YurNLator] HUD style " + mode
                + (mangoHudLaunched ? ": MangoHud " + (mode == MANGOHUD ? "on" : "loaded hidden") + ", config " + MangoHud.configFile(root)
                   + (writeLog ? ", log to " + MangoHud.logFolder() : "") : ""));
    }

    /** The game screen is built: the saved HUD (ours / Bannerlator's) is created, hidden until the game renders. */
    public void createAtLaunch() {
        int mode = savedMode();
        if (mode != YURNLATOR && mode != BANNERLATOR) return;
        create();
        modern.setBannerStyle(mode == BANNERLATOR);
        modern.enableByUser();
    }

    /** The one HUD switch (quick menu and sidebar). */
    public void apply(int mode) {
        if (mode == 1) mode = YURNLATOR; // the classic HUD is gone: old saves open ours
        // MangoHud draws inside the game: hide ours; any other choice hides MangoHud.
        if (mangoHudLaunched) MangoHud.setVisible(host.imageFsRoot(), mode == MANGOHUD, host.mangoHudLog());
        WinlatorHUD m = modern;
        if (mode == OFF || mode == MANGOHUD) {
            if (m != null) m.disableByUser();
        } else {
            if (m != null) m.disableByUser(false);
            if (modern == null) {
                if (host.root() == null || host.renderer() == null) { saveMode(mode); return; }
                create();
                if (host.tracker().adoptRendererWindow()) modern.onRendererDetected(host.tracker().rendererName());
            }
            modern.setBannerStyle(mode == BANNERLATOR);
            modern.enableByUser();
            // The renderer reports frames to one HUD: ours.
            host.renderer().setFrameRating(modern);
            host.tracker().adoptRendererWindow();
        }
        saveMode(mode);
    }

    /** Our HUD, added hidden (it shows itself when the game renders); the renderer counts frames for it. */
    private void create() {
        XServerRendererView renderer = host.renderer();
        WinlatorHUD hud = new WinlatorHUD(host.activity());
        if (renderer instanceof VulkanXServerView && !host.wayland())
            hud.setPresentCounter(((VulkanXServerView) renderer)::getPresentStats);
        if (host.wayland()) hud.setCompositorFrameGen(host.compositorFrameGen());
        hud.setVisibility(View.GONE);
        host.root().addView(hud);
        renderer.setFrameRating(hud);
        modern = hud;
    }
}
