package io.anytype.apibridge.client

import android.content.Context

/**
 * The manifest of Anytype's small-tier MCP tools (`/mcp/small`), bundled with this library: each
 * tool's JSON schema, an example call and a GBNF grammar, plus a `filterGrammar`. An app that runs
 * an on-device model as an agent can use the grammars for constrained decoding, so the model emits
 * valid tool arguments.
 *
 * The manifest matches the heart version of the Anytype release with this library's version. An
 * installed Anytype of another version may offer slightly different tools; `tools/list` over MCP is
 * always current.
 */
object ToolManifest {

    /** Path of the manifest in the app's assets once this library is merged in. */
    const val SMALL_TIER_ASSET = "anytype-api-bridge/small-tools-manifest.json"

    /** The small-tier manifest as JSON text. Reads an asset: call it off the main thread. */
    fun smallTier(context: Context): String =
        context.assets.open(SMALL_TIER_ASSET).bufferedReader().use { it.readText() }
}
