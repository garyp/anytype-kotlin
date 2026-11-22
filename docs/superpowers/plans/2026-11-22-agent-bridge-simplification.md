# Agent Bridge Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `connect()` a synchronous AIDL call, replace our REST/MCP wrappers with OkHttp and the official Kotlin MCP SDK, and move the heart patch, its regression test and the harness into the `garyp/anytype-heart` fork, built by upstream's own workflow.

**Architecture:**
- **anytype-kotlin** (`/home/user/anytype-kotlin`, branch `ccr-f214df8b-69606d`): the client and service AIDL are simplified, the wrappers are deleted, the test app moves to standard libraries, and the in-repo heart pipeline is deleted.
- **The heart fork** (`/home/user/anytype-heart`, a new branch `agentbridge` from tag `v0.51.6`): the patch, a Go regression test, two workflow edits, and `cmd/agentbridge-harness`.

**Tech Stack:** Kotlin/AIDL, coroutines, Robolectric 4.18.1, OkHttp, `io.modelcontextprotocol:kotlin-sdk-client` 0.16.0, Go 1.26 (heart), and GitHub Actions (upstream `build.yml`).

**Spec:** `docs/superpowers/specs/2026-11-22-agent-bridge-simplification-design.md`, which builds on `docs/superpowers/specs/2026-11-21-agent-bridge-design.md`.

## Global Constraints

- **Environment:** run Gradle with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk` and `--no-configuration-cache`.
- **Lint:** `abortOnError` is false, so read `build/reports/lint-results-debug.txt` for each touched module and confirm "No issues found".
- **AIDL:**
  - `IAgentBridge` has `const int VERSION = 2` and `AgentEndpoint connect()`, and is NOT oneway. `IAgentBridgeCallback` is deleted.
  - Error codes, thrown as `ServiceSpecificException`: `NOT_LOGGED_IN = 1`, `START_FAILED = 2`, `API_FAILED = 3`, `INTERNAL = 4`, `APPROVAL_REQUIRED = 5`. Messages are generic.
- **Unchanged behaviour:** controller, approvals, binding count, listener lifecycle, consent and access screens, cert pinning, `findInstalled`, `approvalIntent`/`approvalOptions`, and the bind flags.
- **Unapproved callers** get `APPROVAL_REQUIRED` without ever blocking the binder thread on account start or heart calls.
- **Heart:**
  - The fork branch is `agentbridge`, based on upstream tag `v0.51.6`, and the release tag is `v0.51.6-agentbridge.1`.
  - The artifact is `io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1`.
  - The registry is `https://maven.pkg.github.com/garyp/anytype-heart`.
- **Pushing to `garyp/anytype-heart` is currently refused** (the GitHub App is not installed). Commit locally. The controller pushes when access exists.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01UCJ3BFxTb4YjqF76JH56hV
  ```
  Never commit `github.properties`, `apikeys.properties`, `local.properties`, `app/google-services.json`, or `build/`.

## Review Focus

1. **Anytype dies while a synchronous `connect()` is in flight.** The client must fail promptly with `Failed(INTERNAL)`, not hang, and the next `connect()` must rebind. (Task 1: `serviceDeathDuringConnectFailsThenRebinds`.)
2. **Many unapproved apps call `connect()` at once.** Each must return `APPROVAL_REQUIRED` immediately, with no account start and no controller mutex wait. (Task 2: `unapprovedCallerReturnsImmediatelyWithoutStarting`.)
3. **An agent calls `connect()` on the main thread.** The client must still never block it. `connect()` is `suspend` and does the binder call on `Dispatchers.IO`. (Task 1: `connectDoesBinderCallOffMainThread`.)
4. **The MCP SDK against real heart.** Session headers, the 202 response for `notifications/initialized`, and the 405 on a GET stream must all work without special handling. If they don't, that is a documented finding. (Task 3: the real-heart flow.)
5. **The fork's tag build publishes to the fork, not to anyproto.** The trigger steps must not fail the run. (Task 4: a check of `build.gradle` and `build.yml` by inspection, plus the first workflow run after push.)

---

### Task 1: Synchronous AIDL and the new client

**Files:**
- Create: `agent-bridge-client/src/main/aidl/io/anytype/agentbridge/AgentEndpoint.aidl` (`parcelable AgentEndpoint;`).
- Create: `agent-bridge-client/src/main/java/io/anytype/agentbridge/AgentEndpoint.kt`, a `@Parcelize` or manual `Parcelable` class with `baseUrl: String` and `apiKey: String`.
- Modify: `agent-bridge-client/src/main/aidl/io/anytype/agentbridge/IAgentBridge.aidl`. Delete `IAgentBridgeCallback.aidl`.
- Modify: `agent-bridge-client/src/main/java/io/anytype/agentbridge/client/AnytypeBridge.kt` (rewrite) and `BridgeException.kt`.
- Test: `agent-bridge-client/src/test/java/io/anytype/agentbridge/client/AnytypeBridgeTest.kt`.

**Interfaces:**
- **AIDL:**
  - `interface IAgentBridge { const int VERSION = 2; const int NOT_LOGGED_IN = 1; const int START_FAILED = 2; const int API_FAILED = 3; const int INTERNAL = 4; const int APPROVAL_REQUIRED = 5; AgentEndpoint connect(); }`
  - The service throws `android.os.ServiceSpecificException(code, msg)`.
- **Kept (Kotlin):**
  - `data class Endpoint(val baseUrl: String, val apiKey: String)`.
  - `BridgeException.ApprovalRequired`, `NotInstalled(pkg)`, `Untrusted(pkg)`, `Failed(code: Int, msg: String)`.
  - `AnytypeBridge(context, targetPackage = DEFAULT_PACKAGE, expectedCertSha256: String? = null)`, with:
    - `suspend fun connect(): Endpoint`
    - `fun close()`
    - `fun approvalIntent()`
    - `fun approvalOptions()`
    - `fun markStale()`
    - companion `findInstalled(context, expectedCertSha256 = null)`
- **Behaviour:**
  - `connect()` holds a `Mutex`.
  - It binds if not bound: pin check, then `bindService` with the existing flags, then suspend until `onServiceConnected`. The bind fails on `onBindingDied`, `onNullBinding` or a `false` return.
  - Then `withContext(Dispatchers.IO) { service.connect() }`.
  - Error mapping:
    - `ServiceSpecificException` with code 5 becomes `ApprovalRequired`; any other code becomes `Failed(code, message)`.
    - `DeadObjectException` or any `RemoteException` means: unbind, clear the cached service, and throw `Failed(INTERNAL, "bridge disconnected")`.
  - `onServiceDisconnected` clears the cached service, so the next `connect()` rebinds.

- [ ] **Step 1: Rewrite `AnytypeBridgeTest` against the new contract** (Robolectric, `@Config(sdk=[35])`, with a fake `IAgentBridge.Stub` that returns or throws). Keep the existing pinning, findInstalled, approvalIntent and bind-flags tests. Replace the callback tests with:
  - `connectReturnsEndpoint`: the stub returns `AgentEndpoint("http://127.0.0.1:1","k")`, and the test asserts `Endpoint("http://127.0.0.1:1","k")`.
  - `approvalRequiredCodeMapsToApprovalRequired`: the stub throws `ServiceSpecificException(5,"x")`, and the test asserts `ApprovalRequired`.
  - `errorCodeMapsToFailed`: `ServiceSpecificException(3,"x")` gives `Failed(3, …)`.
  - `serviceDeathDuringConnectFailsThenRebinds`: the stub throws `DeadObjectException`, and the test asserts `Failed(INTERNAL)`. A second `connect()` performs a new `bindService` (bind count 2) and succeeds.
  - `connectDoesBinderCallOffMainThread`: the stub records `Looper.myLooper() != Looper.getMainLooper()`, and the test asserts true.
  - `serviceDisconnectedForcesRebind`.
- [ ] **Step 2: Run the tests.** Run: `./gradlew --no-configuration-cache :agent-bridge-client:testDebugUnitTest`. Expected: FAIL (compile errors).
- [ ] **Step 3: Implement the AIDL changes, the `AgentEndpoint` parcelable, and the new `AnytypeBridge`.** Delete the request/waiter bookkeeping (`ResumeOnce`, `inFlight`, `waiting`, `failWaiting`, `releaseAndFail`). Keep the KDoc that states single-owner use.
- [ ] **Step 4: Run the tests again, plus lint.** Run: `./gradlew --no-configuration-cache :agent-bridge-client:testDebugUnitTest :agent-bridge-client:lintDebug`. Expected: PASS, and the lint report says "No issues found". The `:agent-bridge` module will not compile until Task 2; that is expected.
- [ ] **Step 5: Commit.** Message: `Agent bridge client: synchronous AIDL connect (v2)`.

### Task 2: The service implements the synchronous call

**Files:**
- Modify: `agent-bridge/src/main/java/com/anytypeio/anytype/agentbridge/AgentBridgeService.kt`.
- Modify: `agent-bridge/src/main/java/com/anytypeio/anytype/agentbridge/BridgeController.kt`, only if a fast pre-check accessor is needed. `approvedAnywhere` already exists.
- Test: `agent-bridge/src/test/java/com/anytypeio/anytype/agentbridge/AgentBridgeServiceTest.kt`.

**Interfaces:**
- Consumes: Task 1's AIDL; `BridgeController.connect(uid): ConnectResult` (`Ready`, `ApprovalRequired`, `Error(code, message)`).
- **Behaviour of `IAgentBridge.Stub.connect()`:**
  1. `val uid = Binder.getCallingUid()`.
  2. If no controller is installed: throw `ServiceSpecificException(INTERNAL, "bridge not installed")`.
  3. Fast path: if the controller's `approvedAnywhere(uid)` check is false, throw `ServiceSpecificException(APPROVAL_REQUIRED, "approval required")` at once, without the mutex or an account start.
  4. Otherwise run `controller.connect(uid)` on `Dispatchers.IO` and block on it (`runBlocking(Dispatchers.IO)`). Map the result:
     - `Ready` returns `AgentEndpoint(baseUrl, apiKey)`;
     - `ApprovalRequired` throws code 5;
     - `Error` throws its code with the existing generic message.
- **Kept:** the binding count (`onBind`/`onRebind`/`onUnbind` returning true), `closeIfIdle`, `onDestroy`, and the generic messages.

- [ ] **Step 1: Update `AgentBridgeServiceTest`.**
  - Replace the callback assertions with return/throw assertions through the bound stub. Call `IAgentBridge.Stub.asInterface(binder).connect()` from a background thread where Robolectric needs it.
  - Keep all binding-count, rebind, close and generation tests.
  - Add `unapprovedCallerReturnsImmediatelyWithoutStarting`: no approvals, a launcher whose `ensureStarted` would block on a latch. `connect()` throws code 5 within 1 s, and `ensureStarted` was never called.
  - Add `errorMessagesStayGeneric`: a gateway throws "secret detail", and the `ServiceSpecificException.message` does not contain it.
- [ ] **Step 2: Run the tests.** Run: `./gradlew --no-configuration-cache :agent-bridge:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement the service changes.** Remove the `deliver`/`call` callback plumbing.
- [ ] **Step 4: Run the tests again, plus lint.** Run: `./gradlew --no-configuration-cache :agent-bridge:testDebugUnitTest :agent-bridge:lintDebug :app:testDebugUnitTest --tests '*AgentBridgeInstallerTest*'`. Expected: PASS, and lint is clean.
- [ ] **Step 5: Commit.** Message: `Agent bridge: service answers connect synchronously`.

### Task 3: Delete the wrappers; the test app uses OkHttp and the MCP SDK

**Files:**
- Delete: `agent-bridge-client/src/main/java/io/anytype/agentbridge/client/{HttpTransport,RestClient,McpClient}.kt`.
- Delete: `agent-bridge-client/src/test/java/io/anytype/agentbridge/client/{HttpTransportTest,RestClientTest,McpClientTest,TestServer}.kt` and the whole `realheart/` directory.
- Modify: `agent-bridge-client/build.gradle`, removing anything only the wrappers needed.
- Modify: `gradle/libs.versions.toml`, adding `mcpKotlinClient = "0.16.0"` with alias `mcpKotlinClient` → `io.modelcontextprotocol:kotlin-sdk-client`. Also add a Ktor client engine for Android if the SDK needs one (`ktor-client-okhttp` at the version the SDK depends on; check its POM), and OkHttp if it isn't already in the catalog.
- Modify: `agent-bridge-testapp/build.gradle`, `FlowRunner.kt`, `FlowRunnerTest.kt`, `FlowRunnerRealHeartTest.kt` and `TestServer.kt`.
- Modify: `scripts/agent-bridge/run-contract-tests.sh` (temporarily: drop the `:agent-bridge-client` real-heart run; Task 5 rewrites the rest).

**Interfaces:**
- **`FlowRunner(endpoints: EndpointSource, log: (String) -> Unit)`** keeps the same steps and log lines: `OK <step>` / `FAIL <step>: <msg>` / `ALL OK`.
  - It uses OkHttp for `GET /v2/spaces` with `Authorization: Bearer`.
  - It uses the MCP SDK `Client` over `StreamableHttpClientTransport` at `<baseUrl>/mcp/small`, with the bearer header set on the transport's HTTP client, for `listTools`, `callTool("create"| "find"| "read")`.
- **Finding to record:** if the SDK needs anything heart-specific (for example disabling the GET SSE stream, or a protocol version), set it in `FlowRunner` with a comment, and write it down for the integration guide in the task report.

- [ ] **Step 1: Update `FlowRunnerTest`** (the fake server must speak enough Streamable HTTP for the SDK: an `initialize` response with a session header, 202 for notifications, JSON responses for `tools/list` and `tools/call`, and 405 for GET). Keep `allStepsLogOk` and `failureStopsAndLogsStep`.
- [ ] **Step 2: Run it.** Run: `./gradlew --no-configuration-cache :agent-bridge-testapp:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement the `FlowRunner` change, the dependency changes and the deletions.**
- [ ] **Step 4: Run the tests, the builds, lint and the real-heart flow.**
  - Run: `./gradlew --no-configuration-cache :agent-bridge-client:testDebugUnitTest :agent-bridge-testapp:testDebugUnitTest :agent-bridge-testapp:assembleDebug :agent-bridge-testapp:assembleDebugAndroidTest :agent-bridge-client:lintDebug :agent-bridge-testapp:lintDebug`. Expected: PASS, and lint is clean.
  - Run: `scripts/agent-bridge/run-contract-tests.sh`. The harness is still in this repo at this point. Expected: `FlowRunnerRealHeartTest` passes against real heart, with `ALL OK` and skipped=0.
- [ ] **Step 5: Commit.** Message: `Agent bridge: drop REST/MCP wrappers; test app uses OkHttp + MCP SDK`.

### Task 4: The heart fork branch

**Files (in `/home/user/anytype-heart`):**
- Branch `agentbridge` from tag `v0.51.6`. Fetch it with `git -C /home/user/anytype-heart fetch --depth=1 origin tag v0.51.6`, using upstream as a remote if the fork lacks the tag (`https://github.com/anyproto/anytype-heart` is reachable through the git proxy only if attached; if not, use the fork's copy of the tag, since forks carry tags).
- Modify: `clientlibrary/service/lib.go`. Apply the patch from `/home/user/anytype-kotlin/scripts/agent-bridge/heart-mobile-api.patch`.
- Modify: `clientlibrary/service/lib_test.go`, adding a regression test.
- Modify: `build.gradle`, so the publish URL is `"https://maven.pkg.github.com/" + (System.getenv("GITHUB_REPOSITORY") ?: "anyproto/anytype-heart")`.
- Modify: `.github/workflows/build.yml`, adding `if: github.event_name == 'push' && github.repository_owner == 'anyproto'` to the "Trigger integration tests in anytype-suite" and "Trigger release in anytype-cli" steps.
- Create: `cmd/agentbridge-harness/main.go`, moved from `/home/user/anytype-kotlin/scripts/agent-bridge/heart-harness/main.go` with its import paths adapted to in-module use. Its stdout contract and control endpoints are unchanged.

**Interfaces:**
- Produces: on branch `agentbridge`, three commits and the annotated tag `v0.51.6-agentbridge.1`.
- Running `go build -tags "nosigar nowatchdog" ./cmd/agentbridge-harness` (with the tantivy `CGO_LDFLAGS` as in the old `build.sh`) yields a harness binary.

- [ ] **Step 1: Write the regression test `TestMobileLibrarySetsApiMiddlewareParams`** in `clientlibrary/service/lib_test.go`. After package init, `api`'s middleware params are non-nil. Use an exported accessor if one exists. If none does, add the smallest test-only accessor in `core/api` (for example `func MiddlewareParamsSet() bool`) in the same commit. Prefer, if practical, an end-to-end check: start the JSON API via `AccountChangeJsonApiAddr` on a test account and assert that an authenticated request is not 500. Fall back to the wiring assertion if account setup is too heavy for a unit test, and say so in the report.
- [ ] **Step 2: Run it on the unpatched source.** Run: `cd /home/user/anytype-heart && GOTOOLCHAIN=auto CGO_ENABLED=1 CGO_LDFLAGS=-L<tantivy libs dir> go test -tags "nosigar nowatchdog" ./clientlibrary/service -run TestMobileLibrarySetsApiMiddlewareParams`. Expected: FAIL. The tantivy lib from `/home/user/anytype-kotlin/build/agent-bridge/src/tantivy-go/libs/linux-amd64-musl` can be reused.
- [ ] **Step 3: Apply the one-line fix.** Run the test again. Expected: PASS. Commit: `clientlibrary: set JSON API middleware params in the mobile library` (body: the symptom and the root cause, from `docs/agent-bridge/heart-issue-draft.md`).
- [ ] **Step 4: Make the workflow edits.** Check the YAML with `python3 -c 'import yaml,sys;yaml.safe_load(open(".github/workflows/build.yml"))'`. Commit: `ci: publish to the fork's own package registry; skip anyproto-only triggers in forks`.
- [ ] **Step 5: Move the harness to `cmd/agentbridge-harness`** and build it. Expected: the binary builds. Run it and confirm `READY`, then authenticated `GET /v2/spaces` → 200. Commit: `cmd/agentbridge-harness: native heart harness for agent-bridge contract tests`.
- [ ] **Step 6: Tag** `git tag -a v0.51.6-agentbridge.1 -m "v0.51.6 + agent bridge fix"`. Do not push. The controller pushes the branch and tag when access exists.

### Task 5: anytype-kotlin uses the fork; delete the in-repo pipeline

**Files (in `/home/user/anytype-kotlin`):**
- Modify: `settings.gradle`, adding a `maven { name = "HeartFork"; url = uri("https://maven.pkg.github.com/garyp/anytype-heart"); credentials { … same github.properties … }; content { includeModule("io.anyproto", "anytype-heart-android") } }` before the anyproto repository. Keep `mavenLocal()` first.
- Delete: `scripts/agent-bridge/{build-heart-android.sh,fetch-heart-src.sh,heart-mobile-api.patch}` and `scripts/agent-bridge/heart-harness/`.
- Modify: `scripts/agent-bridge/run-contract-tests.sh`.
  - `HEART_DIR` defaults to `../anytype-heart` and must be at tag `v0.51.6-agentbridge.1` or branch `agentbridge`.
  - It builds tantivy if missing (the cargo recipe from the old `build.sh`, run inside `$HEART_DIR`'s module cache copy of tantivy-go, or reusing `build/agent-bridge/src/tantivy-go` if present).
  - It builds `cmd/agentbridge-harness`, runs it from a temp dir, and runs `:agent-bridge-testapp:testDebugUnitTest --tests '*RealHeart*' --rerun`.
- Modify: `scripts/agent-bridge/README.md` (fork-based workflow, required repository secrets `UNSPLASH_KEY`/`INHOUSE_KEY`, how to bump the heart version: rebase the fork branch on a new upstream tag, then tag `<ver>-agentbridge.N`, push, and update `middlewareVersion`).
- Modify: `docs/agent-bridge/agent-integration.md` (§ using standard libraries: OkHttp/Retrofit and the MCP SDK with `StreamableHttpClientTransport`, with Task 3's findings; the synchronous `connect()` and error codes; delete the wrapper references).
- Modify: `docs/agent-bridge/heart-issue-draft.md` (points at the fork's commit and test).

**Interfaces:**
- Consumes: Task 4's fork branch at `/home/user/anytype-heart`, and Task 3's `FlowRunnerRealHeartTest`.

- [ ] **Step 1: Make the edits and deletions above.**
- [ ] **Step 2: Run the contract script against the fork.** Run: `HEART_DIR=/home/user/anytype-heart scripts/agent-bridge/run-contract-tests.sh`. Expected: the harness builds from the fork, and `FlowRunnerRealHeartTest` gives `ALL OK`.
- [ ] **Step 3: Run the full verification.** Run: `./gradlew --no-configuration-cache :agent-bridge-client:testDebugUnitTest :agent-bridge:testDebugUnitTest :agent-bridge-testapp:testDebugUnitTest :app:testDebugUnitTest --tests '*AgentBridgeInstallerTest*' :agent-bridge-client:lintDebug :agent-bridge:lintDebug :agent-bridge-testapp:lintDebug :app:assembleDebug :agent-bridge-testapp:assembleDebugAndroidTest`. Expected: PASS, and all three lint reports are clean. `:app` still resolves the artifact from Maven Local until the fork publishes; record this.
- [ ] **Step 4: Check that no stale references remain.** Run: `git grep -n "heart-harness\|build-heart-android\|fetch-heart-src\|McpClient\|RestClient\|IAgentBridgeCallback" -- ':!docs/superpowers'`. Expected: no matches.
- [ ] **Step 5: Commit.** Message: `Agent bridge: build heart from the garyp/anytype-heart fork; drop in-repo pipeline`.
