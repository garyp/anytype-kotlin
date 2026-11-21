package io.anytype.agentbridge.testapp

import io.anytype.agentbridge.client.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [FlowRunner] against a real anytype-heart started by scripts/agent-bridge/run-contract-tests.sh.
 * Skipped when the ANYTYPE_HEART_* env vars are absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlowRunnerRealHeartTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Before fun gate() {
        assumeTrue(
            "ANYTYPE_HEART_* env vars not set; run scripts/agent-bridge/run-contract-tests.sh",
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
}
