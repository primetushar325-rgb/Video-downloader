package com.vidx.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.vidx.app.App
import com.vidx.app.BrandConfig
import com.vidx.core.util.ByteFmt
import com.vidx.core.util.TimeFmt
import com.vidx.platform.storage.HistoryDb
import com.vidx.ui.components.Chip
import com.vidx.ui.components.verticalPad
import com.vidx.ui.components.GhostButton
import com.vidx.ui.components.Ui
import com.vidx.ui.theme.ThemeEngine

/**
 * History screen — everything that has ever been downloaded.
 *
 *  - Filters: All / Completed / Failed / Audio / Video
 *  - Tap an entry: details + open / share / redownload / delete
 *  - Clear history + export history
 */
class HistoryScreen(context: Context) : android.widget.LinearLayout(context) {

    private var listView: ListView? = null
    private var adapter: HistoryAdapter? = null
    private var countText: TextView? = null
    private var filter = HistoryDb.Filter.ALL

    init {
        orientation = LinearLayout.VERTICAL
        verticalPad(context)
        addView(Ui.title(context, "History", 22f))
        countText = Ui.subtitle(context, "", 12.5f)
        addView(countText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) })

        buildFilters()
        listView = ListView(context).apply {
            divider = null
            dividerHeight = 0
            setPadding(0, Ui.dp(context, 10f), 0, Ui.dp(context, 90f))
        }
        adapter = HistoryAdapter()
        listView?.adapter = adapter
        addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(context, 4f) })

        val tools = android.widget.LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val export = GhostButton(context).apply {
            text = "Export"
            setOnClickListener {
                Ui.haptic(this)
                exportHistory()
            }
        }
        val clear = GhostButton(context).apply {
            text = "Clear History"
            setTextColor(BrandConfig.colorDanger)
            setOnClickListener {
                Ui.haptic(this)
                AlertDialog.Builder(context)
                    .setTitle("Clear history?")
                    .setMessage("This removes the history list. Your downloaded files stay on your device.")
                    .setPositiveButton("Clear") { _, _ ->
                        App.instance.history.clear()
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                    .apply { window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BrandConfig.colorSurface)) }
            }
        }
        tools.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        tools.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })
    }

    fun onShown() {
        refresh()
    }

    private fun buildFilters() {
        val row = android.widget.HorizontalScrollView(context)
        val chips = android.widget.LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in HistoryDb.Filter.entries) {
            val active = f == filter
            val chip = Chip(
                context,
                if (active) android.graphics.Color.WHITE else BrandConfig.colorTextSecondary,
                when (f) {
                    HistoryDb.Filter.ALL -> "All"
                    HistoryDb.Filter.COMPLETED -> "Completed"
                    HistoryDb.Filter.FAILED -> "Failed"
                    HistoryDb.Filter.AUDIO -> "Audio"
                    HistoryDb.Filter.VIDEO -> "Video"
                },
            )
            chip.background = if (active) ThemeEngine.pillBackground(ThemeEngine.accentColor()) else ThemeEngine.pillBackground(ThemeEngine.withAlpha(BrandConfig.colorTextSecondary, 0.14f))
            chip.setOnClickListener {
                Ui.haptic(it)
                filter = f
                buildFilters()
                refresh()
            }
            chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(context, 6f) })
        }
        row.addView(chips)
        findFilterRow()?.let { removeView(it) }
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12f) })
    }

    private fun findFilterRow(): View? {
        for (i in 0 until childCount) {
            if (getChildAt(i) is android.widget.HorizontalScrollView) return getChildAt(i)
        }
        return null
    }

    private val entries: List<HistoryDb.Entry>
        get() = App.instance.history.list(filter)

    fun refresh() {
        val list = entries
        countText?.text = "${list.size} entr${if (list.size == 1) "y" else "ies"}${when (filter) {
            HistoryDb.Filter.ALL -> ""
            HistoryDb.Filter.COMPLETED -> " completed"
            HistoryDb.Filter.FAILED -> " failed"
            HistoryDb.Filter.AUDIO -> " (audio)"
            HistoryDb.Filter.VIDEO -> " (video)"
        }}"
        adapter?.notifyDataSetChanged()
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): HistoryDb.Entry = entries[position]
        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: android.widget.LinearLayout
            val holder: Holder
            if (convertView is android.widget.LinearLayout && convertView.tag is Holder) {
                row = convertView
                holder = convertView.tag as Holder
            } else {
                row = android.widget.LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val p = Ui.dp(context, 12f)
                    setPadding(p, p, p, p)
                    background = ThemeEngine.glassBackground(radius = 16f, fillAlpha = 0x0A, borderAlpha = 0x12)
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = Ui.dp(context, 8f)
                }
                val col = android.widget.LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val title = Ui.text(context, 13.5f, BrandConfig.colorTextPrimary, android.graphics.Typeface.BOLD).apply { maxLines = 2 }
                val meta = Ui.text(context, 11f, BrandConfig.colorTextSecondary)
                col.addView(title)
                col.addView(meta)
                val chip = Chip(context, BrandConfig.colorTextSecondary, "")
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 8f) })
                holder = Holder(title, meta, chip)
                row.tag = holder
            }
            val e = getItem(position)
            holder.title.text = e.title
            holder.meta.text = buildString {
                append(com.vidx.core.model.Platform.fromId(e.platformId).displayName)
                append("  •  ").append(TimeFmt.date(e.createdAt))
                if (e.sizeBytes > 0) append("  •  ").append(ByteFmt.human(e.sizeBytes))
                if (e.destinationId == "audio") append("  •  audio")
            }
            val statusColor = if (e.statusId == "completed") BrandConfig.colorGreen else BrandConfig.colorDanger
            holder.chip.text = if (e.statusId == "completed") "Completed" else "Failed"
            holder.chip.setTextColor(statusColor)
            holder.chip.background = ThemeEngine.pillBackground(ThemeEngine.withAlpha(statusColor, 0.14f))
            row.setOnClickListener {
                Ui.haptic(row)
                showEntryActions(e)
            }
            return row
        }
    }

    private class Holder(val title: TextView, val meta: TextView, val chip: Chip)

    private fun showEntryActions(e: HistoryDb.Entry) {
        val actions = arrayOf("Open", "Share", "Download again", "Details", "Delete entry")
        AlertDialog.Builder(context)
            .setTitle(e.title)
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "Open" -> open(e)
                    "Share" -> share(e)
                    "Download again" -> {
                        when (val r = App.instance.engine.addUrl(e.url)) {
                            is com.vidx.platform.download.DownloadEngine.AddUrlResult.Added -> Ui.toast(context, "Added to queue")
                            is com.vidx.platform.download.DownloadEngine.AddUrlResult.Duplicate -> Ui.toast(context, "Already in queue")
                            is com.vidx.platform.download.DownloadEngine.AddUrlResult.Invalid -> Ui.toast(context, r.message, long = true)
                        }
                    }
                    "Details" -> showDetails(e)
                    "Delete entry" -> {
                        App.instance.history.delete(e.id)
                        refresh()
                    }
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
            .apply { window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BrandConfig.colorSurface)) }
    }

    private fun showDetails(e: HistoryDb.Entry) {
        val text = buildString {
            appendLine("Title: ${e.title}")
            appendLine("Platform: ${com.vidx.core.model.Platform.fromId(e.platformId).displayName}")
            appendLine("URL: ${e.url}")
            appendLine("Status: ${e.statusId}")
            appendLine("Size: ${if (e.sizeBytes > 0) ByteFmt.human(e.sizeBytes) else "unknown"}")
            if (e.durationMillis > 0) appendLine("Duration: ${TimeFmt.clock(e.durationMillis)}")
            appendLine("Downloaded: ${TimeFmt.date(e.createdAt)}")
            e.outputUri?.let { appendLine("File: $it") }
        }
        val tv = Ui.text(context, 13f, BrandConfig.colorTextPrimary).apply {
            this.text = text
            setPadding(Ui.dp(context, 24f), Ui.dp(context, 20f), Ui.dp(context, 24f), Ui.dp(context, 8f))
        }
        AlertDialog.Builder(context)
            .setTitle("History details")
            .setView(tv)
            .setPositiveButton("Close", null)
            .show()
            .apply { window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BrandConfig.colorSurface)) }
    }

    private fun entryUri(e: HistoryDb.Entry): Uri? {
        val out = e.outputUri ?: return null
        return if (out.startsWith("content://")) Uri.parse(out) else {
            Uri.Builder().scheme("content").authority("com.vidx.app.files").path(out).build()
        }
    }

    private fun open(e: HistoryDb.Entry) {
        val uri = entryUri(e) ?: run { Ui.toast(context, "File not found"); return }
        try {
            val type = if (e.destinationId == "audio") "audio/*" else "video/*"
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (ex: Exception) {
            Ui.toast(context, "No app can open this file type")
        }
    }

    private fun share(e: HistoryDb.Entry) {
        val uri = entryUri(e) ?: run { Ui.toast(context, "File not found"); return }
        try {
            val type = if (e.destinationId == "audio") "audio/*" else "video/*"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(e.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share video"))
        } catch (ex: Exception) {
            Ui.toast(context, "Could not share the file")
        }
    }

    private fun exportHistory() {
        val entries = App.instance.history.list(HistoryDb.Filter.ALL, 500)
        if (entries.isEmpty()) {
            Ui.toast(context, "History is empty")
            return
        }
        val text = buildString {
            appendLine("VIDX download history — ${TimeFmt.date(System.currentTimeMillis())}")
            appendLine()
            for (e in entries) {
                appendLine("${e.title} | ${com.vidx.core.model.Platform.fromId(e.platformId).displayName} | ${e.statusId} | ${e.url}")
            }
        }
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "VIDX history")
            }, "Export history"))
        } catch (ex: Exception) {
            Ui.toast(context, "Could not export history")
        }
    }
}
