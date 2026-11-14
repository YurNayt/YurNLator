package com.winlator.cmod.store.steam

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr

/**
 * Keeps the app alive while Steam games download (a notification with the progress), so leaving
 * the app or turning the screen off doesn't stop them. Stops by itself when nothing is downloading.
 */
class SteamDownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Steam", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(ID, build())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YurNLator::SteamDownload").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun build(): android.app.Notification {
        val active = SteamInstaller.jobs.value.values.filter { it.active }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.winlator_mark)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        val j = active.firstOrNull { it.phase == SteamInstaller.Phase.DOWNLOADING } ?: active.firstOrNull()
        if (j == null) {
            b.setContentTitle("Steam")
        } else {
            val pct = (j.fraction * 100).toInt()
            b.setContentTitle(j.name)
            b.setContentText(if (j.total > 0) uiStr(R.string.l_sts_notif_progress, pct, active.size) else uiStr(R.string.l_sts_preparing))
            b.setProgress(100, pct, j.total <= 0)
        }
        return b.build()
    }

    private fun refresh() {
        if (!SteamInstaller.anyActive()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPost < 1000) return
        lastPost = now
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private var lastPost = 0L

    companion object {
        private const val CHANNEL = "yurn_steam_downloads"
        private const val ID = 4711
        @Volatile private var instance: SteamDownloadService? = null

        fun start(context: Context) {
            runCatching {
                val i = Intent(context, SteamDownloadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        /** Called on every progress change. */
        fun update() {
            instance?.let { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { s.refresh() } }
        }
    }
}
