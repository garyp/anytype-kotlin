package io.anytype.agentbridge.client.realheart

import io.anytype.agentbridge.client.Endpoint
import io.anytype.agentbridge.client.HttpTransport
import org.json.JSONObject
import org.junit.Assume

/**
 * Access to a real anytype-heart started by scripts/agent-bridge/run-contract-tests.sh.
 * Every test that uses it calls [assumeAvailable] first and is skipped when the env vars are absent.
 */
object RealHeart {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    val baseUrl: String get() = env("ANYTYPE_HEART_BASE_URL")!!
    val apiKey: String get() = env("ANYTYPE_HEART_API_KEY")!!
    val controlUrl: String get() = env("ANYTYPE_HEART_CONTROL_URL")!!
    /** Full space id (what grants use); /v2 answers with the short id. */
    val spaceId: String get() = env("ANYTYPE_HEART_SPACE_ID")!!

    val endpoint: Endpoint get() = Endpoint(baseUrl, apiKey)

    val transport = HttpTransport(30_000)

    fun assumeAvailable() {
        Assume.assumeTrue(
            "ANYTYPE_HEART_* env vars not set; run scripts/agent-bridge/run-contract-tests.sh",
            listOf("ANYTYPE_HEART_BASE_URL", "ANYTYPE_HEART_API_KEY", "ANYTYPE_HEART_CONTROL_URL", "ANYTYPE_HEART_SPACE_ID")
                .all { env(it) != null }
        )
    }

    data class App(val key: String, val hash: String)

    private fun control(path: String): JSONObject {
        val resp = transport.request("POST", controlUrl + path, emptyMap(), "")
        check(resp.code == 200) { "control $path -> ${resp.code} ${resp.body}" }
        return JSONObject(resp.body)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** [spaces] are FULL space ids; empty means all spaces. */
    fun createApp(name: String, readWrite: Boolean, spaces: List<String> = emptyList()): App {
        val j = control("/createapp?name=${enc(name)}&perm=${if (readWrite) "rw" else "r"}&spaces=${enc(spaces.joinToString(","))}")
        check(j.has("appKey")) { "createApp failed: $j" }
        return App(j.getString("appKey"), j.getString("appHash"))
    }

    fun revoke(hash: String) {
        val j = control("/revoke?hash=${enc(hash)}")
        check(j.optJSONObject("error")?.optString("code") == "NULL") { "revoke failed: $j" }
    }

    /** Returns the listen address heart reports ("" when the API is closed). */
    fun changeAddr(addr: String): String {
        val j = control("/changeaddr?addr=${enc(addr)}")
        check(j.optJSONObject("error")?.optString("code") == "NULL") { "changeAddr failed: $j" }
        return j.optJSONObject("status")?.optString("listenAddr") ?: ""
    }

    /** Returns the FULL id of the new space. */
    fun createSpace(name: String): String = control("/createspace?name=${enc(name)}").getString("spaceId")

    /** The harness hands out the short id in /v2 responses: last 6 chars of the part before the dot. */
    fun shortId(fullId: String): String = fullId.substringBefore('.').takeLast(6)
}
