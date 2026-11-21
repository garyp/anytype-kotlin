package io.anytype.agentbridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real org.json for JVM unit tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestClientTest {

    @Test
    fun restSendsBearerAndVersion() {
        val s = TestServer { TestResponse(200, """{"ok":true}""") }
        try {
            val rest = RestClient(Endpoint(s.baseUrl, "key123"))
            assertEquals("""{"ok":true}""", rest.get("/v1/spaces"))
            rest.post("/v1/a", """{"x":1}""")
            rest.patch("/v1/b", """{"y":2}""")
            val r = s.requests
            assertEquals(listOf("GET", "POST", "PATCH"), r.map { it.method })
            assertEquals("/v1/spaces", r[0].path)
            r.forEach {
                assertEquals("Bearer key123", it.header("Authorization"))
                assertEquals("application/json", it.header("Accept"))
                assertEquals("2025-11-08", it.header("Anytype-Version"))
            }
            assertEquals("""{"x":1}""", r[1].body)
            assertEquals("application/json", r[1].header("Content-Type")?.substringBefore(";"))
        } finally {
            s.stop()
        }
    }

    @Test
    fun restParsesErrorShape() {
        val s = TestServer {
            TestResponse(401, """{"object":"error","status":401,"code":"unauthorized","message":"invalid api key"}""")
        }
        try {
            val e = assertThrows(ApiError::class.java) { RestClient(Endpoint(s.baseUrl, "k")).get("/v1/x") }
            assertEquals(401, e.status)
            assertEquals("unauthorized", e.code)
            assertEquals("invalid api key", e.message)
            assertEquals(ApiError(401, "unauthorized", "invalid api key").message, e.message)
        } finally {
            s.stop()
        }
    }

    @Test
    fun restToleratesNonJsonError() {
        val s = TestServer { TestResponse(502, "gateway") }
        try {
            val e = assertThrows(ApiError::class.java) { RestClient(Endpoint(s.baseUrl, "k")).get("/v1/x") }
            assertEquals(502, e.status)
            assertEquals("gateway", e.message)
        } finally {
            s.stop()
        }
    }
}
