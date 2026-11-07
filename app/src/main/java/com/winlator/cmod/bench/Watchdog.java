package com.winlator.cmod.bench;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * Bench mode of the game screen: watches YurnBench's heartbeat (status.json, every 0.5 s) and
 * result (result.json) and ends the session when the run is over, crashed or hung.
 *  - result.json written -> done (ok or the benchmark's own error);
 *  - no heartbeat within START_LIMIT (Wine, DXVK and the driver getting ready) -> "timeout";
 *  - heartbeat stopped for HANG_LIMIT -> "crash" (the process died or froze);
 *  - longer than TOTAL_LIMIT -> "timeout".
 * The outcome, with what the phone did per phase (Telemetry: clocks, cycles, GPU load,
 * temperature), goes to files/bench/outcome.json for the bundle finder tab.
 */
public final class Watchdog {
    private static final String TAG = "BundleFinder";
    private static final long START_LIMIT = 120_000, HANG_LIMIT = 15_000, TOTAL_LIMIT = 330_000;

    private Watchdog() {}

    public static void start(Context context, Container container, Runnable endSession) {
        File folder = Bench.INSTANCE.benchFolder(container);
        File status = new File(folder, "status.json");
        File result = new File(folder, "result.json");
        File outcome = Bench.INSTANCE.outcomeFile(context);
        long begin = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            // Root mode: GPU readings through su and the hardware CPU counters (simpleperf).
            boolean rootMode = RootMode.INSTANCE.enabled(context) && RootMode.INSTANCE.available();
            RootShell root = rootMode ? RootShell.Companion.open() : null;
            Telemetry telemetry = new Telemetry(root);
            final String[] current = {"start"};
            PerfStat perf = rootMode ? new PerfStat(() -> current[0]) : null;
            if (perf != null) perf.start();
            String state, detail;
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                current[0] = status.isFile() ? phase(status) : "start";
                try { telemetry.sample(current[0]); } catch (Throwable ignored) {}
                if (result.isFile()) { state = "done"; detail = ""; break; }
                long beat = status.isFile() ? status.lastModified() : 0;
                if (beat == 0 && now - begin > START_LIMIT) { state = "timeout"; detail = "the benchmark didn't start in " + START_LIMIT / 1000 + " s"; break; }
                if (beat > 0 && now - beat > HANG_LIMIT) { state = "crash"; detail = "crash or hang during " + phase(status); break; }
                if (now - begin > TOTAL_LIMIT) { state = "timeout"; detail = "over " + TOTAL_LIMIT / 1000 + " s"; break; }
            }
            if (perf != null) {
                perf.stop();
                synchronized (perf.getPerPhase()) { telemetry.addCounters(perf.getPerPhase()); }
            }
            if (root != null) root.close();
            JSONObject rootInfo = new JSONObject();
            try {
                rootInfo.put("enabled", rootMode).put("sources", telemetry.getSources())
                        .put("perf_error", perf != null && perf.getError() != null ? perf.getError() : "");
            } catch (Exception ignored) {}
            try (FileWriter w = new FileWriter(outcome)) {
                w.write(new JSONObject().put("status", state).put("detail", detail)
                        .put("seconds", (System.currentTimeMillis() - begin) / 1000.0)
                        .put("phase", status.isFile() ? phase(status) : "start")
                        .put("telemetry", telemetry.toJson()).put("root", rootInfo).toString());
            } catch (Exception e) {
                Log.w(TAG, "outcome", e);
            }
            Log.i(TAG, "run finished: " + state + " " + detail);
            endSession.run();
        }, "BenchWatchdog");
        t.setDaemon(true);
        t.start();
    }

    private static String phase(File status) {
        try {
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(status.toPath()))).optString("phase", "?");
        } catch (Exception e) {
            return "?";
        }
    }
}
