package com.vidx.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.vidx.core.download.DownloadTask
import com.vidx.core.model.Platform
import com.vidx.core.settings.AccentTheme
import com.vidx.platform.clipboard.ClipboardMonitor
import com.vidx.platform.download.DownloadService
import com.vidx.ui.components.Ui
import com.vidx.ui.nav.BottomNav
import com.vidx.ui.screens.DownloadsScreen
import com.vidx.ui.screens.HistoryScreen
import com.vidx.ui.screens.HomeScreen
import com.vidx.ui.screens.SettingsScreen
import com.vidx.ui.screens.TranscriptScreen
import com.vidx.ui.theme.ThemeEngine

/**
 * Single-activity host: bottom navigation (Home / Downloads / Transcript /
 * History / Settings), the download service glue and clipboard policy.
 * Rotation/configuration changes are handled by the manifest (configChanges),
 * so the queue and UI state survive them.
 */
class MainActivity : Activity() {

    private lateinit var nav: BottomNav
    private lateinit var container: FrameLayout
    private lateinit var home: HomeScreen
    private lateinit var downloads: DownloadsScreen
    private lateinit var transcript: TranscriptScreen
    private lateinit var history: HistoryScreen
    private lateinit var settings: SettingsScreen

    private val engine get() = App.instance.engine

    private val engineListener = { _: DownloadTask ->
        runOnUiThread {
            home.refresh()
            downloads.refresh()
            history.refresh()
        }
    }

    private val clipboardListener = object : ClipboardMonitor.Listener {
        override fun onVideoLinkDetected(url: String, platform: Platform, repeat: Boolean) {
            runOnUiThread { home.onClipboardLink(url, platform, repeat) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeEngine.setAccent(App.instance.settings.load().accentTheme)
        ThemeEngine.applySystemBars(this)

        val root = android.widget.LinearLayout(this)
        root.orientation = android.widget.LinearLayout.VERTICAL
        root.setBackgroundColor(BrandConfig.colorBackground)

        container = FrameLayout(this)
        root.addView(container, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        nav = BottomNav(this)
        root.addView(nav, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64f)
        ))

        home = HomeScreen(this)
        downloads = DownloadsScreen(this)
        transcript = TranscriptScreen(this)
        history = HistoryScreen(this)
        settings = SettingsScreen(this)

        container.addView(home, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        container.addView(downloads, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        container.addView(transcript, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        container.addView(history, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        container.addView(settings, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        setContentView(root)

        engine.listener = engineListener
        nav.onTabSelected = { tab -> show(tab) }

        // Accept "share text" intents: the URL lands on the Home screen.
        handleShareIntent(intent)
        show(BottomNav.Tab.HOME)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                home.pendingSharedUrl = text.trim()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        show(BottomNav.Tab.HOME)
    }

    private fun show(tab: BottomNav.Tab) {
        nav.select(tab)
        home.visibility = if (tab == BottomNav.Tab.HOME) android.view.View.VISIBLE else android.view.View.GONE
        downloads.visibility = if (tab == BottomNav.Tab.DOWNLOADS) android.view.View.VISIBLE else android.view.View.GONE
        transcript.visibility = if (tab == BottomNav.Tab.TRANSCRIPT) android.view.View.VISIBLE else android.view.View.GONE
        history.visibility = if (tab == BottomNav.Tab.HISTORY) android.view.View.VISIBLE else android.view.View.GONE
        settings.visibility = if (tab == BottomNav.Tab.SETTINGS) android.view.View.VISIBLE else android.view.View.GONE
        when (tab) {
            BottomNav.Tab.HOME -> home.onShown()
            BottomNav.Tab.DOWNLOADS -> downloads.onShown()
            BottomNav.Tab.TRANSCRIPT -> transcript.onShown()
            BottomNav.Tab.HISTORY -> history.onShown()
            BottomNav.Tab.SETTINGS -> settings.onShown()
        }
    }

    fun startDownloadService() {
        val i = Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    fun showDownloadsTab() {
        show(BottomNav.Tab.DOWNLOADS)
    }

    fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        if (App.instance.settings.load().clipboardDetect) {
            App.instance.clipboard.start(clipboardListener)
        }
        home.refresh()
        downloads.refresh()
        history.refresh()
    }

    override fun onPause() {
        super.onPause()
        App.instance.clipboard.stop()
    }

    override fun onDestroy() {
        engine.listener = null
        super.onDestroy()
    }
}
