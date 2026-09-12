package com.vidx.testlib

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Tiny local HTTP/1.1 server for adapter/downloader tests — no network needed.
 *
 * Deliberately built on plain `java.net` sockets: the JDK's
 * `com.sun.net.httpserver` module is NOT visible to AGP unit-test compilation
 * (there `java.*` resolves against android.jar, which has no `com.sun.*`
 * packages), while `java.net` exists in every environment — the real JDK, the
 * Android toolchain, and the Gradle unit-test compile.
 */
class MockServer {

    /** Portable request abstraction (no com.sun.* types). */
    class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** Portable response abstraction for canned handlers. */
    class Response {
        var status: Int = 200
        val headers = LinkedHashMap<String, String>()
        var body: ByteArray = ByteArray(0)

        fun header(name: String, value: String) {
            headers[name] = value
        }
    }

    private val handlers = LinkedHashMap<String, (Request, Response) -> Unit>()
    private val rawHandlers = LinkedHashMap<String, (Request, OutputStream) -> Unit>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var stopped = false
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mock-http").apply { isDaemon = true } }

    val port: Int get() = serverSocket?.localPort ?: error("MockServer not started")
    fun url(path: String) = "http://127.0.0.1:$port$path"

    /** Canned handler: fill the [Response]; the server writes it on the wire. */
    fun route(path: String, handler: (Request, Response) -> Unit) {
        handlers[path] = handler
    }

    /** Raw handler: the handler writes the full response to the [OutputStream]
     *  itself (used to simulate connection drops / premature EOFs). */
    fun routeRaw(path: String, handler: (Request, OutputStream) -> Unit) {
        rawHandlers[path] = handler
    }

    fun json(path: String, body: String, status: Int = 200) {
        route(path) { _, resp ->
            resp.status = status
            resp.header("Content-Type", "application/json")
            resp.body = body.toByteArray(Charsets.UTF_8)
        }
    }

    /** Serves [bytes] with optional Range support (for resume tests). */
    fun file(path: String, bytes: ByteArray, supportRange: Boolean = true, mime: String = "application/octet-stream") {
        route(path) { req, resp ->
            resp.header("Content-Type", mime)
            val range = req.header("Range")
            if (supportRange && range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                val end = bytes.size - 1L
                if (start > end) {
                    resp.status = 416
                    resp.header("Content-Range", "bytes */${bytes.size}")
                    resp.body = ByteArray(0)
                    return@route
                }
                val slice = bytes.copyOfRange(start.toInt(), bytes.size)
                resp.status = 206
                resp.header("Content-Range", "bytes $start-$end/${bytes.size}")
                resp.body = slice
            } else {
                resp.body = bytes
            }
        }
    }

    /** Streams [bytes] with chunked transfer-encoding (no Content-Length),
     *  so the client reports an unknown total size. */
    fun chunkedFile(path: String, bytes: ByteArray) {
        routeRaw(path) { _, out ->
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            var i = 0
            while (i < bytes.size) {
                val n = minOf(4096, bytes.size - i)
                out.write(Integer.toHexString(n).toByteArray())
                out.write("\r\n".toByteArray())
                out.write(bytes, i, n)
                out.write("\r\n".toByteArray())
                out.flush()
                i += n
                Thread.sleep(2)
            }
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    fun start(): MockServer {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        stopped = false
        Thread({ acceptLoop(ss) }, "mock-accept").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ core

    /** Longest-prefix route resolution, like a real HTTP server's context
     *  matching (more specific paths win over shorter prefixes). */
    private fun <V> longestMatch(map: LinkedHashMap<String, V>, path: String): Map.Entry<String, V>? =
        map.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopped) {
            try {
                val sock = ss.accept()
                pool.execute { handleConnection(sock) }
            } catch (e: Exception) {
                if (stopped || ss.isClosed) break
            }
        }
    }

    private fun handleConnection(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val req = Request(method, path, headers)

            val raw = longestMatch(rawHandlers, path)?.value
            if (raw != null) {
                raw(req, output) // handler owns the stream; the server closes after
                return
            }
            val canned = longestMatch(handlers, path)?.value
            if (canned == null) {
                writeHead(output, 404, "Not Found", mapOf("Content-Length" to "0"), body = ByteArray(0), headOnly = method == "HEAD")
                return
            }
            val resp = Response()
            canned(req, resp)
            writeCanned(req, resp, output)
        } catch (e: Exception) {
            // An abrupt close is a legitimate simulation (premature EOF).
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun writeCanned(req: Request, resp: Response, out: OutputStream) {
        val headers = LinkedHashMap(resp.headers)
        if (req.method == "HEAD") {
            headers["Content-Length"] = resp.body.size.toString()
        } else if (!headers.containsKey("Content-Length")) {
            headers["Content-Length"] = resp.body.size.toString()
        }
        writeHead(out, resp.status, reasonFor(resp.status), headers, resp.body, headOnly = req.method == "HEAD")
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, headers: Map<String, String>, body: ByteArray, headOnly: Boolean) {
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        302 -> "Found"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        else -> "Status"
    }
}
