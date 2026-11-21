package io.anytype.agentbridge.testapp

import io.anytype.agentbridge.client.AnytypeBridge
import io.anytype.agentbridge.client.Endpoint
import io.anytype.agentbridge.client.McpClient
import io.anytype.agentbridge.client.RestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Seam over [AnytypeBridge.connect] so the flow can run against any endpoint. */
fun interface EndpointSource {
    suspend fun endpoint(): Endpoint
}

/**
 * Drives the whole agent flow against Anytype: bridge connect, REST /v2/spaces, then the MCP small
 * tier (initialize, tools/list, create, find, read). Logs `OK <step>` / `FAIL <step>: <msg>` and
 * `ALL OK` at the end; stops at the first failing step.
 */
class FlowRunner(
    private val source: EndpointSource,
    private val log: (String) -> Unit,
    private val pollIntervalMs: Long = 500,
    private val findTimeoutMs: Long = 30_000
) {
    constructor(bridge: AnytypeBridge, log: (String) -> Unit) :
        this(EndpointSource { bridge.connect() }, log)

    private class StepFailed(message: String) : Exception(message)

    suspend fun runAll(): Boolean {
        return try {
            val endpoint = step("connect") { source.endpoint() }
            val spaceId = step("v2 spaces") { firstSpace(endpoint) }
            val mcp = McpClient(endpoint, "small")
            step("mcp initialize") { mcp.initialize() }
            step("tools/list") {
                val names = mcp.listTools()
                val missing = listOf("create", "find", "read").filter { it !in names }
                if (missing.isNotEmpty()) throw StepFailed("missing tools $missing, got $names")
            }
            val title = "agent-bridge-testapp-${UUID.randomUUID()}"
            val body = "hello from the test agent"
            step("create page") { create(mcp, spaceId, title, body) }
            val handle = step("find page") { find(mcp, spaceId, title) }
            step("read page") {
                val read = mcp.callTool("read", JSONObject().put("object", handle))
                if (read.optBoolean("isError", false)) throw StepFailed("read failed: $read")
                val text = read.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
                if (!text.contains(body)) throw StepFailed("body not in read output: ${text.take(200)}")
            }
            log("ALL OK")
            true
        } catch (_: StepFailed) {
            false // already logged
        }
    }

    private suspend fun <T> step(name: String, block: suspend () -> T): T {
        try {
            val result = withContext(Dispatchers.IO) { block() }
            log("OK $name")
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("FAIL $name: ${e.message ?: e.javaClass.simpleName}")
            throw StepFailed(e.message.orEmpty())
        }
    }

    private fun firstSpace(endpoint: Endpoint): String {
        val data = JSONObject(RestClient(endpoint).get("/v2/spaces")).optJSONArray("data") ?: JSONArray()
        if (data.length() == 0) throw StepFailed("no spaces granted")
        return data.getJSONObject(0).getString("id")
    }

    // Argument names (space, type, name, markdown, query, object) are those of heart's small tier.
    private fun create(mcp: McpClient, space: String, title: String, body: String) {
        val created = mcp.callTool(
            "create",
            JSONObject().put("space", space).put("type", "Page").put("name", title).put("markdown", body)
        )
        if (created.optBoolean("isError", false)) throw StepFailed("create failed: $created")
    }

    /** Full-text indexing is asynchronous: polls `find` and returns the handle number for `read`. */
    private suspend fun find(mcp: McpClient, space: String, title: String): String {
        val deadline = System.currentTimeMillis() + findTimeoutMs
        while (true) {
            val found = mcp.callTool("find", JSONObject().put("space", space).put("query", title))
            val handles = found.optJSONObject("structuredContent")?.optJSONArray("handles") ?: JSONArray()
            for (i in 0 until handles.length()) {
                val h = handles.getJSONObject(i)
                if (h.optString("name") == title) return h.get("n").toString()
            }
            if (System.currentTimeMillis() >= deadline) throw StepFailed("page not found within ${findTimeoutMs} ms")
            delay(pollIntervalMs)
        }
    }
}
