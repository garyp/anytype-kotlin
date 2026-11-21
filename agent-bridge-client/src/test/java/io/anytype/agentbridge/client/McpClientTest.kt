package io.anytype.agentbridge.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class McpClientTest {

    private fun rpc(id: Any?, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    /** Fake MCP server; [failNextCall] makes the next tools/call answer 404 once. */
    private class Fake(var failNextCall: Boolean = false, var callStatus: Int = 200) {
        val sessions = AtomicInteger(0)
        var validSession = ""
        fun handle(r: RecordedRequest): TestResponse {
            val j = JSONObject(r.body)
            val method = j.getString("method")
            if (method == "initialize") {
                validSession = "sess-${sessions.incrementAndGet()}"
                return TestResponse(
                    200,
                    """{"jsonrpc":"2.0","id":${j.get("id")},"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""",
                    mapOf("Mcp-Session-Id" to validSession, "Content-Type" to "application/json")
                )
            }
            if (r.header("Mcp-Session-Id") == null) return TestResponse(400, "missing session")
            if (r.header("Mcp-Session-Id") != validSession) return TestResponse(404, "unknown session")
            if (method == "notifications/initialized") return TestResponse(202)
            if (method == "tools/call" && failNextCall) {
                failNextCall = false
                return TestResponse(404, "expired")
            }
            if (method == "tools/call" && callStatus != 200) return TestResponse(callStatus, "slow down")
            val id = j.get("id")
            return when (method) {
                "tools/list" -> TestResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"a"},{"name":"b"}]}}""")
                "tools/call" -> TestResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"hi"}]}}""")
                else -> TestResponse(200, """{"jsonrpc":"2.0","id":$id,"result":{}}""")
            }
        }
    }

    private fun withServer(fake: Fake, block: (TestServer, McpClient) -> Unit) {
        val s = TestServer { fake.handle(it) }
        try {
            block(s, McpClient(Endpoint(s.baseUrl, "k")))
        } finally {
            s.stop()
        }
    }

    private fun methods(s: TestServer) = s.requests.map { JSONObject(it.body).getString("method") }

    @Test
    fun mcpStoresAndSendsSessionId() = withServer(Fake()) { s, c ->
        c.initialize()
        assertEquals(listOf("a", "b"), c.listTools())
        val result = c.callTool("a", JSONObject().put("q", 1))
        assertEquals("hi", result.getJSONArray("content").getJSONObject(0).getString("text"))
        val reqs = s.requests
        assertNull(reqs[0].header("Mcp-Session-Id"))
        assertEquals("/mcp/small", reqs[0].path)
        reqs.drop(1).forEach {
            assertEquals("sess-1", it.header("Mcp-Session-Id"))
            assertEquals("2025-06-18", it.header("MCP-Protocol-Version"))
        }
        assertEquals("Bearer k", reqs[0].header("Authorization"))
        assertFalse(reqs.any { it.hasHeader("Origin") })
        // ids increase from 1 on requests
        val ids = reqs.map { JSONObject(it.body) }.filter { it.has("id") }.map { it.getInt("id") }
        assertEquals(listOf(1, 2, 3), ids)
        assertEquals("2025-06-18", JSONObject(reqs[0].body).getJSONObject("params").getString("protocolVersion"))
    }

    @Test
    fun mcpSendsInitializedNotification() = withServer(Fake()) { s, c ->
        c.initialize()
        assertEquals(listOf("initialize", "notifications/initialized"), methods(s))
        val n = JSONObject(s.requests[1].body)
        assertFalse(n.has("id"))
        assertEquals("sess-1", s.requests[1].header("Mcp-Session-Id"))
    }

    @Test
    fun mcpReinitializesOnce404() = withServer(Fake(failNextCall = true)) { s, c ->
        c.initialize()
        val result = c.callTool("a", JSONObject())
        assertTrue(result.has("content"))
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/call",
                "initialize", "notifications/initialized", "tools/call"),
            methods(s)
        )
        assertEquals("sess-2", s.requests.last().header("Mcp-Session-Id"))
    }

    @Test
    fun mcpThrowsApiErrorOn429WithoutRetry() = withServer(Fake(callStatus = 429)) { s, c ->
        c.initialize()
        val e = assertThrows(ApiError::class.java) { c.callTool("a", JSONObject()) }
        assertEquals(429, e.status)
        assertEquals(1, methods(s).count { it == "tools/call" })
    }
}
