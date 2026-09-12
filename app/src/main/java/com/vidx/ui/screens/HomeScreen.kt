package com.vidx.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vidx.app.App
import com.vidx.app.BrandConfig
import com.vidx.app.MainActivity
import com.vidx.core.download.Destination
import com.vidx.core.download.DownloadTask
import com.vidx.core.download.TaskStatus
import com.vidx.core.model.Platform
import com.vidx.core.platforms.toUserMessage
import com.vidx.core.model.VideoMetadata
import com.vidx.core.platforms.PlatformRegistry
import com.vidx.core.util.ByteFmt
import com.vidx.core.util.Outcome
import com.vidx.core.util.TimeFmt
import com.vidx.ui.components.Chip
import com.vidx.ui.components.verticalPad
import com.vidx.ui.components.GlassCard
import com.vidx.ui.components.GhostButton
import com.vidx.ui.components.GradientButton
import com.vidx.ui.components.Ui
import com.vidx.ui.theme.ThemeEngine
import java.util.concurrent.Executors

/**
 * Home screen — premium dashboard.
 *
 *  - Brand header
 *  - Smart clipboard banner (detect / already-in-queue / already-downloaded)
 *  - Main URL card: Paste → Analyze → full metadata (thumbnail, title, platform,
 *    duration, qualities actually available, estimated size, video/audio options)
 *  - Multi-URL queue panels (URL #1, URL #2, … dynamically expandable, ≥20 OK)
 *    with Previous / Next / Add URL / Remove — no confusing scrolling
 *  - Prominent DOWNLOAD ALL
 *  - Recent downloads
 */
class HomeScreen(context: Context) : ScrollView(context) {

    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var clipboardBanner: LinearLayout? = null
    private var clipboardTitle: TextView? = null
    private var clipboardAdd: GradientButton? = null
    private var clipboardAction2: GhostButton? = null

    private lateinit var urlInput: EditText
    private var analysisCard: LinearLayout? = null
    private var analysisThumb: ImageView? = null
    private var analysisTitle: TextView? = null
    private var analysisMeta: TextView? = null
    private var analysisQualities: LinearLayout? = null
    private var analysisNote: TextView? = null
    private var analysisAdd: GradientButton? = null
    private var analysisOpen: GhostButton? = null
    private var lastAnalyzedUrl: String? = null
    private var lastMetadata: VideoMetadata? = null

    private var panelsHost: FrameLayout? = null
    private var pageIndicator: TextView? = null
    private var panelIndex = 0
    private var panelCard: LinearLayout? = null
    private var recentHost: LinearLayout? = null
    private var downloadAll: GradientButton? = null
    private var queueHeader: TextView? = null

    /** Set by MainActivity when another app shares a video link. */
    var pendingSharedUrl: String? = null

    private val analyzeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vidx-analyze").apply { isDaemon = true }
    }

    init {
        isFillViewport = true
        clipToPadding = false
        content.verticalPad(context)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildHeader()
        buildClipboardBanner()
        buildUrlCard()
        buildQueueSection()
        buildRecentSection()
    }

    // ------------------------------------------------------------------ build

    private fun buildHeader() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(context).apply {
            setImageResource(com.vidx.app.R.drawable.ic_launcher_foreground)
            val s = Ui.dp(context, 46f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            contentDescription = "${BrandConfig.appName} logo"
        }
        val nameCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 12f)
            setPadding(p, 0, 0, 0)
        }
        nameCol.addView(Ui.title(context, BrandConfig.appName, 26f))
        nameCol.addView(Ui.subtitle(context, BrandConfig.tagline, 12f))
        row.addView(logo)
        row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
    }

    private fun buildClipboardBanner() {
        clipboardBanner = GlassCard(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = ThemeEngine.glassBackground(radius = 18f, fillAlpha = 0x18, borderAlpha = 0x2E)
        }
        clipboardTitle = Ui.text(context, 13.5f, BrandConfig.colorTextPrimary).apply {
            text = "Video link detected"
        }
        clipboardAdd = GradientButton(context, ThemeEngine.secondaryGradient()).apply {
            text = "Add to Queue"
            setOnClickListener { }
        }
        clipboardAction2 = GhostButton(context).apply { visibility = View.GONE }
        (clipboardBanner as LinearLayout).addView(clipboardTitle)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val p = Ui.dp(context, 6f)
            setPadding(0, p, 0, 0)
        }
        row.addView(clipboardAction2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        row.addView(clipboardAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        (clipboardBanner as LinearLayout).addView(row)
        content.addView(clipboardBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 16f) })
    }

    private fun buildUrlCard() {
        val card = GlassCard(context)
        card.addView(Ui.title(context, "Paste Video URL", 15f))

        urlInput = EditText(context).apply {
            hint = "https://…"
            setHintTextColor(BrandConfig.colorTextTertiary)
            setTextColor(BrandConfig.colorTextPrimary)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 3
            background = ThemeEngine.glassBackground(radius = 14f, fillAlpha = 0x0A, borderAlpha = 0x18)
            val p = Ui.dp(context, 12f)
            setPadding(p, p, p, p)
            setSelectAllOnFocus(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = "Video URL input"
        }
        card.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val paste = GhostButton(context).apply {
            text = "Paste"
            setOnClickListener {
                Ui.haptic(this)
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!clip.isNullOrBlank()) {
                    urlInput.setText(clip.trim())
                    urlInput.setSelection(urlInput.text.length)
                } else Ui.toast(context, "Clipboard is empty")
            }
        }
        val analyze = GradientButton(context, ThemeEngine.primaryGradient()).apply {
            text = "Analyze"
            setOnClickListener {
                Ui.haptic(this)
                analyzeInput()
            }
        }
        buttons.addView(paste, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        buttons.addView(analyze, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })

        // --- analysis result (hidden until Analyze succeeds)
        analysisCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        analysisThumb = ImageView(context).apply {
            val s = Ui.dp(context, 88f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeEngine.glassBackground(radius = 12f, fillAlpha = 0x08, borderAlpha = 0x14)
            contentDescription = "Video thumbnail"
        }
        analysisTitle = Ui.text(context, 14f, BrandConfig.colorTextPrimary, Typeface.BOLD)
        analysisMeta = Ui.text(context, 12f, BrandConfig.colorTextSecondary)
        analysisQualities = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        analysisNote = Ui.text(context, 12f, BrandConfig.colorAmber)
        analysisAdd = GradientButton(context, ThemeEngine.primaryGradient()).apply { text = "Add to Queue" }
        analysisOpen = GhostButton(context).apply { text = "Open in Browser" }

        val ac = analysisCard as LinearLayout
        val headRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 10f)
            setPadding(p, 0, 0, 0)
        }
        titleCol.addView(analysisTitle)
        titleCol.addView(analysisMeta)
        headRow.addView(analysisThumb)
        headRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ac.addView(headRow)
        ac.addView(analysisQualities, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
        ac.addView(analysisNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(analysisOpen, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        actionRow.addView(analysisAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ac.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })
        card.addView(ac, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })

        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 16f) })
    }

    private fun buildQueueSection() {
        queueHeader = Ui.title(context, "Queue", 15f)
        content.addView(queueHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 22f) })

        pageIndicator = Ui.text(context, 12f, BrandConfig.colorTextSecondary, gravity = Gravity.END)
        content.addView(pageIndicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        panelsHost = FrameLayout(context)
        content.addView(panelsHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })

        downloadAll = GradientButton(context, ThemeEngine.primaryGradient()).apply {
            text = "DOWNLOAD ALL"
            textSize = 16f
            setOnClickListener {
                Ui.haptic(this)
                onDownloadAll()
            }
        }
        content.addView(downloadAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 56f)).apply { topMargin = Ui.dp(context, 14f) })
    }

    private fun buildRecentSection() {
        content.addView(Ui.title(context, "Recent downloads", 15f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 22f) })
        recentHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recentHost)
    }

    // ------------------------------------------------------------------ behaviour

    fun onShown() {
        pendingSharedUrl?.let {
            urlInput.setText(it)
            urlInput.setSelection(urlInput.text.length)
            pendingSharedUrl = null
        }
        refresh()
    }

    fun refresh() {
        renderPanels()
        renderRecent()
        updateDownloadAll()
    }

    private fun onDownloadAll() {
        val activity = context as? MainActivity ?: return
        val queue = App.instance.engine.snapshot()
        val pending = queue.filter { it.status in setOf(TaskStatus.QUEUED, TaskStatus.ANALYZING, TaskStatus.FAILED, TaskStatus.PAUSED) }
        if (pending.isEmpty()) {
            Ui.toast(context, "Nothing to download — add video URLs first")
            return
        }
        if (pending.any { it.status == TaskStatus.FAILED }) {
            App.instance.engine.retryAllFailed()
        }
        activity.maybeRequestNotificationPermission()
        activity.startDownloadService()
        App.instance.engine.startAll()
        Ui.toast(context, "Downloading ${pending.size} video${if (pending.size > 1) "s" else ""}…")
        (context as MainActivity).let { act ->
            act.findViewById<View>(android.R.id.content)?.let {}
        }
    }

    private fun analyzeInput() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) {
            Ui.toast(context, "Enter a video URL first")
            return
        }
        val validation = com.vidx.core.url.UrlValidator.validate(raw)
        if (validation != null) {
            Ui.toast(context, validation.message, long = true)
            return
        }
        showAnalysisLoading()
        analyzeExecutor.execute {
            val result = PlatformRegistry.analyze(raw)
            Ui.mainHandler.post {
                when (result) {
                    is Outcome.Ok -> showAnalysisResult(raw, result.value)
                    is Outcome.Err -> {
                        hideAnalysis()
                        Ui.toast(context, result.toUserMessage(), long = true)
                    }
                }
            }
        }
    }

    private fun showAnalysisLoading() {
        analysisCard?.visibility = View.VISIBLE
        analysisQualities?.removeAllViews()
        analysisNote?.visibility = View.GONE
        analysisTitle?.text = "Analyzing…"
        analysisMeta?.text = ""
        analysisThumb?.setImageDrawable(null)
        analysisAdd?.visibility = View.GONE
        analysisOpen?.visibility = View.GONE
    }

    private fun showAnalysisResult(url: String, meta: VideoMetadata) {
        lastAnalyzedUrl = url
        lastMetadata = meta
        analysisCard?.visibility = View.VISIBLE
        analysisTitle?.text = meta.title ?: "Metadata unavailable"
        analysisMeta?.text = buildString {
            append(meta.platform.displayName)
            meta.author?.let { append("  •  ").append(it) }
            meta.durationMillis?.let { append("  •  ").append(TimeFmt.clock(it)) }
        }
        if (meta.thumbnailUrl != null) {
            App.instance.thumbs.get(meta.thumbnailUrl) { bmp ->
                if (bmp != null) Ui.mainHandler.post { analysisThumb?.setImageBitmap(bmp) }
            }
        }

        // Qualities actually available from the source — never invented.
        val q = analysisQualities ?: return
        q.removeAllViews()
        val formats = meta.videoFormats + meta.audioFormats
        if (formats.isNotEmpty()) {
            q.addView(Ui.subtitle(context, "Available qualities", 11.5f))
            val chipsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.VISIBLE }
            val scroll = HorizontalScrollView(context)
            scroll.addView(chipsRow)
            for (f in formats) {
                val chip = Chip(context, ThemeEngine.accentColor(), f.qualityLabel)
                chip.setOnClickListener { }
                chipsRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(context, 6f) })
            }
            q.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 6f) })
            val size = formats.firstOrNull { it.fileSize != null }?.fileSize
            if (size != null) {
                q.addView(Ui.subtitle(context, "Estimated size: ${ByteFmt.human(size)}", 11.5f))
            }
        }
        analysisNote?.let { n ->
            if (meta.downloadNote != null) {
                n.text = meta.downloadNote
                n.visibility = View.VISIBLE
                analysisAdd?.visibility = View.GONE
            } else {
                n.visibility = View.GONE
                analysisAdd?.visibility = View.VISIBLE
                analysisAdd?.setOnClickListener {
                    Ui.haptic(it)
                    addAnalyzedToQueue()
                }
            }
        }
        analysisOpen?.visibility = View.VISIBLE
        analysisOpen?.setOnClickListener {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                Ui.toast(context, "No browser available")
            }
        }
    }

    private fun addAnalyzedToQueue() {
        val url = lastAnalyzedUrl ?: return
        val activity = context as? MainActivity ?: return
        val result = App.instance.engine.addUrl(url)
        when (result) {
            is com.vidx.platform.download.DownloadEngine.AddUrlResult.Added -> {
                Ui.toast(context, "Added to queue")
                hideAnalysis()
                urlInput.setText("")
                refresh()
            }
            is com.vidx.platform.download.DownloadEngine.AddUrlResult.Duplicate -> {
                Ui.toast(context, "Duplicate video — already in queue")
                hideAnalysis()
                refresh()
            }
            is com.vidx.platform.download.DownloadEngine.AddUrlResult.Invalid -> {
                Ui.toast(context, result.message, long = true)
            }
        }
        activity.maybeRequestNotificationPermission()
    }

    private fun hideAnalysis() {
        analysisCard?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ clipboard

    fun onClipboardLink(url: String, platform: Platform, repeat: Boolean) {
        val engine = App.instance.engine
        val key = com.vidx.core.url.UrlNormalizer.normalize(url).canonicalKey
        val existing = engine.snapshot().firstOrNull { it.canonicalKey == key }

        clipboardBanner?.visibility = View.VISIBLE
        clipboardAction2?.visibility = View.VISIBLE
        when {
            existing?.status == TaskStatus.COMPLETED -> {
                clipboardTitle?.text = "Already downloaded"
                clipboardAdd?.text = "Download Again"
                clipboardAdd?.setOnClickListener {
                    Ui.haptic(it)
                    engine.redownload(existing.id)
                    Ui.toast(context, "Added to queue again")
                    refresh()
                }
                clipboardAction2?.text = "View file"
                clipboardAction2?.setOnClickListener {
                    Ui.haptic(it)
                    (context as? MainActivity)?.showDownloadsTab()
                }
            }
            existing != null -> {
                clipboardTitle?.text = if (repeat) "Already in queue" else "Video link detected"
                clipboardAdd?.text = "Go to Existing"
                clipboardAdd?.setOnClickListener {
                    Ui.haptic(it)
                    (context as? MainActivity)?.showDownloadsTab()
                }
                clipboardAction2?.text = "Remove Duplicate"
                clipboardAction2?.setOnClickListener {
                    Ui.haptic(it)
                    engine.remove(existing.id)
                    clipboardBanner?.visibility = View.GONE
                    refresh()
                }
            }
            else -> {
                clipboardTitle?.text = if (repeat) "Video link detected" else "Video link detected"
                val settings = App.instance.settings.load()
                clipboardAdd?.text = "Add to Queue"
                clipboardAdd?.setOnClickListener {
                    Ui.haptic(it)
                    when (val r = engine.addUrl(url)) {
                        is com.vidx.platform.download.DownloadEngine.AddUrlResult.Added -> Ui.toast(context, "Added to queue")
                        is com.vidx.platform.download.DownloadEngine.AddUrlResult.Duplicate -> Ui.toast(context, "Already in queue")
                        is com.vidx.platform.download.DownloadEngine.AddUrlResult.Invalid -> Ui.toast(context, r.message, long = true)
                    }
                    clipboardBanner?.visibility = View.GONE
                    refresh()
                }
                clipboardAction2?.text = "Dismiss"
                clipboardAction2?.setOnClickListener {
                    Ui.haptic(it)
                    clipboardBanner?.visibility = View.GONE
                }
                if (settings.clipboardAutoAdd) {
                    clipboardAdd?.performClick()
                }
            }
        }
        (clipboardBanner as? LinearLayout)?.animate()?.alpha(0f)?.alpha(1f)?.setDuration(250)?.start()
    }

    // ------------------------------------------------------------------ panels

    private fun renderPanels() {
        val host = panelsHost ?: return
        val tasks = App.instance.engine.snapshot()
        if (panelIndex >= tasks.size) panelIndex = (tasks.size - 1).coerceAtLeast(0)

        host.removeAllViews()
        pageIndicator?.text = if (tasks.isEmpty()) "No videos in queue" else "Panel ${panelIndex + 1} / ${tasks.size}"

        if (tasks.isEmpty()) {
            val empty = GlassCard(context).apply {
                val t = Ui.subtitle(context, "Queue is empty.\nPaste a URL and press Analyze — then add videos.\nYou can queue 20, 50 or more.", 13f)
                t.gravity = Gravity.CENTER
                addView(t)
            }
            host.addView(empty)
            return
        }

        val task = tasks[panelIndex]
        host.addView(buildPanel(task))
    }

    private fun buildPanel(task: DownloadTask): View {
        val card = GlassCard(context)
        card.tag = task.id

        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = Ui.text(context, 15f, BrandConfig.colorTextPrimary, Typeface.BOLD).apply {
            text = "URL #${panelIndex + 1}"
        }
        val platformChip = Chip(context, ThemeEngine.accentColor(), platformName(task.platformId))
        val statusChip = Chip(context, statusColor(task.status), statusLabel(task.status))
        head.addView(title)
        head.addView(platformChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 8f) })
        head.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 6f) })
        card.addView(head)

        // body: thumbnail + info
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val thumb = ImageView(context).apply {
            val s = Ui.dp(context, 64f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeEngine.glassBackground(radius = 12f, fillAlpha = 0x08, borderAlpha = 0x14)
            contentDescription = "Video thumbnail"
        }
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 10f)
            setPadding(p, 0, 0, 0)
        }
        val tTitle = Ui.text(context, 13.5f, BrandConfig.colorTextPrimary, Typeface.BOLD).apply {
            text = task.title ?: "Analyzing…"
            maxLines = 2
        }
        val tMeta = Ui.text(context, 11.5f, BrandConfig.colorTextSecondary).apply {
            text = buildString {
                append(platformName(task.platformId))
                task.durationMillis?.let { append("  •  ").append(TimeFmt.clock(it)) }
                task.selectedQualityLabel?.let { append("  •  ").append(it) }
            }
        }
        val tErr = Ui.text(context, 11.5f, BrandConfig.colorDanger).apply {
            text = task.errorMessage ?: ""
            visibility = if (task.status == TaskStatus.FAILED || task.status == TaskStatus.UNSUPPORTED) View.VISIBLE else View.GONE
            maxLines = 3
        }
        infoCol.addView(tTitle)
        infoCol.addView(tMeta)
        infoCol.addView(tErr)
        body.addView(thumb)
        body.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })

        if (task.thumbnailUrl != null) {
            App.instance.thumbs.get(task.thumbnailUrl) { bmp ->
                if (bmp != null) Ui.mainHandler.post {
                    if ((card.tag as String) == task.id) thumb.setImageBitmap(bmp)
                }
            }
        }

        // quality selector — only formats actually available
        val formats = App.instance.engine.availableFormats(task.id)
        if (formats.isNotEmpty()) {
            val qLabel = Ui.subtitle(context, "Quality", 11.5f)
            card.addView(qLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
            val chipsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val scroll = HorizontalScrollView(context)
            scroll.addView(chipsRow)
            for (f in formats) {
                val selected = f.id == task.formatId
                val chip = Chip(
                    context,
                    if (selected) Color.WHITE else ThemeEngine.accentColor(),
                    f.qualityLabel + (f.fileSize?.let { " · ${ByteFmt.human(it)}" } ?: ""),
                )
                chip.background = if (selected) ThemeEngine.pillBackground(ThemeEngine.accentColor()) else ThemeEngine.pillBackground(ThemeEngine.accentSoft())
                chip.setOnClickListener {
                    Ui.haptic(it)
                    App.instance.engine.selectFormat(task.id, f.id)
                    renderPanels()
                }
                chipsRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(context, 6f) })
            }
            card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 6f) })
        }

        // destination selector
        val destLabel = Ui.subtitle(context, "Save to", 11.5f)
        card.addView(destLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
        val destRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in Destination.entries) {
            val selected = d == task.destination
            val chip = Chip(context, if (selected) Color.WHITE else BrandConfig.colorTextSecondary, d.label)
            chip.background = if (selected) ThemeEngine.pillBackground(ThemeEngine.accentColor()) else ThemeEngine.pillBackground(ThemeEngine.withAlpha(BrandConfig.colorTextSecondary, 0.14f))
            chip.setOnClickListener {
                Ui.haptic(it)
                App.instance.engine.setDestination(task.id, d)
                renderPanels()
            }
            destRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(context, 6f) })
        }
        card.addView(destRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 6f) })

        // panel navigation: Previous / Next / Add URL / Remove
        val navRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = GhostButton(context).apply {
            text = "Previous"
            setOnClickListener {
                Ui.haptic(it)
                stepPanel(-1)
            }
        }
        val next = GradientButton(context, ThemeEngine.secondaryGradient()).apply {
            text = "Next"
            setOnClickListener {
                Ui.haptic(it)
                stepPanel(1)
            }
        }
        navRow.addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        navRow.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })

        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val add = GhostButton(context).apply {
            text = "+ Add URL"
            setOnClickListener {
                Ui.haptic(it)
                focusInput()
            }
        }
        val remove = GhostButton(context).apply {
            text = "Remove"
            setTextColor(BrandConfig.colorDanger)
            setOnClickListener {
                Ui.haptic(it)
                App.instance.engine.remove(task.id)
                Ui.toast(context, "Removed from queue")
                panelIndex = panelIndex.coerceAtMost((App.instance.engine.snapshot().size - 1).coerceAtLeast(0))
                renderPanels()
            }
        }
        actionRow.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        actionRow.addView(remove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })

        return card
    }

    private fun stepPanel(delta: Int) {
        val size = App.instance.engine.snapshot().size
        if (size == 0) return
        panelIndex = (panelIndex + delta + size) % size
        renderPanels()
    }

    private fun focusInput() {
        urlInput.requestFocus()
        Ui.toast(context, "Paste the next video URL above and press Analyze")
    }

    private fun updateDownloadAll() {
        val s = App.instance.engine.summary()
        val pending = s.queued + s.paused + s.failed
        downloadAll?.text = if (s.downloading > 0) {
            "DOWNLOADING ${s.downloading} / ${s.total}"
        } else if (pending > 0) {
            "DOWNLOAD ALL ($pending)"
        } else {
            "DOWNLOAD ALL"
        }
        queueHeader?.text = if (s.total > 0) "Queue (${s.total} video${if (s.total > 1) "s" else ""})" else "Queue"
    }

    private fun renderRecent() {
        val host = recentHost ?: return
        host.removeAllViews()
        val entries = App.instance.history.list(com.vidx.platform.storage.HistoryDb.Filter.COMPLETED, 3)
        if (entries.isEmpty()) {
            val empty = Ui.subtitle(context, "Nothing downloaded yet — your finished videos will appear here.", 12.5f)
            host.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })
            return
        }
        for (e in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = Ui.dp(context, 8f)
                setPadding(0, p, 0, p)
                setOnClickListener {
                    Ui.haptic(this)
                    (context as? MainActivity)?.showDownloadsTab()
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(if (e.destinationId == "audio") com.vidx.app.R.drawable.ic_vid else com.vidx.app.R.drawable.ic_vid)
                imageTintList = android.content.res.ColorStateList.valueOf(ThemeEngine.accentColor())
                val s = Ui.dp(context, 20f)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = Ui.dp(context, 10f)
                setPadding(p, 0, 0, 0)
            }
            col.addView(Ui.text(context, 13f, BrandConfig.colorTextPrimary, Typeface.BOLD).apply {
                text = e.title
                maxLines = 1
            })
            col.addView(Ui.subtitle(context, "${platformName(e.platformId)}  •  ${ByteFmt.human(e.sizeBytes)}", 11f))
            row.addView(icon)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            host.addView(row)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun platformName(id: String): String = com.vidx.core.model.Platform.fromId(id).displayName

    private fun statusColor(status: TaskStatus): Int = when (status) {
        TaskStatus.COMPLETED -> BrandConfig.colorGreen
        TaskStatus.FAILED, TaskStatus.UNSUPPORTED -> BrandConfig.colorDanger
        TaskStatus.DOWNLOADING -> ThemeEngine.accentColor()
        TaskStatus.PAUSED -> BrandConfig.colorAmber
        TaskStatus.CANCELLED -> BrandConfig.colorTextTertiary
        else -> BrandConfig.colorTextSecondary
    }

    private fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.QUEUED -> "Queued"
        TaskStatus.ANALYZING -> "Analyzing"
        TaskStatus.DOWNLOADING -> "Downloading"
        TaskStatus.PAUSED -> "Paused"
        TaskStatus.COMPLETED -> "Completed"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.CANCELLED -> "Cancelled"
        TaskStatus.DUPLICATE -> "Duplicate"
        TaskStatus.UNSUPPORTED -> "Unsupported"
    }
}
