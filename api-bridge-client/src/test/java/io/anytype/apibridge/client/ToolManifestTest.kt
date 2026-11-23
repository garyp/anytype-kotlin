package io.anytype.apibridge.client

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolManifestTest {

    @Test
    fun smallTierManifestIsBundled() {
        val json = JSONObject(ToolManifest.smallTier(ApplicationProvider.getApplicationContext()))
        val tools = json.getJSONArray("tools")
        assertTrue(tools.length() > 0)
        val names = List(tools.length()) { tools.getJSONObject(it).getString("name") }
        assertTrue("find" in names && "read" in names && "create" in names, "tools: $names")
        assertTrue(json.has("filterGrammar"))
    }
}
