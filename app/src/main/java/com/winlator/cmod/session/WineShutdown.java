package com.winlator.cmod.session;

import com.winlator.cmod.core.ProcessHelper;

import java.util.concurrent.Executors;

/**
 * Ends every Wine process of the session: asks them to terminate, waits up to 1.5 s, kills what
 * is left, then runs [done] (off the UI thread). Moved out of the game screen (base migration).
 */
public final class WineShutdown {
    private WineShutdown() {}

    public static void terminateAll(Runnable done) {
        Executors.newSingleThreadExecutor().execute(() -> {
            ProcessHelper.terminateAllWineProcesses();
            long start = System.currentTimeMillis();
            while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
                if (System.currentTimeMillis() - start >= 1500) {
                    for (String pid : ProcessHelper.listRunningWineProcesses()) ProcessHelper.killProcess(Integer.parseInt(pid));
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
            }
            done.run();
        });
    }
}
