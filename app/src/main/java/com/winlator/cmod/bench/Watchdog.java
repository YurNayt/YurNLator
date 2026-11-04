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
 * The outcome goes to files/bench/outcome.json for the bundle finder tab.
 */
public final class Watchdog {
    private static final String TAG = "BundleFinder";
    private static final long START_LIMIT = 120_000, HANG_LIMIT = 15_000, TOTAL_LIMIT = 240_000;

    private Watchdog() {}

    public static void start(Context context, Container container, Runnable endSession) {
        File folder = Bench.INSTANCE.benchFolder(container);
        File status = new File(folder, "status.json");
        File result = new File(folder, "result.json");
        File outcome = Bench.INSTANCE.outcomeFile(context);
        long begin = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            String state, detail;
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                if (result.isFile()) { state = "done"; detail = ""; break; }
                long beat = status.isFile() ? status.lastModified() : 0;
                if (beat == 0 && now - begin > START_LIMIT) { state = "timeout"; detail = "the benchmark didn't start in " + START_LIMIT / 1000 + " s"; break; }
                if (beat > 0 && now - beat > HANG_LIMIT) { state = "crash"; detail = "crash or hang during " + phase(status); break; }
                if (now - begin > TOTAL_LIMIT) { state = "timeout"; detail = "over " + TOTAL_LIMIT / 1000 + " s"; break; }
            }
            try (FileWriter w = new FileWriter(outcome)) {
                w.write(new JSONObject().put("status", state).put("detail", detail)
                        .put("seconds", (System.currentTimeMillis() - begin) / 1000.0).toString());
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
