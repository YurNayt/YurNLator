package com.winlator.cmod

import android.app.Application
import com.winlator.cmod.core.CrashReports

/** Installs the crash reporter in every process of the app (main screens and the game screen). */
class YurnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
    }
}
