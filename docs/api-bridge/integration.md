# Connected apps: integration guide

An app on the same Android device can use the user's Anytype data through Anytype's local API:
REST and MCP over loopback, with a key scoped to the spaces and access level the user approves.
Anytype calls such an app a *connected app*.

**Requirements:** Android 15 (API 35) or newer, an Anytype build with the API bridge, and your app
in the same user profile.

## 1. Add the client library

The client library is published to GitHub Packages. GitHub's Maven registry needs a token with
`read:packages` even for public packages:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://maven.pkg.github.com/garyp/anytype-kotlin") {
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()   // token with read:packages
            }
            content { includeGroup("io.anytype.apibridge") }
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("io.anytype.apibridge:api-bridge-client:0.1.0")
}
```

It declares `INTERNET` and the `<queries>` entry for the bridge.

The API is plain HTTP on `127.0.0.1`, so allow cleartext to loopback only:

```xml
<!-- AndroidManifest.xml: <application android:networkSecurityConfig="@xml/network_security_config"> -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

## 2. Connect

Any app can claim the bridge's bind action, so pin the signing certificate of the Anytype build
you trust. A pinned client never binds to a package signed with another certificate.

```kotlin
const val ANYTYPE_CERT = "<sha-256 hex>"   // apksigner verify --print-certs anytype.apk

val target = AnytypeBridge.findInstalled(context, ANYTYPE_CERT).firstOrNull()
    ?: error("No trusted Anytype build installed")
val bridge = AnytypeBridge(context, target, expectedCertSha256 = ANYTYPE_CERT)

val approve = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    if (r.resultCode == Activity.RESULT_OK) scope.launch { endpoint = bridge.connect() }
}

try {
    endpoint = bridge.connect()   // Endpoint(baseUrl, apiKey)
} catch (e: BridgeException.ApprovalRequired) {
    approve.launch(bridge.approvalIntent(), bridge.approvalOptions())
}
```

- `connect()` is a suspend function and can be called from any thread, but don't block the main
  thread while it waits. It can take a few seconds when Anytype has to start, and it gives up after
  `AnytypeBridge.REPLY_TIMEOUT_MS`.
- The first call throws `ApprovalRequired`. `approvalOptions()` shares your app's identity with
  Anytype; the consent screen refuses without it. The user sees your app's name and certificate,
  and picks read-only or read and change, and all spaces or specific ones.
- Approvals are per Anytype account and tied to your package and signing certificate. The user can
  revoke your app at any time under Connected apps; your key then stops working at once (401).

Errors, all subclasses of `BridgeException`:

| Exception | Meaning |
|---|---|
| `ApprovalRequired` | The user hasn't approved your app, or revoked it. |
| `NotInstalled` | No Anytype build with the bridge is installed. |
| `Untrusted` | The package isn't signed with the pinned certificate. |
| `Failed(code)` | `NOT_LOGGED_IN`: no account is open in Anytype. `START_FAILED` / `API_FAILED`: Anytype couldn't start the local API or create the key. `INTERNAL`: anything else, including no answer within the timeout or a lost connection. |

If you don't use the client library, the protocol is `IApiBridge.aidl` and `IConnectCallback.aidl`
in `api-bridge-client/src/main/aidl/`; `AnytypeBridge.kt` shows how to bind and call it.

## 3. Call the API

Use any HTTP or MCP client against `endpoint.baseUrl`:

- REST: `/v2` (for example `GET /v2/spaces`).
- MCP (Streamable HTTP): `/mcp/small`, `/mcp/large`, `/mcp/full`. The small tier is designed for
  small models.

On every request:

- send `Authorization: Bearer <apiKey>`;
- send no `Origin` header (Anytype answers 403). OkHttp, Ktor and Retrofit send none by default.

`api-bridge-testapp/src/main/java/io/anytype/apibridge/testapp/FlowRunner.kt` is a complete
example with OkHttp and the Kotlin MCP SDK.

**Access levels.** What the user approved limits what the key can do:

- `/v1`, the older REST API, works only with full access (all spaces, read and change). Any
  narrower key gets 403 `v1_not_available_for_scoped_keys`, so use `/v2`.
- A key for specific spaces sees only those spaces, gets 403 `space_not_granted` for others, and
  can't create spaces.
- A read-only key gets 403 `write_not_granted` over REST, and a tool error over MCP, on writes.

**MCP notes:**

- With the Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk-client`), install Ktor's `SSE`
  plugin on the `HttpClient`. Anytype has no server-sent stream, and without the plugin the SDK
  keeps retrying one in the background. The SDK needs Kotlin 2.3 or newer.
- A tool that fails returns a normal result with `isError = true`; check it on every call.
- `find` returns numbered handles that `read` accepts, valid only within the same MCP session.
- `find` searches a full-text index, so a just-created object may take a moment to appear.
- `docs/api-bridge/small-tools-manifest.json` lists the small-tier tools with JSON schemas,
  examples and GBNF grammars, for constrained decoding with on-device models.

## 4. Stay connected

- Keep the `AnytypeBridge` instance, and its binding, while you use the API. Anytype opens the
  local API only while an app is bound, and closes it when the last one calls `close()`.
- After a connection error, a 401 or a lost binding, call `connect()` again: the port and the key
  can change.
