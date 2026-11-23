package io.anytype.apibridge.testapp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun hasHeader(name: String) = headers.keys.any { it.equals(name, ignoreCase = true) }
}

class TestResponse(
    val code: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = emptyMap()
)

/**
 * Tiny HTTP/1.1 server bound to 127.0.0.1:0 that records requests and answers through [handler].
 * It is a ServerSocket rather than the JDK's com.sun.net.httpserver.HttpServer because the
 * android.jar-based test compilation cannot see the jdk.httpserver module.
 */
class TestServer(private val handler: (RecordedRequest) -> TestResponse) {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { serve(c) } catch (_: Exception) {} finally { c.close() } }
            }
        }
    }

    private fun readLine(i: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = i.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
    }

    private fun serve(c: Socket) {
        val i = BufferedInputStream(c.getInputStream())
        val (method, path) = (readLine(i) ?: return).split(" ")
        val headers = linkedMapOf<String, MutableList<String>>()
        while (true) {
            val line = readLine(i) ?: break
            if (line.isEmpty()) break
            headers.getOrPut(line.substringBefore(":").trim()) { mutableListOf() }.add(line.substringAfter(":").trim())
        }
        val len = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.first()?.toInt() ?: 0
        val body = ByteArray(len).also { var n = 0; while (n < len) { val r = i.read(it, n, len - n); if (r < 0) break; n += r } }
        val req = RecordedRequest(method, path, headers, body.toString(Charsets.UTF_8))
        requests.add(req)
        val resp = handler(req)
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${resp.code} X\r\n")
        resp.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (bytes.isNotEmpty() && resp.headers.keys.none { it.equals("Content-Type", true) }) {
            head.append("Content-Type: application/json\r\n")
        }
        head.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        c.getOutputStream().apply { write(head.toString().toByteArray()); write(bytes); flush() }
    }

    fun stop() = socket.close()
}
