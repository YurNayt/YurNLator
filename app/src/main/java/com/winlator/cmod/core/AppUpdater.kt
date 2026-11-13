package com.winlator.cmod.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.winlator.cmod.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * App updates from the GitHub releases of YurNLator. Stable: the latest published release
 * (v1.0.0, v1.1.0…); test builds: the rolling "dev" release of YurNLator_test_build. The APK of the same variant as
 * the installed one is downloaded (standard, antutu…: each is its own app), with pause /
 * resume / cancel through [DownloadTracker], and handed to Android's package installer.
 */
object AppUpdater {
    const val REPO = "YurNayt/YurNLator"
    /** Test builds: their own public repository, so REPO's releases page shows main versions only. */
    const val DEV_REPO = "YurNayt/YurNLator_test_build"
    const val DOWNLOAD_KEY = "app-update"
    private const val PREF_DEV = "yurn_update_dev_builds"

    data class Update(
        /** What to show: "v0.2.0" or "dev 230". */
        val version: String,
        val url: String,
        val size: Long,
        val notes: String,
        val fileName: String
    )

    sealed class Check {
        class Available(val update: Update) : Check()
        object UpToDate : Check()
        /** No stable release published yet. */
        object NoRelease : Check()
        class Failed(val reason: String) : Check()
    }

    val flavor: String get() = BuildConfig.FLAVOR

    fun devChannel(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_DEV, false)

    fun setDevChannel(context: Context, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_DEV, on).apply()

    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    @Suppress("DEPRECATION")
    fun installedBuild(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    /** Blocking (network): call off the main thread. */
    fun check(context: Context): Check = runCatching {
        if (devChannel(context)) checkDev(context) else checkStable(context)
    }.getOrElse { Check.Failed(it.message ?: it.javaClass.simpleName) }

    private fun get(url: String): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        return try {
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally { conn.disconnect() }
    }

    private fun checkStable(context: Context): Check {
        val (code, body) = get("https://api.github.com/repos/$REPO/releases/latest")
        if (code == 404) return Check.NoRelease
        if (code !in 200..299) return Check.Failed("HTTP $code")
        val release = JSONObject(body)
        val tag = release.optString("tag_name")
        val asset = assets(release).firstOrNull { it.optString("name").endsWith("-$flavor.apk") }
            ?: return Check.Failed("no $flavor APK in $tag")
        if (!Version.parse(tag).newerThan(Version.parse(installedVersion(context)))) return Check.UpToDate
        return Check.Available(update(asset, tag, release.optString("body")))
    }

    private fun checkDev(context: Context): Check {
        val (code, body) = get("https://api.github.com/repos/$DEV_REPO/releases/tags/dev")
        if (code !in 200..299) return Check.Failed("HTTP $code")
        val release = JSONObject(body)
        // YurNLator-dev-230-abc1234.apk (standard) / YurNLator-dev-230-abc1234-antutu.apk
        val re = if (flavor == "standard") Regex("""YurNLator-dev-(\d+)-[0-9a-f]+\.apk""")
                 else Regex("""YurNLator-dev-(\d+)-[0-9a-f]+-${Regex.escape(flavor)}\.apk""")
        val best = assets(release).mapNotNull { a -> re.matchEntire(a.optString("name"))?.let { it.groupValues[1].toLong() to a } }
            .maxByOrNull { it.first } ?: return Check.Failed(
                if (flavor != "standard" && flavor != "antutu") "test builds: standard and AnTuTu only" else "no $flavor APK")
        // A build's versionCode is its CI run number, the number in the APK name.
        if (best.first <= installedBuild(context)) return Check.UpToDate
        return Check.Available(update(best.second, "dev ${best.first}", release.optString("body")))
    }

    private fun assets(release: JSONObject): List<JSONObject> {
        val arr = release.optJSONArray("assets") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun update(asset: JSONObject, version: String, notes: String) = Update(
        version = version,
        url = asset.optString("browser_download_url"),
        size = asset.optLong("size"),
        notes = notes.trim(),
        fileName = asset.optString("name")
    )

    private fun dir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /** The downloaded APK, or null on failure / cancel. Blocking. */
    fun download(context: Context, update: Update): File? {
        val file = File(dir(context), update.fileName)
        DownloadTracker.begin(DOWNLOAD_KEY)
        return try {
            dir(context).listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
            if (DownloadTracker.download(DOWNLOAD_KEY, update.url, file, true, expectedSize = update.size)
                && (update.size <= 0 || file.length() == update.size)) file else null
        } catch (e: DownloadTracker.CancelledException) {
            file.delete(); null
        } finally {
            DownloadTracker.finish(DOWNLOAD_KEY)
        }
    }

    /** Android 8+: the user allows this app to install apps once. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens Android's installer for [apk] (it updates the app in place: same package and signature). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".tileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** "v0.2.0", "0.1.0-dev.217": numbers, then a dev build number (a dev build is older than its release). */
    data class Version(val nums: List<Int>, val dev: Int?) {
        fun newerThan(o: Version): Boolean {
            for (i in 0 until maxOf(nums.size, o.nums.size)) {
                val a = nums.getOrElse(i) { 0 }; val b = o.nums.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return when {
                dev == null && o.dev != null -> true
                dev != null && o.dev != null -> dev > o.dev
                else -> false
            }
        }

        companion object {
            fun parse(v: String): Version {
                val s = v.trim().removePrefix("v").removePrefix("V")
                val main = s.substringBefore('-')
                val nums = main.split('.').mapNotNull { it.toIntOrNull() }
                val dev = Regex("""dev\.?(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull()
                return Version(nums, dev)
            }
        }
    }
}
