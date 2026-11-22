package io.anytype.apibridge.testapp

import io.anytype.apibridge.client.Endpoint
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [FlowRunner] against a real anytype-heart started by scripts/api-bridge/run-contract-tests.sh,
 * and checks that heart rejects REST and MCP calls that carry no key. Skipped when the
 * ANYTYPE_HEART_* env vars are absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlowRunnerRealHeartTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Before fun gate() {
        assumeTrue(
            "ANYTYPE_HEART_* env vars not set; run scripts/api-bridge/run-contract-tests.sh",
            env("ANYTYPE_HEART_BASE_URL") != null && env("ANYTYPE_HEART_API_KEY") != null
        )
    }

    @Test fun flowAgainstRealHeart() = runBlocking {
        val log = mutableListOf<String>()
        val ok = FlowRunner(
            EndpointSource { Endpoint(env("ANYTYPE_HEART_BASE_URL")!!, env("ANYTYPE_HEART_API_KEY")!!) },
            log = { println(it); log.add(it) }
        ).runAll()
        assertTrue("flow failed: $log", ok)
        assertEquals("ALL OK", log.last())
    }

    private val http = OkHttpClient()

    private fun baseUrl() = env("ANYTYPE_HEART_BASE_URL")!!.trimEnd('/')

    private fun status(request: Request): Int = http.newCall(request).execute().use { it.code }

    @Test fun keylessRestIsRejected() {
        assertEquals(401, status(Request.Builder().url("${baseUrl()}/v2/spaces").get().build()))
    }

    @Test fun keylessMcpIsRejected() {
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-06-18","capabilities":{},""" +
            """"clientInfo":{"name":"keyless-check","version":"1"}}}"""
        val request = Request.Builder()
            .url("${baseUrl()}/mcp/small")
            .header("Accept", "application/json, text/event-stream")
            .post(initialize.toRequestBody("application/json".toMediaType()))
            .build()
        assertEquals(401, status(request))
    }
}
