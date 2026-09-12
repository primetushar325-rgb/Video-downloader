package com.vidx.core.download

import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.MockServer
import com.vidx.testlib.Tests
import java.io.File

object HttpDownloaderTest {
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    init {
        Tests.test("downloader", "downloads a full file") {
            val srv = MockServer().apply { file("/f.bin", bytes); start() }
            try {
                val dest = File.createTempFile("vidx", ".part")
                var last = 0L
                val res = HttpDownloader.run(
                    HttpDownloader.Request(srv.url("/f.bin"), dest, onProgress = { d, _, _ -> last = d })
                )
                assertTrue(res is HttpDownloader.Result.Completed, "expected completed, got $res")
                assertEquals(bytes.size.toLong(), dest.length())
                assertTrue(bytes.contentEquals(dest.readBytes()), "downloaded bytes must match")
            } finally { srv.stop() }
        }

        Tests.test("downloader", "pause and resume completes the file") {
            val srv = MockServer().apply { file("/f.bin", bytes); start() }
            try {
                val dest = File.createTempFile("vidx", ".part")
                var cancelled = false
                var pausedAt = 0L

                // First attempt: cancel after ~50 KB
                val r1 = HttpDownloader.run(
                    HttpDownloader.Request(srv.url("/f.bin"), dest, onProgress = { d, _, _ ->
                        pausedAt = d
                        if (d > 50_000) cancelled = true
                    }, isCancelled = { cancelled })
                )
                assertTrue(r1 is HttpDownloader.Result.PausedOrCancelled && r1.cancelled, "first run should pause")
                val partialLen = dest.length()
                assertTrue(partialLen in 50_001..bytes.size.toLong(), "partial file exists ($partialLen)")

                // Second attempt: resume from partial
                var completed = 0L
                val r2 = HttpDownloader.run(
                    HttpDownloader.Request(srv.url("/f.bin"), dest, resumeFrom = partialLen,
                        onProgress = { d, _, _ -> completed = d })
                )
                assertTrue(r2 is HttpDownloader.Result.Completed, "resume should complete, got $r2")
                assertEquals(bytes.size.toLong(), dest.length())
                assertTrue(bytes.contentEquals(dest.readBytes()), "resumed bytes must match")
                assertTrue(completed == bytes.size.toLong())
            } finally { srv.stop() }
        }

        Tests.test("downloader", "resume restarts cleanly when server ignores Range") {
            val srv = MockServer().apply { file("/f.bin", bytes, supportRange = false); start() }
            try {
                val dest = File.createTempFile("vidx", ".part")
                dest.writeBytes(ByteArray(1234)) // stale partial
                val res = HttpDownloader.run(HttpDownloader.Request(srv.url("/f.bin"), dest, resumeFrom = 1234))
                assertTrue(res is HttpDownloader.Result.Completed, "got $res")
                assertEquals(bytes.size.toLong(), dest.length())
                assertTrue(bytes.contentEquals(dest.readBytes()))
            } finally { srv.stop() }
        }

        Tests.test("downloader", "http errors surface with retryable flags") {
            val srv = MockServer().apply { json("/gone", "{}", 404); start() }
            try {
                val res = HttpDownloader.run(HttpDownloader.Request(srv.url("/gone"), File.createTempFile("vidx", ".part")))
                assertTrue(res is HttpDownloader.Result.Failed, "got $res")
                assertEquals("http_404", (res as HttpDownloader.Result.Failed).code)
                assertEquals(false, res.retryable)
            } finally { srv.stop() }
        }

        Tests.test("downloader", "chunked responses (unknown size) complete") {
            val srv = MockServer().apply { chunkedFile("/c.bin", bytes); start() }
            try {
                val dest = File.createTempFile("vidx", ".part")
                var lastTotal = -1L
                val res = HttpDownloader.run(
                    HttpDownloader.Request(srv.url("/c.bin"), dest, onProgress = { _, total, _ -> lastTotal = total })
                )
                assertTrue(res is HttpDownloader.Result.Completed, "got $res")
                assertEquals(bytes.size.toLong(), dest.length())
                assertEquals(-1L, lastTotal, "total stays unknown for chunked")
            } finally { srv.stop() }
        }

        Tests.test("downloader", "timeout is retryable and keeps partial file") {
            val srv = MockServer().apply {
                routeRaw("/slow") { _, out ->
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    out.write(bytes.copyOfRange(0, 1000))
                    out.flush() // deliver a chunk, then drop the connection
                    throw RuntimeException("connection dropped")
                }
                start()
            }
            try {
                val dest = File.createTempFile("vidx", ".part")
                val res = HttpDownloader.run(HttpDownloader.Request(srv.url("/slow"), dest))
                assertTrue(res is HttpDownloader.Result.Failed, "got $res")
                assertEquals(true, (res as HttpDownloader.Result.Failed).retryable)
                assertTrue(dest.length() > 0, "partial kept for resume")
            } finally { srv.stop() }
        }
    }
}
