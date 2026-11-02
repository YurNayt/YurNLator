package com.winlator.cmod.core

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.PreferenceManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports testers can send without logcat: a Java crash is written to a file as it happens;
 * on the next launch the app shows it (plus native crashes / ANRs Android recorded, API 30+) with
 * Share and Copy buttons.
 */
object CrashReports {
    private const val FILE = "crash/last-crash.txt"
    private const val PREF_SEEN = "yurn_crash_seen_ts"

    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = header(app) + "Thread: ${thread.name}\n\n" + sw
                File(app.filesDir, FILE).apply { parentFile?.mkdirs() }.writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun header(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "YurNLator $version (${context.packageName})\n" +
            "${Build.MANUFACTURER} ${Build.MODEL} · $soc · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Time: $time\n"
    }

    /** Shows the last crash once, if there is one. Call from the main screen's onCreate. */
    @JvmStatic
    fun checkOnLaunch(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val seen = prefs.getLong(PREF_SEEN, 0L)
        val parts = mutableListOf<String>()
        var newest = seen

        val file = File(activity.filesDir, FILE)
        if (file.isFile && file.lastModified() > seen) {
            parts += runCatching { file.readText() }.getOrDefault("")
            newest = maxOf(newest, file.lastModified())
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(activity.packageName, 0, 10)
                .filter { it.timestamp > seen }
                .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR ||
                    (it.reason == ApplicationExitInfo.REASON_CRASH && parts.isEmpty()) }
                .forEach { info ->
                    val kind = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                        ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
                        else -> "Crash"
                    }
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
                    parts += "$kind in ${info.processName} at $time\n${info.description.orEmpty()}"
                    newest = maxOf(newest, info.timestamp)
                }
        }
        if (parts.isEmpty()) return
        prefs.edit().putLong(PREF_SEEN, maxOf(newest, System.currentTimeMillis())).apply()

        val report = (if (parts.first().startsWith("YurNLator")) "" else header(activity) + "\n") + parts.joinToString("\n\n---\n\n")
        val text = TextView(activity).apply {
            setText(report)
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(activity)
            .setTitle("YurNLator closed unexpectedly last time")
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "YurNLator crash report")
                    .putExtra(Intent.EXTRA_TEXT, report)
                activity.startActivity(Intent.createChooser(send, "Send the crash report"))
            }
            .setNeutralButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("YurNLator crash", report))
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
