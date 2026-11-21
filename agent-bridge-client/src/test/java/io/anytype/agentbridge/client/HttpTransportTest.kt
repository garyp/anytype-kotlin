package io.anytype.agentbridge.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpTransportTest {
    private val server = TestServer { TestResponse(200, "ok", mapOf("X-Thing" to "v")) }

    @After
    fun tearDown() = server.stop()

    @Test
    fun noOriginHeaderIsSent() {
        val resp = HttpTransport().request("POST", server.baseUrl + "/x", mapOf("A" to "b"), "{}")
        assertEquals(200, resp.code)
        assertEquals("ok", resp.body)
        assertEquals("v", resp.header("x-thing"))
        assertFalse(server.requests.single().hasHeader("Origin"))
    }

    @Test
    fun nonSuccessBodyIsReturned() {
        val s = TestServer { TestResponse(401, "bad") }
        try {
            val resp = HttpTransport().request("GET", s.baseUrl + "/", emptyMap(), null)
            assertEquals(401, resp.code)
            assertEquals("bad", resp.body)
        } finally {
            s.stop()
        }
    }
}
