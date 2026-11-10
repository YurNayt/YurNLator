package com.winlator.cmod.session;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Window;

/**
 * Which CPU cores the game's processes run on: the launch's CPU list (the game's own where set,
 * else the container's; 32-bit WoW64 processes take the container's WoW64 list unless the game
 * set its own), applied per process as its windows map, and the WINE_CPU_TOPOLOGY value Wine
 * reports to the game. Moved out of the game screen (base migration).
 */
public final class CpuAffinity {
    private final short mask;
    private final short maskWoW64;
    private final String wineCpuTopology;
    /** Cores the user set for a process in the task manager (Windows pid -> mask): kept when the
     *  process maps another window, and shown instead of what Wine last reported. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> userMasks = new java.util.concurrent.ConcurrentHashMap<>();

    private CpuAffinity(short mask, short maskWoW64, String wineCpuTopology) {
        this.mask = mask;
        this.maskWoW64 = maskWoW64;
        this.wineCpuTopology = wineCpuTopology != null ? wineCpuTopology : "";
    }

    public static CpuAffinity of(LaunchConfig lc, Container container) {
        short mask = (short) ProcessHelper.getAffinityMask(lc.getCpuList());
        short wow64 = lc.getCpuListFromShortcut() ? mask
                : (short) ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));
        return new CpuAffinity(mask, wow64, lc.getWineCpuTopology());
    }

    public static CpuAffinity none() { return new CpuAffinity((short) 0, (short) 0, ""); }

    public String wineCpuTopology() { return wineCpuTopology; }

    /** Performance › big cores: every process goes there instead of the launch's cores (0 = off). */
    private volatile int preferredMask = 0;

    public void setPreferredMask(int mask) { preferredMask = mask; }

    /** Cores for a process now (big cores, else the launch's list for its kind). */
    private int maskFor(boolean wow64) {
        int p = preferredMask;
        if (p != 0) return p;
        return wow64 ? maskWoW64 : mask;
    }

    /** Pins a running process again (big cores switched); the task manager's choice for it stays. */
    public void repin(int pid, boolean wow64, WinHandler winHandler) {
        if (pid <= 0 || winHandler == null || userMasks.containsKey(pid)) return;
        int m = maskFor(wow64);
        if (m != 0) winHandler.setProcessAffinity(pid, m);
    }

    /** The task manager set [mask] for [pid]: applies it and remembers it. */
    public void setUserMask(int pid, int mask, WinHandler winHandler) {
        if (mask == 0) return;
        userMasks.put(pid, mask);
        if (winHandler != null) winHandler.setProcessAffinity(pid, mask);
    }

    /** What to show for a process: the user's choice for it, else what Wine reported. */
    public int shownMask(int pid, int reported) {
        Integer m = userMasks.get(pid);
        return m != null ? m : reported;
    }

    /** A window mapped: pins its process (by pid, else by window class) to the launch's cores. */
    public void apply(Window window, WinHandler winHandler) {
        if (winHandler == null || (preferredMask == 0 && (mask == 0 || maskWoW64 == 0))) return;
        int pid = window.getProcessId();
        if (pid > 0 && userMasks.containsKey(pid)) return;
        int affinity = maskFor(window.isWoW64());
        String className = window.getClassName();
        if (pid > 0) winHandler.setProcessAffinity(pid, affinity);
        else if (!className.isEmpty()) winHandler.setProcessAffinity(className, affinity);
    }
}
