# Agent integration guide

How an agent app on the same Android phone gets read/write access to the user's Anytype data
through the agent bridge. Design background: `docs/superpowers/specs/2026-11-21-agent-bridge-design.md`.

Requirements: Android 15 (API 35) or newer, both apps in the same user profile, and the fork of
Anytype installed. The debug build (`io.anytype.app.dev`) installs beside the Play Store app.

## 1. Depend on the client library

Use the `:agent-bridge-client` module (`implementation project(':agent-bridge-client')`) or copy
its sources and AIDL files into the agent. Its manifest adds `INTERNET` and a `<queries>` entry for
the bridge bind action; both merge into the agent's manifest.

The agent's manifest still needs a network security config that allows cleartext only to
loopback (the API is plain HTTP on `127.0.0.1`):

```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

## 2. Find Anytype and connect

```kotlin
val target = AnytypeBridge.findInstalled(context).firstOrNull { it.endsWith(".dev") }
    ?: AnytypeBridge.findInstalled(context).first()
val bridge = AnytypeBridge(context, target)   // default target is io.anytype.app
```

`connect()` is a suspend function. It binds to the Anytype bridge service (starting the Anytype
process if needed) and returns an `Endpoint(baseUrl, apiKey)` such as `http://127.0.0.1:<port>`
plus a bearer key.

### The approval loop

The first call fails with `BridgeException.ApprovalRequired`. Launch the consent screen, and
connect again when the user approved:

```kotlin
val approve = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    if (r.resultCode == Activity.RESULT_OK) scope.launch { endpoint = bridge.connect() }
}

try {
    endpoint = bridge.connect()
} catch (e: BridgeException.ApprovalRequired) {
    approve.launch(bridge.approvalIntent(), bridge.approvalOptions())
}
```

`approvalOptions()` enables share-identity (API 34+), which lets Anytype see which app is asking.
Without it the consent screen refuses. The user sees your app name and signing certificate, picks
all spaces or specific ones, and read-only or read-write. Other exceptions: `NotInstalled`
(Anytype is missing or too old) and `Failed(code, msg)`.

Approvals are per Anytype account and pinned to your package name and signing certificate. The user
can list and revoke approved agents on the Anytype access screen. Revoking cuts a live agent off
at once when the account is running (its next request gets 401).

## 3. Call the API

Prefer MCP on the small tier, which heart designed for small models:

```kotlin
val mcp = McpClient(endpoint, tier = "small")   // /mcp/small
withContext(Dispatchers.IO) {
    mcp.initialize()
    val tools = mcp.listTools()
    val result = mcp.callTool("spaces", JSONObject())
}
```

Protocol notes (`McpClient` already does these): one JSON-RPC message per POST, a session header
(`Mcp-Session-Id`) after `initialize`, re-initialize when a request answers 404 (session gone), and
never send an `Origin` header (heart rejects foreign origins with 403). `find` returns numbered
handles that are valid only within the same MCP session, so keep one `McpClient` per task.

REST is available through `RestClient(endpoint)` (`get`, `post`, `patch`) on `/v2/...`.

Access levels:

- A key narrowed to specific spaces works only with `/v2` and MCP, not `/v1`, and cannot create
  spaces. Other spaces answer 403 `space_not_granted`.
- A read-only key gets MCP tool errors (`isError: true`) on writes and 403 `write_not_granted`
  over REST.

## 4. Keep the binding open, and reconnect on failure

- Keep the `AnytypeBridge` instance (the binding) alive while you work. A bound service keeps
  Anytype from being frozen in the background and keeps the listener open. Closing the last
  binding (`close()`) closes the listener.
- On any connection failure (connection refused, 401, binder death) call `connect()` again: the
  port can change between sessions, and the key can be re-minted. If the endpoint was rejected,
  call `markStale()` first so the next `connect()` rebinds afresh.

## 5. Small-model tool manifest (GBNF)

`docs/agent-bridge/small-tools-manifest.json` lists the small-tier tools with their JSON schema,
an example call and a GBNF grammar per tool, plus a `filterGrammar`. Bundle it with the agent and
use the grammars for constrained decoding (llama.cpp style) so a weak model emits valid tool
arguments.

Regenerate it whenever heart changes:

```bash
scripts/agent-bridge/heart-harness/build.sh        # builds build/agent-bridge/bin/anytype if missing
build/agent-bridge/bin/anytype tools --tier small > docs/agent-bridge/small-tools-manifest.json
```

## Security notes

While an agent is connected, heart's port on `127.0.0.1` is reachable by any local app. Without a
valid key such an app gets only 401/403. This exposure is accepted and documented in
`scripts/agent-bridge/README.md`. The listener is closed when no agent is bound.

## Known limitations

- The on-device instrumented test (`AgentBridgeFlowTest` in `:agent-bridge-testapp`) has only been
  compiled, not run: the build environment has no emulator or KVM.
- Test pages created by the test app are not cleaned up.
- The consent screen shows a blank account name if the profile name cannot be read.
