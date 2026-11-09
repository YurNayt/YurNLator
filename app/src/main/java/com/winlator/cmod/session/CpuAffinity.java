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

    /** A window mapped: pins its process (by pid, else by window class) to the launch's cores. */
    public void apply(Window window, WinHandler winHandler) {
        if (mask == 0 || maskWoW64 == 0 || winHandler == null) return;
        int affinity = window.isWoW64() ? maskWoW64 : mask;
        int pid = window.getProcessId();
        String className = window.getClassName();
        if (pid > 0) winHandler.setProcessAffinity(pid, affinity);
        else if (!className.isEmpty()) winHandler.setProcessAffinity(className, affinity);
    }
}
