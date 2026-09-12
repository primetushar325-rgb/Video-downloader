package com.vidx.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.vidx.platform.clipboard.ClipboardMonitor
import com.vidx.platform.download.DownloadEngine
import com.vidx.platform.net.NetworkMonitor
import com.vidx.platform.settings.AndroidSettings
import com.vidx.platform.storage.AndroidStorage
import com.vidx.platform.storage.HistoryDb
import com.vidx.platform.storage.ThumbnailCache

class App : Application() {

    companion object {
        const val CHANNEL_DOWNLOADS = "vidx_downloads"
        const val CHANNEL_COMPLETED = "vidx_completed"
        const val CHANNEL_ALERTS = "vidx_alerts"

        lateinit var instance: App
            private set
    }

    lateinit var settings: AndroidSettings
        private set
    lateinit var storage: AndroidStorage
        private set
    lateinit var history: HistoryDb
        private set
    lateinit var thumbs: ThumbnailCache
        private set
    lateinit var engine: DownloadEngine
        private set
    lateinit var clipboard: ClipboardMonitor
        private set
    private var networkMonitor: NetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        settings = AndroidSettings(this)
        storage = AndroidStorage(this)
        history = HistoryDb(this)
        thumbs = ThumbnailCache(this)
        engine = DownloadEngine(this, settings, storage, history)
        clipboard = ClipboardMonitor(this)

        engine.onAppStart()
        (networkMonitor ?: NetworkMonitor(this).also { networkMonitor = it }).start()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val downloads = NotificationChannel(
            CHANNEL_DOWNLOADS, "Active downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress and controls for ongoing downloads"
            setShowBadge(false)
        }
        val completed = NotificationChannel(
            CHANNEL_COMPLETED, "Completed downloads",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Notifications when downloads finish" }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS, "Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Important VIDX alerts" }
        nm.createNotificationChannel(downloads)
        nm.createNotificationChannel(completed)
        nm.createNotificationChannel(alerts)
    }
}
