package com.vidx.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.vidx.app.App
import com.vidx.app.BrandConfig
import com.vidx.app.MainActivity
import com.vidx.core.download.DownloadTask
import com.vidx.core.download.TaskStatus
import com.vidx.core.util.ByteFmt
import com.vidx.core.util.Eta
import com.vidx.core.util.TimeFmt
import com.vidx.ui.components.Chip
import com.vidx.ui.components.verticalPad
import com.vidx.ui.components.GhostButton
import com.vidx.ui.components.GradientButton
import com.vidx.ui.components.Ui
import com.vidx.ui.theme.ThemeEngine

/**
 * Download manager screen.
 *
 *  - Virtualized list (ListView) — handles 100+ items smoothly
 *  - Live per-task progress (speed, size, %, ETA)
 *  - Actions: start / pause / resume / cancel / retry / delete / open / share / details
 *  - Drag-reorder (long-press) + accessible Move up/down/top fallback
 *  - Multi-select with bulk retry / redownload / delete
 *  - Empty / loading / error / success states per task
 */
class DownloadsScreen(context: Context) : LinearLayout(context) {

    private var adapter: TaskAdapter? = null
    private var listView: ListView? = null
    private var summaryText: TextView? = null
    private var selectButton: GhostButton? = null
    private var bulkBar: LinearLayout? = null
    private var bulkLabel: TextView? = null

    private val selected = mutableSetOf<String>()
    private var selectionMode = false
    private val engine get() = App.instance.engine

    private val dragController = DragController()

    init {
        orientation = LinearLayout.VERTICAL
        verticalPad(context)
        buildHeader()
        buildList()
        refresh()
    }

    fun onShown() {
        refresh()
    }

    // ------------------------------------------------------------------ build

    private fun buildHeader() {
        addView(Ui.title(context, "Downloads", 22f))

        summaryText = Ui.subtitle(context, "", 12.5f)
        addView(summaryText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) })

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val startAll = GradientButton(context, ThemeEngine.primaryGradient()).apply {
            text = "Start All"
            setOnClickListener {
                Ui.haptic(this)
                (context as? MainActivity)?.let {
                    it.maybeRequestNotificationPermission()
                    it.startDownloadService()
                }
                engine.startAll()
            }
        }
        val pauseAll = GhostButton(context).apply {
            text = "Pause All"
            setOnClickListener {
                Ui.haptic(this)
                engine.pauseAll()
            }
        }
        selectButton = GhostButton(context).apply {
            text = "Select"
            setOnClickListener {
                Ui.haptic(this)
                selectionMode = !selectionMode
                if (!selectionMode) selected.clear()
                updateBulkBar()
                adapter?.notifyDataSetChanged()
            }
        }
        controls.addView(startAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        controls.addView(pauseAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8f) })
        controls.addView(selectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 14f) })

        bulkBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        bulkLabel = Ui.text(context, 12f, BrandConfig.colorTextSecondary, Typeface.BOLD)
        val retry = GhostButton(context).apply {
            text = "Retry"
            setOnClickListener {
                Ui.haptic(this)
                engine.retryMany(selected.toSet())
                selectionMode = false
                selected.clear()
                updateBulkBar()
                adapter?.notifyDataSetChanged()
            }
        }
        val redl = GhostButton(context).apply {
            text = "Redownload"
            setOnClickListener {
                Ui.haptic(this)
                engine.snapshot().filter { it.id in selected && it.status == TaskStatus.COMPLETED }
                    .forEach { engine.redownload(it.id) }
                selected.clear()
                updateBulkBar()
                adapter?.notifyDataSetChanged()
            }
        }
        val del = GhostButton(context).apply {
            text = "Delete"
            setTextColor(BrandConfig.colorDanger)
            setOnClickListener {
                Ui.haptic(this)
                engine.removeMany(selected.toSet())
                selected.clear()
                selectionMode = false
                updateBulkBar()
                adapter?.notifyDataSetChanged()
                Ui.toast(context, "Deleted")
            }
        }
        bulkBar?.addView(bulkLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bulkBar?.addView(retry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 6f) })
        bulkBar?.addView(redl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 6f) })
        bulkBar?.addView(del, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(bulkBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 10f) })
    }

    private fun buildList() {
        listView = ListView(context).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(0, Ui.dp(context, 10f), 0, Ui.dp(context, 90f))
            isVerticalScrollBarEnabled = false
        }
        adapter = TaskAdapter()
        listView?.adapter = adapter
        addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(context, 6f) })
        listView?.emptyView = buildEmptyState()
        dragController.attach(this)
    }

    private fun buildEmptyState(): View {
        val empty = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val icon = ImageView(context).apply {
            setImageResource(com.vidx.app.R.drawable.ic_download)
            imageTintList = android.content.res.ColorStateList.valueOf(BrandConfig.colorTextTertiary)
            val s = Ui.dp(context, 56f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        val t = Ui.text(context, 15f, BrandConfig.colorTextPrimary, Typeface.BOLD, Gravity.CENTER).apply {
            text = "No downloads yet"
        }
        val s = Ui.subtitle(context, "Add video URLs from the Home screen,\nthen press DOWNLOAD ALL.", 13f).apply { gravity = Gravity.CENTER }
        empty.addView(icon)
        empty.addView(t)
        empty.addView(s)
        return empty
    }

    // ------------------------------------------------------------------ refresh

    fun refresh() {
        val tasks = engine.snapshot()
        val s = engine.summary()
        summaryText?.text = when {
            s.downloading > 0 ->
                "Downloading ${s.downloading} / ${s.total}  •  ${ByteFmt.human(s.downloadedBytes)}${if (s.totalBytes > 0) " of ${ByteFmt.human(s.totalBytes)}" else ""}"
            s.queued > 0 -> "${s.queued} queued  •  ${s.paused} paused  •  ${s.failed} failed  •  ${s.completed} completed"
            s.completed > 0 -> "${s.completed} completed"
            else -> "No active downloads"
        }
        if (selectionMode) {
            selected.retainAll(tasks.map { it.id }.toSet())
            updateBulkBar()
        }
        adapter?.notifyDataSetChanged()
        // list position stability: ListView handles recycling; we only rebind.
    }

    private fun updateBulkBar() {
        val active = selectionMode && selected.isNotEmpty()
        bulkBar?.visibility = if (active) View.VISIBLE else View.GONE
        bulkLabel?.text = "${selected.size} selected"
        selectButton?.text = if (selectionMode) "Cancel" else "Select"
    }

    // ------------------------------------------------------------------ adapter

    private inner class TaskAdapter : BaseAdapter() {
        override fun getCount(): Int = engine.snapshot().size
        override fun getItem(position: Int): DownloadTask = engine.snapshot()[position]
        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TaskRow) ?: TaskRow(context, dragController)
            val task = getItem(position)
            row.bind(task, position, selectionMode, selected.contains(task.id))
            return row
        }
    }

    // ------------------------------------------------------------------ actions

    internal fun onTaskLongPress(task: DownloadTask, row: View) {
        if (selectionMode) return
        val actions = arrayOf(
            when (task.status) {
                TaskStatus.DOWNLOADING -> "Pause"
                TaskStatus.PAUSED -> "Resume"
                TaskStatus.FAILED -> "Retry"
                TaskStatus.QUEUED -> "Pause (skip)"
                else -> "Start"
            },
            "Cancel",
            "Move to top",
            "Move up",
            "Move down",
            "Open",
            "Share",
            "Edit",
            "Details",
            "Delete",
        )
        AlertDialog.Builder(context)
            .setTitle(task.title ?: "Video")
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "Pause" -> engine.pause(task.id)
                    "Resume" -> engine.resume(task.id)
                    "Retry" -> engine.retry(task.id)
                    "Start" -> { engine.resume(task.id); pumpStart(task.id) }
                    "Pause (skip)" -> engine.pause(task.id)
                    "Cancel" -> engine.cancel(task.id)
                    "Move to top" -> engine.moveToTop(task.id)
                    "Move up" -> moveBy(task.id, -1)
                    "Move down" -> moveBy(task.id, 1)
                    "Open" -> openFile(task)
                    "Share" -> shareFile(task)
                    "Edit" -> editFile(task)
                    "Details" -> showDetails(task)
                    "Delete" -> {
                        engine.remove(task.id)
                        Ui.toast(context, "Deleted")
                    }
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
            .apply {
                window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BrandConfig.colorSurface))
            }
    }

    private fun pumpStart(id: String) {
        (context as? MainActivity)?.let {
            it.maybeRequestNotificationPermission()
            it.startDownloadService()
        }
    }

    private fun moveBy(id: String, delta: Int) {
        val tasks = engine.snapshot()
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return
        val to = (idx + delta).coerceIn(0, tasks.size - 1)
        engine.reorder(idx, to)
        adapter?.notifyDataSetChanged()
    }

    private fun openFile(task: DownloadTask) {
        val uri = taskUri(task) ?: run { Ui.toast(context, "File not found"); return }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeFor(task))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Ui.toast(context, "No app can open this file type")
        }
    }

    private fun shareFile(task: DownloadTask) {
        val uri = taskUri(task) ?: run { Ui.toast(context, "File not found"); return }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(task)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(task.title, uri)
            }
            context.startActivity(Intent.createChooser(intent, "Share video"))
        } catch (e: Exception) {
            Ui.toast(context, "Could not share the file")
        }
    }

    /** Exposes the file to compatible editors (CapCut etc.) via standard intents. */
    private fun editFile(task: DownloadTask) {
        val uri = taskUri(task) ?: run { Ui.toast(context, "File not found"); return }
        try {
            val edit = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, mimeFor(task))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.startActivity(edit)
        } catch (e: Exception) {
            shareFile(task) // fall back to the share sheet
        }
    }

    private fun taskUri(task: DownloadTask): Uri? {
        val out = task.outputUri ?: return null
        return if (out.startsWith("content://")) {
            Uri.parse(out)
        } else {
            Uri.Builder().scheme("content").authority("com.vidx.app.files").path(out).build()
        }
    }

    private fun mimeFor(task: DownloadTask): String =
        if (task.destination.id == "audio") "audio/*" else "video/*"

    private fun showDetails(task: DownloadTask) {
        val lines = buildString {
            appendLine("Title: ${task.title ?: "—"}")
            appendLine("Platform: ${com.vidx.core.model.Platform.fromId(task.platformId).displayName}")
            appendLine("URL: ${task.displayUrl}")
            appendLine("Status: ${task.status.id} (attempt ${task.attempts})")
            appendLine("Quality: ${task.selectedQualityLabel ?: "—"}")
            appendLine("Destination: ${task.destination.label}")
            appendLine("Size: ${ByteFmt.human(task.downloadedBytes)} / ${if (task.totalBytes > 0) ByteFmt.human(task.totalBytes) else "unknown"}")
            appendLine("Speed: ${ByteFmt.human(task.speedBps)}/s")
            appendLine("ETA: ${Eta.human(task.totalBytes - task.downloadedBytes, task.speedBps)}")
            task.durationMillis?.let { appendLine("Duration: ${TimeFmt.clock(it)}") }
            appendLine("File: ${task.outputFileName ?: "—"}")
            task.errorMessage?.let { appendLine("Error: $it") }
        }
        val tv = Ui.text(context, 13f, BrandConfig.colorTextPrimary).apply {
            text = lines
            setPadding(Ui.dp(context, 24f), Ui.dp(context, 20f), Ui.dp(context, 24f), Ui.dp(context, 8f))
        }
        AlertDialog.Builder(context)
            .setTitle("Download details")
            .setView(tv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Retry") { _, _ -> engine.retry(task.id) }
            .show()
            .apply { window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BrandConfig.colorSurface)) }
    }

    // ------------------------------------------------------------------ drag reorder

    internal inner class DragController {
        private var dragging: DownloadTask? = null
        private var overlay: View? = null
        private var fromIndex = -1

        fun attach(screen: DownloadsScreen) {
            listView?.setOnTouchListener { _, event ->
                if (dragging == null) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        moveOverlay(event.rawY)
                        val lv = listView ?: return@setOnTouchListener true
                        val pos = lv.pointToPosition(event.x.toInt(), event.y.toInt())
                        if (pos >= 0 && pos != fromIndex) {
                            val tasks = engine.snapshot()
                            if (pos < tasks.size) {
                                engine.reorder(fromIndex, pos)
                                fromIndex = pos
                                adapter?.notifyDataSetChanged()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                    else -> true
                }
                true
            }
        }

        fun startDrag(task: DownloadTask, row: View, index: Int) {
            if (dragging != null) return
            dragging = task
            fromIndex = index
            Ui.haptic(row)
            val snapshot = FrameLayout(context)
            val copy = buildDragCopy(task)
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            snapshot.addView(copy, lp)
            val root = (context as MainActivity).findViewById<FrameLayout>(android.R.id.content)
            val holder = FrameLayout(context)
            root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val pos = IntArray(2)
            row.getLocationInWindow(pos)
            val olp = FrameLayout.LayoutParams(row.width, row.height)
            olp.leftMargin = pos[0]
            olp.topMargin = pos[1] - getStatusBarOffset()
            overlay = snapshot
            holder.addView(snapshot, olp)
            snapshot.alpha = 0.92f
            snapshot.elevation = Ui.dp(context, 12f).toFloat()
            copy.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
        }

        private fun buildDragCopy(task: DownloadTask): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = Ui.dp(context, 12f)
                setPadding(p, p, p, p)
                background = ThemeEngine.glassBackground(radius = 16f, fillAlpha = 0xEE, borderAlpha = 0x30)
                setBackgroundColor(BrandConfig.colorSurfaceHigh)
            }
            row.addView(Ui.text(context, 13f, BrandConfig.colorTextPrimary, Typeface.BOLD).apply {
                text = task.title ?: task.displayUrl
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        private fun moveOverlay(rawY: Float) {
            val ov = overlay ?: return
            val lp = ov.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = (rawY - ov.height / 2f - getStatusBarOffset()).toInt()
            ov.layoutParams = lp
        }

        private fun getStatusBarOffset(): Int {
            val root = (context as MainActivity).findViewById<FrameLayout>(android.R.id.content)
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            return loc[1]
        }

        private fun endDrag() {
            overlay?.let { (it.parent as? ViewGroup)?.removeView(it.parent as View) }
            overlay = null
            dragging = null
            fromIndex = -1
            adapter?.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------ row view

    private inner class TaskRow(context: Context, private val drag: DragController) : LinearLayout(context) {

        private val checkbox: CheckBox
        private val thumb: ImageView
        private val title: TextView
        private val meta: TextView
        private val statusChip: Chip
        private val pctText: TextView
        private val bar: ProgressBar2
        private var taskId: String = ""

        init {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 12f)
            setPadding(p, p, p, p)
            background = ThemeEngine.glassBackground(radius = 18f, fillAlpha = 0x0E, borderAlpha = 0x14)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = Ui.dp(context, 10f)

            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            checkbox = CheckBox(context).apply {
                visibility = View.GONE
                buttonTintList = android.content.res.ColorStateList.valueOf(ThemeEngine.accentColor())
                setOnClickListener { toggleSelection() }
            }
            thumb = ImageView(context).apply {
                val s = Ui.dp(context, 46f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ThemeEngine.glassBackground(radius = 10f, fillAlpha = 0x08, borderAlpha = 0x14)
            }
            val mid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pp = Ui.dp(context, 10f)
                setPadding(pp, 0, pp, 0)
            }
            title = Ui.text(context, 13.5f, BrandConfig.colorTextPrimary, Typeface.BOLD).apply { maxLines = 1 }
            meta = Ui.text(context, 11f, BrandConfig.colorTextSecondary).apply { maxLines = 2 }
            mid.addView(title)
            mid.addView(meta)
            statusChip = Chip(context, BrandConfig.colorTextSecondary, "")
            pctText = Ui.text(context, 11f, BrandConfig.colorTextSecondary, Typeface.BOLD)
            top.addView(checkbox)
            top.addView(thumb)
            top.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(statusChip)
            top.addView(pctText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 6f) })

            bar = ProgressBar2(context)
            addView(top)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 4f)).apply { topMargin = Ui.dp(context, 10f) })

            setOnLongClickListener { v ->
                drag.startDrag(engine.getTask(taskId) ?: return@setOnLongClickListener false, v, indexOfTask())
                true
            }
            setOnClickListener {
                if (selectionMode) {
                    checkbox.toggle()
                } else {
                    val task = engine.getTask(taskId) ?: return@setOnClickListener
                    if (task.status == TaskStatus.COMPLETED) openFile(task) else onTaskLongPress(task, this)
                }
            }
        }

        private fun indexOfTask(): Int = engine.snapshot().indexOfFirst { it.id == taskId }

        private fun toggleSelection() {
            val task = engine.getTask(taskId) ?: return
            if (selected.contains(task.id)) selected.remove(task.id) else selected.add(task.id)
            updateBulkBar()
            adapter?.notifyDataSetChanged()
        }

        fun bind(task: DownloadTask, position: Int, selectMode: Boolean, isSelected: Boolean) {
            taskId = task.id
            checkbox.visibility = if (selectMode) View.VISIBLE else View.GONE
            checkbox.isChecked = isSelected
            title.text = task.title ?: "Analyzing…"
            statusChip.text = statusLabel(task.status)
            statusChip.setTextColor(statusColor(task.status))
            statusChip.background = ThemeEngine.pillBackground(ThemeEngine.withAlpha(statusColor(task.status), 0.14f))

            meta.text = buildString {
                append(com.vidx.core.model.Platform.fromId(task.platformId).displayName)
                task.selectedQualityLabel?.let { append("  •  ").append(it) }
                when (task.status) {
                    TaskStatus.DOWNLOADING -> {
                        append("  •  ${ByteFmt.human(task.speedBps)}/s")
                        if (task.totalBytes > 0) append("  •  ETA ${Eta.human(task.totalBytes - task.downloadedBytes, task.speedBps)}")
                    }
                    TaskStatus.COMPLETED -> append("  •  ${ByteFmt.human(task.totalBytes.takeIf { it > 0 } ?: task.downloadedBytes)}")
                    TaskStatus.FAILED -> task.errorMessage?.let { append("  •  ").append(it) }
                    else -> {}
                }
            }
            pctText.text = when {
                task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0 -> "${task.progressPct}%"
                task.status == TaskStatus.DOWNLOADING -> ByteFmt.human(task.downloadedBytes)
                else -> ""
            }
            bar.setProgress(task.progressPct, task.status == TaskStatus.DOWNLOADING && task.totalBytes <= 0)
            bar.visibility = if (task.status == TaskStatus.DOWNLOADING) View.VISIBLE else View.GONE

            if (task.thumbnailUrl != null && thumb.drawable == null) {
                App.instance.thumbs.get(task.thumbnailUrl) { bmp ->
                    if (bmp != null) Ui.mainHandler.post {
                        if (taskId == task.id) thumb.setImageBitmap(bmp)
                    }
                }
            }
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

        private fun statusColor(status: TaskStatus): Int = when (status) {
            TaskStatus.COMPLETED -> BrandConfig.colorGreen
            TaskStatus.FAILED, TaskStatus.UNSUPPORTED -> BrandConfig.colorDanger
            TaskStatus.DOWNLOADING -> ThemeEngine.accentColor()
            TaskStatus.PAUSED -> BrandConfig.colorAmber
            else -> BrandConfig.colorTextSecondary
        }
    }

    /** Slim animated progress bar. */
    private class ProgressBar2(context: Context) : View(context) {
        private val track = android.graphics.Paint().apply {
            color = ThemeEngine.withAlpha(BrandConfig.colorTextPrimary, 0.10f)
        }
        private val fill = android.graphics.Paint().apply {
            color = ThemeEngine.accentColor()
        }
        private var pct = 0f
        private var indeterminate = false
        private var shift = 0f

        init {
            val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
            anim.duration = 800
            anim.repeatCount = android.animation.ValueAnimator.INFINITE
            anim.addUpdateListener {
                shift += 0.03f
                if (shift > 1f) shift = 0f
                if (indeterminate) invalidate()
            }
            anim.start()
        }

        fun setProgress(p: Int, ind: Boolean) {
            pct = p.coerceIn(0, 100) / 100f
            indeterminate = ind
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = Ui.dp(context, 2f).toFloat()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            if (indeterminate) {
                val w = width * 0.3f
                val left = shift * (width + w) - w
                canvas.drawRoundRect(left, 0f, left + w, height.toFloat(), r, r, fill)
            } else if (pct > 0f) {
                canvas.drawRoundRect(0f, 0f, width * pct, height.toFloat(), r, r, fill)
            }
        }
    }
}
