package com.vidx.platform.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.vidx.core.download.AddResult
import com.vidx.core.download.Destination
import com.vidx.core.download.DownloadQueue
import com.vidx.core.download.DownloadTask
import com.vidx.core.download.HttpDownloader
import com.vidx.core.download.QueueJson
import com.vidx.core.download.QueueStore
import com.vidx.core.download.TaskStatus
import com.vidx.core.model.Platform
import com.vidx.core.model.VideoFormat
import com.vidx.core.model.VideoMetadata
import com.vidx.core.platforms.PlatformRegistry
import com.vidx.core.settings.AppSettings
import com.vidx.core.util.FileNames
import com.vidx.core.util.Outcome
import com.vidx.core.util.RetryPolicy
import com.vidx.core.util.newId
import com.vidx.platform.settings.AndroidSettings
import com.vidx.platform.storage.AndroidStorage
import com.vidx.platform.storage.HistoryDb
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Download engine — orchestrates the queue, workers, persistence, notifications
 * and network policy. Single instance per process (see App).
 *
 * Concurrency: downloads run on a dedicated thread pool (default 1 = sequential,
 * configurable up to 4). The UI observes state changes on the main thread.
 */
class DownloadEngine(
    private val context: Context,
    private val settingsStore: AndroidSettings,
    private val storage: AndroidStorage,
    private val history: HistoryDb,
) {

    sealed class AddUrlResult {
        data class Added(val task: DownloadTask) : AddUrlResult()
        data class Duplicate(val existing: DownloadTask) : AddUrlResult()
        data class Invalid(val message: String) : AddUrlResult()
    }

    var settings: AppSettings = settingsStore.load()
        private set

    internal val storageForWorker: AndroidStorage get() = storage

    private val queue = DownloadQueue(loadPersisted())
    private val analyzed = ConcurrentHashMap<String, VideoMetadata>()
    private val workers = ConcurrentHashMap<String, DownloadWorker>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newFixedThreadPool(2) { r -> Thread(r, "vidx-io").apply { isDaemon = true } }

    @Volatile
    private var downloadExecutor: ThreadPoolExecutor = newPool(settings.maxConcurrent)

    @Volatile
    var hasNetwork = true
        private set
    @Volatile
    var isWifi = false
        private set

    /** UI observer — called on the main thread after every task mutation. */
    var listener: ((DownloadTask) -> Unit)? = null

    private var service: DownloadService? = null

    private val persistDebounced = AtomicBoolean(false)
    private val persistRunnable = Runnable {
        persistDebounced.set(false)
        writePersisted()
    }

    // ------------------------------------------------------------------ lifecycle

    fun attachService(s: DownloadService) { service = s }
    fun detachService() { service = null }

    /** Called when the process starts / restarts. */
    fun onAppStart() {
        // Tasks that were mid-download when the process died resume as paused.
        queue.snapshot().forEach { t ->
            if (t.status == TaskStatus.DOWNLOADING) {
                queue.update(t.id) { it.copy(status = TaskStatus.PAUSED) }
            }
        }
        notifyState()
        pump()
    }

    fun shutdown() {
        workers.values.forEach { it.cancelRequested.set(true) }
        ioExecutor.shutdown()
        downloadExecutor.shutdown()
        writePersisted()
    }

    fun updateSettings(next: AppSettings) {
        settings = next
        if (downloadExecutor.maximumPoolSize != next.maxConcurrent) {
            downloadExecutor.shutdownNow()
            downloadExecutor = newPool(next.maxConcurrent)
        }
        onNetworkChanged(hasNetwork, isWifi) // re-apply policy
        pump()
    }

    fun onNetworkChanged(hasNet: Boolean, wifi: Boolean) {
        hasNetwork = hasNet
        isWifi = wifi
        val allowed = networkAllowed()
        if (!allowed) {
            queue.snapshot().forEach { t ->
                if (t.status == TaskStatus.DOWNLOADING) {
                    workers[t.id]?.pauseRequested?.set(true)
                }
            }
        }
        if (hasNet && allowed) {
            queue.queueAllPaused().let { if (it > 0) notifyState() }
            pump()
        }
    }

    private fun networkAllowed(): Boolean = hasNetwork && (!settings.wifiOnly || isWifi)

    // ------------------------------------------------------------------ mutations

    fun addUrl(rawUrl: String): AddUrlResult {
        val normalized = com.vidx.core.url.UrlNormalizer.normalize(rawUrl)
        if (normalized.platform == Platform.UNKNOWN) return AddUrlResult.Invalid("Unsupported platform or website.")
        val validation = com.vidx.core.url.UrlValidator.validate(rawUrl)
        if (validation != null) return AddUrlResult.Invalid(validation.message)

        val existing = queue.findByCanonicalKey(normalized.canonicalKey)
        if (existing != null) return AddUrlResult.Duplicate(existing)

        val task = DownloadTask(
            id = newId(),
            url = rawUrl.trim(),
            displayUrl = normalized.display,
            canonicalKey = normalized.canonicalKey,
            platformId = normalized.platform.id,
            status = TaskStatus.ANALYZING,
            destination = Destination.VIDEO,
        )
        when (val added = queue.add(task)) {
            is AddResult.Added -> {
                persistSoon()
                notifyState()
                analyze(task)
                return AddUrlResult.Added(task)
            }
            is AddResult.Duplicate -> return AddUrlResult.Duplicate(added.existing)
        }
    }

    fun addMany(urls: List<String>): Pair<Int, Int> { // (added, duplicates)
        var added = 0
        var dups = 0
        for (u in urls) when (addUrl(u)) {
            is AddUrlResult.Added -> added++
            is AddUrlResult.Duplicate -> dups++
            is AddUrlResult.Invalid -> {}
        }
        return added to dups
    }

    fun analyze(taskId: String) {
        val task = queue.get(taskId) ?: return
        analyze(task)
    }

    private fun analyze(task: DownloadTask) {
        ioExecutor.execute {
            val result = PlatformRegistry.analyze(task.url)
            when (result) {
                is Outcome.Ok -> {
                    val meta = result.value
                    analyzed[task.id] = meta
                    val newStatus = if (meta.videoFormats.isEmpty() && meta.audioFormats.isEmpty() && meta.downloadNote != null) {
                        TaskStatus.UNSUPPORTED
                    } else {
                        TaskStatus.QUEUED
                    }
                    queue.update(task.id) {
                        it.copy(
                            status = newStatus,
                            title = meta.title ?: it.title,
                            thumbnailUrl = meta.thumbnailUrl ?: it.thumbnailUrl,
                            durationMillis = meta.durationMillis ?: it.durationMillis,
                            errorCode = if (newStatus == TaskStatus.UNSUPPORTED) "unsupported" else null,
                            errorMessage = if (newStatus == TaskStatus.UNSUPPORTED) meta.downloadNote else null,
                            selectedQualityLabel = pickDefaultFormat(meta)?.qualityLabel,
                            formatId = pickDefaultFormat(meta)?.id,
                        )
                    }
                    notifyState()
                    persistSoon()
                    if (settings.autoStartQueue) pump()
                }
                is Outcome.Err -> {
                    queue.update(task.id) {
                        it.copy(
                            status = TaskStatus.FAILED,
                            errorCode = result.code,
                            errorMessage = result.message,
                            retryable = result.retryable,
                        )
                    }
                    notifyState()
                    persistSoon()
                }
            }
        }
    }

    fun startAll() {
        queue.snapshot().forEach { t ->
            if (t.status in setOf(TaskStatus.QUEUED, TaskStatus.FAILED, TaskStatus.PAUSED)) {
                queue.update(t.id) { it.copy(status = TaskStatus.QUEUED, errorCode = null, errorMessage = null) }
            }
        }
        notifyState()
        pump()
    }

    fun pause(id: String) {
        workers[id]?.pauseRequested?.set(true)
    }

    fun pauseAll() {
        workers.values.forEach { it.pauseRequested.set(true) }
    }

    fun resume(id: String) {
        queue.get(id)?.let { t ->
            if (t.status == TaskStatus.PAUSED) {
                queue.update(id) { it.copy(status = TaskStatus.QUEUED) }
                notifyState()
                pump()
            }
        }
    }

    fun cancel(id: String) {
        workers[id]?.cancelRequested?.set(true)
        queue.get(id)?.let { t ->
            if (t.status !in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)) {
                queue.update(id) { it.copy(status = TaskStatus.CANCELLED, errorCode = null, errorMessage = null, speedBps = 0) }
                storage.cleanupTempFor(id)
                notifyState()
                persistSoon()
            }
        }
    }

    fun remove(id: String) {
        cancel(id)
        queue.remove(id)
        notifyState()
        persistSoon()
    }

    fun removeMany(ids: Set<String>) {
        ids.forEach { workers[it]?.cancelRequested?.set(true) }
        queue.removeMany(ids)
        ids.forEach { storage.cleanupTempFor(it) }
        notifyState()
        persistSoon()
    }

    fun retry(id: String) {
        queue.get(id)?.let { t ->
            if (t.status == TaskStatus.FAILED) {
                queue.update(id) { it.copy(status = TaskStatus.ANALYZING, errorCode = null, errorMessage = null, attempts = 0) }
                notifyState()
                analyze(t)
            }
        }
    }

    fun retryMany(ids: Set<String>) = ids.forEach { retry(it) }

    fun retryAllFailed() {
        queue.failedTasks().forEach { retry(it.id) }
    }

    /** "Download Again" for a completed item: remove + re-add the same URL. */
    fun redownload(id: String) {
        val old = queue.get(id) ?: return
        queue.remove(id)
        addUrl(old.url)
        persistSoon()
    }

    fun reorder(from: Int, to: Int) {
        if (queue.move(from, to)) {
            notifyState()
            persistSoon()
        }
    }

    fun moveToTop(id: String) {
        if (queue.moveToTop(id)) {
            notifyState()
            persistSoon()
        }
    }

    fun setDestination(id: String, destination: Destination) {
        queue.update(id) { it.copy(destination = destination) }
        notifyState()
        persistSoon()
    }

    fun selectFormat(id: String, formatId: String) {
        val meta = analyzed[id] ?: return
        val fmt = (meta.videoFormats + meta.audioFormats).firstOrNull { it.id == formatId } ?: return
        queue.update(id) { it.copy(formatId = fmt.id, selectedQualityLabel = fmt.qualityLabel) }
        notifyState()
        persistSoon()
    }

    fun availableFormats(id: String): List<VideoFormat> {
        val meta = analyzed[id] ?: return emptyList()
        return meta.videoFormats + meta.audioFormats
    }

    fun metadataFor(id: String): VideoMetadata? = analyzed[id]

    // ------------------------------------------------------------------ scheduling

    fun pump() {
        if (!networkAllowed()) return
        val active = workers.count { !it.value.finished }
        val blocked = workers.keys
        val next = queue.nextToStart(active, settings.maxConcurrent, blocked)
        for (t in next) {
            queue.update(t.id) { it.copy(status = TaskStatus.QUEUED) }
            startWorker(t.id)
        }
        notifyState()
        service?.onEngineStateChanged()
    }

    private fun startWorker(id: String) {
        val task = queue.get(id) ?: return
        if (workers.containsKey(id)) return
        val worker = DownloadWorker(this, task)
        workers[id] = worker
        downloadExecutor.execute(worker)
    }

    fun snapshot(): List<DownloadTask> = queue.snapshot()
    fun summary() = queue.summary()
    fun getTask(id: String): DownloadTask? = queue.get(id)

    internal fun metadataOrResolve(task: DownloadTask): Outcome<VideoMetadata> {
        analyzed[task.id]?.let { return Outcome.Ok(it) }
        // Process restarted without the in-memory cache — re-analyze once.
        val res = PlatformRegistry.analyze(task.url)
        if (res is Outcome.Ok) analyzed[task.id] = res.value
        return res
    }

    internal fun pickFormat(task: DownloadTask, meta: VideoMetadata): VideoFormat? {
        val all = meta.videoFormats + meta.audioFormats
        if (all.isEmpty()) return null
        val byId = all.firstOrNull { it.id == task.formatId }
        if (byId != null) return byId
        // fall back to the default-quality policy
        val pref = settings.defaultQuality
        val byLabel = all.firstOrNull { it.qualityLabel.equals(pref, ignoreCase = true) }
        if (byLabel != null) return byLabel
        if (pref == "audio") return meta.audioFormats.firstOrNull()
        return meta.videoFormats.maxByOrNull { it.height ?: 0 } ?: meta.audioFormats.firstOrNull()
    }

    private fun pickDefaultFormat(meta: VideoMetadata): VideoFormat? {
        val all = meta.videoFormats + meta.audioFormats
        return when (settings.defaultQuality) {
            "audio" -> meta.audioFormats.firstOrNull()
            "best" -> meta.videoFormats.maxByOrNull { it.height ?: 0 } ?: meta.audioFormats.firstOrNull()
            else -> all.firstOrNull { it.qualityLabel.equals(settings.defaultQuality, ignoreCase = true) }
                ?: meta.videoFormats.maxByOrNull { it.height ?: 0 }
        }
    }

    internal fun onWorkerProgress(task: DownloadTask) {
        notifyState()
    }

    internal fun onWorkerFinished(worker: DownloadWorker, task: DownloadTask, final: DownloadTask) {
        workers.remove(task.id)
        queue.update(task.id) { final }
        if (final.status == TaskStatus.COMPLETED) {
            recordHistory(final)
            Notifications.completed(context, final, settingsStore.load())
        } else if (final.status == TaskStatus.FAILED) {
            Notifications.failed(context, final, settingsStore.load())
        }
        notifyState()
        persistSoon()
        pump()
        service?.onEngineStateChanged()
    }

    private fun recordHistory(task: DownloadTask) {
        history.insert(
            HistoryDb.Entry(
                id = task.id,
                url = task.url,
                title = task.title ?: FileNames.sanitize(task.url),
                platformId = task.platformId,
                thumbnailUrl = task.thumbnailUrl,
                durationMillis = task.durationMillis ?: 0,
                sizeBytes = task.totalBytes.takeIf { it > 0 } ?: 0,
                outputUri = task.outputUri,
                destinationId = task.destination.id,
                statusId = task.status.id,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    internal fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        queue.update(id, transform)
        notifyState()
        persistSoon()
    }

    // ------------------------------------------------------------------ persistence

    private fun loadPersisted(): List<DownloadTask> {
        val file = queueFile()
        if (!file.exists()) return emptyList()
        return QueueJson.deserialize(file.readText())
    }

    private fun queueFile(): File = File(context.filesDir, "queue.json")

    private fun persistSoon() {
        if (persistDebounced.compareAndSet(false, true)) {
            mainHandler.postDelayed(persistRunnable, 400)
        }
    }

    private fun writePersisted() {
        val file = queueFile()
        val tmp = File(context.filesDir, "queue.json.tmp")
        try {
            tmp.writeText(QueueJson.serialize(queue.snapshot()))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            // Persistence failure must never crash the engine; the queue keeps
            // working in memory and will retry on the next transition.
        }
    }

    private fun notifyState() {
        val tasks = queue.snapshot()
        mainHandler.post {
            listener?.let { l -> tasks.forEach(l) }
        }
    }

    private fun newPool(max: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(
            1, max.coerceAtLeast(1), 20, TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
            { r -> Thread(r, "vidx-dl").apply { isDaemon = true } },
        ) { task, _ -> downloadExecutor.execute(task) }
}

/**
 * One download attempt-run. Handles retries with resume, format resolution,
 * storage finalize and error classification. Pause/cancel are cooperative
 * flags checked between chunks (and reflected into task state).
 */
class DownloadWorker(
    private val engine: DownloadEngine,
    private val task: DownloadTask,
) : Runnable {

    val pauseRequested = AtomicBoolean(false)
    val cancelRequested = AtomicBoolean(false)
    @Volatile var finished = false

    override fun run() {
        try {
            execute()
        } finally {
            finished = true
        }
    }

    private fun execute() {
        val settings = engine.settings

        // 1. Metadata (re-analyzed lazily after process restarts).
        val metaResult = engine.metadataOrResolve(task)
        val meta: VideoMetadata = when (metaResult) {
            is Outcome.Ok -> metaResult.value
            is Outcome.Err -> {
                engine.onWorkerFinished(this, task, task.copy(
                    status = TaskStatus.FAILED,
                    errorCode = metaResult.code,
                    errorMessage = metaResult.message,
                    retryable = metaResult.retryable,
                ))
                return
            }
        }
        engine.updateTask(task.id) { it.copy(title = meta.title ?: it.title, thumbnailUrl = meta.thumbnailUrl ?: it.thumbnailUrl) }

        // 2. Format.
        val format = engine.pickFormat(task, meta)
        if (format == null) {
            engine.onWorkerFinished(this, task, task.copy(
                status = TaskStatus.UNSUPPORTED,
                errorCode = "unsupported",
                errorMessage = meta.downloadNote ?: "This video does not expose a downloadable file.",
            ))
            return
        }

        // 3. Storage headroom (before starting, not after filling the disk).
        val needed = format.fileSize ?: 50L * 1024 * 1024
        if (engine.storageForWorker.freeBytes() < needed + 50L * 1024 * 1024) {
            engine.onWorkerFinished(this, task, task.copy(
                status = TaskStatus.FAILED,
                errorCode = "insufficient_storage",
                errorMessage = "Not enough free storage space for this download.",
                retryable = true,
            ))
            return
        }

        // 4. Download with retries + resume.
        val temp = engine.storageForWorker.createTemp(task.id, format.container ?: "bin")
        var taskState = task.copy(status = TaskStatus.DOWNLOADING, attempts = task.attempts + 1)
        engine.updateTask(task.id) { taskState.copy(totalBytes = format.fileSize ?: -1) }

        val retryDelays = RetryPolicy.delays(settings.autoRetryCount + 1, 1500)
        var attempt = 0
        var outcome: HttpDownloader.Result? = null

        while (attempt <= settings.autoRetryCount) {
            if (cancelRequested.get()) break
            if (pauseRequested.get()) break
            if (!engine.hasNetwork) {
                Thread.sleep(1500)
                continue
            }
            val resumeFrom = if (temp.exists()) temp.length() else 0
            val lastProgress = LongArray(1)
            val lastTotal = LongArray(1) { -1 }
            outcome = HttpDownloader.run(
                HttpDownloader.Request(
                    url = format.url,
                    destFile = temp,
                    resumeFrom = resumeFrom,
                    onProgress = { down, total, speed ->
                        lastProgress[0] = down
                        lastTotal[0] = total
                        engine.updateTask(task.id) {
                            it.copy(status = TaskStatus.DOWNLOADING, downloadedBytes = down, totalBytes = total, speedBps = speed)
                        }
                    },
                    isCancelled = { pauseRequested.get() || cancelRequested.get() },
                )
            )
            when (outcome) {
                is HttpDownloader.Result.Completed -> {
                    taskState = taskState.copy(downloadedBytes = outcome.downloadedBytes, totalBytes = outcome.totalBytes)
                    break
                }
                is HttpDownloader.Result.AlreadyComplete -> {
                    taskState = taskState.copy(downloadedBytes = outcome.size, totalBytes = outcome.size)
                    break
                }
                is HttpDownloader.Result.PausedOrCancelled -> {
                    taskState = taskState.copy(downloadedBytes = outcome.downloadedBytes, totalBytes = outcome.totalBytes)
                    break
                }
                is HttpDownloader.Result.Failed -> {
                    taskState = taskState.copy(
                        downloadedBytes = outcome.downloadedBytes,
                        totalBytes = outcome.totalBytes,
                        errorCode = outcome.code,
                        errorMessage = outcome.message,
                        retryable = outcome.retryable,
                    )
                    if (!outcome.retryable || attempt >= settings.autoRetryCount) break
                    Thread.sleep(retryDelays.getOrElse(attempt) { 1500 })
                }
                null -> break
            }
            attempt++
        }

        if (cancelRequested.get()) {
            temp.delete()
            engine.onWorkerFinished(this, task, taskState.copy(status = TaskStatus.CANCELLED, errorCode = null, errorMessage = null))
            return
        }
        if (pauseRequested.get()) {
            engine.onWorkerFinished(this, task, taskState.copy(status = TaskStatus.PAUSED, speedBps = 0))
            return
        }

        val finalResult = outcome
        when {
            finalResult is HttpDownloader.Result.Completed || finalResult is HttpDownloader.Result.AlreadyComplete -> {
                val ext = format.container?.ifBlank { null } ?: extFromMime(format.mimeType) ?: "mp4"
                val stored = engine.storageForWorker.finalize(
                    temp, task.destination,
                    task.title ?: "video", ext, format.mimeType ?: "video/mp4",
                )
                if (stored == null) {
                    engine.onWorkerFinished(this, task, taskState.copy(
                        status = TaskStatus.FAILED,
                        errorCode = "storage_unavailable",
                        errorMessage = "The file could not be saved. Check storage and retry.",
                        retryable = true,
                    ))
                    return
                }
                engine.onWorkerFinished(this, task, taskState.copy(
                    status = TaskStatus.COMPLETED,
                    outputFileName = stored.name,
                    outputUri = stored.uri?.toString() ?: stored.path,
                    totalBytes = stored.size.takeIf { it > 0 } ?: taskState.totalBytes,
                    downloadedBytes = stored.size.takeIf { it > 0 } ?: taskState.downloadedBytes,
                    speedBps = 0,
                    errorCode = null,
                    errorMessage = null,
                ))
            }
            finalResult is HttpDownloader.Result.PausedOrCancelled -> {
                engine.onWorkerFinished(this, task, taskState.copy(status = TaskStatus.PAUSED, speedBps = 0))
            }
            else -> {
                temp.delete()
                engine.onWorkerFinished(this, task, taskState.copy(
                    status = TaskStatus.FAILED,
                    errorCode = taskState.errorCode ?: "error",
                    errorMessage = taskState.errorMessage ?: "Download failed.",
                    speedBps = 0,
                ))
            }
        }
    }

    private fun extFromMime(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "audio/mp4" -> "m4a"
        "audio/mpeg" -> "mp3"
        "audio/webm" -> "weba"
        "audio/ogg" -> "ogg"
        else -> null
    }
}
