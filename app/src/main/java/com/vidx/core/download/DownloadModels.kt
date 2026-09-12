package com.vidx.core.download

/** Download destination categories shown as folder choices in the UI. */
enum class Destination(val id: String, val label: String) {
    VIDEO("video", "Videos"),
    AUDIO("audio", "Audio"),
    DOWNLOADS("downloads", "Downloads");

    companion object {
        fun fromId(id: String?): Destination = entries.firstOrNull { it.id == id } ?: VIDEO
    }
}

/** Lifecycle status of one download task. */
enum class TaskStatus(val id: String) {
    QUEUED("queued"),
    ANALYZING("analyzing"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DUPLICATE("duplicate"),
    UNSUPPORTED("unsupported");

    companion object {
        fun fromId(id: String?): TaskStatus = entries.firstOrNull { it.id == id } ?: QUEUED
    }
}

/**
 * Immutable state of one queue item. Every mutation produces a copy — this makes
 * the queue trivially persistable (JSON snapshot) and unit-testable.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val displayUrl: String,
    val canonicalKey: String,
    val platformId: String,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationMillis: Long? = null,
    val destination: Destination = Destination.VIDEO,
    val status: TaskStatus = TaskStatus.QUEUED,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val speedBps: Long = 0,
    val attempts: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryable: Boolean = true,
    val outputFileName: String? = null,
    val outputUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: Int = 0,      // lower number = higher priority (queue example: 1,2,3)
    val formatId: String? = null,
    val selectedQualityLabel: String? = null,
) {
    val progressPct: Int
        get() = when {
            totalBytes > 0 -> ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            downloadedBytes > 0 -> -1 // indeterminate
            else -> 0
        }
}

/** Outcome of adding a URL to the queue — duplicate detection lives here. */
sealed class AddResult {
    data class Added(val task: DownloadTask) : AddResult()
    data class Duplicate(val existing: DownloadTask) : AddResult()
}

/**
 * Pure in-memory queue with the complete task state machine.
 * Persistence is delegated to a [QueueStore] by the Android engine layer.
 */
class DownloadQueue(private val initial: List<DownloadTask> = emptyList()) {

    private val tasks = ArrayList<DownloadTask>(initial)

    fun snapshot(): List<DownloadTask> = tasks.toList()

    fun get(id: String): DownloadTask? = tasks.firstOrNull { it.id == id }

    fun findByCanonicalKey(key: String): DownloadTask? = tasks.firstOrNull { it.canonicalKey == key }

    fun add(task: DownloadTask): AddResult {
        val existing = findByCanonicalKey(task.canonicalKey)
        if (existing != null && existing.id != task.id) return AddResult.Duplicate(existing)
        tasks.add(task)
        return AddResult.Added(task)
    }

    fun addAll(newTasks: List<DownloadTask>): Pair<Int, Int> { // (added, duplicates)
        var added = 0
        var dups = 0
        for (t in newTasks) {
            when (add(t)) {
                is AddResult.Added -> added++
                is AddResult.Duplicate -> dups++
            }
        }
        return added to dups
    }

    fun update(id: String, transform: (DownloadTask) -> DownloadTask): DownloadTask? {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = transform(tasks[idx])
        tasks[idx] = updated
        return updated
    }

    fun remove(id: String): DownloadTask? {
        val idx = tasks.indexOfFirst { it.id == id }
        return if (idx >= 0) tasks.removeAt(idx) else null
    }

    fun removeMany(ids: Set<String>): Int {
        val before = tasks.size
        tasks.removeAll { it.id in ids }
        return before - tasks.size
    }

    fun clearFinished(): Int {
        val finished = tasks.count { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.DUPLICATE, TaskStatus.UNSUPPORTED) }
        tasks.removeAll { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.DUPLICATE, TaskStatus.UNSUPPORTED) }
        return finished
    }

    /** Move a task from position [from] to [to] (drag/reorder support). */
    fun move(from: Int, to: Int): Boolean {
        if (from !in tasks.indices || to !in tasks.indices || from == to) return false
        val t = tasks.removeAt(from)
        tasks.add(to, t)
        renumberPriorities()
        return true
    }

    fun moveToTop(id: String): Boolean {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx <= 0) return false
        val t = tasks.removeAt(idx)
        tasks.add(0, t)
        renumberPriorities()
        return true
    }

    fun renumberPriorities() {
        tasks.forEachIndexed { i, t ->
            if (t.priority != i) tasks[i] = t.copy(priority = i)
        }
    }

    /**
     * Picks the next tasks to start, honoring max concurrent slots and the
     * skip set (e.g. tasks blocked by Wi-Fi-only policy are paused by the engine).
     * Order: priority, then creation time. Sequential by default (maxConcurrent=1).
     */
    fun nextToStart(
        activeCount: Int,
        maxConcurrent: Int,
        blockedIds: Set<String> = emptySet(),
    ): List<DownloadTask> {
        if (activeCount >= maxConcurrent) return emptyList()
        val candidates = tasks.asSequence()
            .filter { it.status == TaskStatus.QUEUED && it.id !in blockedIds }
            .sortedWith(compareBy({ it.priority }, { it.createdAt }))
            .take((maxConcurrent - activeCount).coerceAtLeast(1))
            .toList()
        return candidates
    }

    fun activeCount(): Int = tasks.count { it.status == TaskStatus.DOWNLOADING }

    fun queuedCount(): Int = tasks.count { it.status == TaskStatus.QUEUED }

    fun failedTasks(): List<DownloadTask> = tasks.filter { it.status == TaskStatus.FAILED }

    fun retryFailed(): Int {
        var n = 0
        tasks.indices.forEach { i ->
            val t = tasks[i]
            if (t.status == TaskStatus.FAILED) {
                tasks[i] = t.copy(status = TaskStatus.QUEUED, errorCode = null, errorMessage = null)
                n++
            }
        }
        return n
    }

    fun retryIds(ids: Set<String>): Int {
        var n = 0
        tasks.indices.forEach { i ->
            val t = tasks[i]
            if (t.id in ids && t.status == TaskStatus.FAILED) {
                tasks[i] = t.copy(status = TaskStatus.QUEUED, errorCode = null, errorMessage = null)
                n++
            }
        }
        return n
    }

    fun pauseAllDownloading(): Int {
        var n = 0
        tasks.indices.forEach { i ->
            val t = tasks[i]
            if (t.status == TaskStatus.DOWNLOADING) { tasks[i] = t.copy(status = TaskStatus.PAUSED, speedBps = 0); n++ }
        }
        return n
    }

    fun queueAllPaused(): Int {
        var n = 0
        tasks.indices.forEach { i ->
            val t = tasks[i]
            if (t.status == TaskStatus.PAUSED) { tasks[i] = t.copy(status = TaskStatus.QUEUED); n++ }
        }
        return n
    }

    fun summary(): QueueSummary = QueueSummary(
        total = tasks.size,
        queued = queuedCount(),
        downloading = activeCount(),
        paused = tasks.count { it.status == TaskStatus.PAUSED },
        completed = tasks.count { it.status == TaskStatus.COMPLETED },
        failed = tasks.count { it.status == TaskStatus.FAILED },
        totalBytes = tasks.sumOf { if (it.totalBytes > 0) it.totalBytes else 0 },
        downloadedBytes = tasks.sumOf { it.downloadedBytes },
    )
}

data class QueueSummary(
    val total: Int,
    val queued: Int,
    val downloading: Int,
    val paused: Int,
    val completed: Int,
    val failed: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
)
