package com.winlator.cmod.session;

import android.app.Activity;
import android.os.SystemClock;

import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.core.ApiSniffer;
import com.winlator.cmod.core.DriverSniffer;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.xserver.OffscreenStats;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YurNLator base, part 17: what a session learns from Wine's output, moved out of the game
 * screen — the log window (with time marks), the DXVK build, the API and layer the game uses
 * (VKD3D · DX12, DXVK · DX11…) and the Vulkan driver it really runs on.
 */
public final class SessionLog {
    private SessionLog() {}

    public interface Host {
        /** "3.1.1-1-gplasync-arm64ec", from DXVK's banner. */
        void dxvkBuild(String build);
        void api(String label);
        void driver(String label);
    }

    private static final Pattern DXVK_BANNER = Pattern.compile("^info:\\s+DXVK:\\s+v?(\\S+)");

    /**
     * Installs the callbacks on Wine's output. [configuredDriver]: the driver the container /
     * game picked, named when the game reports only a version number. Returns the log window
     * (null when logs are off).
     */
    public static DebugDialog install(Activity activity, boolean enableLogs, Shortcut shortcut, String dxwrapperConfig,
                                      Supplier<String> configuredDriver, Host host) {
        ProcessHelper.removeAllDebugCallbacks();
        DebugDialog debugDialog = null;
        if (enableLogs) {
            LogView.setFilename(executableName(shortcut));
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(activity));
            // Time marks in the log (it has no timestamps): where a slow start loses its time.
            final long logStart = SystemClock.elapsedRealtime();
            final long[] lastMark = {logStart};
            final DebugDialog log = debugDialog;
            ProcessHelper.addDebugCallback(line -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastMark[0] < 5000) return;
                lastMark[0] = now;
                log.call("[YurNLator +" + ((now - logStart) / 1000) + "s]");
                String xo = OffscreenStats.drain();
                if (xo != null) log.call(xo);
            });
        }
        final boolean[] dxvkFound = {false};
        ProcessHelper.addDebugCallback(line -> {
            if (dxvkFound[0] || line == null) return;
            Matcher m = DXVK_BANNER.matcher(line.trim());
            if (m.find()) {
                dxvkFound[0] = true;
                host.dxvkBuild(m.group(1));
            }
        });
        ProcessHelper.addDebugCallback(new ApiSniffer(dxwrapperConfig, label -> {
            host.api(label);
            return kotlin.Unit.INSTANCE;
        }));
        ProcessHelper.addDebugCallback(new DriverSniffer(found -> {
            // Only a version number ("driver 26.99.99" from DXVK): name the configured driver.
            String label = found;
            String configured = configuredDriver.get();
            if (found.startsWith("driver ") && configured != null && !configured.isEmpty() && !"System".equalsIgnoreCase(configured))
                label = configured + " (" + found + ")";
            host.driver(label);
            return kotlin.Unit.INSTANCE;
        }));
        return debugDialog;
    }

    /** The game's .exe name for the log file ("wfm.exe" for the desktop / file manager). */
    static String executableName(Shortcut shortcut) {
        if (shortcut == null || shortcut.path == null) return "wfm.exe";
        String clean = shortcut.path.replace("\"", "");
        int sep = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return sep != -1 ? clean.substring(sep + 1) : clean;
    }
}
