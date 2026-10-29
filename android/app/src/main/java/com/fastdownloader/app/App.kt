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
import com.fastdownloader.app.engine.Ytdlp
import com.fastdownloader.app.service.DownloadService
import com.fastdownloader.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
    }
}
