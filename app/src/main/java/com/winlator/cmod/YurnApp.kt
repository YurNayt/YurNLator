package com.winlator.cmod

import android.app.Application
import com.winlator.cmod.core.CrashReports

/** Installs the crash reporter in every process of the app (main screens and the game screen). */
class YurnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.winlator.cmod.ui.Motion.init(this)
        // The floating motion editor over every screen of the app (not the game's).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) {
                if (a is androidx.activity.ComponentActivity && a !is XServerDisplayActivity)
                    runCatching { com.winlator.cmod.ui.MotionEditorHost.sync(a) }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
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
