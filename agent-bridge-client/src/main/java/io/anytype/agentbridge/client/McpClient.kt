package io.anytype.agentbridge.client

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal MCP (streamable HTTP, plain-JSON responses) client for heart's `/mcp/{tier}`.
 * Blocking and not thread-safe; call from one background thread.
 */
class McpClient(
    private val endpoint: Endpoint,
    private val tier: String = "small",
    private val transport: HttpTransport = HttpTransport()
) {
    private var sessionId: String? = null
    private var nextId = 1

    fun initialize(): JSONObject {
        sessionId = null
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "agent-bridge-client").put("version", "1"))
        val resp = send(request("initialize", params), withSession = false)
        sessionId = resp.header("Mcp-Session-Id")
        val result = parseResult(resp)
        val note = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        val ack = send(note, withSession = true)
        if (ack.code !in 200..299) throw ApiError.from(ack)
        return result
    }

    fun listTools(): List<String> {
        val tools = rpc("tools/list", JSONObject()).optJSONArray("tools") ?: JSONArray()
        return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
    }

    fun callTool(name: String, arguments: JSONObject): JSONObject =
        rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))

    private fun rpc(method: String, params: JSONObject): JSONObject {
        if (sessionId == null) initialize()
        var resp = send(request(method, params), withSession = true)
        if (resp.code == 404) {
            // Session unknown or expired: re-initialize once and retry.
            initialize()
            resp = send(request(method, params), withSession = true)
        }
        return parseResult(resp)
    }

    private fun request(method: String, params: JSONObject) = JSONObject()
        .put("jsonrpc", "2.0").put("id", nextId++).put("method", method).put("params", params)

    private fun send(message: JSONObject, withSession: Boolean): HttpResponse {
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${endpoint.apiKey}",
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream"
        )
        if (withSession) {
            sessionId?.let { headers["Mcp-Session-Id"] = it }
            headers["MCP-Protocol-Version"] = PROTOCOL_VERSION
        }
        return transport.request("POST", endpoint.baseUrl.trimEnd('/') + "/mcp/$tier", headers, message.toString())
    }

    private fun parseResult(resp: HttpResponse): JSONObject {
        if (resp.code !in 200..299) throw ApiError.from(resp)
        val json = try {
            JSONObject(resp.body)
        } catch (e: JSONException) {
            throw ApiError(resp.code, "invalid_response", "Not a JSON-RPC response: ${resp.body.take(200)}")
        }
        json.optJSONObject("error")?.let {
            throw ApiError(it.optInt("code", resp.code), "mcp_error", it.optString("message", it.toString()))
        }
        return json.optJSONObject("result") ?: JSONObject()
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
