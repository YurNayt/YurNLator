package com.winlator.cmod.bench;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.widget.WinlatorHUD;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.function.Supplier;

/**
 * Bench mode of the game screen for a game (not YurnBench): the game's frames are measured by our
 * HUD's counter (the same FPS / 1% low the HUD shows), so any game works, its built-in benchmark
 * or a scene. Steps: wait for the game's first frames (loading), warm up, measure, end the session.
 *
 * The outcome goes to files/bench/outcome.json: "done" at the end of the window, "partial" (written
 * every second while measuring) when the game ends sooner — a built-in benchmark that quits by
 * itself — so the frames measured until then still count. Telemetry phase "mix": the game loads
 * CPU and GPU together, like YurnBench's CPU+GPU phase.
 */
public final class GameWatchdog {
    private static final String TAG = "BundleFinder";
    private static final long START_LIMIT = 240_000;

    private GameWatchdog() {}

    public static void start(Context context, Supplier<WinlatorHUD> hud, int warmupSec, int measureSec, Runnable endSession) {
        File outcome = Bench.INSTANCE.outcomeFile(context);
        long begin = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            boolean rootMode = RootMode.INSTANCE.enabled(context) && RootMode.INSTANCE.available();
            RootShell root = rootMode ? RootShell.Companion.open() : null;
            Telemetry telemetry = new Telemetry(root);
            final String[] phase = {"load"};
            PerfStat perf = rootMode ? new PerfStat(() -> phase[0]) : null;
            if (perf != null) perf.start();
            long firstFrames = 0, measureFrom = 0;
            int rendering = 0;
            String state = null, detail = "";
            double[] result = null;
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                try { telemetry.sample(phase[0]); } catch (Throwable ignored) {}
                WinlatorHUD h = hud.get();
                float fps = h != null ? h.gameFps() : 0f;
                if (firstFrames == 0) {
                    // Loading screens draw a few frames too: steady frames for 3 s count as the game.
                    rendering = fps >= 5f ? rendering + 1 : 0;
                    if (rendering >= 6) {
                        firstFrames = now;
                        phase[0] = "warmup";
                        Log.i(TAG, "game renders: warm-up " + warmupSec + " s");
                    } else if (now - begin > START_LIMIT) {
                        state = "timeout"; detail = "the game didn't render in " + START_LIMIT / 1000 + " s";
                        break;
                    }
                    continue;
                }
                if (measureFrom == 0) {
                    if (now - firstFrames >= warmupSec * 1000L && h != null) {
                        h.benchStart();
                        measureFrom = now;
                        phase[0] = "mix";
                        Log.i(TAG, measureSec > 0 ? "measuring " + measureSec + " s" : "measuring until the game ends");
                    }
                    continue;
                }
                if (h == null) continue;
                // measureSec <= 0: until the game ends (its benchmark quits), at most 10 min.
                long window = measureSec > 0 ? measureSec * 1000L : 600_000L;
                if (now - measureFrom >= window) {
                    result = h.benchResult(true);
                    state = "done";
                    break;
                }
                // Every second: what's measured so far, in case the game ends before the window does.
                if ((now - measureFrom) % 1000 < 500) write(outcome, "partial", "", begin, telemetry, rootMode, h.benchResult(false));
            }
            if (perf != null) {
                perf.stop();
                synchronized (perf.getPerPhase()) { telemetry.addCounters(perf.getPerPhase()); }
            }
            if (root != null) root.close();
            write(outcome, state, detail, begin, telemetry, rootMode, result);
            Log.i(TAG, "game run finished: " + state + " " + detail);
            endSession.run();
        }, "BenchGameWatchdog");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void write(File outcome, String state, String detail, long begin, Telemetry telemetry,
                                           boolean rootMode, double[] r) {
        try (FileWriter w = new FileWriter(outcome)) {
            JSONObject o = new JSONObject().put("status", state).put("detail", detail)
                    .put("seconds", (System.currentTimeMillis() - begin) / 1000.0)
                    .put("phase", "game").put("telemetry", telemetry.toJson())
                    .put("root", new JSONObject().put("enabled", rootMode).put("sources", telemetry.getSources()));
            if (r != null) o.put("game", new JSONObject().put("avg", r[0]).put("low1", r[1]).put("low01", r[2])
                    .put("frames", (int) r[3]).put("measured", r[4]));
            w.write(o.toString());
        } catch (Exception e) {
            Log.w(TAG, "outcome", e);
        }
    }
}
