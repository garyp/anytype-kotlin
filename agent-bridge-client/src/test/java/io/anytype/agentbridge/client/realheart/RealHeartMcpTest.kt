package io.anytype.agentbridge.client.realheart

import io.anytype.agentbridge.client.McpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealHeartMcpTest {

    private lateinit var mcp: McpClient

    @Before fun gate() {
        RealHeart.assumeAvailable()
        mcp = McpClient(RealHeart.endpoint, "small", RealHeart.transport)
    }

    @Test fun smallTierListsEightTools() {
        mcp.initialize()
        assertEquals(
            setOf("spaces", "find", "read", "describe", "create", "set_properties", "add_blocks", "edit_text"),
            mcp.listTools().toSet()
        )
    }

    // Argument names (space, type, name, markdown, query, object) come from tools/list inputSchema of
    // heart v0.51.6; `find` numbers matches and `read` takes that handle number as `object`.
    @Test fun createFindReadRoundTrip() {
        val title = "agent-bridge-contract-${UUID.randomUUID()}"
        val body = "hello from contract test"
        val created = mcp.callTool(
            "create",
            JSONObject().put("space", RealHeart.spaceId).put("type", "Page").put("name", title).put("markdown", body)
        )
        assertTrue("create failed: $created", !created.optBoolean("isError", false))
        val id = created.getJSONObject("structuredContent").getString("id")

        // Full-text indexing is asynchronous: poll find until the new object shows up.
        var handles = org.json.JSONArray()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val found = mcp.callTool("find", JSONObject().put("space", RealHeart.spaceId).put("query", title))
            handles = found.optJSONObject("structuredContent")?.optJSONArray("handles") ?: org.json.JSONArray()
            if (handles.length() > 0) break
            Thread.sleep(500)
        }
        assertEquals("find did not return the new object", 1, handles.length())
        assertEquals(id, handles.getJSONObject(0).getString("id"))
        assertEquals(title, handles.getJSONObject(0).getString("name"))

        val read = mcp.callTool("read", JSONObject().put("object", handles.getJSONObject(0).getInt("n").toString()))
        assertTrue("read failed: $read", !read.optBoolean("isError", false))
        val text = read.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue("body not in read output: $text", text.contains(body))
    }
}
