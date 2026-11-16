package com.winlator.cmod

import android.app.Application
import com.winlator.cmod.core.CrashReports

/** Installs the crash reporter in every process of the app (main screens and the game screen). */
class YurnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        com.winlator.cmod.ui.UiStrings.install(this)
        // A driver that crashed the app while being probed is not loaded here again.
        runCatching { com.winlator.cmod.core.GPUInformation.recoverFromProbeCrash(this) }
        // Performance controls (from Bannerlator): restore anything a killed session left changed,
        // probe root, load the global defaults.
        runCatching {
            com.winlator.cmod.perf.RootManager.onAppStartup(this)
            com.winlator.cmod.perf.TempWatchdog.init(this)
            com.winlator.cmod.perf.PerformanceSettings.init(this)
            com.winlator.cmod.perf.galaxy.GalaxyPerfManager.initialize(this)
        }
    }
}
