package com.winlator.cmod.xconnector;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pushes out bytes queued for clients that were not reading when someone else wrote to them. */
final class PendingFlusher {
    private static final Set<XOutputStream> streams = ConcurrentHashMap.newKeySet();
    private static final Object signal = new Object();
    private static Thread thread;

    private PendingFlusher() {}

    static void add(XOutputStream stream) {
        if (!streams.add(stream)) return;
        synchronized (signal) {
            if (thread == null) {
                thread = new Thread(PendingFlusher::run, "XPendingFlush");
                thread.setDaemon(true);
                thread.start();
            }
            signal.notifyAll();
        }
    }

    private static void run() {
        for (;;) {
            synchronized (signal) {
                while (streams.isEmpty()) {
                    try {
                        signal.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            XOutputStream only = null;
            int left = 0;
            for (Iterator<XOutputStream> it = streams.iterator(); it.hasNext(); ) {
                XOutputStream stream = it.next();
                if (stream.drainFromFlusher()) it.remove();
                else {
                    only = stream;
                    left++;
                }
            }
            if (left == 0) continue;
            try {
                if (left == 1) only.waitForRoom(20);
                else Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
