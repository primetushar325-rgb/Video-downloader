package com.vidx.core.download

import com.vidx.core.json.Json
import com.vidx.core.json.JsonValue
import com.vidx.core.json.JsonWriter

/**
 * Persistence port for the queue. The Android layer implements this with a JSON
 * file + SQLite history; unit tests use an in-memory implementation.
 *
 * The queue state is snapshotted on every transition so process death or app
 * restart loses nothing (except a partial chunk, which resumes via Range).
 */
interface QueueStore {
    fun save(tasks: List<DownloadTask>)
    fun load(): List<DownloadTask>
}

class InMemoryQueueStore : QueueStore {
    var tasks: List<DownloadTask> = emptyList()
    override fun save(tasks: List<DownloadTask>) { this.tasks = tasks }
    override fun load(): List<DownloadTask> = tasks
}

/** JSON snapshot format (also used for debugging). */
object QueueJson {
    fun serialize(tasks: List<DownloadTask>): String {
        val items = tasks.joinToString(",") { taskJson(it) }
        return "{\"version\":1,\"tasks\":[$items]}"
    }

    fun deserialize(text: String): List<DownloadTask> {
        return try {
            val root = Json.parseObject(text)
            root.arr("tasks")?.items?.mapNotNull { it as? JsonValue.Obj }?.mapNotNull(::taskFromJson)
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun taskJson(t: DownloadTask): String = JsonWriter.obj(
        "id" to JsonWriter.string(t.id),
        "url" to JsonWriter.string(t.url),
        "displayUrl" to JsonWriter.string(t.displayUrl),
        "canonicalKey" to JsonWriter.string(t.canonicalKey),
        "platformId" to JsonWriter.string(t.platformId),
        "title" to JsonWriter.string(t.title ?: ""),
        "thumbnailUrl" to JsonWriter.string(t.thumbnailUrl ?: ""),
        "durationMillis" to (t.durationMillis ?: -1).toString(),
        "destination" to JsonWriter.string(t.destination.id),
        "status" to JsonWriter.string(t.status.id),
        "totalBytes" to t.totalBytes.toString(),
        "downloadedBytes" to t.downloadedBytes.toString(),
        "speedBps" to t.speedBps.toString(),
        "attempts" to t.attempts.toString(),
        "errorCode" to JsonWriter.string(t.errorCode ?: ""),
        "errorMessage" to JsonWriter.string(t.errorMessage ?: ""),
        "retryable" to (if (t.retryable) "true" else "false"),
        "outputFileName" to JsonWriter.string(t.outputFileName ?: ""),
        "outputUri" to JsonWriter.string(t.outputUri ?: ""),
        "createdAt" to t.createdAt.toString(),
        "priority" to t.priority.toString(),
        "formatId" to JsonWriter.string(t.formatId ?: ""),
        "selectedQualityLabel" to JsonWriter.string(t.selectedQualityLabel ?: ""),
    )

    private fun taskFromJson(o: JsonValue.Obj): DownloadTask? {
        val id = o.str("id") ?: return null
        val url = o.str("url") ?: return null
        return DownloadTask(
            id = id,
            url = url,
            displayUrl = o.str("displayUrl") ?: url,
            canonicalKey = o.str("canonicalKey") ?: url,
            platformId = o.str("platformId") ?: "unknown",
            title = o.str("title")?.takeIf { it.isNotEmpty() },
            thumbnailUrl = o.str("thumbnailUrl")?.takeIf { it.isNotEmpty() },
            durationMillis = o.long("durationMillis")?.takeIf { it > 0 },
            destination = Destination.fromId(o.str("destination")),
            status = TaskStatus.fromId(o.str("status")),
            totalBytes = o.long("totalBytes") ?: -1,
            downloadedBytes = o.long("downloadedBytes") ?: 0,
            speedBps = o.long("speedBps") ?: 0,
            attempts = o.int("attempts") ?: 0,
            errorCode = o.str("errorCode")?.takeIf { it.isNotEmpty() },
            errorMessage = o.str("errorMessage")?.takeIf { it.isNotEmpty() },
            retryable = o.bool("retryable") ?: true,
            outputFileName = o.str("outputFileName")?.takeIf { it.isNotEmpty() },
            outputUri = o.str("outputUri")?.takeIf { it.isNotEmpty() },
            createdAt = o.long("createdAt") ?: System.currentTimeMillis(),
            priority = o.int("priority") ?: 0,
            formatId = o.str("formatId")?.takeIf { it.isNotEmpty() },
            selectedQualityLabel = o.str("selectedQualityLabel")?.takeIf { it.isNotEmpty() },
        )
    }
}
