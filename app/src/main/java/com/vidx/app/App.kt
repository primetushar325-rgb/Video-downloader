package com.vidx.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    companion object {
        const val CHANNEL_DOWNLOADS = "vidx_downloads"
        const val CHANNEL_COMPLETED = "vidx_completed"
        const val CHANNEL_ALERTS = "vidx_alerts"

        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
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
