package io.anytype.agentbridge.client

import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL

data class HttpResponse(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: String
) {
    /** Case-insensitive lookup of the first value of a response header. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Minimal HttpURLConnection wrapper. Never sets an Origin header: heart answers 403 to any
 * non-loopback Origin. Non-2xx responses are returned, not thrown.
 */
class HttpTransport(private val timeoutMs: Int = 60_000) {

    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            setMethod(conn, method)
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val respHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.toList() }
            return HttpResponse(code, respHeaders, text)
        } finally {
            conn.disconnect()
        }
    }

    // Android's HttpURLConnection accepts PATCH; the JDK's does not, so fall back to setting the
    // protected `method` field (needs --add-opens java.base/java.net=ALL-UNNAMED on a JVM).
    private fun setMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
        } catch (e: ProtocolException) {
            try {
                val f = HttpURLConnection::class.java.getDeclaredField("method")
                f.isAccessible = true
                f.set(conn, method)
            } catch (_: Exception) {
                throw e
            }
        }
    }
}
