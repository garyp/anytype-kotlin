package io.anytype.agentbridge.testapp

import io.anytype.agentbridge.client.BridgeException
import io.anytype.agentbridge.client.Endpoint
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlowRunnerTest {

    private var created: JSONObject? = null
    private var findCalls = 0
    private var tools = listOf("spaces", "find", "read", "create")
    // A Streamable HTTP server may answer any request as a one-event SSE stream instead of JSON.
    private var sse = false

    // Speaks the Streamable HTTP subset heart speaks: plain-JSON responses, a session id from
    // initialize, 202 for notifications and 405 for the GET (no server-push stream).
    private val server = TestServer { req ->
        when {
            req.path == "/v2/spaces" ->
                TestResponse(200, """{"data":[{"id":"sp1234","name":"Space"}],"total":1}""")
            req.path == "/mcp/small" && req.method == "GET" -> TestResponse(405)
            req.path == "/mcp/small" && req.method == "POST" -> mcp(req, JSONObject(req.body))
            else -> TestResponse(404, """{"status":404,"code":"not_found","message":"no ${req.path}"}""")
        }
    }

    @After fun stop() = server.stop()

    private fun rpc(id: Any?, result: JSONObject): TestResponse {
        val json = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
        return if (sse) {
            TestResponse(200, "event: message\ndata: $json\n\n", mapOf("Content-Type" to "text/event-stream"))
        } else {
            TestResponse(200, json)
        }
    }

    private fun mcp(req: RecordedRequest, msg: JSONObject): TestResponse {
        val id = msg.opt("id") // echoed as sent: the SDK uses string ids
        val method = msg.getString("method")
        // Like heart: every call after initialize must carry the session id.
        if (method != "initialize" && req.header("Mcp-Session-Id") != "s1") return TestResponse(400, "{}")
        return when (method) {
            "initialize" -> TestResponse(
                200,
                JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                    "result",
                    JSONObject()
                        .put("protocolVersion", msg.getJSONObject("params").getString("protocolVersion"))
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject().put("name", "fake-heart").put("version", "1"))
                ).toString(),
                mapOf("Mcp-Session-Id" to "s1")
            )
            "notifications/initialized" -> TestResponse(202)
            "tools/list" -> rpc(
                id,
                JSONObject().put(
                    "tools",
                    JSONArray(tools.map { JSONObject().put("name", it).put("inputSchema", JSONObject().put("type", "object")) })
                )
            )
            "tools/call" -> {
                val params = msg.getJSONObject("params")
                val args = params.getJSONObject("arguments")
                when (params.getString("name")) {
                    "create" -> {
                        created = args
                        rpc(
                            id,
                            JSONObject().put("content", JSONArray())
                                .put("structuredContent", JSONObject().put("id", "obj1"))
                        )
                    }
                    "find" -> {
                        // Not indexed on the first call: the runner must poll.
                        val handles = JSONArray()
                        if (++findCalls > 1) {
                            handles.put(JSONObject().put("n", 1).put("id", "obj1").put("name", created!!.getString("name")))
                        }
                        rpc(
                            id,
                            JSONObject().put("content", JSONArray())
                                .put("structuredContent", JSONObject().put("handles", handles))
                        )
                    }
                    "read" -> rpc(
                        id,
                        JSONObject().put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", "# t\n" + created!!.getString("markdown")))
                        )
                    )
                    else -> error("unexpected tool")
                }
            }
            else -> TestResponse(400, "{}")
        }
    }

    private fun runner(log: MutableList<String>) = FlowRunner(
        EndpointSource { Endpoint(server.baseUrl, "key") },
        log = { log.add(it) },
        pollIntervalMs = 10
    )

    @Test fun allStepsLogOk() = runBlocking {
        val log = mutableListOf<String>()
        val ok = runner(log).runAll()
        assertTrue("$log ${server.requests.map { "${it.method} ${it.path} ${it.body.take(80)}" }}", ok)
        assertEquals(
            listOf(
                "OK connect", "OK v2 spaces", "OK mcp initialize", "OK tools/list",
                "OK create page", "OK find page", "OK read page", "ALL OK"
            ),
            log
        )
        assertTrue(created!!.getString("name").startsWith("agent-bridge-testapp-"))
        assertEquals("sp1234", created!!.getString("space"))
        // The bearer key rides on every request, REST and MCP alike; no Origin header is sent.
        assertTrue(server.requests.isNotEmpty())
        server.requests.forEach {
            assertEquals("${it.method} ${it.path}", "Bearer key", it.header("Authorization"))
            assertFalse("${it.method} ${it.path}", it.hasHeader("Origin"))
        }
        assertTrue(server.requests.any { it.path == "/mcp/small" && it.method == "POST" })
    }

    @Test fun sseResponsesAreUnderstood() = runBlocking {
        sse = true
        val log = mutableListOf<String>()
        val ok = runner(log).runAll()
        assertTrue(log.toString(), ok)
        assertEquals("ALL OK", log.last())
    }

    @Test fun failureStopsAndLogsStep() = runBlocking {
        tools = listOf("spaces", "find") // read and create missing
        val log = mutableListOf<String>()
        assertFalse(runner(log).runAll())
        assertEquals(4, log.size)
        assertTrue(log[3], log[3].startsWith("FAIL tools/list: "))
        assertFalse(log.contains("ALL OK"))
        assertTrue(created == null)
    }

    @Test fun connectFailureIsLogged() = runBlocking {
        val log = mutableListOf<String>()
        val r = FlowRunner(
            EndpointSource { throw BridgeException.ApprovalRequired() },
            log = { log.add(it) }
        )
        assertFalse(r.runAll())
        assertEquals(listOf("FAIL connect: Approval required"), log)
    }
}
