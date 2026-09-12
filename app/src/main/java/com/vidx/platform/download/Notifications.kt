package com.vidx.platform.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vidx.app.App
import com.vidx.app.MainActivity
import com.vidx.core.download.DownloadTask
import com.vidx.core.settings.AppSettings
import com.vidx.core.util.ByteFmt

/**
 * Notification plumbing for the download service: progress (with pause/cancel
 * actions), completion and failure. Notification behaviour honours the user's
 * settings (progress/completed/failed toggles).
 */
object Notifications {

    const val ACTION_PAUSE = "com.vidx.action.PAUSE"
    const val ACTION_CANCEL = "com.vidx.action.CANCEL"
    const val ACTION_RETRY = "com.vidx.action.RETRY"
    const val EXTRA_TASK_ID = "task_id"

    fun progressNotification(context: Context, task: DownloadTask): Notification {
        val openApp = PendingIntent.getActivity(
            context, task.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            context, (task.id + "pause").hashCode(),
            Intent(context, DownloadService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            context, (task.id + "cancel").hashCode(),
            Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = task.title ?: "Downloading video"
        val progress = task.progressPct
        val builder = Notification.Builder(context, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(com.vidx.app.R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(
                when {
                    task.totalBytes > 0 ->
                        "${ByteFmt.human(task.downloadedBytes)} / ${ByteFmt.human(task.totalBytes)} • ${ByteFmt.human(task.speedBps)}/s"
                    else -> "Downloading… ${ByteFmt.human(task.downloadedBytes)}"
                }
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(com.vidx.app.R.drawable.ic_stat_pause, "Pause", pause)
            .addAction(com.vidx.app.R.drawable.ic_stat_cancel, "Cancel", cancel)
            .setProgress(100, progress, progress < 0)
        if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(App.CHANNEL_DOWNLOADS)
        return builder.build()
    }

    fun completed(context: Context, task: DownloadTask, settings: AppSettings) {
        if (!settings.notifyCompleted) return
        val open = PendingIntent.getActivity(
            context, task.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, App.CHANNEL_COMPLETED)
            .setSmallIcon(com.vidx.app.R.drawable.ic_stat_done)
            .setContentTitle("Download complete")
            .setContentText(task.title ?: task.outputFileName ?: "Your video is ready.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, task.id.hashCode(), n)
    }

    fun failed(context: Context, task: DownloadTask, settings: AppSettings) {
        if (!settings.notifyFailed) return
        val retry = PendingIntent.getService(
            context, (task.id + "retry").hashCode(),
            Intent(context, DownloadService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(com.vidx.app.R.drawable.ic_stat_alert)
            .setContentTitle("Download failed")
            .setContentText(task.title ?: task.url)
            .setStyle(Notification.BigTextStyle().bigText(task.errorMessage ?: "The download could not be completed."))
            .setAutoCancel(true)
            .addAction(com.vidx.app.R.drawable.ic_stat_retry, "Retry", retry)
            .build()
        notify(context, task.id.hashCode() + 1, n)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 33 && nm.areNotificationsEnabled()) nm.notify(id, notification)
            else if (Build.VERSION.SDK_INT < 33) nm.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied — silently skip (in-app UI still shows state).
        }
    }
}
