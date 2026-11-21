package io.anytype.agentbridge.client

import org.json.JSONException
import org.json.JSONObject

/** Error answered by heart: `{"object":"error","status":..,"code":..,"message":..}`. */
class ApiError(val status: Int, val code: String, override val message: String) :
    RuntimeException("$status $code: $message") {
    override fun equals(other: Any?) =
        other is ApiError && other.status == status && other.code == code && other.message == message
    override fun hashCode() = listOf(status, code, message).hashCode()

    companion object {
        fun from(resp: HttpResponse): ApiError = try {
            val j = JSONObject(resp.body)
            ApiError(
                j.optInt("status", resp.code),
                j.optString("code", "http_${resp.code}"),
                j.optString("message", resp.body)
            )
        } catch (_: JSONException) {
            ApiError(resp.code, "http_${resp.code}", resp.body.ifEmpty { "HTTP ${resp.code}" })
        }
    }
}

/** Thin JSON client for heart's REST API at [Endpoint.baseUrl]. Blocking; call off the main thread. */
class RestClient(
    private val endpoint: Endpoint,
    private val transport: HttpTransport = HttpTransport()
) {
    fun get(path: String): String = call("GET", path, null)
    fun post(path: String, json: String): String = call("POST", path, json)
    fun patch(path: String, json: String): String = call("PATCH", path, json)

    private fun call(method: String, path: String, body: String?): String {
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${endpoint.apiKey}",
            "Accept" to "application/json",
            "Anytype-Version" to API_VERSION
        )
        if (body != null) headers["Content-Type"] = "application/json"
        val resp = transport.request(method, endpoint.baseUrl.trimEnd('/') + path, headers, body)
        if (resp.code !in 200..299) throw ApiError.from(resp)
        return resp.body
    }

    companion object {
        const val API_VERSION = "2025-11-08"
    }
}
