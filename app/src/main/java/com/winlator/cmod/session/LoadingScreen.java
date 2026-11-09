package com.winlator.cmod.session;

import android.app.Activity;
import android.os.Process;
import android.os.SystemClock;

import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.PreloaderDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Wayland has no X window content to close the loading screen on: it closes as soon as the
 * game's own .exe runs (a process of ours whose command line names it), at most 3 minutes after
 * the start. Moved out of the game screen (base migration).
 */
public final class LoadingScreen {
    private LoadingScreen() {}

    public static void closeWhenGameRuns(Activity activity, Shortcut shortcut, PreloaderDialog preloader) {
        if (shortcut == null || shortcut.path == null || preloader == null) return;
        String p = shortcut.path.replace('\\', '/');
        final String exe = p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!exe.endsWith(".exe")) return;
        final int uid = Process.myUid();
        Thread t = new Thread(() -> {
            long until = SystemClock.uptimeMillis() + 180_000;
            while (SystemClock.uptimeMillis() < until && !activity.isFinishing()) {
                if (!preloader.isShowing()) return;
                String[] pids = new File("/proc").list();
                if (pids != null) for (String pid : pids) {
                    if (pid.isEmpty() || !Character.isDigit(pid.charAt(0))) continue;
                    try {
                        if (android.system.Os.stat("/proc/" + pid).st_uid != uid) continue;
                        byte[] raw = Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath());
                        String cmd = new String(raw, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                        if (cmd.contains(exe) && !cmd.contains("winhandler")) {
                            preloader.closeOnUiThread();
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "WaylandLoading");
        t.setDaemon(true);
        t.start();
    }
}
