# Agent bridge: on-device agent access to Anytype data

Status: draft for approval · 2026-11-21 (rev 2: heart patch, per-account approvals, debug target)

## Intent

The user is building an AI agent app for their own Android phone, elsewhere. It runs a weak
on-device model with limited context, and it must read and write the user's Anytype data. This
work gives the agent that access. Building the agent itself is out of scope.

Heart, the Go middleware inside the Anytype app, already contains a full local HTTP API: REST
`/v1` and `/v2`, plus MCP at `/mcp/{small,large,full}`. The Android app never starts it. The
bridge starts the API on loopback, and hands an approved agent app the address and a scoped
bearer key over Binder.

**Heart blocker (found while validating, rev 2).** In heart's mobile library
(`clientlibrary/service`) nothing calls `api.SetMiddlewareParams(mw)`; only
`cmd/grpcserver` does. On Android the JSON API therefore binds and reports success, but every
authenticated request nil-derefs `mw` in `core/api/server/middleware.go` and gin answers an
empty HTTP 500. Verified in source for v0.51.6, v0.51.11 (latest release) and the
2026-11-21 nightly, and reproduced with a natively built heart. A one-line fix in
`clientlibrary/service/lib.go` `init()` (call `api.SetMiddlewareParams(mw)`) makes REST, MCP and
key management work. No Kotlin-side workaround exists, so the fork ships a patched heart
(see "Patched heart library").

Starting point: the research report and sample patch the user supplied
(`anytype-agent-bridge-report.md` and `anytype-kotlin-agent-bridge.patch`, written against
`24d9344`). Both are uncompiled. This design keeps their architecture and fixes what newer
Android APIs and review turned up.

## Goals

- **G1. Bind and connect.** An approved agent app binds to an exported service in the (forked)
  Anytype app and calls `connect`. It gets back `http://127.0.0.1:<port>` and a bearer key. It
  then uses `/mcp/small` (the tool tier heart designed for ~8B models) or `/v2`.
- **G2. Cold start.** It works from a cold start. Binding starts the Anytype process, and the
  account comes up headlessly if the user is logged in.
- **G3. User consent.** Access needs explicit consent, pinned to the agent's package name and its
  signing certificate. The two apps share no signing key.
- **G4. Revocation.** The user can see approved agents and revoke them. Revoking an agent also
  revokes its heart key.
- **G5. Client library.** A small client library, ready to drop into the user's agent: the AIDL
  files, a `bindService`/`connect` client, and minimal REST and MCP helpers.
- **G6. Test app.** A test app plays the agent role end to end.
- **G7. Tests run here.** Automated tests run in this container, against real heart wherever that
  is technically possible.

## Non-goals

- **The agent:** the model, prompting, or tool selection.
- **Old Android versions:** anything below Android 15 (API 35). The bridge stays off there.
- **Choosing spaces at consent:** a grant covers every space. Access level is the only choice
  (see below).
- **Upstreaming:** possible later. The module boundary keeps that door open, but no upstream PR
  is part of this work.
- **AppFunctions:** calling another app's AppFunctions still needs allowlisting, so a third-party
  agent can't use them yet.

## Anti-goals

These would make the work a failure even if it technically works.

- **Exposure:** any app other than one the user approved gets a usable key or learns anything
  beyond "approval required".
- **Listening on a non-loopback interface,** or leaving the listener open while no agent is
  bound.
- **Changing the normal Anytype app's behaviour** for users who never use an agent: no new
  permissions prompts, no startup cost, no UI changes on the main screens.
- **Upstream conflicts:** a fork that conflicts with upstream on every middleware update. The
  edits to existing files stay a handful of lines.

## Constraints

- **Toolchain:** heart is pinned at v0.51.6 and comes as the prebuilt
  `io.anyproto:anytype-heart-android` library. The project needs JDK 17. compileSdk and
  targetSdk are 36 for the existing modules, and minSdk is 26.
- **No emulator here:** this container has no KVM, so it can't run an Android emulator. Tests
  that need a device are written and compiled here, but run on the user's machine.
- **Network:** this container reaches GitHub Packages (for heart), Google Maven, jitpack, Maven
  Central and the Go and Rust registries. It does not reach github.com.

## Platform facts this design relies on

Checked against developer.android.com on 2026-11-21.

- **Caller identity for activities:**
  - `Activity.getInitialCaller()` (API 35) returns a `ComponentCaller` with the launcher's UID
    and package.
  - It does so only when the caller launched with
    `ActivityOptions.makeBasic().setShareIdentityEnabled(true)`.
  - `getCallingPackage()` works only for `startActivityForResult`.
- **Signing check:** `PackageManager.hasSigningCertificate(uid, sha256, CERT_INPUT_SHA256)`
  is current and follows key-rotation lineage.
- **Freezer:**
  - A bound service can't be cached, and so can't be frozen, before its client is.
  - `BIND_INCLUDE_CAPABILITIES` passes the client's network capability along.
  - Long `BindServiceFlags` (API 34) are available.
- **Loopback:** Android 17's local-network permission doesn't cover loopback. Loopback between
  profiles is blocked, so the agent and Anytype must be in the same profile.
- **Tapjacking:** `setHideOverlayWindows(true)` (needs `HIDE_OVERLAY_WINDOWS`) is still
  current, as are `filterTouchesWhenObscured` and `accessibilityDataSensitive`. Android 17
  recommends `FLAG_SECURE` over content-capture opt-outs.
- **Cleartext:** Android 17 is moving away from `usesCleartextTraffic`. The client allows
  cleartext only to `127.0.0.1`, through a network security config.

## Approaches considered

1. **Self-contained `agent-bridge` module (chosen by the user).**
   - **How:** a new library module holds the service, the consent screen, the approvals store
     and the settings screen. Heart access goes through a narrow interface implemented in
     `:middleware`.
   - **Footprint:** a few lines of wiring in existing files.
   - **Trade-off:** it bypasses the app's use-case and repository layers for six RPCs that
     nothing else in the app uses.
2. **Sample patch as-is, polished:** the code lives in `:app` and reuses the OS-widget DI
   component. It is smallest, but harder to test and couples the bridge to widget DI.
3. **Full Clean-Architecture integration:** use cases, repository methods and the large
   `MiddlewareService`. It is the most idiomatic, but touches heavily-edited files, so every
   upstream rebase risks conflicts.

The user picked option 1.

## Design

### Patched heart library

- **Script:** `scripts/agent-bridge/build-heart-android.sh`, committed. It:
  - fetches heart `v0.51.6` source from the Go module proxy;
  - applies `scripts/agent-bridge/heart-mobile-api.patch`, the one-line fix;
  - builds tantivy's Android static libraries from the Rust source of tantivy-go v1.0.6;
  - runs `gomobile bind` with the same tags as heart's `makefiles/android.mk`
    (`nogrpcserver gomobile nowatchdog nosigar timetzdata rasterizesvg`, `-androidapi 26`);
  - publishes the AAR to Maven Local as
    `io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1`.
- **ABIs:** by default it builds every ABI heart ships, so the app's ABI splits stay intact.
  `--abis` narrows the list for a quicker local build.
- **Version pin:** `gradle/libs.versions.toml` points `middlewareVersion` at that version.
  `mavenLocal()` is already the first repository, so nothing else changes. The repo's existing
  `libs/` module isn't used, because its version is hard-coded to `0.99`.
- **Protos:** the `.proto` files are synced from the same heart source by the same script, and
  match what the patch already did.
- **README:** `scripts/agent-bridge/README.md` documents the rebuild for a heart upgrade, and
  how to drop the patch once upstream fixes it.
- **Upstream:** the builder also writes an upstream issue draft to
  `docs/agent-bridge/heart-issue-draft.md`.

### Modules

| Module | Kind | Contents |
|---|---|---|
| `agent-bridge-client` | Android library | `IAgentBridge.aidl` and `IAgentBridgeCallback.aidl`; `AnytypeBridge` (bind, connect, approval launch); `McpClient` and `RestClient`, small OkHttp-free helpers on `HttpURLConnection`. No dependency on any Anytype module, so the user's agent can depend on it or copy it. |
| `agent-bridge` | Android library | `AgentBridgeService`, `AgentApprovalActivity`, `AgentAccessActivity` (manage approvals), `AgentApprovals` (store), `CallerVerifier`, `BridgeController` (connect logic), and the `LocalApiGateway` and `AccountLauncher` interfaces. Depends on `agent-bridge-client` for the AIDL only. |
| `middleware` (existing) | | Adds `LocalApiControl`, which implements `LocalApiGateway` with direct gomobile `Service.*` calls, as in the patch. |
| `app` (existing) | | Depends on `:agent-bridge`. Supplies the gateway and the account launcher to the module (see Wiring). |
| `protocol` (existing) | | `.proto` files synced from heart v0.51.6, as in the patch, so that `ChangeJsonApiAddr.Response.status` decodes. |
| `agent-bridge-testapp` | Android application | The test agent. Package `io.anytype.agentbridge.testapp`, a different signing key from Anytype's debug key. Its screen has buttons: Bind, Connect, Approve, List spaces (`/v2`), MCP initialize → `tools/list` → create a test page → find it → read it back, Unbind. It has a log view, and an instrumented test that drives the same flow. |

**Wiring.** The module can't see `:app`'s Dagger graph, so the app installs two small objects
into the module at `Application.onCreate`:
- a `LocalApiGateway`, which is `LocalApiControl`;
- an `AccountLauncher`, which wraps `AwaitAccountStartManager` and `launchMiddlewareForConfig`.

They go through an `AgentBridge.install(gateway, launcher)` call. A dedicated tiny Dagger
component in `:app` supplies `LaunchWallet`, `LaunchAccount` and `AwaitAccountStartManager`
through `MainComponent` dependencies; the widget component is not reused. Edits to existing
files: `settings.gradle` (3 includes), `app/build.gradle` (1 dependency), `AndroidApplication`
(1 install call), `MainComponent` and `ComponentManager` (component registration), plus the
proto sync.

### Android version gating

- The module keeps minSdk 26, matching the app, so the build isn't affected.
- Every bridge component is declared `android:enabled="@bool/agent_bridge_enabled"`. The value
  is `false` by default and `true` in `values-v35`, so the bridge doesn't exist on older
  devices.
- The code is annotated `@RequiresApi(35)`.
- The new modules compile against SDK 37 if AGP supports it. Otherwise they use 36, and the
  builder notes this.
- The test app uses minSdk 35 and targets the latest SDK the build supports.

### Package names

- **Release:** the agent addresses Anytype as `io.anytype.app`.
- **Debug:** the debug build's application ID is `io.anytype.app.dev`.
- **Client:** `AnytypeBridge` takes the target package as a constructor parameter, defaulting
  to `io.anytype.app`. It also offers `AnytypeBridge.findInstalled(context)`, which resolves the
  service through `PackageManager.queryIntentServices` on the bind action. The agent then
  works with either build.
- **Manifest:** the client library's manifest declares `<queries>` for the bind action, so any
  package exposing it is visible.

### Connect flow

1. The agent binds to the explicit component it resolved. The intent carries action
   `io.anytype.agentbridge.BIND`, and the flags are
   `BindServiceFlags.of(BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES)`.
2. The agent calls `connect(callback)`. This is a `oneway` call.
3. The service reads `Binder.getCallingUid()` on the binder thread. `CallerVerifier` maps the UID
   to a package with a stored approval whose pinned SHA-256 passes `hasSigningCertificate(uid, …)`.
   - **No match:** `onApprovalRequired()`.
4. With a match, a single coroutine mutex serializes the rest:
   1. **Start the account.** If the account isn't running, the service calls
      `AccountLauncher.ensureStarted()`. A failure maps to an error code: `NOT_LOGGED_IN` when
      there is no stored mnemonic, otherwise `START_FAILED`.
   2. **Get a listener.** The service reuses the current listen address if a short TCP probe
      succeeds. Otherwise it calls `ChangeJsonApiAddr("127.0.0.1:0")` and takes
      `status.listenAddr`. A missing or failed status gives `API_FAILED`.
   3. **Get the key.** The service looks it up in heart, without keeping a copy in Anytype
      storage. It calls `ListApps` and filters by `appName == "agent:<package>:<cert8>"`, where
      `<cert8>` is the first 8 hex digits of the pinned cert digest.
      - **Several entries:** keep the newest and revoke the rest.
      - **No entry:** call `CreateApp` with scope `JsonAPI`, grant all spaces, and the access
        level stored with the approval.
      - **Re-approval under a new cert:** the name changes, so the old key's entries match only
        by package prefix and are revoked.
   4. **Answer.** `onReady(baseUrl, apiKey)`.
5. **Retry:** the agent calls `connect()` again after any connection failure. Heart may have
   restarted the account and changed the port.
6. **Unbind:** `onUnbind` with no clients left calls `ChangeJsonApiAddr("")` under the mutex.
   `onRebind` is not needed: the service returns `false` from `onUnbind`, so the next bind goes
   through `onBind`.

### AIDL

- **`IAgentBridge`** (`oneway`):
  - `connect(IAgentBridgeCallback cb)`.
- **`IAgentBridgeCallback`** (`oneway`):
  - `onReady(String baseUrl, String apiKey)`;
  - `onApprovalRequired()`;
  - `onError(int code, String message)`, with codes `NOT_LOGGED_IN = 1`, `START_FAILED = 2`,
    `API_FAILED = 3` and `INTERNAL = 4`;
  - `const int VERSION = 1`. A later version adds methods only at the end.

### Consent screen (`AgentApprovalActivity`)

- **Launch:**
  - The agent launches action `io.anytype.agentbridge.APPROVE` on Anytype's package, with share
    identity enabled. The client library's `approvalIntent()` returns the intent and
    `approvalOptions()` returns the bundle.
  - It uses `startActivityForResult` / `ActivityResultLauncher` (a launcher overload takes
    `ActivityOptionsCompat`).
- **Identity:**
  - It comes from `getInitialCaller()`. The UID must be valid and the package must belong to
    that UID.
  - Otherwise the activity finishes with `RESULT_CANCELED` and an explanatory toast.
  - `getCallingPackage()`, when present, must agree with it.
- **The screen:** a Compose dialog in Anytype's theme. It shows:
  - the app label and icon, the package, and a short certificate fingerprint;
  - the warning "can read [and change] all objects in all your spaces while connected";
  - an access-level choice: Read & write (default) or Read only;
  - Allow and Don't allow buttons.
- **Protection:**
  - The window uses `setHideOverlayWindows(true)` and `FLAG_SECURE`.
  - The Allow button uses `filterTouchesWhenObscured` and `accessibilityDataSensitive`.
- **Denial:** a cooldown is stored for that package. A re-launch within 30 s finishes
  immediately with `RESULT_CANCELED`, which stops prompt spam.
- **Re-approval:** an existing approval for the same package under a different cert is
  replaced. The old key is revoked on the next connect, through the name scheme above.

### Managing approvals (`AgentAccessActivity`)

- **What it shows:** a simple Compose list of approved agents, each with its label, package,
  fingerprint, access level and approval date, and a Revoke button.
- **Revoke:**
  - deletes the approval;
  - revokes the agent's heart keys (all `agent:<package>:*`) when the account is running;
  - otherwise marks them for revocation on the next account start, and sweeps them on the next
    connect.
- **How users reach it:**
  - an `ACTION_APPLICATION_PREFERENCES` intent filter, which shows as the gear on Anytype's
    system App info page, so no edit to Anytype's own settings UI is needed;
  - from the consent screen's footer link.

### Approvals store

- **Format:** a single JSON blob in a module-private `SharedPreferences` file. Each entry is
  `{accountId, package, certSha256, access, approvedAt}`, plus a `deniedUntil` map and a
  `pendingRevoke` set of `{accountId, package}`.
- **Per account:** an approval covers the Anytype account that was active when the user
  approved. After logging in to another account, the agent gets `onApprovalRequired`.
  - The consent screen names the account.
  - `AccountLauncher` exposes the running account's ID.
  - Pending revocations are swept only while their own account is running.
- **Why no encryption:**
  - The store holds no secrets: keys live only in heart, which is already the source of truth
    for them.
  - The pins aren't sensitive.
  - Tampering needs root.
- **Backup:** the file is excluded from backup and device transfer, so approvals never leave the
  device.

### Release-readiness (cheap only)

- `consumer-rules.pro` in `agent-bridge` and `agent-bridge-client` keeps the AIDL stubs.
- `middleware` keeps the classes `LocalApiControl` calls. They are already covered by the
  existing Wire/gomobile rules; the builder checks this.
- No `assembleRelease` run is required.

### Errors and edge cases

- **Logged out:** `NOT_LOGGED_IN`.
  - In the main app, logout stops the account and, with it, the listener.
  - The next `connect` relaunches only if a mnemonic is stored.
- **Account restart** (for example a network-mode change): heart drops the listener. The next
  `connect` sees the probe fail and rebinds.
- **Agent process death:** the binding drops. With no clients left, `onUnbind` closes the
  listener.
- **Anytype process death:** the agent gets `onServiceDisconnected`. Android rebinds, and the
  client marks its endpoint stale.
- **Several agents bound:** they share one listener. Each has its own key.

## Testing (in this container)

The tests run in four layers.

1. **JVM unit tests** (`agent-bridge`, Robolectric, a newer Robolectric version for these
   modules if the repo's 4.11.1 can't run SDK 35):
   - `BridgeController` with fake gateway and launcher: approval required, cold start,
     listener reuse and rebind, key lookup, create, dedupe and revoke-on-recert, each error code,
     mutex serialization, unbind closes the listener.
   - `CallerVerifier` and `AgentApprovals`: pin match, cert change, multi-package UID, denial
     cooldown, pending revoke.
   - `AgentApprovalActivity` under Robolectric: no shared identity → cancelled; Allow → stored
     approval and `RESULT_OK`.
2. **Middleware contract tests:** JVM tests for `LocalApiControl`'s request building and response
   parsing, using encoded Wire messages through a seam around `Service`.
3. **Real heart, natively on Linux** (the harness exists as a spike, and gets committed under
   `scripts/agent-bridge/heart-harness/`):
   - Heart v0.51.6 is built from source, with tantivy built from its Rust source, and run with a
     fresh local-only account.
   - A Go harness calls the same service-level RPCs gomobile exposes (`ChangeJsonApiAddr`,
     `CreateApp`, `ListApps`, `RevokeApp`) and serves the JSON API.
   - JVM tests of the client library (`McpClient`, `RestClient`) then run against it:
     initialize, `tools/list`, create a page, find it, read it, a revoked key gives 401, and
     `ChangeJsonApiAddr("")` closes the port.
   - This validates the heart behaviour the bridge depends on, using the same heart version.
   - It is gated: the tests skip unless `ANYTYPE_HEART_BASE_URL` is set, and a script starts
     the harness and runs them.
   - Known heart behaviour to encode in the client and the tests:
     - `Mcp-Session-Id` is a response header from initialize, and is required afterwards;
       without it the server answers 400, and with an unknown id 404 (re-initialize).
     - Protocol versions 2025-06-18 and 2025-11-25 are accepted.
     - Requests must carry no `Origin`, or a loopback one; anything else gives 403.
     - A failed bind leaves nothing listening.
     - `ListApps` returns full keys.
     - A duplicate app name is allowed.
     - Errors have the shape `{object, status, code, message}`.
   - The harness also checks the unpatched build: an authenticated request returns 500, which
     documents why the patch exists.
4. **Device level** (built here, not run here):
   - The test app APK and its instrumented test compile.
   - The UiAutomator test: launch the test app, Bind/Connect, tap Allow in Anytype's dialog,
     run the MCP flow, assert on the log.
   - The user runs it with Anytype (debug) and the test app installed on an emulator or phone.

**Build checks:**
- The patched heart AAR builds here, and `:app:assembleDebug` links against it.
- `:agent-bridge-testapp:assembleDebug` and `assembleDebugAndroidTest` succeed.
- The unit tests of the touched and new modules pass.
- `lintDebug` raises no new errors in the new modules.

## Left to the builder

- Exact Compose layout of the two screens. Use existing `core-ui` theme components where
  `agent-bridge` can depend on `core-ui` cheaply; otherwise use Material3 defaults.
- How the `Service` seam in `LocalApiControl` is shaped for tests.
- Whether the native heart harness uses `cmd/evalheart` or a small custom Go program.
- Where the gated real-heart tests and their run script live: `scripts/agent-bridge/`
  is suggested.

## Decisions made with the user

- Approach 1: the self-contained module.
- Tests run only in what this container can execute: JVM, Robolectric, and real heart natively.
  Device tests are compiled but not run.
- Ship a patched heart AAR built from source.
- Target the debug build first.

## Decisions made without the user (please check)

- Approvals are per Anytype account.
- The open loopback port is accepted and documented, with tests that key-less calls are
  rejected.

- **Old Android:** the bridge is enabled only on Android 15+. Below that, the components are
  disabled.
- **Access:** all spaces, with Read & write or Read only chosen at consent. There is no per-space
  picker.
- **Keys:** stored only in heart and looked up by a name scheme. Approvals live in plain
  SharedPreferences, excluded from backup.
- **Settings entry:** through Android's App info gear and the consent screen. Anytype's own
  settings screens are unchanged.
- **Denial cooldown:** 30 s.
- **Test app:** identifies Anytype through service discovery, so it works with
  `io.anytype.app.dev` debug builds.
