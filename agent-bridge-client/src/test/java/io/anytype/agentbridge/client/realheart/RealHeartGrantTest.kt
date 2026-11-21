package io.anytype.agentbridge.client.realheart

import io.anytype.agentbridge.client.ApiError
import io.anytype.agentbridge.client.Endpoint
import io.anytype.agentbridge.client.McpClient
import io.anytype.agentbridge.client.RestClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealHeartGrantTest {

    @Before fun gate() = RealHeart.assumeAvailable()

    private fun narrowApp() =
        RealHeart.createApp("contract-narrow-${UUID.randomUUID()}", readWrite = true, spaces = listOf(RealHeart.spaceId))

    private fun rest(key: String) = RestClient(Endpoint(RealHeart.baseUrl, key), RealHeart.transport)

    @Test fun narrowKeyWorksInGrantedSpace() {
        val key = narrowApp().key
        val rest = rest(key)
        val list = JSONObject(rest.get("/v2/spaces")).getJSONArray("data")
        val shortId = RealHeart.shortId(RealHeart.spaceId)
        val ids = (0 until list.length()).map { list.getJSONObject(it).getString("id") }
        assertTrue("$shortId not in $ids", shortId in ids)
        assertEquals(shortId, JSONObject(rest.get("/v2/spaces/$shortId")).getString("id"))

        val mcp = McpClient(Endpoint(RealHeart.baseUrl, key), "small", RealHeart.transport)
        val res = mcp.callTool("spaces", JSONObject())
        assertFalse(res.toString(), res.optBoolean("isError", false))
        val spaces = res.getJSONObject("structuredContent").getJSONArray("spaces")
        assertTrue(res.toString(), (0 until spaces.length()).any { spaces.getJSONObject(it).getString("id") == shortId })
    }

    @Test fun narrowKeyForbiddenInOtherSpace() {
        val other = RealHeart.shortId(RealHeart.createSpace("other-${UUID.randomUUID()}"))
        val rest = rest(narrowApp().key)
        for (path in listOf("/v2/spaces/$other", "/v2/spaces/$other/objects")) {
            val e = assertThrows(path, ApiError::class.java) { rest.get(path) }
            assertEquals(path, 403, e.status)
            assertEquals("space_not_granted", e.code)
        }
    }

    @Test fun narrowKeyRefusedOnV1() {
        val e = assertThrows(ApiError::class.java) { rest(narrowApp().key).get("/v1/spaces") }
        assertEquals(403, e.status)
        assertEquals("v1_not_available_for_scoped_keys", e.code)
    }

    @Test fun readOnlyKeyCannotWrite() {
        val app = RealHeart.createApp("contract-ro-${UUID.randomUUID()}", readWrite = false)
        val title = "agent-bridge-ro-${UUID.randomUUID()}"
        val mcp = McpClient(Endpoint(RealHeart.baseUrl, app.key), "small", RealHeart.transport)
        val denied = try {
            val res = mcp.callTool(
                "create",
                JSONObject().put("space", RealHeart.spaceId).put("type", "Page").put("name", title)
            )
            res.optBoolean("isError", false)
        } catch (e: ApiError) {
            e.status == 403
        }
        assertTrue("read-only key was not refused", denied)

        // Nothing was created: neither a full-access search nor the REST write path finds it.
        val full = McpClient(RealHeart.endpoint, "small", RealHeart.transport)
        Thread.sleep(1_500)
        val found = full.callTool("find", JSONObject().put("space", RealHeart.spaceId).put("query", title))
        val handles = found.optJSONObject("structuredContent")?.optJSONArray("handles")
        assertTrue("object exists: $found", handles == null || handles.length() == 0)

        val e = assertThrows(ApiError::class.java) {
            rest(app.key).post("/v2/spaces/${RealHeart.shortId(RealHeart.spaceId)}/objects", """{"name":"$title","type_key":"page"}""")
        }
        assertEquals(403, e.status)
        assertEquals("write_not_granted", e.code)
    }
}
