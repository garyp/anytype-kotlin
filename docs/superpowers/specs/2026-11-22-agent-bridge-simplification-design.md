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

## Follow-up (2026-11-23): callback connect

The user approved going back to a oneway connect with a callback, now that the client is small.
This replaces the synchronous connect of §1; the result codes, the parcelable and the security
model stay.

- **Why.** The blocking `connect()` held one of Anytype's binder threads for up to 45 s
  (`runBlocking` with a timeout) while an account started, and a per-uid map was needed so one app
  could not pile up threads behind the controller mutex. The client in turn needed a `Mutex` plus
  a lock, because it called the service off the main thread while the bind callbacks arrived on
  it. With a callback, no binder thread waits, and the client can live on the main thread alone,
  with no locks.
- **AIDL (VERSION 3).** `oneway void connect(IConnectCallback callback)`, where
  `oneway interface IConnectCallback { void onResult(in ApiEndpoint endpoint); }`. `version()`
  keeps its transaction code. No compatibility shim: no client of version 2 was released.
- **Service.** Reads the calling uid on the binder thread and returns. A caller approved under no
  account is answered `APPROVAL_REQUIRED` at once, as before. Every other connect runs in
  `ApiBridge.backgroundScope`, and a second call from the same uid joins the connect in flight
  (the existing map of `Deferred`, one completion handler per caller). The answer goes to
  `onResult`; a `RemoteException` (the caller has gone) is swallowed. The server-side timeout,
  `runBlocking` and `ApiBridge.connectTimeoutMs` are gone.
- **Client.** The deadline moves to the client: 50 s (the old 45 s plus a margin), then
  `Failed(INTERNAL)` as the old server timeout gave. All state is confined to the main thread
  through a handler dispatcher on the main looper (not `Dispatchers.Main`, which an app's tests
  may replace), so `connect()` and `close()` still work from any thread. Concurrent connects
  share one binding; a lost binding fails every waiting answer at once. There is no separate
  version check: no bridge with the blocking connect was released, and an incompatible bridge
  would never answer, so it shows up as the timeout, whose message says so. `version()` stays
  for a later protocol change.
- **Size.** Trimmed to the old size on the service side (77 lines of code against 76); the
  client is about 20 lines larger for the callback and the lost-binding handling, and has no
  locks.

## Follow-up (2026-11-23): the bridge follows the account lifecycle like the app

The cached listen address used to be checked against a counter added to
`AwaitAccountStartManager`. The app's other account-scoped components (chat previews, space
views, participants) instead collect `AwaitAccountStartManager.state()` and react to `Stopped`.
The bridge now does the same: `AccountLauncher.accountStops` emits on `Stopped`,
`ApiBridge.install` collects it, and `BridgeController.onAccountStopped()` forgets the address.
The counter is gone, so the PR no longer changes `:domain`.

A `StateFlow` conflates, so this relies on two facts, both checked: the only caller of heart's
account stop is `Logout`, which reports `Stopped`; and heart's `AccountSelect` for the account
that is already running returns early without closing the listener, so a repeated `Started`
needs no handling. A logout followed by a login can't outrun the collector: the login goes
through the UI.

## Follow-up (2026-11-23): approvals in a DataStore

The user asked for Jetpack DataStore with a JSON schema instead of raw `SharedPreferences`
access for the approvals store. Nothing of the bridge was released, so there is no migration
from the old prefs file and no compatibility code.

- **Schema.** A typed `DataStore<ApprovalsState>` (datastore core, not Preferences).
  `ApprovalsState` is `@Serializable`: a `version` field (1), `approvals`, `pending` and
  `deniedUntil`. The domain types `Approval`, `Grant`, `Access` and `PendingRevoke` are
  `@Serializable` themselves; deserialization runs `Grant`'s `init`, so a stored grant that
  breaks its rule is rejected.
- **Tolerance.** `ApprovalsSerializer` parses the root as a `JsonObject` and decodes each
  approval, pending revoke and cooldown on its own, skipping a bad entry and never the rest
  (losing a pending revoke would leave a revoked app's key valid). A file that is not a JSON
  object goes to a `ReplaceFileCorruptionHandler`, which starts from an empty state: the pending
  revokes in it are lost, but every connect and screen keeps working, so the user can still
  revoke.
- **One instance.** DataStore allows one instance per file in a process. `ApiBridge.approvals()`
  creates it once, on `files/datastore/api_bridge_approvals.json`, in its own IO scope, and the
  controller, the caller check and both screens share it. Tests replace it through
  `ApiBridge.approvalsOverride`, which `resetForTest` clears; `resetForTest` also closes the
  default store and waits until DataStore releases the file.
- **Suspend.** `AppApprovals` and `CallerApprovals.verifiedApprovals` suspend; `updateData`
  serializes read-modify-write, so the process-wide lock is gone. The service's check for a
  caller approved under no account moves from the binder thread into the coroutine it launches:
  the binder call still returns at once, and such a caller is still answered before it can wait
  on the controller mutex or start the account. The locking of the controller is unchanged.
- **Backup.** The data extraction rules exclude `datastore/api_bridge_approvals.json` from
  cloud backup and device transfer, in place of the old prefs file.

## Follow-up (2026-11-23): heart calls go through MiddlewareService

`HeartCalls` and `LocalApiControl` rebuilt, for four RPCs, the layer every other heart call already
goes through. `ChangeJsonApiAddr` and `LocalLink.ListApps/CreateApp/RevokeApp` are now in
`MiddlewareService` and `MiddlewareServiceImplementation` like the rest, with `Middleware`
methods on top (`LocalApiApp` in core-models), and the bridge's gateway adapter calls
`Middleware`. A revoke of an unknown key still counts as done: the service implementation accepts
`NOT_FOUND`.
