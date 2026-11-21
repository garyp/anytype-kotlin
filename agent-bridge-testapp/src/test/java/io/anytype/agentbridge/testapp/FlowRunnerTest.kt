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

    private val server = TestServer { req ->
        when {
            req.path == "/v2/spaces" ->
                TestResponse(200, """{"data":[{"id":"sp1234","name":"Space"}],"total":1}""")
            req.path == "/mcp/small" -> mcp(JSONObject(req.body))
            else -> TestResponse(404, """{"status":404,"code":"not_found","message":"no ${req.path}"}""")
        }
    }

    @After fun stop() = server.stop()

    private fun rpc(id: Int, result: JSONObject) =
        TestResponse(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())

    private fun mcp(msg: JSONObject): TestResponse {
        val id = msg.optInt("id")
        return when (msg.getString("method")) {
            "initialize" -> TestResponse(
                200,
                JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject()).toString(),
                mapOf("Mcp-Session-Id" to "s1")
            )
            "notifications/initialized" -> TestResponse(202)
            "tools/list" -> rpc(id, JSONObject().put("tools", JSONArray(tools.map { JSONObject().put("name", it) })))
            "tools/call" -> {
                val params = msg.getJSONObject("params")
                val args = params.getJSONObject("arguments")
                when (params.getString("name")) {
                    "create" -> {
                        created = args
                        rpc(id, JSONObject().put("structuredContent", JSONObject().put("id", "obj1")))
                    }
                    "find" -> {
                        // Not indexed on the first call: the runner must poll.
                        val handles = JSONArray()
                        if (++findCalls > 1) {
                            handles.put(JSONObject().put("n", 1).put("id", "obj1").put("name", created!!.getString("name")))
                        }
                        rpc(id, JSONObject().put("structuredContent", JSONObject().put("handles", handles)))
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
        assertTrue(runner(log).runAll())
        assertEquals(
            listOf(
                "OK connect", "OK v2 spaces", "OK mcp initialize", "OK tools/list",
                "OK create page", "OK find page", "OK read page", "ALL OK"
            ),
            log
        )
        assertTrue(created!!.getString("name").startsWith("agent-bridge-testapp-"))
        assertEquals("sp1234", created!!.getString("space"))
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
