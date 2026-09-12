package com.vidx.testlib

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Tiny local HTTP server for adapter/downloader tests — no network needed.
 * Serves canned responses and supports Range requests for resume testing.
 */
class MockServer {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val handlers = LinkedHashMap<String, (HttpExchange) -> Unit>()

    val port: Int get() = server.address.port
    fun url(path: String) = "http://127.0.0.1:$port$path"

    private var started = false

    fun route(path: String, handler: (HttpExchange) -> Unit) {
        handlers[path] = handler
        if (started) registerContext(path, handler)
    }

    private fun registerContext(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            try {
                handler(ex)
            } catch (e: Exception) {
                try {
                    val msg = e.message?.toByteArray() ?: ByteArray(0)
                    ex.sendResponseHeaders(500, msg.size.toLong())
                    ex.responseBody.use { it.write(msg) }
                } catch (_: Exception) {}
            } finally {
                try { ex.close() } catch (_: Exception) {}
            }
        }
    }

    fun json(path: String, body: String, status: Int = 200) {
        route(path) { ex ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    /** Serves [bytes] with optional Range support (for resume tests). */
    fun file(path: String, bytes: ByteArray, supportRange: Boolean = true, mime: String = "application/octet-stream", failAfter: Int = Int.MAX_VALUE) {
        route(path) { ex ->
            ex.responseHeaders.add("Content-Type", mime)
            if (ex.requestMethod == "HEAD") {
                ex.responseHeaders.add("Content-Length", bytes.size.toString())
                ex.sendResponseHeaders(200, -1)
                ex.close()
                return@route
            }
            val range = ex.requestHeaders.getFirst("Range")
            if (supportRange && range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                val end = bytes.size - 1L
                if (start > end) {
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return@route
                }
                val slice = bytes.copyOfRange(start.toInt(), bytes.size)
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                ex.responseHeaders.add("Content-Length", slice.size.toString())
                ex.sendResponseHeaders(206, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            } else {
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
    }

    fun chunkedFile(path: String, bytes: ByteArray) {
        route(path) { ex ->
            ex.sendResponseHeaders(200, 0) // chunked — no Content-Length
            ex.responseBody.use { out ->
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(4096, bytes.size - i)
                    out.write(bytes, i, n)
                    out.flush()
                    i += n
                    Thread.sleep(2)
                }
            }
        }
    }

    fun start(): MockServer {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "mock-http").apply { isDaemon = true } }
        started = true
        for ((path, handler) in handlers) registerContext(path, handler)
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
    }
}
