package com.vidx.core.download

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streaming HTTP downloader — pure Kotlin/Java (JVM-testable against a local
 * server). One call = one attempt; retries with resume are orchestrated by the
 * engine above it.
 *
 * Features:
 *  - resume via Range requests (paused/interrupted downloads continue)
 *  - pause = stop between chunks (the partial file stays valid for resume)
 *  - cancel checks every chunk
 *  - speed measurement (smoothed) for live UI
 *  - handles missing Content-Length (indeterminate progress)
 *  - 416 handling: if the server says the range is invalid AND the partial
 *    size equals the file size, the download is already complete.
 */
object HttpDownloader {

    data class Request(
        val url: String,
        val destFile: File,
        val resumeFrom: Long = 0,
        val onProgress: (downloadedTotal: Long, total: Long, speedBps: Long) -> Unit = { _, _, _ -> },
        val isCancelled: () -> Boolean = { false },
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 40_000,
        val chunkSize: Int = 64 * 1024,
    )

    sealed class Result {
        data class Completed(val file: File, val downloadedBytes: Long, val totalBytes: Long) : Result()
        data class PausedOrCancelled(val downloadedBytes: Long, val totalBytes: Long, val cancelled: Boolean) : Result()
        data class Failed(val code: String, val message: String, val retryable: Boolean, val downloadedBytes: Long, val totalBytes: Long) : Result()
        data class AlreadyComplete(val file: File, val size: Long) : Result()
    }

    fun run(req: Request): Result {
        val partial = req.resumeFrom
        val startOffset = if (partial > 0 && req.destFile.exists()) minOf(partial, req.destFile.length()) else 0

        val conn = try {
            val c = URL(req.url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = req.connectTimeoutMs
            c.readTimeout = req.readTimeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "VIDX/1.0")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Connection", "close")
            if (startOffset > 0) c.setRequestProperty("Range", "bytes=$startOffset-")
            c
        } catch (e: Exception) {
            return Result.Failed("network", "Could not connect to the server.", true, startOffset, -1)
        }

        try {
            val status = conn.responseCode

            // 416 = range not satisfiable → we may already have the whole file.
            if (status == 416) {
                val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                if (total != null && total == req.destFile.length()) {
                    return Result.AlreadyComplete(req.destFile, total)
                }
                // Range was wrong — restart from scratch.
                conn.disconnect()
                return run(req.copy(resumeFrom = 0).let { r ->
                    r.copy(destFile = req.destFile, onProgress = req.onProgress, isCancelled = req.isCancelled)
                })
            }

            if (status !in 200..299) {
                return Result.Failed(
                    "http_$status",
                    "The server returned HTTP $status.",
                    status in setOf(408, 425, 429, 500, 502, 503, 504),
                    startOffset, -1,
                )
            }

            val resuming = status == 206
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
            val total = when {
                contentLength != null && resuming -> startOffset + contentLength
                contentLength != null -> contentLength
                else -> -1L
            }

            // 200 while we expected resume → the server ignored Range; restart clean.
            val raf = if (resuming) RandomAccessFile(req.destFile, "rw") else {
                RandomAccessFile(req.destFile, "rw").apply { setLength(0) }
            }
            raf.seek(if (resuming) startOffset else 0)

            val input = conn.inputStream
            val buf = ByteArray(req.chunkSize)
            var downloaded = if (resuming) startOffset else 0L
            var lastBytes = 0L
            var lastTime = System.nanoTime()
            var speed = 0L

            try {
                while (true) {
                    if (req.isCancelled()) {
                        return Result.PausedOrCancelled(downloaded, total, cancelled = true)
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    lastBytes += n
                    val now = System.nanoTime()
                    val elapsedSec = (now - lastTime) / 1e9
                    if (elapsedSec >= 0.5) {
                        speed = if (elapsedSec > 0) (lastBytes / elapsedSec).toLong() else 0
                        lastBytes = 0
                        lastTime = now
                    }
                    req.onProgress(downloaded, total, speed)
                }
            } finally {
                try { input.close() } catch (_: Exception) {}
                try { raf.close() } catch (_: Exception) {}
            }

            return if (total > 0 && downloaded < total) {
                Result.Failed("interrupted", "The connection dropped before the download finished.", true, downloaded, total)
            } else {
                Result.Completed(req.destFile, downloaded, total)
            }
        } catch (e: java.net.SocketTimeoutException) {
            return Result.Failed("timeout", "The download timed out.", true, req.resumeFrom, -1)
        } catch (e: java.io.IOException) {
            return Result.Failed("network", "The connection dropped.", true, req.resumeFrom, -1)
        } catch (e: Exception) {
            return Result.Failed("error", e.message ?: "Download failed.", false, req.resumeFrom, -1)
        } finally {
            conn.disconnect()
        }
    }
}
