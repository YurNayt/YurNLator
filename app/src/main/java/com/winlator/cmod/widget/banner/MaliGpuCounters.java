package com.winlator.cmod.widget.banner;

/**
 * Bannerlator reads CSF-generation Mali hardware counters natively here; that part isn't ported
 * yet, so Mali load / clock come from sysfs or the precise sensors (root / ADB) only.
 */
final class MaliGpuCounters {
    private MaliGpuCounters() {}
    static Integer usagePercent() { return null; }
    static Integer clockMhz() { return null; }
}
