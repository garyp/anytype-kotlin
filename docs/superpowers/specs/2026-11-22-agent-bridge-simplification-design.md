# Agent bridge simplification

Status: approved by the user, 2026-11-22. This builds on
`2026-11-21-agent-bridge-design.md`, and that spec still holds except where this document
replaces it.

## Why

The user reviewed the finished bridge and asked three questions. Each one found a part that
was more complicated than it needed to be, or in the wrong place:

1. **Wrappers nobody needs.** Agents will use standard libraries: Retrofit/OkHttp, and the
   official Kotlin MCP SDK. Heart's API needs no special client handling, so our REST and
   MCP wrappers are dead weight in a library meant for other apps.
2. **An over-complex client.** `AnytypeBridge` is complex because `connect()` is a one-way
   call with a callback. A plain AIDL call that returns its result is much simpler.
3. **Heart built in the wrong repo.** Rebuilding heart's Android pipeline inside
   anytype-kotlin duplicates upstream tooling, and it has already drifted: it is missing
   the `nomutexdeadlockdetector` tag. A heart fork that uses upstream's own workflow is
   simpler to maintain and to rebase.

## Approaches (decided by the user)

- **Connect call:** a synchronous AIDL `connect()` (chosen) instead of the oneway call
  with a callback.
- **Client libraries:** the official MCP SDK plus OkHttp (chosen) instead of our own
  wrappers.
- **Where heart is built:** the fork `garyp/anytype-heart` using upstream's `build.yml`
  with minimal edits (chosen). The alternatives were an in-repo build pipeline, or an
  Android-only workflow in the fork.

## Design

### 1. AIDL v2: a synchronous connect (`agent-bridge-client`, `agent-bridge`)

- **The interface:**
  - `interface IAgentBridge { const int VERSION = 2; AgentEndpoint connect(); }`. It is
    not `oneway`.
  - `AgentEndpoint` is a parcelable defined in AIDL, carrying `code: Int`, `baseUrl: String?`
    and `apiKey: String?`. `code == 0` means OK and carries `baseUrl` and `apiKey`.
  - `IAgentBridgeCallback` is deleted.
- **Errors.** They travel in the result as `AgentEndpoint.code`, with these codes: `NOT_LOGGED_IN = 1`, `START_FAILED = 2`, `API_FAILED = 3`, `INTERNAL = 4`,
  `APPROVAL_REQUIRED = 5`, as constants on `IAgentBridge`. (Ruling made during execution:
  `android.os.ServiceSpecificException` is hidden from the public SDK, so errors cannot be
  thrown that way.)
- **The service.**
  - It reads `Binder.getCallingUid()`, then runs the controller's connect and blocks the
    binder thread until it finishes, for example with `runBlocking(Dispatchers.IO)`.
  - Callers with no approval under any account must get `APPROVAL_REQUIRED` before
    anything that waits. That keeps other apps from tying up Anytype's binder threads.
    The fast `approvedAnywhere` check runs synchronously first.
- **The client (`AnytypeBridge`).**
  - `connect()` binds if needed, suspending until `onServiceConnected`, and keeps the
    binding. Then, on `Dispatchers.IO`, it makes one call to `service.connect()`, which
    blocks.
  - It maps a non-zero `code` to `BridgeException.Failed(code)`, or to `ApprovalRequired`
    for code 5. `DeadObjectException` and `RemoteException` mean the bridge is
    gone: the client clears the binding and fails.
  - Kept as before: optional cert pinning (`expectedCertSha256`), `findInstalled`,
    `approvalIntent()`, `approvalOptions()`, `close()`, and the bind flags
    (`BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES`).
  - The client has a single owner and is not thread-safe; this is documented. A `Mutex`
    around connect is acceptable if it stays simple.
  - The target is roughly a third of today's size.
- **Unchanged:** the controller, approvals, binding-count, and listener logic.

### 2. Delete the REST and MCP wrappers

- Delete `HttpTransport`, `RestClient`, `McpClient` and their unit tests from
  `agent-bridge-client`.
- The test app's `FlowRunner` uses OkHttp for `/v2/spaces` and the official Kotlin MCP SDK
  (`io.modelcontextprotocol:kotlin-sdk-client`) with its Streamable HTTP transport.
  - The test app's own `FlowRunnerTest` uses a fake server.
  - The real-heart run is the proof that a standard client works against heart. If the SDK
    needs special handling for heart, that is a finding to document.
- The Kotlin real-heart contract tests that check heart's own behaviour are deleted
  (`RealHeartRestTest`, `RealHeartMcpTest`, `RealHeartGrantTest`). Heart's own Go tests
  cover grants. The patch regression moves into the fork (§3).

### 3. Heart from `garyp/anytype-heart`

- **The fork.**
  - Branch `agentbridge` from upstream tag `v0.51.6`.
  - Commits on that branch:
    1. The one-line fix in `clientlibrary/service/lib.go` (`api.SetMiddlewareParams(mw)`),
       with a Go regression test in `clientlibrary/service`. The test proves that the
       middleware params are set by the mobile library, and that an authenticated
       JSON-API request does not fail with 500. If a full account is impractical in a unit
       test, it asserts the wiring.
    2. Workflow edits:
       - `build.gradle` publishes to
         `https://maven.pkg.github.com/${GITHUB_REPOSITORY}` (fallback: the upstream URL).
       - The `anytype-suite` and `anytype-cli` trigger steps get
         `if: github.repository_owner == 'anyproto'`.
       - Everything else in upstream `build.yml` stays.
    3. The harness, as `cmd/agentbridge-harness`, moved from anytype-kotlin's
       `scripts/agent-bridge/heart-harness/main.go`. It keeps the same output lines and
       control endpoints.
  - The branch is tagged `v0.51.6-agentbridge.1`. Pushing the tag runs `build.yml`, which
    publishes `io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1` to the fork's
    GitHub Packages.
  - Repository secrets the user sets: `UNSPLASH_KEY` and `INHOUSE_KEY`. `ANYTYPE_PAT` is
    probably unnecessary; this is to be confirmed by the first run.
- **anytype-kotlin.**
  - `settings.gradle` uses `https://maven.pkg.github.com/garyp/anytype-heart` for the heart
    artifact. The anyproto repository may stay as a fallback for other artifacts.
  - The version stays `v0.51.6-agentbridge.1`.
  - Delete `scripts/agent-bridge/build-heart-android.sh`, `fetch-heart-src.sh`,
    `heart-harness/*` and `heart-mobile-api.patch`.
  - `run-contract-tests.sh` builds the harness from a checkout of the fork at the tag,
    whose path is configurable (default `../anytype-heart`), with the same cargo/tantivy
    recipe. It then runs only the test app's real-heart flow test.
  - Update the README, the integration guide, and the heart issue draft. The draft points
    at the fork's commit and test.

## Constraints

- **Pushes to the fork are currently refused.** The Claude GitHub App is not installed on
  `garyp/anytype-heart`. The fork branch is prepared locally, and is pushed once access is
  fixed.
- **The build in this container needs the published artifact.** Until the fork's workflow
  has run, the container keeps building against the existing Maven Local copy of the
  artifact, which has the same coordinates. The repository switch is verified after
  publishing.
- **Network.** `maven.pkg.github.com` (the saved credential applies to any owner path) and
  `github-registry-files.githubusercontent.com` are already allowed.

## Non-goals

- Changing the controller, approval, consent or security behaviour.
- Upstreaming. The fork's commits are kept small, so upstreaming stays possible.

## Follow-up (2026-11-23): revoke takes no lock

The user asked to simplify the locking: approvals and revokes come only from the user, so no
app can force a race, and a short window in which a revoke and a connect overlap is acceptable
as long as the result stays coherent and no revoked app keeps a working key for long.

- `revokeApp` no longer takes the controller mutex, so it never waits behind a connect that is
  starting the account. The order of the steps keeps it safe: a revoke removes the approval
  before it lists the keys, and a connect mints the key before it re-checks the approval
  (step 5b). Whichever runs first, one side sees the new key and revokes it. At worst a connect
  hands out a key that the revoke cuts off a moment later.
- The mutex still serializes connects and the unbind close, which keeps the listener and key
  state whole (one bind at a time, no duplicate keys, no close in the middle of a connect), and
  the background revoke retry, where the lock costs nothing and keeps a sweep from revoking a
  key that a connect is about to hand out.
- The approval check at the start of a connect stays: it is what keeps an unapproved caller
  from starting the account, not a guard against revokes.
