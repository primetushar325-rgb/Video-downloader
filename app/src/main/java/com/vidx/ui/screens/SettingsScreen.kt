package com.vidx.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vidx.app.App
import com.vidx.app.BrandConfig
import com.vidx.core.settings.AccentTheme
import com.vidx.core.settings.AppSettings
import com.vidx.core.settings.DayNightMode
import com.vidx.core.util.ByteFmt
import com.vidx.ui.components.Chip
import com.vidx.ui.components.GhostButton
import com.vidx.ui.components.Ui
import com.vidx.ui.components.verticalPad
import com.vidx.ui.theme.ThemeEngine

/**
 * Settings screen — every V2/V3 option, persisted immediately.
 *
 * Sections: Downloads, Network, Clipboard, Notifications, Appearance,
 * Transcript, Storage & Privacy, About.
 */
class SettingsScreen(context: Context) : ScrollView(context) {

    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var s: AppSettings = AppSettings.DEFAULT

    init {
        isFillViewport = true
        content.verticalPad(context)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.title(context, "Settings", 22f))
    }

    fun onShown() {
        s = App.instance.settings.load()
        rebuild()
    }

    private fun rebuild() {
        // keep the header, drop the rest
        while (content.childCount > 1) content.removeViewAt(1)

        section("Downloads")
        choiceRow(
            "Default quality",
            listOf("best", "2160p", "1440p", "1080p", "720p", "480p", "360p"),
            s.defaultQuality,
        ) { v -> save(s.copy(defaultQuality = v)) }
        choiceRow("Preferred container", listOf("mp4", "webm"), s.defaultFormat) { v -> save(s.copy(defaultFormat = v)) }
        toggleRow("Start downloads automatically", "Begin downloading as soon as videos are added", s.autoStartQueue) { v -> save(s.copy(autoStartQueue = v)) }
        choiceRow("Simultaneous downloads", listOf("1", "2", "3"), s.maxConcurrent.toString()) { v -> save(s.copy(maxConcurrent = v.toInt())) }
        choiceRow("Auto retry attempts", listOf("0", "1", "2", "3", "5"), s.autoRetryCount.toString()) { v -> save(s.copy(autoRetryCount = v.toInt())) }
        toggleRow("Retry failed after all finish", null, s.retryFailedAfterAll) { v -> save(s.copy(retryFailedAfterAll = v)) }

        section("Network")
        toggleRow("Wi-Fi only", "Downloads wait for Wi-Fi when enabled", s.wifiOnly) { v -> save(s.copy(wifiOnly = v)) }
        toggleRow("Warn before mobile data", null, s.warnOnMobileData) { v -> save(s.copy(warnOnMobileData = v)) }
        toggleRow("Allow cellular downloads", null, s.allowCellular) { v -> save(s.copy(allowCellular = v)) }

        section("Clipboard")
        toggleRow("Detect copied video links", "A smart banner appears on Home when you copy a link", s.clipboardDetect) { v -> save(s.copy(clipboardDetect = v)) }
        toggleRow("Add clipboard links automatically", "Off = always ask first (privacy-safe)", s.clipboardAutoAdd) { v -> save(s.copy(clipboardAutoAdd = v)) }

        section("Notifications")
        toggleRow("Download progress", null, s.notifyProgress) { v -> save(s.copy(notifyProgress = v)) }
        toggleRow("Completion alerts", null, s.notifyCompleted) { v -> save(s.copy(notifyCompleted = v)) }
        toggleRow("Failure alerts", null, s.notifyFailed) { v -> save(s.copy(notifyFailed = v)) }

        section("Appearance")
        choiceRow("Accent color", AccentTheme.entries.map { it.id }, s.accentTheme.id) { v ->
            save(s.copy(accentTheme = AccentTheme.fromId(v)))
            ThemeEngine.setAccent(AccentTheme.fromId(v))
        }
        choiceRow("Theme", DayNightMode.entries.map { it.id }, s.dayNight.id) { v ->
            save(s.copy(dayNight = DayNightMode.fromId(v)))
        }
        infoRow("Dark theme is applied by default. 'System' follows the device setting when available.")

        section("Transcript")
        choiceRow("Preferred language", listOf("", "en", "bn", "hi", "ur", "ar", "es", "fr", "de", "pt", "ru", "zh", "ja", "ko", "it", "tr", "id", "ms", "th", "vi", "nl", "pl", "uk", "fa", "he", "ta", "te", "mr", "gu", "pa", "ml", "kn", "sw", "fil"),
            s.transcriptPreferredLanguage,
            labels = { it.ifEmpty { "Auto" }.let { c -> com.vidx.core.transcript.TranscriptLanguages.label(c) + if (it.isNotEmpty()) "" else "" } }
        ) { v -> save(s.copy(transcriptPreferredLanguage = v)) }
        toggleRow("Fetch transcript automatically", "After a download finishes, fetch captions when available", s.transcriptAutoFetch) { v -> save(s.copy(transcriptAutoFetch = v)) }

        section("Storage & Privacy")
        val storage = App.instance.storage.usage()
        infoRow(
            "App storage: ${ByteFmt.human(storage)} used" +
                "\nDownloaded files are saved in ${App.instance.storage.baseDescription()} — visible in your gallery / file manager. " +
                "They are never deleted with your queue or history."
        )
        val clearCache = GhostButton(context).apply {
            text = "Clear Thumbnail Cache"
            setOnClickListener {
                Ui.haptic(this)
                App.instance.thumbs.clearDisk()
                Ui.toast(context, "Thumbnail cache cleared")
            }
        }
        content.addView(clearCache, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
        toggleRow("Share diagnostics", "Include error codes in feedback (never personal data)", s.shareDiagnostics) { v -> save(s.copy(shareDiagnostics = v)) }

        section("About")
        infoRow("${BrandConfig.appName} ${BrandConfig.versionName}\n${BrandConfig.appDescription}")
        infoRow("Open-source. Downloading copyrighted content without permission may be illegal in your jurisdiction — use VIDX only for content you have the right to download.\n\nSupported platforms: " +
            com.vidx.core.platforms.PlatformRegistry.supportedPlatforms().joinToString(", ") { it.displayName } +
            ".\n\nLimitations: YouTube, TikTok and Vimeo do not allow downloads of their media from third-party apps; VIDX offers transcripts and metadata for them instead.")
    }

    private fun save(next: AppSettings) {
        s = next
        App.instance.settings.update { next }
        App.instance.engine.updateSettings(next)
    }

    // ------------------------------------------------------------------ builders

    private fun section(label: String) {
        content.addView(
            Ui.text(context, 12f, BrandConfig.colorTextTertiary, android.graphics.Typeface.BOLD).apply {
                text = label.uppercase()
                letterSpacing = 0.12f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 24f) },
        )
    }

    private fun card(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeEngine.glassBackground(radius = 18f, fillAlpha = 0x0A, borderAlpha = 0x14)
            val p = Ui.dp(context, 14f)
            setPadding(p, p, p, p)
        }

    private fun addCard(c: LinearLayout) {
        content.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
    }

    private fun toggleRow(label: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val c = card()
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(context, 14f, BrandConfig.colorTextPrimary).apply { text = label })
        if (hint != null) {
            col.addView(Ui.subtitle(context, hint, 11.5f).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 2f) }
            })
        }
        val sw = android.widget.Switch(context).apply {
            isChecked = value
            setOnCheckedChangeListener { _, v ->
                Ui.haptic(this)
                onChange(v)
            }
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        c.addView(row)
        addCard(c)
    }

    private fun choiceRow(
        label: String,
        options: List<String>,
        current: String,
        labels: (String) -> String = { it },
        onChange: (String) -> Unit,
    ) {
        val c = card()
        c.addView(Ui.text(context, 14f, BrandConfig.colorTextPrimary).apply { text = label })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = android.widget.HorizontalScrollView(context)
        scroll.addView(chips)
        for (opt in options) {
            val active = opt == current
            val chip = Chip(context, if (active) android.graphics.Color.WHITE else BrandConfig.colorTextSecondary, labels(opt))
            chip.background = if (active) ThemeEngine.pillBackground(ThemeEngine.accentColor()) else ThemeEngine.pillBackground(ThemeEngine.withAlpha(BrandConfig.colorTextSecondary, 0.14f))
            chip.setOnClickListener {
                Ui.haptic(it)
                onChange(opt)
                rebuild()
            }
            chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(context, 6f) })
        }
        c.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })
        addCard(c)
    }

    private fun infoRow(text: String) {
        val c = card()
        c.addView(Ui.subtitle(context, text, 12.5f))
        addCard(c)
    }
}
