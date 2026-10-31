package com.fastdownloader.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Net
import com.fastdownloader.app.engine.Status
import com.fastdownloader.app.engine.Updater
import com.fastdownloader.app.engine.Ytdlp
import com.fastdownloader.app.service.DownloadService
import com.fastdownloader.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        Engine.init(this)
        Engine.onActive = { DownloadService.ensureRunning(this) }

        scope.launch {
            Engine.finished.collect { v ->
                if (!Engine.settings.notifyOnComplete) return@collect
                val done = v.status == Status.COMPLETED
                val open = PendingIntent.getActivity(
                    this@App, v.id.hashCode(),
                    Intent(this@App, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ID, v.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(this@App, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(if (done) "Download complete" else "Download failed")
                    .setContentText(if (done) v.fileName else "${v.fileName}: ${v.error}")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                runCatching { NotificationManagerCompat.from(this@App).notify(v.id.hashCode(), n) }
            }
        }
        // The phone's real browser identity; loading WebView for it is slow, so not on the main thread.
        scope.launch { runCatching { Net.userAgent = WebSettings.getDefaultUserAgent(this@App) } }
        // Keep yt-dlp current, since video sites change often.
        scope.launch { runCatching { Ytdlp.updateIfDue(this@App) } }
        // New versions: checked shortly after start and every 6 hours while the app runs.
        scope.launch {
            delay(15_000)
            while (true) {
                if (Engine.settings.checkUpdates) runCatching { Updater.check()?.let { notifyUpdate(it) } }
                delay(Updater.EVERY_MS)
            }
        }
    }

    /** One notification per new version; the banner in the app stays until it's installed. */
    private fun notifyUpdate(r: Updater.Release) {
        if (Engine.settings.updateNotified == r.version) return
        Engine.updateSettings { it.copy(updateNotified = r.version) }
        val open = PendingIntent.getActivity(
            this, 7001,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_UPDATE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("FastDownloader ${r.version} is available")
            .setContentText("You have ${BuildConfig.VERSION_NAME}. Tap to update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(7001, n) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        const val CHANNEL_UPDATES = "updates"
    }
}
