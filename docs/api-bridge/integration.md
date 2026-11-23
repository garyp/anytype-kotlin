# Connected apps: integration guide

How an app on the same Android phone gets read/write access to the user's Anytype data through
the API bridge. Anytype calls such an app a *connected app*: the user approves it once, and it
gets heart's local REST and MCP API with a key scoped to the spaces and access level the user
chose. Nothing in the bridge is specific to one kind of app. Typical connected apps are automation
tools (workflows that file captured notes or read a to-do list), sync utilities that
mirror objects to or from another service, and AI agents that read and edit pages through MCP
(section 3 and section 5 cover what an agent needs). Design background: the 2026-11-21 design
spec under `docs/superpowers/specs/`.

Requirements: the Anytype bridge needs Android 15 (API 35) or newer. Your app runs on the same
phone, and the consent identity sharing (`approvalOptions()`) needs API 34+ in your app's runtime,
so in practice your app runs on API 35+ too. Both apps must be in the same user profile, with the
fork of Anytype installed. The debug build (`io.anytype.app.dev`) installs beside the Play Store
app.

## 1. Depend on the client library

Use the `:api-bridge-client` module (`implementation project(':api-bridge-client')`) or copy
its sources and AIDL files into your app. Its manifest adds `INTERNET` and a `<queries>` entry for
the bridge bind action; both merge into your app's manifest.

Your app's manifest still needs a network security config that allows cleartext only to
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

Any installed app can expose the bridge's bind action, so pin the signing certificate of the
Anytype build you trust. A pinned bridge binds only to a package signed with that certificate;
otherwise `connect()` throws `BridgeException.Untrusted` and the impostor never sees a request.

```kotlin
// SHA-256 of the Anytype build's signing certificate (64 hex digits; colons are fine).
const val ANYTYPE_CERT = "<sha-256 hex>"

val target = AnytypeBridge.findInstalled(context, ANYTYPE_CERT).firstOrNull()
    ?: error("No trusted Anytype build installed")
val bridge = AnytypeBridge(context, target, expectedCertSha256 = ANYTYPE_CERT)
```

Get the digest from the APK you install (the debug build is signed with your debug keystore,
the Play Store build with Anytype's key):

```bash
apksigner verify --print-certs app-debug.apk | grep 'SHA-256 digest'
# Signer #1 certificate SHA-256 digest: 0123...cdef
```

`apksigner` is in the Android SDK build tools. Do not pick the first package `findInstalled(context)`
returns without a pin: it lists every app that claims the action. Without a pin,
`AnytypeBridge(context, target)` (default target `io.anytype.app`) binds to whatever is
installed under that name.

### What `connect()` does

`connect()` is a suspend function. It binds to the Anytype bridge service if it is not bound yet
(Anytype's process starts if needed) and keeps the binding. Then it makes one synchronous AIDL
call, `IApiBridge.connect()`, on `Dispatchers.IO`. The service answers when the local API is
ready, so the call can take a few seconds when Anytype has to start heart's listener. The result
is an `Endpoint(baseUrl, apiKey)`: a loopback URL such as `http://127.0.0.1:<port>` and a bearer
key for that app.

The AIDL interface (version 2) is small, and an app that does not want the client library can
use it directly:

```aidl
interface IApiBridge {
    const int VERSION = 2;
    const int OK = 0;
    const int NOT_LOGGED_IN = 1;
    const int START_FAILED = 2;
    const int API_FAILED = 3;
    const int INTERNAL = 4;
    const int APPROVAL_REQUIRED = 5;
    ApiEndpoint connect();   // blocks; ApiEndpoint { int code; String baseUrl; String apiKey }
    int version();             // returns VERSION
}
```

`connect()` waits at most about 45 seconds; past that the service answers `INTERNAL`, though the
connect goes on inside Anytype and a later call can pick up its result. Cancelling the caller's
coroutine cannot interrupt a binder call in progress, so this bound is the longest a call takes.
Concurrent calls from one app share a single connect. New methods are only ever appended, after
`version()`.

Errors travel in `ApiEndpoint.code`, never as exceptions over the binder. The client maps them:

| `code` | Meaning | `AnytypeBridge.connect()` throws |
|---|---|---|
| `OK` (0) | `baseUrl` and `apiKey` are set | returns the `Endpoint` |
| `NOT_LOGGED_IN` (1) | no account is open in Anytype | `BridgeException.Failed(1, "not logged in")` |
| `START_FAILED` (2) | heart's local API did not start | `BridgeException.Failed(2, ...)` |
| `API_FAILED` (3) | a middleware request (for example minting the key) failed | `BridgeException.Failed(3, ...)` |
| `INTERNAL` (4) | anything else; also a dead binder, a failed call or a malformed reply | `BridgeException.Failed(4, ...)` |
| `APPROVAL_REQUIRED` (5) | the user has not approved this app (answered at once, before anything waits) | `BridgeException.ApprovalRequired` |

Before the call, the client throws `BridgeException.NotInstalled` when it cannot bind (Anytype
missing, or a build without the bridge) and `BridgeException.Untrusted` when the package is not signed with the pinned
certificate. An Anytype whose bridge is too old or otherwise incompatible binds but sends no reply:
that shows as `BridgeException.Failed(INTERNAL, "no reply: Anytype bridge too old or
incompatible")`, not as `NotInstalled`. A `RemoteException` or binder death drops the binding, so the next `connect()` binds
afresh.

An `AnytypeBridge` instance has a single owner and is not thread-safe beyond `connect()`, which a
mutex serializes.

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
(Anytype is missing or has no bridge), `Untrusted` (the package is not signed with the pinned
certificate) and `Failed(code)`, whose `message` says what failed (including an
Anytype bridge too old for this client: `INTERNAL`, "no reply: ...").

Approvals are per Anytype account and pinned to your package name and signing certificate. The user
can list and revoke connected apps on Anytype's "Connected apps" screen (also reachable from the
"Manage connected apps" button of the consent screen). Revoking cuts a live app off at once when the account is running (its next request gets 401).

## 3. Call the API with standard HTTP and MCP libraries

The bridge only hands out the endpoint. Talk to it with any HTTP client and any MCP client: heart
serves REST on `/v2` and MCP (Streamable HTTP) on `/mcp/small`, `/mcp/large` and `/mcp/full`. It
also serves the older REST `/v1`, but only to a key with full access (see Access levels); write new
code against `/v2`. The test app (`api-bridge-testapp`, `FlowRunner.kt`) does exactly this, and
`scripts/api-bridge/run-contract-tests.sh` runs it against a real heart.

Two rules hold for every client:

- Send `Authorization: Bearer <apiKey>` on every request.
- Never send an `Origin` header: heart answers 403 to a foreign origin. OkHttp, Ktor and Retrofit
  send none by default. (The JDK `HttpURLConnection` silently drops a caller-set `Origin`.)

### REST with OkHttp (or Retrofit)

```kotlin
val http = OkHttpClient.Builder()
    .addInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${endpoint.apiKey}")
                .build()
        )
    }
    .build()

val spaces = http.newCall(
    Request.Builder().url(endpoint.baseUrl.trimEnd('/') + "/v2/spaces").build()
).execute().use { it.body.string() }   // {"data":[{"id": ...}, ...]}
```

For Retrofit, pass the same `OkHttpClient` and `baseUrl = endpoint.baseUrl + "/"`. Build a new
client when the endpoint changes (see section 4).

### MCP with the Kotlin MCP SDK

`io.modelcontextprotocol:kotlin-sdk-client` (0.16.0) with its `StreamableHttpClientTransport`
works against heart unchanged, over Ktor with the OkHttp engine (`io.ktor:ktor-client-okhttp`):

```kotlin
val http = HttpClient(OkHttp) { install(SSE) }
val transport = StreamableHttpClientTransport(
    client = http,
    url = endpoint.baseUrl.trimEnd('/') + "/mcp/small",
    requestBuilder = { header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey}") },
)
val mcp = Client(Implementation(name = "my-app", version = "1"))
mcp.connect(transport)                  // initialize + notifications/initialized
val tools = mcp.listTools().tools
val found = mcp.callTool("find", mapOf("space" to spaceId, "query" to "groceries"))
```

The SDK does what heart requires: it stores the `Mcp-Session-Id` from the initialize response and
sends it, with `mcp-protocol-version`, on every later request; it accepts plain JSON responses and
the 202 answer to `notifications/initialized`; it negotiates protocol `2025-11-25`, which heart
accepts. `requestBuilder` puts the bearer key on every request of the transport (POST, the GET
stream and DELETE).

Findings from running the SDK against heart:

- **Install Ktor's `SSE` plugin.** After initialize the SDK opens a GET stream for
  server-initiated messages through `sseSession`. Heart has none and answers 405; with the plugin
  installed the SDK treats 405 as "stream disabled" and moves on. Without it the GET fails locally
  and the SDK keeps retrying it in the background with backoff, then reports an error. Calls still
  work, but it is noisy.
- **Kotlin version.** The 0.16.0 POM asks for `kotlin-stdlib` 2.4.21. A Kotlin 2.2 compiler cannot
  read 2.4 metadata, so use Kotlin 2.3+, or pin the stdlib:
  ```groovy
  constraints {
      implementation('org.jetbrains.kotlin:kotlin-stdlib') { version { strictly '2.3.21' } }
  }
  ```
  This is safe: the SDK jars are built for Kotlin 2.2 and Ktor 3.5.2 / kotlinx-serialization 1.11
  for 2.3. The SDK also brings kotlinx-coroutines 1.11.0 and kotlinx-serialization 1.11.0.
- **JSON-RPC ids are strings** in the SDK (UUID-like). Heart echoes them as sent. A hand-written
  fake server for tests must echo the id unchanged, or the SDK waits out its 60 s timeout.
- **`find` handles are per MCP session.** `find` returns numbered handles in
  `structuredContent.handles[{n,id,name,type}]`; `read` takes `object = "<n>"` (the number as a
  string) and must go through the same connected `Client`. Keep one `Client` per task.
- **`find` is eventually consistent** (full-text index): a page created a moment ago may not be
  found yet, so poll.
- **Tool failures are results, not errors.** A failing tool returns a normal result with
  `isError = true` and the reason in its `TextContent`; check `isError` on every `callTool`.
  JSON-RPC errors are reserved for protocol problems.
- Results: `create` returns `structuredContent {id, type, etag}`; `read` returns the markdown as
  `TextContent`. Small-tier argument names include `create{space,type,name,markdown,properties}`,
  `find{space,query,type,filter,limit}` and `read{object,space,mode}`; `tools/list` has the rest.
- Without an SLF4J binding the SDK (kotlin-logging) prints the SLF4J "no providers" warning once.
  It is harmless.

MCP suits any client that wants tool-style calls, and it is the natural fit for AI agents. For an
agent driven by a small model, prefer the small tier (`/mcp/small`); heart designed it for them.

### Access levels

- Only a key with full access (all spaces, read and change) can use `/v1`. Any narrower key
  works only with `/v2` and MCP; `/v1` answers it 403 `v1_not_available_for_scoped_keys`.
- A key narrowed to specific spaces cannot create spaces. Other spaces answer 403
  `space_not_granted`, and `/v2/spaces` lists only the granted ones.
- A read-only key gets MCP tool errors (`isError: true`) on writes and 403 `write_not_granted`
  over REST.

## 4. Keep the binding open, and reconnect on failure

- Keep the `AnytypeBridge` instance (the binding) alive while you work. A bound service keeps
  Anytype from being frozen in the background and keeps the listener open. The listener closes
  when the last binding of any app closes (`close()`).
- On any connection failure (connection refused, 401, binder death) call `connect()` again: the
  port can change between sessions, and the key can be re-minted. Each `connect()` asks the
  service afresh, and a dead binding is rebound.

## 5. Use case: AI agents on small models (GBNF tool manifest)

`docs/api-bridge/small-tools-manifest.json` lists the small-tier tools with their JSON schema,
an example call and a GBNF grammar per tool, plus a `filterGrammar`. An app that runs an
on-device model as an agent can bundle it and use the grammars for constrained decoding
(llama.cpp style) so a weak model emits valid tool arguments.

Regenerate it whenever heart changes, from a checkout of the heart fork
([garyp/anytype-heart](https://github.com/garyp/anytype-heart), branch `apibridge`, see
`scripts/api-bridge/README.md`) at the version the app uses:

```bash
cd ../anytype-heart
go run ./cmd/anytype tools --tier small > ../anytype-kotlin/docs/api-bridge/small-tools-manifest.json
```

## Security notes

While any app is bound to the bridge service, heart's port on `127.0.0.1` is reachable by any
local app. Without a valid key such an app gets only 401/403. This exposure is accepted and
documented in `scripts/api-bridge/README.md`. The listener is closed when no app is bound; an
unapproved app that stays bound can keep it open, but gets no key.

## Known limitations

- The on-device instrumented test (`ApiBridgeFlowTest` in `:api-bridge-testapp`) has only been
  compiled, not run: the build environment has no emulator or KVM.
- Test pages created by the test app are not cleaned up.
- The consent screen leaves out the account name if the profile name cannot be read.
