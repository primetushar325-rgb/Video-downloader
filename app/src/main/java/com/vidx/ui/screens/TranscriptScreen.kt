package com.vidx.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.vidx.app.BrandConfig
import com.vidx.core.transcript.TranscriptEngine
import com.vidx.core.transcript.TranscriptLanguages
import com.vidx.core.transcript.TranscriptResult
import com.vidx.core.transcript.TranscriptTrack
import com.vidx.core.platforms.toUserMessage
import com.vidx.core.util.Outcome
import com.vidx.core.util.TimeFmt
import com.vidx.ui.components.Chip
import com.vidx.ui.components.GhostButton
import com.vidx.ui.components.GradientButton
import com.vidx.ui.components.verticalPad
import com.vidx.ui.components.Ui
import com.vidx.ui.theme.ThemeEngine
import java.util.concurrent.Executors

/**
 * Transcript screen.
 *
 *  - Paste a video URL and fetch its transcript (captions)
 *  - Track/language selection when the source offers several
 *  - Timestamped segments, toggle timestamps, copy, export
 *  - Honest states: no transcript available, fetching, failure
 */
class TranscriptScreen(context: Context) : android.widget.LinearLayout(context) {

    private lateinit var urlInput: EditText
    private lateinit var fetchButton: GradientButton
    private lateinit var statusText: TextView
    private lateinit var tracksHost: HorizontalScrollView
    private var segmentsList: ListView? = null
    private var segmentsAdapter: SegmentAdapter? = null
    private var timestampsToggle: CheckBox? = null
    private var exportButton: GhostButton? = null
    private var copyButton: GhostButton? = null

    private var currentResult: TranscriptResult? = null
    private var currentTracks: List<TranscriptTrack> = emptyList()
    private var selectedTrack: TranscriptTrack? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vidx-transcript").apply { isDaemon = true }
    }

    init {
        orientation = LinearLayout.VERTICAL
        verticalPad(context)
        addView(Ui.title(context, "Transcript", 22f))
        addView(Ui.subtitle(context, "Get the full text of any supported video from its captions.", 12.5f).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) }
        })

        urlInput = EditText(context).apply {
            hint = "Video URL"
            setHintTextColor(BrandConfig.colorTextTertiary)
            setTextColor(BrandConfig.colorTextPrimary)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 2
            background = ThemeEngine.glassBackground(radius = 14f, fillAlpha = 0x0A, borderAlpha = 0x18)
            val p = Ui.dp(context, 12f)
            setPadding(p, p, p, p)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = "Video URL for transcript"
        }
        addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 14f) })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val paste = GhostButton(context).apply {
            text = "Paste"
            setOnClickListener {
                Ui.haptic(this)
                val cm = context.getSystemService(ClipboardManager::class.java)
                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!clip.isNullOrBlank()) {
                    urlInput.setText(clip.trim())
                    urlInput.setSelection(urlInput.text.length)
                } else Ui.toast(context, "Clipboard is empty")
            }
        }
        fetchButton = GradientButton(context, ThemeEngine.primaryGradient()).apply {
            text = "Get Transcript"
            setOnClickListener {
                Ui.haptic(this)
                fetch()
            }
        }
        row.addView(paste, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        row.addView(fetchButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })

        statusText = Ui.text(context, 12.5f, BrandConfig.colorTextSecondary)
        addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })

        tracksHost = HorizontalScrollView(context)
        addView(tracksHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })

        // toolbar: timestamps toggle / copy / export
        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timestampsToggle = CheckBox(context).apply {
            text = "Timestamps"
            setTextColor(BrandConfig.colorTextPrimary)
            textSize = 12.5f
            isChecked = true
            buttonTintList = android.content.res.ColorStateList.valueOf(ThemeEngine.accentColor())
            setOnClickListener { segmentsAdapter?.notifyDataSetChanged() }
        }
        copyButton = GhostButton(context).apply {
            text = "Copy"
            setOnClickListener {
                Ui.haptic(this)
                currentResult?.let { copyToClipboard(if (timestampsToggle?.isChecked == true) it.withTimestamps() else it.withoutTimestamps()) }
            }
        }
        exportButton = GhostButton(context).apply {
            text = "Export"
            setOnClickListener {
                Ui.haptic(this)
                currentResult?.let { export(if (timestampsToggle?.isChecked == true) it.withTimestamps() else it.withoutTimestamps()) }
            }
        }
        tools.addView(timestampsToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(copyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 6f) })
        tools.addView(exportButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })

        segmentsList = ListView(context).apply {
            divider = null
            dividerHeight = 0
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 90f))
        }
        segmentsAdapter = SegmentAdapter()
        segmentsList?.adapter = segmentsAdapter
        addView(segmentsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(context, 4f) })
    }

    fun onShown() = Unit

    // ------------------------------------------------------------------ fetch

    private fun fetch() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) {
            Ui.toast(context, "Enter a video URL first")
            return
        }
        setStatus("Finding captions…")
        currentResult = null
        currentTracks = emptyList()
        selectedTrack = null
        segmentsAdapter?.notifyDataSetChanged()
        executor.execute {
            when (val tracks = TranscriptEngine.listTracks(raw)) {
                is Outcome.Ok -> {
                    val list = tracks.value
                    if (list.isEmpty()) {
                        Ui.mainHandler.post { setStatus("No transcript available for this video.") }
                    } else {
                        val preferred = com.vidx.app.App.instance.settings.load().transcriptPreferredLanguage
                        val pick = if (preferred.isNotEmpty()) {
                            list.firstOrNull { it.langCode.startsWith(preferred, ignoreCase = true) } ?: list.first()
                        } else {
                            list.firstOrNull { !it.autoGenerated } ?: list.first()
                        }
                        Ui.mainHandler.post {
                            currentTracks = list
                            selectedTrack = pick
                            renderTracks()
                            fetchTrack(pick)
                        }
                    }
                }
                is Outcome.Err -> Ui.mainHandler.post {
                    setStatus(tracks.toUserMessage())
                }
            }
        }
    }

    private fun fetchTrack(track: TranscriptTrack) {
        setStatus("Fetching ${TranscriptLanguages.label(track.langCode)} transcript…")
        executor.execute {
            when (val res = TranscriptEngine.fetchTrack(track)) {
                is Outcome.Ok -> Ui.mainHandler.post {
                    currentResult = res.value
                    segmentsAdapter?.notifyDataSetChanged()
                    segmentsList?.setSelection(0)
                    setStatus(
                        "${res.value.segments.size} segments • ${TranscriptLanguages.label(track.langCode)}" +
                            (if (track.autoGenerated) " • auto-generated" else "") +
                            (res.value.confidenceNote?.let { "\n$it" } ?: "")
                    )
                }
                is Outcome.Err -> Ui.mainHandler.post {
                    setStatus(res.toUserMessage())
                }
            }
        }
    }

    private fun renderTracks() {
        val host = tracksHost
        host.removeAllViews()
        val row = android.widget.LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (track in currentTracks) {
            val selected = track.id == selectedTrack?.id
            val chip = Chip(
                context,
                if (selected) android.graphics.Color.WHITE else ThemeEngine.accentColor(),
                TranscriptLanguages.label(track.langCode) + (if (track.autoGenerated) " (auto)" else ""),
            )
            chip.background = if (selected) ThemeEngine.pillBackground(ThemeEngine.accentColor()) else ThemeEngine.pillBackground(ThemeEngine.accentSoft())
            chip.setOnClickListener {
                Ui.haptic(it)
                selectedTrack = track
                renderTracks()
                fetchTrack(track)
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(context, 6f) })
        }
        host.addView(row)
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    // ------------------------------------------------------------------ copy / export

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("VIDX transcript", text))
        Ui.toast(context, "Transcript copied")
    }

    private fun export(text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "VIDX transcript")
            }
            context.startActivity(Intent.createChooser(intent, "Export transcript"))
        } catch (e: Exception) {
            copyToClipboard(text)
        }
    }

    // ------------------------------------------------------------------ segments adapter

    private inner class SegmentAdapter : BaseAdapter() {
        override fun getCount(): Int = currentResult?.segments?.size ?: 0
        override fun getItem(position: Int) = currentResult?.segments?.get(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: SegHolder
            val row: android.widget.LinearLayout
            if (convertView is android.widget.LinearLayout && convertView.tag is SegHolder) {
                row = convertView
                holder = convertView.tag as SegHolder
            } else {
                row = android.widget.LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = Ui.dp(context, 12f)
                    setPadding(p, p, p, p)
                    background = ThemeEngine.glassBackground(radius = 14f, fillAlpha = 0x0A, borderAlpha = 0x12)
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = Ui.dp(context, 8f)
                }
                val stamp = Ui.text(context, 11f, ThemeEngine.accentColor(), Typeface.BOLD)
                val body = Ui.text(context, 13.5f, BrandConfig.colorTextPrimary)
                row.addView(stamp)
                row.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) })
                holder = SegHolder(stamp, body)
                row.tag = holder
            }
            val segment = currentResult!!.segments[position]
            val showStamps = timestampsToggle?.isChecked == true
            holder.stamp.visibility = if (showStamps) View.VISIBLE else View.GONE
            holder.stamp.text = TimeFmt.transcriptStamp(segment.startMs)
            holder.body.text = segment.text.trim()
            return row
        }
    }

    private class SegHolder(val stamp: TextView, val body: TextView)
}
