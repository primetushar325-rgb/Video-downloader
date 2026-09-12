package com.vidx.platform.download

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.vidx.app.App
import com.vidx.core.download.TaskStatus

/**
 * Foreground service hosting the download engine.
 *
 * - Runs with a dataSync foreground type so downloads continue when the app is
 *   minimized or the screen is locked.
 * - Shows per-task progress notifications (pause/cancel actions where supported).
 * - Survives process recreation: the queue state is persisted by the engine and
 *   re-loaded on start (tasks mid-download come back as Paused and resume).
 * - Network loss pauses gracefully; recovery resumes (policy from settings).
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.vidx.action.START"
        private const val NOTIF_ID = 0x5EED
    }

    private val engine: DownloadEngine get() = App.instance.engine

    override fun onCreate() {
        super.onCreate()
        engine.attachService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Notifications.ACTION_PAUSE -> engine.pause(intent.getStringExtra(Notifications.EXTRA_TASK_ID) ?: "")
            Notifications.ACTION_CANCEL -> engine.cancel(intent.getStringExtra(Notifications.EXTRA_TASK_ID) ?: "")
            Notifications.ACTION_RETRY -> engine.retry(intent.getStringExtra(Notifications.EXTRA_TASK_ID) ?: "")
        }
        goForegroundIfNeeded()
        return START_STICKY
    }

    /** Called by the engine after state transitions. */
    fun onEngineStateChanged() {
        goForegroundIfNeeded()
    }

    private fun goForegroundIfNeeded() {
        val active = engine.snapshot().filter { it.status == TaskStatus.DOWNLOADING }
        if (active.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        val n: Notification = if (active.size == 1) {
            Notifications.progressNotification(this, active[0])
        } else {
            val total = active.sumOf { if (it.totalBytes > 0) it.totalBytes else 0 }
            val done = active.sumOf { it.downloadedBytes }
            Notification.Builder(this, App.CHANNEL_DOWNLOADS)
                .setSmallIcon(com.vidx.app.R.drawable.ic_stat_download)
                .setContentTitle("Downloading ${active.size} videos")
                .setContentText("${com.vidx.core.util.ByteFmt.human(done)}${if (total > 0) " / ${com.vidx.core.util.ByteFmt.human(total)}" else ""}")
                .setOngoing(true)
                .setProgress(100, if (total > 0) ((done * 100 / total).toInt()) else 0, total <= 0)
                .build()
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Foreground start can fail on some OEMs without notification permission;
            // downloads still run as a normal started service.
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        engine.detachService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
