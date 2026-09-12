package com.vidx.core.download

import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.Tests

object DownloadQueueTest {

    private fun task(id: String, key: String, status: TaskStatus = TaskStatus.QUEUED, priority: Int = 0) =
        DownloadTask(id = id, url = "https://example.com/$id", displayUrl = "https://example.com/$id",
            canonicalKey = key, platformId = "direct", status = status, priority = priority)

    init {
        Tests.test("queue", "duplicate keys are blocked") {
            val q = DownloadQueue()
            val r1 = q.add(task("a", "k1"))
            val r2 = q.add(task("b", "k1"))
            assertTrue(r1 is AddResult.Added)
            assertTrue(r2 is AddResult.Duplicate)
            assertEquals("a", (r2 as AddResult.Duplicate).existing.id)
            assertEquals(1, q.snapshot().size)
        }

        Tests.test("queue", "different keys are all added") {
            val q = DownloadQueue()
            q.addAll(listOf(task("a", "k1"), task("b", "k2"), task("c", "k3")))
            assertEquals(3, q.snapshot().size)
        }

        Tests.test("queue", "reorder moves positions and renumbers priorities") {
            val q = DownloadQueue(listOf(task("a", "k1", priority = 0), task("b", "k2", priority = 1), task("c", "k3", priority = 2)))
            q.move(2, 0) // C to position 1
            assertEquals(listOf("c", "a", "b"), q.snapshot().map { it.id })
            assertEquals(listOf(0, 1, 2), q.snapshot().map { it.priority })
            assertTrue(q.moveToTop("b"))
            assertEquals(listOf("b", "c", "a"), q.snapshot().map { it.id })
        }

        Tests.test("queue", "nextToStart respects max concurrent + priority order") {
            val q = DownloadQueue(listOf(
                task("a", "k1"), task("b", "k2"), task("c", "k3"),
                task("d", "k4", priority = -1), // highest priority
            ))
            val batch = q.nextToStart(activeCount = 0, maxConcurrent = 2)
            assertEquals(listOf("d", "a"), batch.map { it.id })
            val none = q.nextToStart(activeCount = 2, maxConcurrent = 2)
            assertEquals(0, none.size)
        }

        Tests.test("queue", "status transitions") {
            val q = DownloadQueue(listOf(task("a", "k1"), task("b", "k2", status = TaskStatus.FAILED)))
            q.update("a") { it.copy(status = TaskStatus.DOWNLOADING) }
            q.update("a") { it.copy(status = TaskStatus.PAUSED, downloadedBytes = 5) }
            q.update("a") { it.copy(status = TaskStatus.COMPLETED, downloadedBytes = 10, totalBytes = 10) }
            assertEquals(100, q.get("a")?.progressPct)
            assertEquals(1, q.retryFailed())
            assertEquals(TaskStatus.QUEUED, q.get("b")?.status)
            val s = q.summary()
            assertEquals(2, s.total)
            assertEquals(1, s.completed)
            assertEquals(1, s.queued)
        }

        Tests.test("queue", "retry only affects failed tasks") {
            val q = DownloadQueue(listOf(
                task("a", "k1", status = TaskStatus.FAILED),
                task("b", "k2", status = TaskStatus.COMPLETED),
            ))
            assertEquals(1, q.retryIds(setOf("a", "b")))
            assertEquals(TaskStatus.QUEUED, q.get("a")?.status)
            assertEquals(TaskStatus.COMPLETED, q.get("b")?.status)
        }

        Tests.test("queue", "pause all / queue all") {
            val q = DownloadQueue(listOf(
                task("a", "k1", status = TaskStatus.DOWNLOADING),
                task("b", "k2", status = TaskStatus.DOWNLOADING),
                task("c", "k3", status = TaskStatus.QUEUED),
            ))
            assertEquals(2, q.pauseAllDownloading())
            assertEquals(2, q.queueAllPaused())
            assertEquals(3, q.queuedCount())
        }

        Tests.test("queue", "remove many + clear finished") {
            val q = DownloadQueue(listOf(
                task("a", "k1"), task("b", "k2"), task("c", "k3", status = TaskStatus.COMPLETED),
                task("d", "k4", status = TaskStatus.CANCELLED),
            ))
            assertEquals(2, q.removeMany(setOf("a", "b")))
            assertEquals(2, q.clearFinished())
            assertEquals(0, q.snapshot().size)
        }

        Tests.test("queue", "json persistence round-trip") {
            val q = DownloadQueue(listOf(
                DownloadTask(id = "x1", url = "https://youtu.be/abc", displayUrl = "https://youtu.be/abc",
                    canonicalKey = "youtube:abc", platformId = "youtube", title = "Some \"video\"\nTitle",
                    destination = Destination.AUDIO, status = TaskStatus.FAILED, totalBytes = 1234,
                    downloadedBytes = 100, attempts = 2, errorCode = "http_404", errorMessage = "gone",
                    retryable = false, createdAt = 1700000000000L, priority = 3),
            ))
            val json = QueueJson.serialize(q.snapshot())
            val restored = QueueJson.deserialize(json)
            assertEquals(q.snapshot(), restored)
        }

        Tests.test("queue", "corrupt persistence is ignored, never crashes") {
            assertEquals(0, QueueJson.deserialize("{not json").size)
            assertEquals(0, QueueJson.deserialize("").size)
        }
    }
}
