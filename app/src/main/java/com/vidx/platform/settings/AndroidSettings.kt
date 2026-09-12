package com.vidx.platform.settings

import android.content.Context
import android.content.SharedPreferences
import com.vidx.core.settings.AccentTheme
import com.vidx.core.settings.AppSettings
import com.vidx.core.settings.DayNightMode

/**
 * SharedPreferences-backed settings store.
 * Values are read once per access; no caching layer that could drift.
 */
class AndroidSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vidx_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        defaultQuality = prefs.getString("default_quality", AppSettings.DEFAULT.defaultQuality) ?: AppSettings.DEFAULT.defaultQuality,
        defaultFormat = prefs.getString("default_format", AppSettings.DEFAULT.defaultFormat) ?: AppSettings.DEFAULT.defaultFormat,
        autoStartQueue = prefs.getBoolean("auto_start_queue", AppSettings.DEFAULT.autoStartQueue),
        maxConcurrent = prefs.getInt("max_concurrent", AppSettings.DEFAULT.maxConcurrent).coerceIn(1, 4),
        autoRetryCount = prefs.getInt("auto_retry_count", AppSettings.DEFAULT.autoRetryCount).coerceIn(0, 10),
        retryFailedAfterAll = prefs.getBoolean("retry_failed_after_all", AppSettings.DEFAULT.retryFailedAfterAll),
        wifiOnly = prefs.getBoolean("wifi_only", AppSettings.DEFAULT.wifiOnly),
        warnOnMobileData = prefs.getBoolean("warn_mobile", AppSettings.DEFAULT.warnOnMobileData),
        allowCellular = prefs.getBoolean("allow_cellular", AppSettings.DEFAULT.allowCellular),
        clipboardDetect = prefs.getBoolean("clipboard_detect", AppSettings.DEFAULT.clipboardDetect),
        clipboardAutoAdd = prefs.getBoolean("clipboard_auto_add", AppSettings.DEFAULT.clipboardAutoAdd),
        clipboardAskBeforeAdd = prefs.getBoolean("clipboard_ask", AppSettings.DEFAULT.clipboardAskBeforeAdd),
        notifyProgress = prefs.getBoolean("notify_progress", AppSettings.DEFAULT.notifyProgress),
        notifyCompleted = prefs.getBoolean("notify_completed", AppSettings.DEFAULT.notifyCompleted),
        notifyFailed = prefs.getBoolean("notify_failed", AppSettings.DEFAULT.notifyFailed),
        accentTheme = AccentTheme.fromId(prefs.getString("accent_theme", null)),
        dayNight = DayNightMode.fromId(prefs.getString("day_night", null)),
        transcriptPreferredLanguage = prefs.getString("transcript_lang", AppSettings.DEFAULT.transcriptPreferredLanguage) ?: "",
        transcriptAutoFetch = prefs.getBoolean("transcript_auto_fetch", AppSettings.DEFAULT.transcriptAutoFetch),
        shareDiagnostics = prefs.getBoolean("share_diagnostics", AppSettings.DEFAULT.shareDiagnostics),
    )

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(load())
        prefs.edit()
            .putString("default_quality", next.defaultQuality)
            .putString("default_format", next.defaultFormat)
            .putBoolean("auto_start_queue", next.autoStartQueue)
            .putInt("max_concurrent", next.maxConcurrent)
            .putInt("auto_retry_count", next.autoRetryCount)
            .putBoolean("retry_failed_after_all", next.retryFailedAfterAll)
            .putBoolean("wifi_only", next.wifiOnly)
            .putBoolean("warn_mobile", next.warnOnMobileData)
            .putBoolean("allow_cellular", next.allowCellular)
            .putBoolean("clipboard_detect", next.clipboardDetect)
            .putBoolean("clipboard_auto_add", next.clipboardAutoAdd)
            .putBoolean("clipboard_ask", next.clipboardAskBeforeAdd)
            .putBoolean("notify_progress", next.notifyProgress)
            .putBoolean("notify_completed", next.notifyCompleted)
            .putBoolean("notify_failed", next.notifyFailed)
            .putString("accent_theme", next.accentTheme.id)
            .putString("day_night", next.dayNight.id)
            .putString("transcript_lang", next.transcriptPreferredLanguage)
            .putBoolean("transcript_auto_fetch", next.transcriptAutoFetch)
            .putBoolean("share_diagnostics", next.shareDiagnostics)
            .apply()
        return next
    }
}
