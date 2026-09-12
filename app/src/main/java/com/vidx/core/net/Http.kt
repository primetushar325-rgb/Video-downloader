package com.vidx.core.net

import com.vidx.core.util.Outcome
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

/**
 * Dependency-free HTTP client used by every VIDX adapter and the downloader.
 *
 * Design notes:
 *  - Plain java.net.HttpURLConnection (no OkHttp dependency).
 *  - Strict timeouts; never infinite hangs.
 *  - Errors are mapped to stable codes so the UI can show friendly messages.
 *  - Response bodies for API calls are capped ([maxBytes]) to bound memory use.
 *  - No cookies/auth tokens are stored or attached; only public endpoints are called.
 *  - Redirects are followed by the platform default (https→https).
 */
object HttpClient {

    const val USER_AGENT = "VIDX/1.0 (Android; +https://github.com/primetushar325-rgb/Video-downloader)"
    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    const val DEFAULT_READ_TIMEOUT_MS = 30_000

    /**
     * Test hook: maps an exact URL prefix to a replacement (e.g. the public
     * YouTube endpoint to a local mock server). Only used by the JVM test suite;
     * empty in production builds.
     */
    @Volatile
    var urlOverrides: Map<String, String> = emptyMap()

    data class Response(
        val status: Int,
        val statusText: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val ok: Boolean get() = status in 200..299
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
        fun contentLength(): Long = header("content-length")?.toLongOrNull() ?: -1L
        fun text(): String = String(body, Charsets.UTF_8)
    }

    sealed class HttpError(val code: String, message: String, val retryable: Boolean, val status: Int) :
        Exception(message) {
        class Network(message: String) : HttpError("network", "Network error — check your connection: $message", true, 0)
        class Timeout : HttpError("timeout", "Request timed out. The server did not respond in time.", true, 0)
        class TooLarge(max: Long) : HttpError("too_large", "Response exceeded the $max byte limit.", false, 0)
        class Status(val responseStatus: Int, val reason: String) : HttpError(
            when (responseStatus) {
                403 -> "forbidden"; 401 -> "auth_required"; 404 -> "not_found"; 410 -> "gone"
                429 -> "rate_limited"; 451 -> "unavailable_legal"; else -> "http_$responseStatus"
            },
            messageFor(responseStatus, reason),
            responseStatus in setOf(408, 425, 429, 500, 502, 503, 504),
            responseStatus,
        )

        companion object {
            fun messageFor(status: Int, reason: String): String = when (status) {
                400 -> "The server rejected the request (400)."
                401, 403 -> "This content is private or access-restricted (HTTP $status)."
                404 -> "The content was not found — it may have been removed or the URL is wrong."
                410 -> "This content has been permanently removed (HTTP 410)."
                429 -> "Too many requests — the server asked us to slow down (HTTP 429)."
                451 -> "This content is not available for legal reasons in your region (HTTP 451)."
                in 500..599 -> "The server is having trouble right now (HTTP $status)."
                else -> "The server returned HTTP $status${if (reason.isNotBlank()) ": $reason" else ""}."
            }
        }
    }

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Long = 4L * 1024 * 1024,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): Outcome<Response> {
        val effectiveUrl = urlOverrides.entries.firstOrNull { (from, _) -> url.startsWith(from) }
            ?.let { (from, to) -> to + url.substring(from.length) } ?: url

        val conn = try {
            val c = URL(effectiveUrl).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.setRequestProperty("Connection", "close")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            c
        } catch (e: UnknownHostException) {
            return Outcome.Err(HttpError.Network("unknown host").code, HttpError.Network("unknown host").message ?: "network error", true)
        } catch (e: java.net.SocketTimeoutException) {
            return Outcome.Err("timeout", HttpError.Timeout().message ?: "timeout", true)
        } catch (e: Exception) {
            return Outcome.Err("network", "Network error — check your connection.", true)
        }

        try {
            val status = conn.responseCode
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { (k, _) -> k!!.lowercase() }
                .mapValues { (_, v) -> v.filterNotNull() }
            val stream: InputStream = try {
                if (status in 200..299) conn.inputStream else conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
            } catch (e: Exception) {
                ByteArrayInputStream(ByteArray(0))
            }
            val body = readCapped(stream, maxBytes)
            stream.close()
            return if (status in 200..299) {
                Outcome.Ok(Response(status, conn.responseMessage ?: "", headers, body))
            } else {
                Outcome.Err("http_$status", HttpError.Status(status, "").message ?: "HTTP $status", status in setOf(408, 425, 429, 500, 502, 503, 504))
            }
        } catch (e: java.net.SocketTimeoutException) {
            return Outcome.Err("timeout", "Request timed out.", true)
        } catch (e: HttpError) {
            return Outcome.Err(e.code, e.message ?: "HTTP error", e.retryable)
        } catch (e: Exception) {
            return Outcome.Err("network", "Network error — check your connection.", true)
        } finally {
            conn.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = 4L * 1024 * 1024): Outcome<Response> =
        request(url, "GET", headers, maxBytes = maxBytes)

    fun postJson(url: String, json: String, maxBytes: Long = 8L * 1024 * 1024): Outcome<Response> =
        request(url, "POST", body = json.toByteArray(Charsets.UTF_8), maxBytes = maxBytes)

    private fun readCapped(input: InputStream, maxBytes: Long): ByteArray {
        val pb = java.io.PushbackInputStream(java.io.BufferedInputStream(input), 2)
        val magic = ByteArray(2)
        val n = pb.read(magic)
        val out = ByteArrayOutputStream(64 * 1024)
        if (n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
            GZIPInputStream(pb).use { gz ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val r = gz.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > maxBytes) throw HttpError.TooLarge(maxBytes)
                    out.write(buf, 0, r)
                }
            }
            return out.toByteArray()
        }
        if (n == 1) out.write(magic, 0, 1)
        if (n == 2) out.write(magic, 0, 2)
        val buf = ByteArray(16 * 1024)
        var total = n.toLong()
        while (true) {
            val r = pb.read(buf)
            if (r < 0) break
            total += r
            if (total > maxBytes) throw HttpError.TooLarge(maxBytes)
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    fun InputStream.copyTo(out: OutputStream, bufferSize: Int = 32 * 1024): Long {
        val buf = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val r = read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        return total
    }
}

private class ByteArrayInputStream(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes)
