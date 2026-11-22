package io.anytype.apibridge.testapp

import io.anytype.apibridge.client.AnytypeBridge
import io.anytype.apibridge.client.Endpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Seam over [AnytypeBridge.connect] so the flow can run against any endpoint. */
fun interface EndpointSource {
    suspend fun endpoint(): Endpoint
}

/**
 * Drives the whole connected-app flow against Anytype: bridge connect, REST /v2/spaces (OkHttp), then the
 * MCP small tier through the MCP Kotlin SDK (initialize, tools/list, create, find, read). Logs
 * `OK <step>` / `FAIL <step>: <msg>` and `ALL OK` at the end; stops at the first failing step.
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
            mcpHttpClient().use { http ->
                val mcp = Client(Implementation(name = "api-bridge-testapp", version = "1"))
                try {
                    // connect() runs initialize and notifications/initialized.
                    step("mcp initialize") { mcp.connect(mcpTransport(http, endpoint)) }
                    runTools(mcp, spaceId)
                } finally {
                    withContext(Dispatchers.IO) { runCatching { mcp.close() } }
                }
            }
            log("ALL OK")
            true
        } catch (_: StepFailed) {
            false // already logged
        }
    }

    /**
     * Shows what the current key can reach, for checking an approval's limits by hand: the spaces
     * `/v2/spaces` lists, a read of every space any earlier probe has listed (a space left out of a
     * narrower approval should now be refused), and `/v1/spaces` (refused for a space-limited key).
     */
    suspend fun probe() {
        try {
            val endpoint = step("connect") { source.endpoint() }
            val spaces = step("v2 spaces") { listSpaces(endpoint) }
            log("visible spaces: ${spaces.size} ${spaces.values.joinToString(prefix = "[", postfix = "]")}")
            seenSpaces.putAll(spaces)
            for ((id, name) in seenSpaces) {
                val code = withContext(Dispatchers.IO) { status(endpoint, "/v2/spaces/$id/objects?limit=1") }
                log("read space $name: HTTP $code")
            }
            val v1 = withContext(Dispatchers.IO) { status(endpoint, "/v1/spaces") }
            log("v1 spaces: HTTP $v1")
        } catch (_: StepFailed) {
            // already logged
        }
    }

    private fun listSpaces(endpoint: Endpoint): Map<String, String> {
        val body = get(endpoint, "/v2/spaces").use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw StepFailed("HTTP ${resp.code}: ${text.take(200)}")
            text
        }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).associate {
            val o = data.getJSONObject(it)
            o.getString("id") to o.optString("name", o.getString("id"))
        }
    }

    private fun status(endpoint: Endpoint, path: String): Int = get(endpoint, path).use { it.code }

    private fun get(endpoint: Endpoint, path: String) = rest.newCall(
        Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()
    ).execute()

    private suspend fun runTools(mcp: Client, spaceId: String) {
        step("tools/list") {
            val names = mcp.listTools().tools.map { it.name }
            val missing = listOf("create", "find", "read").filter { it !in names }
            if (missing.isNotEmpty()) throw StepFailed("missing tools $missing, got $names")
        }
        val title = "api-bridge-testapp-${UUID.randomUUID()}"
        val body = "hello from the test app"
        step("create page") { create(mcp, spaceId, title, body) }
        // `find` handles are numbered per MCP session: `read` must use the same session.
        val handle = step("find page") { find(mcp, spaceId, title) }
        step("read page") {
            val read = mcp.callTool("read", mapOf("object" to handle)).orFail("read")
            val text = read.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (!text.contains(body)) throw StepFailed("body not in read output: ${text.take(200)}")
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

    /** REST: a plain OkHttp GET with the bearer key. OkHttp sends no Origin header (heart: 403). */
    private fun firstSpace(endpoint: Endpoint): String {
        val request = Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + "/v2/spaces")
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()
        val body = rest.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw StepFailed("HTTP ${resp.code}: ${text.take(200)}")
            text
        }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        if (data.length() == 0) throw StepFailed("no spaces granted")
        return data.getJSONObject(0).getString("id")
    }

    // Argument names (space, type, name, markdown, query, object) are those of heart's small tier.
    private suspend fun create(mcp: Client, space: String, title: String, body: String) {
        mcp.callTool(
            "create",
            mapOf("space" to space, "type" to "Page", "name" to title, "markdown" to body)
        ).orFail("create")
    }

    /** Full-text indexing is asynchronous: polls `find` and returns the handle number for `read`. */
    private suspend fun find(mcp: Client, space: String, title: String): String {
        val deadline = System.currentTimeMillis() + findTimeoutMs
        while (true) {
            val found = mcp.callTool("find", mapOf("space" to space, "query" to title)).orFail("find")
            val handles = found.structuredContent?.get("handles") as? JsonArray ?: JsonArray(emptyList())
            for (h in handles.filterIsInstance<JsonObject>()) {
                if ((h["name"] as? JsonPrimitive)?.content == title) {
                    return (h["n"] as? JsonPrimitive)?.content ?: throw StepFailed("handle without n: $h")
                }
            }
            if (System.currentTimeMillis() >= deadline) throw StepFailed("page not found within $findTimeoutMs ms")
            delay(pollIntervalMs)
        }
    }

    /** A tool error is an ordinary result with isError = true, not a JSON-RPC error. */
    private fun CallToolResult.orFail(tool: String): CallToolResult {
        if (isError == true) {
            val text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            throw StepFailed("$tool failed: ${text.take(200)}")
        }
        return this
    }

    private companion object {
        val rest = OkHttpClient()

        /** Every space a probe in this process has listed, id to name. */
        val seenSpaces = linkedMapOf<String, String>()

        /**
         * Ktor client for the MCP SDK. The SDK opens a GET stream for server-initiated messages
         * after initialize through Ktor's SSE plugin; heart has no such stream and answers the GET
         * with 405, which the SDK treats as "stream disabled". Without the plugin installed the
         * GET fails locally and the SDK retries it in the background, so install it.
         */
        fun mcpHttpClient() = HttpClient(OkHttp) { install(SSE) }

        /** The bearer key goes on every request of the transport (POST, the GET stream, DELETE). */
        fun mcpTransport(http: HttpClient, endpoint: Endpoint) = StreamableHttpClientTransport(
            client = http,
            url = endpoint.baseUrl.trimEnd('/') + "/mcp/small",
            requestBuilder = { header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey}") }
        )
    }
}
