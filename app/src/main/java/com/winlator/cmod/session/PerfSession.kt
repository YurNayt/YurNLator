package com.winlator.cmod.session

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.ProcessHelper
import com.winlator.cmod.perf.CpuTopology
import com.winlator.cmod.perf.GameModeSignal
import com.winlator.cmod.perf.PerfGpuTurbo
import com.winlator.cmod.perf.PerfPriority
import com.winlator.cmod.perf.PerfRevertRegistry
import com.winlator.cmod.perf.PerfRootApplier
import com.winlator.cmod.perf.PerformanceSettings
import com.winlator.cmod.perf.RootManager
import com.winlator.cmod.perf.TempWatchdog
import com.winlator.cmod.perf.galaxy.GalaxyPerfManager
import com.winlator.cmod.winhandler.OnGetProcessInfoListener
import com.winlator.cmod.winhandler.ProcessInfo
import com.winlator.cmod.winhandler.WinHandler
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent
import java.util.concurrent.Executors

/**
 * The game's performance controls (ported from Bannerlator's Performance panel): Sustained
 * Performance Mode, thread priority boost, big cores, the GPU max-clock pin (KGSL turbo without
 * root on Adreno, sysfs with root) and, with root, the CPU governor / clock lock / all cores online
 * and fan max. Everything changed is put back when the game closes (and, after a crash, at the next
 * start). No "disable thermal throttling": that one is left to whoever wants it.
 *
 * Each switch: the game's own choice (a shortcut extra, kept only when it differs from the default)
 * or else the global default (Settings). Power profiles set several at once.
 */
class PerfSession(
    private val activity: Activity,
    private val container: Container?,
    private val shortcut: Shortcut?,
    private val cpuAffinity: () -> CpuAffinity,
    private val winHandler: () -> WinHandler?,
) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "PerfSession").apply { isDaemon = true } }
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    // ---- what each switch is -------------------------------------------------------------------

    fun value(key: String): Boolean {
        val own = shortcut?.getExtra(key, "") ?: ""
        if (own == "1" || own == "0") return own == "1"
        return PerformanceSettings.globalDefault(key)
    }

    /** The game's own setting differs from the default (shown as "this game"). */
    fun isOwn(key: String): Boolean = (shortcut?.getExtra(key, "") ?: "").let { it == "1" || it == "0" }

    fun set(key: String, on: Boolean) {
        if (shortcut != null) {
            shortcut.putExtra(key, if (on == PerformanceSettings.globalDefault(key)) null else if (on) "1" else "0")
            shortcut.saveData()
        } else {
            PerformanceSettings.setGlobalDefault(key, on)
        }
        applyLive(key, on)
    }

    /** Back to the defaults for every switch of this game. */
    fun resetToDefaults() {
        for (key in KEYS) {
            if (shortcut != null && isOwn(key)) shortcut.putExtra(key, null)
            applyLive(key, PerformanceSettings.globalDefault(key))
        }
        shortcut?.saveData()
    }

    // ---- profiles --------------------------------------------------------------------------------

    fun applyProfile(name: String) {
        val perf = name == PROFILE_PERFORMANCE
        val balanced = name == PROFILE_BALANCED
        set(SUSTAINED, perf || balanced)
        set(PRIORITY, perf)
        set(BIG_CORES, perf)
        set(PerfRootApplier.KEY_GPU_CLOCK_LOCK, perf)
        if (RootManager.isGranted) {
            set(PerfRootApplier.KEY_CPU_GOVERNOR, perf)
            set(PerfRootApplier.KEY_CPU_FREQ_LOCK, perf)
            set(PerfRootApplier.KEY_CORES_ONLINE, perf)
        }
    }

    /** Which profile the switches match ("" = a custom mix). */
    fun currentProfile(): String {
        val s = value(SUSTAINED); val p = value(PRIORITY); val b = value(BIG_CORES); val g = value(PerfRootApplier.KEY_GPU_CLOCK_LOCK)
        val rootOn = RootManager.isGranted && (value(PerfRootApplier.KEY_CPU_GOVERNOR) || value(PerfRootApplier.KEY_CPU_FREQ_LOCK) || value(PerfRootApplier.KEY_CORES_ONLINE))
        val rootAll = !RootManager.isGranted || (value(PerfRootApplier.KEY_CPU_GOVERNOR) && value(PerfRootApplier.KEY_CPU_FREQ_LOCK) && value(PerfRootApplier.KEY_CORES_ONLINE))
        return when {
            !s && !p && !b && !g && !rootOn -> PROFILE_STOCK
            s && !p && !b && !g && !rootOn -> PROFILE_BALANCED
            s && p && b && g && rootAll -> PROFILE_PERFORMANCE
            else -> ""
        }
    }

    // ---- session ---------------------------------------------------------------------------------

    /** Before the game starts: big cores go into the affinity the windows get. */
    fun prepareLaunch() {
        if (value(BIG_CORES)) bigCoreMask()?.let { cpuAffinity().setPreferredMask(it) }
    }

    /** The game screen is up. */
    fun start() {
        if (started) return
        started = true
        // The environment starts on a worker thread: window changes made from there broke the
        // views later shown or hidden (the HUD and the on-screen controls stayed invisible).
        val sustained = value(SUSTAINED)
        ui.post { runCatching { activity.window.setSustainedPerformanceMode(sustained) } }
        val effective = PerfRootApplier.ROOT_KEYS.associateWith { value(it) }
        io.execute {
            // A remembered root grant comes back without a prompt (the root manager answers it).
            if (!RootManager.isGranted) runCatching { RootManager.probe() }
            runCatching { PerfRootApplier.applyEffective(effective) }
        }
        TempWatchdog.start(activity)
        if (value(PRIORITY)) ui.postDelayed({ io.execute { PerfPriority.boost(GuestProgramLauncherComponent.getPid()) } }, 5000)
        container?.let { GalaxyPerfManager.start(it.rootDir) }
        GameModeSignal.enterGameplay(activity)
    }

    /** The game closes: everything changed goes back. */
    fun stop() {
        if (!started) return
        started = false
        ui.removeCallbacksAndMessages(null)
        TempWatchdog.stop()
        runCatching { PerfPriority.restore() }
        runCatching { PerfRevertRegistry.revertAll() }
        runCatching { PerfGpuTurbo.revert() }
        runCatching { GalaxyPerfManager.stop() }
        GameModeSignal.exitGameplay(activity)
        io.shutdown()
    }

    private fun applyLive(key: String, on: Boolean) {
        when (key) {
            SUSTAINED -> ui.post { runCatching { activity.window.setSustainedPerformanceMode(on) } }
            PRIORITY -> io.execute {
                if (on) PerfPriority.boost(GuestProgramLauncherComponent.getPid()) else PerfPriority.restore()
            }
            BIG_CORES -> applyBigCores(on)
            else -> io.execute { runCatching { PerfRootApplier.apply(key, on) } }
        }
    }

    // ---- big cores -------------------------------------------------------------------------------

    private fun bigCoreMask(): Int? {
        val list = CpuTopology.detectBigCoreCpuList()
        if (list.isEmpty()) return null
        Log.i(TAG, "prefer big cores: " + CpuTopology.describeBigCores())
        val mask = ProcessHelper.getAffinityMask(list)
        return if (mask != 0) mask else null
    }

    /** New windows get the big cores (or the launch's cores again), and running processes move now. */
    private fun applyBigCores(on: Boolean) {
        val affinity = cpuAffinity()
        val mask = if (on) bigCoreMask() ?: return else 0
        affinity.setPreferredMask(mask)
        val wh = winHandler() ?: return
        // One listing of the game's processes, then each one is pinned (the task manager's choice stays).
        wh.setOnGetProcessInfoListener(object : OnGetProcessInfoListener {
            override fun onGetProcessInfo(index: Int, count: Int, info: ProcessInfo?) {
                if (info != null) affinity.repin(info.pid, info.wow64Process, wh)
                if (count == 0 || index >= count - 1) wh.setOnGetProcessInfoListener(null)
            }
        })
        wh.listProcesses()
    }

    companion object {
        private const val TAG = "PerfSession"
        const val SUSTAINED = "sustainedPerfMode"
        const val PRIORITY = "perfPriorityBoost"
        const val BIG_CORES = "preferBigCores"
        val KEYS = listOf(SUSTAINED, PRIORITY, BIG_CORES) + PerfRootApplier.ROOT_KEYS

        const val PROFILE_STOCK = "stock"
        const val PROFILE_BALANCED = "balanced"
        const val PROFILE_PERFORMANCE = "performance"
    }
}
