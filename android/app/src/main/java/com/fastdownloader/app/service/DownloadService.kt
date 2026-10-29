package com.fastdownloader.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fastdownloader.app.App
import com.fastdownloader.app.R
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Format
import com.fastdownloader.app.engine.Status
import com.fastdownloader.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps downloads running while the app is in the background or the screen is off, and shows
 * their progress in the notification shade. Stops itself a few seconds after the last download ends.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(0, 0, -1f, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastDownloader:downloads").apply { acquire(6 * 3600_000L) }
        @Suppress("DEPRECATION")
        wifi = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "FastDownloader:downloads",
            ).apply { acquire() }
        job = scope.launch {
            var idleTicks = 0
            while (true) {
                val list = Engine.itemsFlow.value
                val active = list.filter { it.status == Status.DOWNLOADING }
                if (active.isEmpty() && Engine.activeCount == 0) {
                    if (++idleTicks >= 6) break // ~3 s with nothing running
                } else {
                    idleTicks = 0
                    val total = active.filter { it.total > 0 }.sumOf { it.total }
                    val got = active.filter { it.total > 0 }.sumOf { it.received }
                    val fraction = if (total > 0) got.toFloat() / total else -1f
                    val n = build(active.size, list.count { it.status == Status.QUEUED }, fraction, active.sumOf { it.speed })
                    runCatching { NotificationManagerCompat.from(this@DownloadService).notify(NOTIFICATION_ID, n) }
                }
                delay(500)
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) Engine.pauseAll()
        return START_NOT_STICKY
    }

    /** Android 15+: data-sync services get 6 hours a day. Pause cleanly; the user can continue later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Engine.pauseAll()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    private fun build(active: Int, queued: Int, fraction: Float, speed: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (active) {
            0 -> "Starting downloads…"
            1 -> "Downloading 1 file"
            else -> "Downloading $active files"
        }
        val text = listOfNotNull(
            if (fraction >= 0) "${(fraction * 100).toInt()}%" else null,
            if (speed > 0) Format.speed(speed) else null,
            if (queued > 0) "$queued waiting" else null,
        ).joinToString(" · ")
        return NotificationCompat.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, if (fraction >= 0) (fraction * 100).toInt() else 0, fraction < 0)
            .addAction(0, "Pause all", pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE_ALL = "pause_all"

        @Volatile var running = false
            private set

        fun ensureRunning(ctx: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }
    }
}
