# Agent Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user-approved on-device agent app reach heart's local JSON API (REST `/v2`, MCP `/mcp/small`) through an exported bound service in the forked Anytype app, with a reusable client library, a test agent app, and tests that run in this container against real heart.

**Architecture:**
- **New modules:**
  - `agent-bridge` holds the service, the consent and access screens, the approvals store and the connect logic.
  - `agent-bridge-client` holds the AIDL contract, the bind/connect client and the REST/MCP helpers.
  - `agent-bridge-testapp` is the test agent app.
- **Heart access:** heart is reached through `LocalApiControl` in `:middleware`. `:app` adapts it to the module's `LocalApiGateway` interface.
- **Heart build:** heart v0.51.6 is rebuilt from source with a one-line fix (`api.SetMiddlewareParams(mw)` in the mobile library). It is published to Maven Local as `v0.51.6-agentbridge.1`.

**Tech Stack:** Kotlin 2.2.10, AGP 9.0.0, AIDL, Jetpack Compose (Material3), Dagger 2, Wire protos, coroutines, Robolectric 4.18.1, JUnit4, `com.sun.net.httpserver` (JDK) for HTTP unit tests, Go 1.26.5 + gomobile and Rust/cargo + NDK 28.1 for heart.

**Spec:** `docs/superpowers/specs/2026-11-21-agent-bridge-design.md`

## Global Constraints

- **Environment:** every Gradle command runs with
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk`.
  - Abbreviated below as `G=./gradlew` with those exports. Define it once with:
    ```
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
    ```
  - Local, untracked files `github.properties`, `apikeys.properties`, `local.properties` and
    `app/google-services.json` already exist. Never commit them.
- **Heart:**
  - Version `v0.51.6`. The patched artifact is `io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1`.
  - Build tags: `nogrpcserver gomobile nowatchdog nosigar timetzdata rasterizesvg`, with `-androidapi 26`.
  - tantivy-go `v1.0.6`.
- **SDK levels:**
  - The new library modules use `minSdk` 26, the root config's default.
  - The test app uses `minSdk 35`.
  - New modules use `compileSdk`/`targetSdk` 37 if AGP 9.0.0 accepts it, otherwise 36. Task 3 decides this once and records it in the module build files.
- **Bridge availability:**
  - Components are enabled only on API 35+, through `@bool/agent_bridge_enabled` (`false` in
    `values/`, `true` in `values-v35/`).
  - Code that runs on the bridge path is `@RequiresApi(35)`.
- **Names fixed by the spec:**
  - AIDL package: `io.anytype.agentbridge`.
  - Actions: `io.anytype.agentbridge.BIND` and `io.anytype.agentbridge.APPROVE`.
  - Error codes: `NOT_LOGGED_IN = 1`, `START_FAILED = 2`, `API_FAILED = 3`, `INTERNAL = 4`.
  - `VERSION = 1`.
- **Key name:** `agent:<package>:<cert8>`, where `cert8` is the first 8 lowercase hex chars of
  the pinned SHA-256.
  - Default access is Read & write; default spaces are all spaces.
  - The denial cooldown is 30 000 ms.
- **Listener:** `127.0.0.1:0` (ephemeral port).
- **Package names:**
  - The client defaults to target package `io.anytype.app`.
  - The debug app is `io.anytype.app.dev`.
  - The test app is `io.anytype.agentbridge.testapp`.
- **Existing files** get only the edits listed in Task 12 and Task 2: `settings.gradle`,
  `gradle/libs.versions.toml`, `app/build.gradle`, `AndroidApplication.kt`, `MainComponent.kt`,
  `ComponentManager.kt`, and `protocol/src/main/proto/*.proto`.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01UCJ3BFxTb4YjqF76JH56hV
  ```
- **Branch:** commit and push on `ccr-f214df8b-69606d`.

## Review Focus

These cases are most likely to hurt a real user. Each one gets a test in its owning task.

1. **Anytype's process was killed and restarted while the agent stayed bound.** The stored listen
   address is stale and the probe fails. `connect` must rebind and return the new port, never
   the dead one. (Task 8: `staleListenerIsRebound`.)
2. **Two agents, or two `connect` calls, arrive at once on a cold process.** Exactly one
   `setListenAddr` and one account start may happen, and both callers get the same base URL.
   (Task 8: `concurrentConnectsShareOneListener`.)
3. **The user logs out and logs into a different Anytype account.** An agent approved under
   account A must get `onApprovalRequired` under account B, and must not get a key.
   (Task 7: `approvalIsScopedToAccount`; Task 8: `otherAccountRequiresApproval`.)
4. **Heart fails to bind** (it reports `success=false`) **after a previous listener existed.**
   Heart has already closed the old one, so the bridge must clear its cached address and return
   `API_FAILED`, and the next `connect` must try again. (Task 8: `failedBindClearsAddressAndRetries`.)
5. **On the consent screen, the user picks "specific spaces" and unticks all of them, or the
   space list is still loading.** Allow must be disabled, so an empty grant is never stored.
   (Task 10: `allowDisabledWithEmptySelection`.)

---

## File Structure

```
scripts/agent-bridge/
  README.md                         how to build patched heart, run harness + contract tests, drop the patch later
  heart-mobile-api.patch            the one-line fix to clientlibrary/service/lib.go
  fetch-heart-src.sh                proxy.golang.org → build/agent-bridge/src/{heart,tantivy-go}, applies patch
  build-heart-android.sh            tantivy Android libs + gomobile bind → mavenLocal + proto sync
  heart-harness/{main.go,go.mod,go.sum,build.sh,run.sh}   native Linux heart for contract tests
  run-contract-tests.sh             starts harness, exports env, runs gated JVM tests
docs/agent-bridge/heart-issue-draft.md
agent-bridge-client/                Android library (namespace io.anytype.agentbridge.client)
  src/main/aidl/io/anytype/agentbridge/{IAgentBridge,IAgentBridgeCallback}.aidl
  src/main/java/io/anytype/agentbridge/client/
    AnytypeBridge.kt                bind / connect / approval intent+options / findInstalled
    BridgeException.kt              ApprovalRequired, BridgeError(code,message), NotInstalled
    HttpTransport.kt                HttpURLConnection wrapper → HttpResponse(code, headers, body)
    RestClient.kt                   GET/POST/PATCH with bearer, ApiError parsing
    McpClient.kt                    initialize / tools/list / tools/call, session header, 404 re-init
  src/test/...                      unit tests (JDK HttpServer, Robolectric) + gated RealHeart*Test
agent-bridge/                       Android library (namespace com.anytypeio.anytype.agentbridge)
  src/main/java/com/anytypeio/anytype/agentbridge/
    AgentBridge.kt                  install(...) / holder for gateway, launcher, spaces
    Ports.kt                        LocalApiGateway, AccountLauncher, SpaceDirectory + models
    Approval.kt                     Approval, Grant, Access, PendingRevoke, KeyNames
    AgentApprovals.kt               SharedPreferences JSON store
    CallerVerifier.kt               uid → approved package (account-scoped, cert-pinned)
    BridgeController.kt             connect / onAllUnbound logic (pure, testable)
    AgentBridgeService.kt           exported bound service (AIDL stub → controller)
    consent/ConsentState.kt         pure state reducer for the consent screen
    consent/CallerIdentity.kt       getInitialCaller() resolver behind an interface
    consent/AgentApprovalActivity.kt Compose consent dialog
    access/AgentAccessActivity.kt   Compose list + revoke
  src/main/res/values{,-v35}/bools.xml, res/xml/agent_bridge_backup_rules.xml (+ data_extraction_rules)
middleware/src/main/java/com/anytypeio/anytype/middleware/agent/
  HeartCalls.kt                     bytes→bytes seam over gomobile Service
  LocalApiControl.kt                typed wrapper (setListenAddr/listApps/createApp/revokeApp)
app/src/main/java/com/anytypeio/anytype/agentbridge/
  AgentBridgeComponent.kt           Dagger component + dependencies interface
  AgentBridgeInstaller.kt           adapters: gateway, AccountLauncher, SpaceDirectory
agent-bridge-testapp/               Android application (io.anytype.agentbridge.testapp)
  src/main/java/.../MainActivity.kt + FlowRunner.kt
  src/main/res/xml/network_security_config.xml
  src/androidTest/.../AgentBridgeFlowTest.kt (UiAutomator)
```

---

### Task 1: Heart source and the patched native harness

**Files:**
- Create: `scripts/agent-bridge/heart-mobile-api.patch`, copied verbatim from
  `/tmp/claude-0/-home-user-anytype-kotlin/28617b3e-9dc5-5b9c-afb6-799101b58e7f/scratchpad/heart/harness/heart-mobile-api.patch`.
  The paths in it are relative to the heart root.
- Create: `scripts/agent-bridge/fetch-heart-src.sh`.
- Create: `scripts/agent-bridge/heart-harness/*`, adapted from the spike at
  `.../scratchpad/heart/harness/`.
- Create: `scripts/agent-bridge/README.md`, the harness section only. Task 14 completes it.
- Modify: `.gitignore`, adding `/build/agent-bridge/`.

**Interfaces:**
- Produces:
  - `scripts/agent-bridge/fetch-heart-src.sh [--unpatched]` populates `build/agent-bridge/src/heart`
    (patched unless `--unpatched`) and `build/agent-bridge/src/tantivy-go`. It is idempotent.
  - `scripts/agent-bridge/heart-harness/run.sh [-root DIR]` prints these lines and keeps running:
    - `BASE_URL=http://127.0.0.1:<port>`, `API_KEY=…`, `APP_HASH=…`;
    - `CONTROL_URL=http://127.0.0.1:<port>`, `SPACE_ID=<full id of the account's first space>`;
    - `READY`.
  - Control endpoints:
    - `POST /changeaddr?addr=`;
    - `POST /createapp?name=&perm=rw|r&spaces=<comma-separated full ids or empty for all>`, which
      returns `{"appKey","appHash"}`;
    - `GET /listapps`, `POST /revoke?hash=`;
    - `POST /createspace?name=`, which returns `{"spaceId"}`.

- [ ] **Step 1: Write `fetch-heart-src.sh`.**
  - It downloads `https://proxy.golang.org/github.com/anyproto/anytype-heart/@v/v0.51.6.zip` and
    `.../tantivy-go/@v/v1.0.6.zip` with `curl -L`, unzips them into `build/agent-bridge/src/` and
    makes them writable (`chmod -R u+w`).
  - It renames `tantivy-go/rust/rust-toolchain.toml` to `.orig`.
  - It applies the patch with `patch -p1` unless `--unpatched`, writing to a sibling `heart-unpatched` dir.
- [ ] **Step 2: Port the harness to use `build/agent-bridge/src/`.**
  - The `go.mod` `replace` points to `../../../build/agent-bridge/src/heart`, relative to the
    harness dir.
  - Add the `spaces` parameter to `/createapp`, which sets `AppGrant.SpaceIds` and
    `AllSpaces=false` when it isn't empty.
  - Add `/createspace`, which calls `WorkspaceCreate` through the service bytes API.
  - Print `SPACE_ID`.
  - `build.sh` takes `--unpatched`. It builds `bin/harness-unpatched` against `heart-unpatched`,
    keeping the binaries in `build/agent-bridge/bin/`.
- [ ] **Step 3: Verify that the patched harness serves authenticated requests.**
  Run: `scripts/agent-bridge/heart-harness/run.sh > /tmp/h.out 2>/tmp/h.err &`. Wait for
  `READY`, then `curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $API_KEY" $BASE_URL/v2/spaces`.
  Expected: `200`.
- [ ] **Step 4: Verify that the unpatched harness shows the bug.**
  Same as step 3 with `--unpatched`. Expected: `500`.
- [ ] **Step 5: Commit.**
  Message: `Agent bridge: heart fetch script, mobile-API patch and native harness`.

### Task 2: Patched heart Android library, and proto sync

**Files:**
- Create: `scripts/agent-bridge/build-heart-android.sh`.
- Modify: `gradle/libs.versions.toml:2`, setting `middlewareVersion = "v0.51.6-agentbridge.1"`.
- Modify: `protocol/src/main/proto/{commands,events,models,changes,localstore}.proto`. They are
  synced by the script, then normalized with `scripts/mw/normalize-imports.sh`. Read that script
  first.

**Interfaces:**
- Consumes: `fetch-heart-src.sh` (Task 1).
- Produces:
  - `scripts/agent-bridge/build-heart-android.sh [--abis arm64-v8a,x86_64]` publishes
    `~/.m2/repository/io/anyproto/anytype-heart-android/v0.51.6-agentbridge.1/` (aar + pom).
  - The default `--abis` is all four ABIs heart ships: arm, arm64, 386, amd64.
  - Wire classes then include `Rpc.Account.ChangeJsonApiAddr.Response.status`,
    `Rpc.Account.LocalLink.{CreateApp,ListApps,RevokeApp}`, `model.Account.Auth.{AppInfo,AppGrant,LocalApiScope}`.

- [ ] **Step 1: Write the script, mirroring heart's `makefiles/android.mk` and `makefiles/tantivy.mk`.**
  - Install the Rust targets with `rustup target add` for `aarch64-linux-android`,
    `armv7-linux-androideabi`, `i686-linux-android` and `x86_64-linux-android`.
  - Build `libtantivy_go.a` per ABI with the NDK clang from `/opt/android-sdk/ndk/28.1.13356709`
    as linker/CC (`CARGO_TARGET_<TRIPLE>_LINKER`). Copy each into
    `tantivy-go/libs/android-{arm,arm64,386,amd64}/`.
  - Install `golang.org/x/mobile/cmd/gomobile` and `gobind` at the version pinned in heart's
    `go.mod`, then run `gomobile init`.
  - Run the `gomobile bind` with the tags and `-androidapi 26` from Global Constraints. Use
    `-target=android/arm64,android/amd64,...` from `--abis`, and point the `CGO_LDFLAGS` path at
    the tantivy libs.
  - Publish with `mvn install:install-file` (Maven 3.9 is at `/opt/maven`), or by writing the pom
    by hand. Use groupId `io.anyproto`, artifactId `anytype-heart-android`, version
    `v0.51.6-agentbridge.1`, packaging `aar`.
  - Copy `pb/protos/*.proto` and `pkg/lib/pb/model/protos/*.proto` into
    `protocol/src/main/proto/`, then run `scripts/mw/normalize-imports.sh`.
- [ ] **Step 2: Run the script (it may take 30–60 min; run it in the background).**
  Run: `scripts/agent-bridge/build-heart-android.sh`.
  Expected: the aar exists under `~/.m2/.../v0.51.6-agentbridge.1/`.
- [ ] **Step 3: Check that the fix is in the binary.**
  Run: `unzip -p <aar> jni/arm64-v8a/libgojni.so | strings | grep -c 'core/api.SetMiddlewareParams'`.
  Expected: ≥ 1.
- [ ] **Step 4: Check that the proto sync only adds things.**
  Run: `git diff --stat protocol/`, and check `git diff protocol/ | grep '^-' | grep -v '^---'`
  shows no removed fields or enum values. Removed comments are fine.
- [ ] **Step 5: Build the app against it.**
  Run: `$G :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** the script, version bump and protos.
  Message: `Agent bridge: build patched heart v0.51.6 for Android; sync protos`.

### Task 3: `agent-bridge-client`: AIDL contract and `AnytypeBridge`

**Files:**
- Create: `agent-bridge-client/build.gradle`.
  - Library, namespace `io.anytype.agentbridge.client`, with `buildFeatures { aidl = true }`.
  - No project dependencies.
  - Deps: coroutines; test: junit, robolectric 4.18.1 (add `robolectricBridgeVersion = '4.18.1'`
    and `robolectricBridge` to `libs.versions.toml`), `androidx.test:core`, kotlinTest.
- Create: `agent-bridge-client/src/main/AndroidManifest.xml` with
  `<queries><intent><action android:name="io.anytype.agentbridge.BIND"/></intent></queries>`
  and `INTERNET`.
- Create: the two AIDL files.
- Create: `AnytypeBridge.kt` and `BridgeException.kt`.
- Modify: `settings.gradle`, adding `include ':agent-bridge-client'`.
- Test: `agent-bridge-client/src/test/java/io/anytype/agentbridge/client/AnytypeBridgeTest.kt`.

**Interfaces:**
- **AIDL** (both `oneway`):
  - `IAgentBridge { const int VERSION = 1; void connect(IAgentBridgeCallback cb); }`
  - `IAgentBridgeCallback { const int NOT_LOGGED_IN = 1; const int START_FAILED = 2; const int API_FAILED = 3; const int INTERNAL = 4; void onReady(String baseUrl, String apiKey); void onApprovalRequired(); void onError(int code, String message); }`
- **`class AnytypeBridge(context: Context, val targetPackage: String = DEFAULT_PACKAGE)`:**
  - `suspend fun connect(): Endpoint`, which binds if needed and throws `BridgeException.*`;
  - `fun close()`;
  - `fun approvalIntent(): Intent`, with action `APPROVE_ACTION` and package `targetPackage`;
  - `fun approvalOptions(): ActivityOptionsCompat`, a wrapper over
    `ActivityOptions.makeBasic().setShareIdentityEnabled(true)`;
  - `fun markStale()`.
- **Companion:**
  - `DEFAULT_PACKAGE = "io.anytype.app"`, `BIND_ACTION`, `APPROVE_ACTION`;
  - `fun findInstalled(context: Context): List<String>`, the packages exposing `BIND_ACTION`
    through `queryIntentServices`.
- **`data class Endpoint(val baseUrl: String, val apiKey: String)`.**
- **`sealed class BridgeException : Exception`**, with subclasses `ApprovalRequired`,
  `NotInstalled(pkg)` and `Failed(code: Int, msg: String)`.
- **Binding:** use `Context.bindService(Intent(BIND_ACTION).setPackage(targetPackage), Context.BindServiceFlags.of(BIND_AUTO_CREATE or BIND_INCLUDE_CAPABILITIES), executor, conn)`.

- [ ] **Step 1: Decide the SDK level.** Set `compileSdk 37` in this module and run
  `$G :agent-bridge-client:assembleDebug`. If AGP 9.0.0 rejects it, use 36. Write the outcome in
  a comment in `build.gradle`, and use the same value in Tasks 7 and 13.
- [ ] **Step 2: Write failing Robolectric tests** (`@Config(sdk = [35])`). Install a fake
  `IAgentBridge.Stub` with
  `shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent, cn, stub)`.
  - `connectReturnsEndpointOnReady`: the stub calls `onReady("http://127.0.0.1:1","k")`, and the
    test asserts `Endpoint("http://127.0.0.1:1","k")`.
  - `connectThrowsApprovalRequired`.
  - `connectThrowsFailedWithCode`: `onError(3,"x")` gives `Failed(3,"x")`.
  - `connectThrowsNotInstalledWhenBindFails`, using `shadowOf(app).declareActionUnbindable(BIND_ACTION)`.
  - `approvalIntentTargetsPackage`: the action and package are correct.
  - `findInstalledListsBridgeProviders`, using `shadowOf(packageManager).addServiceIfNotPresent` +
    `addIntentFilterForService`. It returns `["io.anytype.app.dev"]`.
- [ ] **Step 3: Run the tests.**
  Run: `$G :agent-bridge-client:testDebugUnitTest`. Expected: compile failure, or FAIL.
- [ ] **Step 4: Implement `AnytypeBridge`** so it resumes once per `connect` call.
  `onServiceDisconnected` and `onBindingDied` clear the cached service.
- [ ] **Step 5: Run the tests.** Same command. Expected: PASS.
- [ ] **Step 6: Commit.** Message: `Agent bridge client: AIDL contract and bind/connect client`.

### Task 4: `agent-bridge-client`: REST and MCP helpers

**Files:**
- Create: `HttpTransport.kt`, `RestClient.kt`, `McpClient.kt`.
- Test: `HttpTransportTest.kt`, `RestClientTest.kt` and `McpClientTest.kt`, using the JDK's
  `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`.

**Interfaces:**
- **`HttpTransport`:**
  - `class HttpTransport(timeoutMs: Int = 60_000)`;
  - `fun request(method: String, url: String, headers: Map<String,String>, body: String?): HttpResponse`;
  - `data class HttpResponse(val code: Int, val headers: Map<String,List<String>>, val body: String)`,
    where header lookup is case-insensitive through `fun header(name): String?`;
  - it never sends an `Origin` header.
- **`RestClient`:**
  - `class RestClient(endpoint: Endpoint, transport: HttpTransport = HttpTransport())`;
  - `fun get(path): String`, `fun post(path, json): String`, `fun patch(path, json): String`;
  - it sends `Authorization: Bearer`, `Accept: application/json` and `Anytype-Version: 2025-11-08`;
  - a non-2xx response throws `ApiError(status: Int, code: String, message: String)`, parsed from
    `{"object":"error","status","code","message"}` with `org.json`.
- **`McpClient`:**
  - `class McpClient(endpoint: Endpoint, tier: String = "small", transport: HttpTransport = HttpTransport())`;
  - `fun initialize(): JSONObject`, which also sends `notifications/initialized`;
  - `fun listTools(): List<String>` (tool names);
  - `fun callTool(name: String, arguments: JSONObject): JSONObject`, which returns `result`.
- **MCP behaviour:**
  - Use `protocolVersion "2025-06-18"`.
  - Store `Mcp-Session-Id` from the initialize response and send it, with
    `MCP-Protocol-Version: 2025-06-18`, on later calls.
  - On HTTP 404, re-initialize once and retry.
  - On 429, throw `ApiError` (no retry).
  - JSON-RPC ids increase from 1.

- [ ] **Step 1: Write failing tests.**
  - `noOriginHeaderIsSent`.
  - `restSendsBearerAndVersion`.
  - `restParsesErrorShape`: the server returns 401 with
    `{"object":"error","status":401,"code":"unauthorized","message":"invalid api key"}`, and the
    test asserts `ApiError(401,"unauthorized","invalid api key")`.
  - `mcpStoresAndSendsSessionId`.
  - `mcpReinitializesOnce404`: the first `tools/call` gets 404, then the test asserts a second
    `initialize` and a successful retry.
  - `mcpSendsInitializedNotification`.
- [ ] **Step 2: Run them.** Run: `$G :agent-bridge-client:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement the three classes.**
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Agent bridge client: REST and MCP helpers`.

### Task 5: Contract tests against real heart

**Files:**
- Create: `agent-bridge-client/src/test/java/io/anytype/agentbridge/client/realheart/RealHeart.kt`.
  - It reads the env vars `ANYTYPE_HEART_BASE_URL`, `ANYTYPE_HEART_API_KEY`,
    `ANYTYPE_HEART_CONTROL_URL` and `ANYTYPE_HEART_SPACE_ID`.
  - `RealHeart.assumeAvailable()` calls `Assume.assumeTrue`.
  - It offers control helpers: `createApp(name, readWrite, spaces)`, `revoke(hash)`,
    `changeAddr(addr)`, `createSpace(name)`.
- Create: `RealHeartRestTest.kt`, `RealHeartMcpTest.kt` and `RealHeartGrantTest.kt`.
- Create: `scripts/agent-bridge/run-contract-tests.sh`. It starts the harness, waits for `READY`,
  exports the four vars, runs
  `$G :agent-bridge-client:testDebugUnitTest --tests '*realheart*'`, and kills the harness on exit.

**Interfaces:**
- Consumes: `RestClient`, `McpClient`, `Endpoint`, `ApiError` (Task 4); the harness control
  endpoints (Task 1).

- [ ] **Step 1: Write the tests.**
  - **`RealHeartRestTest`:**
    - `v2SpacesListsAccountSpace`;
    - `keylessRequestsAreRejected`: `/v1/spaces`, `/v2/spaces` and `/mcp/small` without a key
      all give 401;
    - `revokedKeyGets401`;
    - `foreignOriginIs403`: a raw transport call with `Origin: https://evil.example` gives 403;
    - `emptyListenAddrClosesPort`: after `changeAddr("")`, a TCP connect to the old port fails.
      The test then calls `changeAddr("127.0.0.1:0")` and continues with the new address.
  - **`RealHeartMcpTest`:**
    - `smallTierListsEightTools`: the list equals the set
      `{spaces,find,read,describe,create,set_properties,add_blocks,edit_text}`;
    - `createFindReadRoundTrip`: create a page titled `agent-bridge-contract-<uuid>` with markdown
      body `hello from contract test`, find it by title, read it back, and assert the body text
      appears. Take the argument names from `tools/list` `inputSchema`.
  - **`RealHeartGrantTest`:**
    - `narrowKeyWorksInGrantedSpace`: `createApp(spaces=[SPACE_ID])`, then `/v2/spaces/<short id>`
      and MCP `spaces` work. Resolve the short id from `/v2/spaces`.
    - `narrowKeyForbiddenInOtherSpace`: `createSpace("other")`, and the narrowed key gets 403 on
      it.
    - `narrowKeyRefusedOnV1`: `/v1/spaces` gives 403.
    - `readOnlyKeyCannotWrite`: MCP `create` with a read-only key returns a tool error or 403, and
      no object is created.
- [ ] **Step 2: Check that the tests are gated.**
  Run: `$G :agent-bridge-client:testDebugUnitTest`, with no env vars set.
  Expected: PASS, with the realheart tests skipped.
- [ ] **Step 3: Run them against real heart.**
  Run: `scripts/agent-bridge/run-contract-tests.sh`. Expected: all realheart tests PASS.
  - Where heart's actual behaviour differs from these assertions, it is a finding. Look at the
    harness evidence before changing an assertion, and record it in the README.
- [ ] **Step 4: Commit.** Message: `Agent bridge client: contract tests against real heart`.

### Task 6: `LocalApiControl` in `:middleware`

**Files:**
- Create: `middleware/src/main/java/com/anytypeio/anytype/middleware/agent/HeartCalls.kt`.
- Create: `middleware/src/main/java/com/anytypeio/anytype/middleware/agent/LocalApiControl.kt`.
- Test: `middleware/src/test/java/com/anytypeio/anytype/middleware/agent/LocalApiControlTest.kt`.

**Interfaces:**
- Consumes: the synced Wire classes (Task 2).
- Produces:
  - **`interface HeartCalls`**, with
    `changeJsonApiAddr(req: ByteArray): ByteArray`, `listApps(...)`, `createApp(...)` and
    `revokeApp(...)`. `object GomobileHeartCalls : HeartCalls` delegates to `service.Service.*`.
  - **`class LocalApiControl(calls: HeartCalls = GomobileHeartCalls)`:**
    - `fun setListenAddr(addr: String): String?` returns the bound address. It returns null for
      `""`, and throws `LocalApiException` when `status.success == false`, when status is
      missing, or on an error code.
    - `fun listApps(): List<LocalApiApp>`.
    - `fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): LocalApiApp`.
      `null` means all spaces; an empty list is rejected with `IllegalArgumentException`.
    - `fun revokeApp(appHash: String)`. A `NOT_FOUND` error is treated as success.
  - `data class LocalApiApp(val appHash: String, val appName: String, val appKey: String, val createdAt: Long)`.
  - `class LocalApiException(message: String) : Exception(message)`.

- [ ] **Step 1: Write failing tests with a fake `HeartCalls`** that decodes the request and
  returns an encoded Wire response.
  - `setListenAddrReturnsStatusAddress`.
  - `setListenAddrThrowsOnFailedBind`: the message contains `status.error`.
  - `setListenAddrEmptyReturnsNull`.
  - `createAppSendsAllSpacesWhenNull`.
  - `createAppSendsSpaceIdsAndReadPerm`.
  - `createAppRejectsEmptySpaceList`.
  - `listAppsMapsFields`.
  - `revokeNotFoundIsSuccess`.
  - `errorCodeBecomesException`.
- [ ] **Step 2: Run them.**
  Run: `$G :middleware:testDebugUnitTest --tests '*LocalApiControlTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Middleware: LocalApiControl for heart's local JSON API`.

### Task 7: `agent-bridge`: ports, approvals store, caller verification

**Files:**
- Create: `agent-bridge/build.gradle`.
  - Library, namespace `com.anytypeio.anytype.agentbridge`, compose on.
  - Deps: `:agent-bridge-client` (`api`, for the AIDL), coroutines, compose/material3, lifecycle.
  - Same test deps as Task 3.
- Create: `Ports.kt`, `Approval.kt`, `AgentApprovals.kt` and `CallerVerifier.kt`.
- Create: `res/values/bools.xml` (`agent_bridge_enabled=false`), `res/values-v35/bools.xml` (`true`).
- Modify: `settings.gradle`, adding `include ':agent-bridge'`.
- Test: `AgentApprovalsTest.kt` and `CallerVerifierTest.kt`.

**Interfaces:**
- **Ports** (in `Ports.kt`):
  ```kotlin
  data class AccountRef(val id: String, val name: String)
  data class SpaceRef(val id: String, val name: String)
  interface LocalApiGateway {
      fun setListenAddr(addr: String): String?
      fun listApps(): List<GatewayApp>
      fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): GatewayApp
      fun revokeApp(appHash: String)
  }
  data class GatewayApp(val appHash: String, val appName: String, val appKey: String, val createdAt: Long)
  interface AccountLauncher {
      fun runningAccount(): AccountRef?          // null when not started
      suspend fun ensureStarted(): AccountRef    // throws AccountStartException(code) with NOT_LOGGED_IN or START_FAILED
  }
  interface SpaceDirectory { suspend fun activeSpaces(): List<SpaceRef> }
  class AccountStartException(val code: Int, message: String) : Exception(message)
  ```
- **Approval model** (in `Approval.kt`):
  - `enum class Access { READ_WRITE, READ_ONLY }`;
  - `data class Grant(val allSpaces: Boolean, val spaceIds: List<String>)`, with `Grant.ALL`.
    `init` requires `allSpaces || spaceIds.isNotEmpty()`.
  - `data class Approval(accountId, packageName, certSha256, access, grant, approvedAt: Long)`;
  - `data class PendingRevoke(accountId, packageName)`;
  - `object KeyNames { fun name(pkg, certSha256) = "agent:$pkg:${certSha256.take(8)}"; fun prefix(pkg) = "agent:$pkg:" }`.
- **`class AgentApprovals(context: Context, clock: () -> Long = System::currentTimeMillis)`:**
  - `fun find(accountId, pkg): Approval?`, `fun all(): List<Approval>`;
  - `fun approve(a: Approval)`, which also adds a `PendingRevoke` for that account and package;
  - `fun revoke(accountId, pkg)`, which removes the approval and adds a `PendingRevoke`;
  - `fun pendingRevokes(accountId): Set<String>` (packages), `fun clearPendingRevoke(accountId, pkg)`;
  - `fun deny(pkg)`, which sets `deniedUntil = now + 30_000`; `fun isInDenialCooldown(pkg): Boolean`.
  - **Storage:** prefs file `agent_bridge`, one key `state`, holding JSON written with `org.json`.
- **`class CallerVerifier(pm: PackageManager, approvals: AgentApprovals)`:**
  - `fun approvedCaller(uid: Int, accountId: String): Approval?` checks each of
    `pm.getPackagesForUid(uid)` that has an approval for `accountId`, and requires
    `pm.hasSigningCertificate(uid, hex(certSha256), CERT_INPUT_SHA256)`.
  - `fun currentCertSha256(pkg): String?` returns null for multiple signers.

- [ ] **Step 1: Write failing tests** (Robolectric `sdk=[35]`).
  - `approveThenFind`.
  - `approvalIsScopedToAccount`: approved under `A`, so `find("B", pkg)` is null.
  - `revokeRemovesAndQueuesPendingRevoke`.
  - `approveQueuesRevokeOfOldKeys`.
  - `denialCooldownExpiresAfter30s`, with a fake clock at +29 999 and +30 001.
  - `grantRejectsEmptySpaceList`.
  - `stateSurvivesNewInstance`.
  - `CallerVerifierTest`:
    - `matchingCertIsApproved`;
    - `changedCertIsNotApproved`;
    - `sharedUidPicksApprovedPackage`;
    - `unknownUidIsNull`.
    Use `ShadowPackageManager` with `PackageInfo.signingInfo` set. If Robolectric can't stub
    `hasSigningCertificate`, wrap the call in `CallerVerifier(…, certCheck: (uid, ByteArray) -> Boolean)`.
- [ ] **Step 2: Run them.** Run: `$G :agent-bridge:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Agent bridge: approvals store and caller verification`.

### Task 8: `BridgeController`, the connect and unbind logic

**Files:**
- Create: `agent-bridge/src/main/java/com/anytypeio/anytype/agentbridge/BridgeController.kt`.
- Test: `BridgeControllerTest.kt`, using fakes for `LocalApiGateway`, `AccountLauncher` and
  `CallerVerifier`. Inject the verifier behind a `fun interface CallerCheck { fun approved(uid, accountId): Approval? }`.

**Interfaces:**
- Consumes: Task 7's types.
- Produces: `class BridgeController(gateway, launcher, approvals: AgentApprovals, callerCheck: CallerCheck, probe: (String) -> Boolean, dispatcher: CoroutineDispatcher)`.
  - `sealed interface ConnectResult { data class Ready(baseUrl, apiKey); object ApprovalRequired; data class Error(code: Int, message: String) }`.
  - `suspend fun connect(uid: Int): ConnectResult`.
  - `suspend fun onAllUnbound()`.
  - `const val LISTEN = "127.0.0.1:0"`.
- **Logic, in order, under one `Mutex`:**
  1. `ensureStarted()`. An `AccountStartException` becomes `Error(code)`.
  2. `callerCheck.approved(uid, account.id)`. If it is null, return `ApprovalRequired`.
  3. Sweep: for each pending package of this account, revoke every gateway app whose name starts
     with `KeyNames.prefix(pkg)` and isn't `KeyNames.name(current approval)`. Then clear the
     pending entry.
  4. Listener: reuse the cached address if `probe(addr)`. Otherwise call `setListenAddr(LISTEN)`.
     On an exception, clear the cache and return `Error(API_FAILED)`.
  5. Key: take the apps named `KeyNames.name(pkg, cert)`. If there are several, keep the newest
     by `createdAt` and revoke the rest. If there are none, call `createApp` with the approval's
     grant (`null` for all spaces) and access.
  6. Return `Ready("http://$addr", key)`.
- **`onAllUnbound`:** if an address is cached, call `setListenAddr("")` and clear the cache. It
  ignores exceptions after logging them.
- **Approval check placement:** the check needs the running account. If the account isn't
  started, the controller starts it first (step 1).

- [ ] **Step 1: Write failing tests** (`runTest`).
  - `unapprovedCallerGetsApprovalRequiredAndNoKey`.
  - `coldStartStartsAccountThenBinds`.
  - `notLoggedInMapsToCode1`.
  - `staleListenerIsRebound`: the probe returns false, so a second `setListenAddr` returns a new
    port and `Ready` carries it.
  - `liveListenerIsReused`.
  - `concurrentConnectsShareOneListener`: two `async` connects give exactly one `setListenAddr`
    and the same baseUrl.
  - `failedBindClearsAddressAndRetries`: the first call throws, giving `Error(3)`; the second
    succeeds.
  - `existingKeyIsReused`.
  - `duplicateKeysAreDeduped`: the newest is kept and the others revoked.
  - `narrowGrantPassesSpaceIds`.
  - `readOnlyGrantPassesReadWriteFalse`.
  - `pendingRevokeSweepsOldKeys`.
  - `otherAccountRequiresApproval`.
  - `unboundClosesListener`.
- [ ] **Step 2: Run them.**
  Run: `$G :agent-bridge:testDebugUnitTest --tests '*BridgeControllerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Agent bridge: connect/unbind controller`.

### Task 9: `AgentBridgeService`, `AgentBridge.install`, manifest and backup rules

**Files:**
- Create: `AgentBridge.kt` and `AgentBridgeService.kt`.
- Create: `agent-bridge/src/main/AndroidManifest.xml`.
  - The service is `exported=true`, `enabled="@bool/agent_bridge_enabled"`, with an intent-filter
    on `io.anytype.agentbridge.BIND`.
  - The two activities from Tasks 10–11 are declared here too.
  - Permission `HIDE_OVERLAY_WINDOWS`.
- Create: `res/xml/agent_bridge_backup_rules.xml` and `agent_bridge_data_extraction_rules.xml`,
  excluding `sharedpref/agent_bridge.xml`.
  - Read `app/src/main/AndroidManifest.xml` for existing `fullBackupContent` and
    `dataExtractionRules` attributes. If the app already sets them, add the exclusion to the
    app's files instead, and list that file under Task 12's existing-file edits.
- Test: `AgentBridgeServiceTest.kt`.

**Interfaces:**
- Consumes: `BridgeController` (Task 8).
- Produces:
  - `object AgentBridge { fun install(gateway: LocalApiGateway, launcher: AccountLauncher, spaces: SpaceDirectory); internal val gateway/launcher/spaces }`.
    Before `install` is called, the service answers `onError(INTERNAL, "bridge not installed")`.
  - In the service:
    - `connect` reads `Binder.getCallingUid()` on the binder thread, then launches on a
      `SupervisorJob + Dispatchers.IO` scope and maps `ConnectResult` to the callbacks.
      Callback `RemoteException`s are swallowed.
    - `onUnbind` launches `controller.onAllUnbound()` and returns false.
    - `onDestroy` cancels the scope.
    - `probe` is a 300 ms TCP connect.

- [ ] **Step 1: Write failing tests** (Robolectric `sdk=[35]`, with `ServiceController` +
  `ShadowBinder.setCallingUid`, and fakes installed through `AgentBridge.install`).
  - `connectDeliversOnReady`.
  - `connectDeliversApprovalRequired`.
  - `notInstalledDeliversInternalError`.
  - `unbindClosesListener`.
  - `componentsDisabledBelowApi35`: under `@Config(sdk=[34])`, the resolved
    `R.bool.agent_bridge_enabled` is false.
- [ ] **Step 2: Run them.** Run: `$G :agent-bridge:testDebugUnitTest --tests '*AgentBridgeServiceTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Agent bridge: exported bound service`.

### Task 10: Consent screen

**Files:**
- Create: `consent/ConsentState.kt`, `consent/CallerIdentity.kt` and `consent/AgentApprovalActivity.kt`.
- Test: `ConsentStateTest.kt` and `AgentApprovalActivityTest.kt`.

**Interfaces:**
- Consumes: `AgentApprovals`, `CallerVerifier.currentCertSha256`, `AgentBridge.launcher/spaces`
  (Tasks 7 and 9).
- Produces:
  - **`fun interface CallerIdentity { fun resolve(activity: Activity): Caller? }`**, with
    `data class Caller(val uid: Int, val packageName: String)`.
    - The default uses `activity.initialCaller`. It returns null if the uid is
      `Process.INVALID_UID`, if the package is null, if the package isn't in
      `getPackagesForUid(uid)`, or if `activity.callingPackage` is non-null and differs.
    - It is swappable through `AgentApprovalActivity.callerIdentity`, an internal companion var
      for tests.
  - **`data class ConsentState(...)`** with fields:
    - `loading`, `accountName`, `appLabel`, `packageName`, `certShort`;
    - `spaces: List<SpaceRef>`, `allSpaces = true`, `selected: Set<String>`;
    - `access = READ_WRITE`, `error: String?`.
    - Methods: `canAllow` (`!loading && error == null && (allSpaces || selected.isNotEmpty())`),
      `toggleSpace(id)`, `setAllSpaces(b)`, `setAccess(a)`, `grant(): Grant`.
    - `certShort` is the first 16 hex chars, grouped by 4 and separated with spaces.
  - **Activity behaviour:**
    - The theme is translucent and the screen is a Compose `Dialog`. `onCreate` calls
      `window.setHideOverlayWindows(true)` and adds `FLAG_SECURE`. The Allow button uses
      `Modifier.semantics` plus a `View`-level `filterTouchesWhenObscured = true` and
      `isAccessibilityDataSensitive`; apply them on the `ComposeView` root.
    - The result starts as `RESULT_CANCELED`.
    - If there is no caller, or the cert is null, it shows a toast and finishes.
    - If the caller is in its denial cooldown, it finishes immediately.
    - Otherwise it calls `ensureStarted()` and `activeSpaces()`. Failure shows "Sign in to Anytype first" and finishes.
    - **Allow:** `approvals.approve(Approval(account.id, pkg, cert, access, grant, now))`, then
      `RESULT_OK` and finish.
    - **Don't allow:** `approvals.deny(pkg)`, then finish.
    - A footer link opens `AgentAccessActivity`.
  - **Copy:**
    - Title: `Allow <label> to access Anytype?`
    - Body: `<label> will be able to read[ and change] all objects in <all your spaces|N spaces> of <accountName> while it is connected.`
    - Narrowed-grant note: `With specific spaces, the app can use the v2 API and MCP only, and cannot create spaces.`

- [ ] **Step 1: Write failing tests.**
  - `ConsentStateTest`:
    - `defaultsToAllSpacesReadWrite`;
    - `allowDisabledWithEmptySelection`: `setAllSpaces(false)` with nothing selected gives
      `canAllow == false`;
    - `allowDisabledWhileLoading`;
    - `grantFromSelection`;
    - `certShortFormatting`.
  - `AgentApprovalActivityTest` (Robolectric `sdk=[35]`, with a fake `CallerIdentity` and fake
    ports):
    - `noIdentityCancels`;
    - `deniedCallerInCooldownCancelsImmediately`;
    - `allowStoresApprovalAndReturnsOk`, which drives the Compose rule and clicks "Allow";
    - `dontAllowSetsCooldown`;
    - `notLoggedInCancels`.
- [ ] **Step 2: Run them.** Run: `$G :agent-bridge:testDebugUnitTest --tests '*Consent*' --tests '*AgentApprovalActivity*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Add test deps `ui-test-junit4` and `ui-test-manifest` for
  Compose-in-Robolectric if they aren't present.
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Agent bridge: consent screen with space and access choice`.

### Task 11: Access management screen

**Files:**
- Create: `access/AgentAccessActivity.kt`.
  - It is exported for its intent filter `android.intent.action.APPLICATION_PREFERENCES`
    (category `DEFAULT`), and enabled by `@bool/agent_bridge_enabled`.
- Test: `AgentAccessActivityTest.kt`.

**Interfaces:**
- Consumes: `AgentApprovals`, `AgentBridge.gateway/launcher`, `KeyNames`.
- **Behaviour:**
  - It lists `approvals.all()` filtered to `launcher.runningAccount()?.id`. If the account isn't
    running, it calls `ensureStarted()`.
  - Each row shows the label, package, fingerprint, access, grant ("All spaces" or "N spaces")
    and approval date, with a Revoke button.
  - **Revoke** calls `approvals.revoke(accountId, pkg)`, then on IO tries to revoke every gateway
    app with `KeyNames.prefix(pkg)`. When that succeeds it calls `clearPendingRevoke`.
  - Empty state text: `No apps have access to Anytype.`

- [ ] **Step 1: Write failing tests.**
  - `listsApprovalsOfRunningAccount`.
  - `revokeRemovesApprovalAndKeys`.
  - `revokeWhileGatewayFailsKeepsPending`.
- [ ] **Step 2: Run them.** Run: `$G :agent-bridge:testDebugUnitTest --tests '*AgentAccessActivity*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `Agent bridge: manage approved agents`.

### Task 12: Wire the bridge into `:app`

**Files:**
- Create: `app/src/main/java/com/anytypeio/anytype/agentbridge/AgentBridgeComponent.kt`.
  - `@Component(dependencies=[AgentBridgeDependencies::class]) @PerScreen interface AgentBridgeComponent { fun installer(): AgentBridgeInstaller }`.
  - The dependencies are the same list as `CreateObjectWidgetConfigDependencies` (read it at
    `app/src/main/java/com/anytypeio/anytype/di/feature/oswidgets/CreateObjectWidgetConfigDI.kt:197-209`).
- Create: `app/src/main/java/com/anytypeio/anytype/agentbridge/AgentBridgeInstaller.kt`, holding
  three adapters:
  - `LocalApiControl` → `LocalApiGateway`.
  - **`AccountLauncher`:**
    - `runningAccount()` returns `ConfigStorage.getAccountId()` when
      `AwaitAccountStartManager.hasStarted()`, with the name from
      `AuthRepository.getCurrentAccount().name`.
    - `ensureStarted()`: if `AuthRepository.getMnemonic()` is blank, throw `NOT_LOGGED_IN`.
      Otherwise call `launchMiddlewareForConfig("AgentBridge", launchWallet, launchAccount)`.
      `Failure` becomes `START_FAILED`.
  - **`SpaceDirectory`:** `SpaceViewSubscriptionContainer.start()` if needed, then
    `awaitFirstSpacesEmission(observe())`, then `observe().first()`. Map active views to
    `SpaceRef(view.targetSpaceId, view.name)`, and check that field name in `ObjectWrapper.SpaceView`.
- Modify: `settings.gradle`, including `:agent-bridge-testapp`. The other two includes landed
  earlier.
- Modify: `app/build.gradle`, adding `implementation project(':agent-bridge')`.
- Modify: `MainComponent.kt`, adding `AgentBridgeDependencies` to its supertypes and a
  `@ComponentDependenciesKey` binding, mirroring lines 173 and 501-502.
- Modify: `ComponentManager.kt`, adding `val agentBridgeComponent = Component { DaggerAgentBridgeComponent.factory().create(findComponentDependencies()) }`.
- Modify: `AndroidApplication.kt`, adding one line in `onCreate()` after `main.inject(this)`:
  `if (Build.VERSION.SDK_INT >= 35) componentManager.agentBridgeComponent.get().installer().install()`.

**Interfaces:**
- Consumes: `AgentBridge.install` (Task 9), `LocalApiControl` (Task 6), `launchMiddlewareForConfig`, `awaitFirstSpacesEmission`.
- Produces: `class AgentBridgeInstaller @Inject constructor(...) { fun install() }`.

- [ ] **Step 1: Write failing unit tests** in `app/src/test/java/com/anytypeio/anytype/agentbridge/AgentBridgeInstallerTest.kt`
  (mockito-kotlin, already a test dep).
  - `ensureStartedWithoutMnemonicThrowsNotLoggedIn`.
  - `ensureStartedLaunchesWalletThenAccount`.
  - `runningAccountNullBeforeStart`.
  - `gatewayAdapterMapsCreateApp`.
- [ ] **Step 2: Run them.** Run: `$G :app:testDebugUnitTest --tests '*AgentBridgeInstallerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement the wiring.**
- [ ] **Step 4: Run the tests and the build.**
  Run: `$G :app:testDebugUnitTest --tests '*AgentBridgeInstallerTest*' :app:assembleDebug`.
  Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Check the merged manifest.**
  Run: `grep -c "io.anytype.agentbridge.BIND\|io.anytype.agentbridge.APPROVE\|APPLICATION_PREFERENCES" app/build/intermediates/merged_manifests/debug/*/AndroidManifest.xml`.
  Expected: 3.
- [ ] **Step 6: Commit.** Message: `App: install agent bridge`.

### Task 13: The test agent app and its instrumented test

**Files:**
- Create: `agent-bridge-testapp/build.gradle`.
  - Application `io.anytype.agentbridge.testapp`, minSdk 35, with targetSdk and compileSdk from
    Task 3.
  - Debug signing uses the default debug keystore. It differs from Anytype's only if the
    Anytype debug build uses a custom key: check `app/build.gradle` `signingConfigs`. If both use
    `~/.android/debug.keystore`, generate a separate keystore at
    `agent-bridge-testapp/testapp-debug.jks` with fixed, documented passwords and commit it, so
    certificate pinning is exercised across distinct certs.
  - Deps: `:agent-bridge-client`, compose/material3, coroutines.
  - androidTest deps: `androidx.test.ext:junit`, `androidx.test:runner`,
    `androidx.test.uiautomator:uiautomator` (add to the version catalog).
- Create: `src/main/AndroidManifest.xml`, with `networkSecurityConfig` allowing cleartext only to
  `127.0.0.1`.
- Create: `src/main/java/io/anytype/agentbridge/testapp/MainActivity.kt` and `FlowRunner.kt`.
- Create: `src/androidTest/java/io/anytype/agentbridge/testapp/AgentBridgeFlowTest.kt`.

**Interfaces:**
- Consumes: `AnytypeBridge`, `RestClient`, `McpClient` (Tasks 3–4).
- **`FlowRunner(bridge, log: (String) -> Unit)`:**
  - `suspend fun runAll(): Boolean` performs the steps "connect", "v2 spaces", "mcp initialize",
    "tools/list", "create page", "find page" and "read page".
  - It logs `OK <step>` or `FAIL <step>: <msg>`, then `ALL OK` at the end.
- **`MainActivity`:**
  - A target selector filled from `AnytypeBridge.findInstalled`.
  - Buttons: `Connect`, `Approve`, `Run flow`, `Unbind`.
  - `Approve` uses `registerForActivityResult(StartActivityForResult())` with
    `bridge.approvalOptions()`.
  - A scrolling log with test tag `log`.
- **`AgentBridgeFlowTest`:**
  - It launches `MainActivity` and taps `Approve`.
  - With UiAutomator it waits for the text `Allow` in the package `io.anytype.app.dev` and taps it.
  - It taps `Run flow` and waits up to 120 s for `ALL OK` in the log.
  - It precondition-skips when `findInstalled` is empty.

- [ ] **Step 1: Write a failing JVM test** `FlowRunnerTest`, using a fake `AnytypeBridge` seam
  (`fun interface EndpointSource { suspend fun endpoint(): Endpoint }`) and a JDK `HttpServer`
  that scripts the MCP responses.
  - `allStepsLogOk`.
  - `failureStopsAndLogsStep`.
- [ ] **Step 2: Run it.** Run: `$G :agent-bridge-testapp:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement the app and the instrumented test.**
- [ ] **Step 4: Run the tests and build everything.**
  Run: `$G :agent-bridge-testapp:testDebugUnitTest :agent-bridge-testapp:assembleDebug :agent-bridge-testapp:assembleDebugAndroidTest`.
  Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Optional real-heart run of `FlowRunner`.** `run-contract-tests.sh` also runs
  `FlowRunnerRealHeartTest` (gated like Task 5), which drives `FlowRunner` with an
  `EndpointSource` backed by the harness env vars. Expected: `ALL OK`.
- [ ] **Step 6: Commit.** Message: `Agent bridge: test agent app and instrumented flow test`.

### Task 14: Release-readiness, docs and final verification

**Files:**
- Create: `agent-bridge/consumer-rules.pro` and `agent-bridge-client/consumer-rules.pro`.
  - Keep `io.anytype.agentbridge.**` (the AIDL stubs) and the activity and service classes.
  - Wire them through `consumerProguardFiles`.
- Create or complete: `scripts/agent-bridge/README.md`. It covers:
  - building patched heart (Task 2);
  - running the contract tests (Task 5);
  - installing `app-debug.apk` and the test app on an emulator or phone, and running
    `connectedDebugAndroidTest` for `:agent-bridge-testapp`;
  - upgrading heart: bump the version in both scripts, rebuild, rerun the contract tests;
  - dropping the patch once upstream calls `api.SetMiddlewareParams` in `clientlibrary/service`;
  - the accepted loopback exposure.
- Create: `docs/agent-bridge/heart-issue-draft.md`. It gives the symptom (an empty 500 on every
  authenticated JSON API request from the mobile library), the root cause and file and line
  references, the one-line fix, and a reproduction with the harness. It is not filed.
- Create: `docs/agent-bridge/agent-integration.md`, a short guide for the user's agent:
  - depend on or copy `agent-bridge-client`;
  - the `connect` → `ApprovalRequired` → `approvalIntent` + `approvalOptions` loop;
  - `McpClient` on `/mcp/small`;
  - the bundled GBNF manifest, taken from `build/agent-bridge/bin/anytype tools --tier small`
    output and committed as `docs/agent-bridge/small-tools-manifest.json`;
  - reconnect on failure.

- [ ] **Step 1: Verify the R8 rules apply.**
  Run: `$G :agent-bridge:assembleRelease :agent-bridge-client:assembleRelease`. Expected: BUILD
  SUCCESSFUL. No app release build is required.
- [ ] **Step 2: Run the full verification.**
  Run: `$G :agent-bridge-client:testDebugUnitTest :agent-bridge:testDebugUnitTest :middleware:testDebugUnitTest :agent-bridge-testapp:testDebugUnitTest :app:assembleDebug :agent-bridge-testapp:assembleDebugAndroidTest :agent-bridge:lintDebug :agent-bridge-client:lintDebug :agent-bridge-testapp:lintDebug`,
  then `scripts/agent-bridge/run-contract-tests.sh`.
  Expected: all PASS. The new modules report no lint errors.
- [ ] **Step 3: Commit and push.**
  - Message: `Agent bridge: release rules, docs and integration guide`.
  - Then run `git push -u origin ccr-f214df8b-69606d`.
