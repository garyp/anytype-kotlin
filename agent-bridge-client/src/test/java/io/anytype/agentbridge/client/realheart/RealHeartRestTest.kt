package io.anytype.agentbridge.client.realheart

import io.anytype.agentbridge.client.ApiError
import io.anytype.agentbridge.client.Endpoint
import io.anytype.agentbridge.client.RestClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealHeartRestTest {

    @Before fun gate() = RealHeart.assumeAvailable()

    @Test fun v2SpacesListsAccountSpace() {
        val body = JSONObject(RestClient(RealHeart.endpoint, RealHeart.transport).get("/v2/spaces"))
        val ids = (0 until body.getJSONArray("data").length()).map { body.getJSONArray("data").getJSONObject(it).getString("id") }
        assertTrue("short id of ${RealHeart.spaceId} not in $ids", RealHeart.shortId(RealHeart.spaceId) in ids)
    }

    @Test fun keylessRequestsAreRejected() {
        for (path in listOf("/v1/spaces", "/v2/spaces")) {
            val r = RealHeart.transport.request("GET", RealHeart.baseUrl + path, mapOf("Accept" to "application/json"), null)
            assertEquals(path, 401, r.code)
        }
        val mcp = RealHeart.transport.request(
            "POST", RealHeart.baseUrl + "/mcp/small",
            mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream"), "{}"
        )
        assertEquals(401, mcp.code)
    }

    @Test fun revokedKeyGets401() {
        val app = RealHeart.createApp("contract-revoke-${System.nanoTime()}", readWrite = true)
        val client = RestClient(Endpoint(RealHeart.baseUrl, app.key), RealHeart.transport)
        client.get("/v2/spaces") // works before revoke
        RealHeart.revoke(app.hash)
        val e = assertThrows(ApiError::class.java) { client.get("/v2/spaces") }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
        assertEquals("invalid api key", e.message)
    }

    // The JDK's HttpURLConnection silently drops a caller-set `Origin` header (restricted header), so
    // this sends the request over a raw socket to make sure heart really sees the foreign Origin.
    @Test fun foreignOriginIs403() {
        val uri = URI(RealHeart.baseUrl)
        val status = Socket(uri.host, uri.port).use { s ->
            s.soTimeout = 10_000
            val req = "GET /v2/spaces HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" +
                "Authorization: Bearer ${RealHeart.apiKey}\r\nOrigin: https://evil.example\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine()
        }
        assertTrue("status line: $status", status.startsWith("HTTP/1.1 403"))
        // Same request without the Origin header succeeds, so the 403 is caused by the Origin.
        assertEquals(200, RealHeart.transport.request(
            "GET", RealHeart.baseUrl + "/v2/spaces", mapOf("Authorization" to "Bearer ${RealHeart.apiKey}"), null
        ).code)
    }

    /**
     * Closes the listener, then reopens it on the SAME port (not `:0`) so the env-provided base URL
     * stays valid for every other test class in this JVM.
     */
    @Test fun emptyListenAddrClosesPort() {
        val uri = URI(RealHeart.baseUrl)
        val addr = "${uri.host}:${uri.port}"
        assertEquals("", RealHeart.changeAddr(""))
        try {
            assertFalse("port ${uri.port} still accepts connections", canConnect(uri.host, uri.port))
        } finally {
            val reopened = RealHeart.changeAddr(addr)
            assertEquals(addr, reopened)
        }
        assertTrue(canConnect(uri.host, uri.port))
        RestClient(RealHeart.endpoint, RealHeart.transport).get("/v2/spaces")
    }

    private fun canConnect(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        true
    } catch (_: Exception) {
        false
    }
}
