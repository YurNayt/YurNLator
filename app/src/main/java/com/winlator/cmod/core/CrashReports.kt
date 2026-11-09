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

    /**
     * What Android kept besides the one-line description: the ANR thread dump (main thread and
     * our renderer / X server threads), or the native tombstone's backtrace (a protobuf: its
     * readable strings - signal, abort message, frames "libX.so (function+off)").
     */
    private fun details(info: ApplicationExitInfo): String {
        val raw = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: return ""
        if (raw.isEmpty()) return ""
        return if (info.reason == ApplicationExitInfo.REASON_ANR) {
            val text = String(raw, Charsets.UTF_8)
            // Thread blocks are separated by blank lines; keep main + the interesting ones.
            val blocks = text.split(Regex("\\n\\s*\\n"))
            val keep = blocks.filter { b ->
                // The thread's own header line: the main thread's block also starts with the dump's
                // "----- pid ... -----" / "Cmd line:" lines, and was dropped (no main thread in ANRs).
                val head = b.lineSequence().map { it.trimStart() }.firstOrNull { it.startsWith("\"") }.orEmpty()
                head.startsWith("\"main\"") || Regex("(?i)render|xserver|vulkan|wayland|winhandler|gyro|surface|GLThread").containsMatchIn(head)
            }.take(8)
            "\n\n--- ANR threads ---\n" + keep.joinToString("\n\n") { it.lines().take(45).joinToString("\n") }
        } else {
            Tombstone.format(raw)?.let { return it }
            val strings = Regex("[\\x20-\\x7e]{6,}").findAll(String(raw, Charsets.ISO_8859_1)).map { it.value.trim() }
                .filter { it.contains(".so") || it.contains("SIG") || it.contains("Abort") || it.contains("abort") ||
                          it.contains("Cause") || it.contains("/data/") || it.contains("fault") }
                .distinct().take(80).toList()
            if (strings.isEmpty()) "" else "\n\n--- native backtrace ---\n" + strings.joinToString("\n")
        }
    }

    /** Also saved to Downloads/YurNLator so it can be found later or sent as a file. */
    private fun saveToDownloads(context: Context, report: String): String? = runCatching {
        val name = "YurNLator-crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/YurNLator")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "YurNLator")
            dir.mkdirs(); File(dir, name).writeText(report)
        }
        "Download/YurNLator/$name"
    }.getOrNull()

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
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    (it.reason == ApplicationExitInfo.REASON_CRASH && parts.isEmpty()) }
                .forEach { info ->
                    val kind = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                        ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
                        // The game closes with "X connection broken": Android ran out of RAM and
                        // closed the app (heavy game, transcoded textures, other apps in memory).
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: out of memory " +
                            "(app used ${info.pss / 1024} MB, ${info.rss / 1024} MB resident)"
                        else -> "Crash"
                    }
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
                    parts += "$kind in ${info.processName} at $time\n${info.description.orEmpty()}" + details(info)
                    newest = maxOf(newest, info.timestamp)
                }
        }
        if (parts.isEmpty()) return
        prefs.edit().putLong(PREF_SEEN, maxOf(newest, System.currentTimeMillis())).apply()

        val report = (if (parts.first().startsWith("YurNLator")) "" else header(activity) + "\n") + parts.joinToString("\n\n---\n\n")
        val saved = saveToDownloads(activity, report)
        val text = TextView(activity).apply {
            setText((if (saved != null) "Saved: $saved\n\n" else "") + report)
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
