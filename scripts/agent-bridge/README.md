# Agent bridge tooling

> **Before the first build:** the app depends on the patched heart artifact
> `io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1`, which exists only in Maven Local. A
> fresh clone cannot build `:app` until `scripts/agent-bridge/build-heart-android.sh` (all ABIs,
> 30-60+ minutes) has published it; see "Building the patched heart for Android" below. Export
> `UNSPLASH_KEY` and `INHOUSE_KEY` for that build, or the Unsplash cover picker will not work.

## Native heart harness

Contract tests for the agent bridge run against the real anytype-heart (Go middleware) built as a
native Linux binary. Heart's mobile library never calls `api.SetMiddlewareParams`, so the JSON API
nil-dereferences on every authenticated request. `heart-mobile-api.patch` adds that call.

- `fetch-heart-src.sh [--unpatched]` downloads heart v0.51.6 and tantivy-go v1.0.6 from the Go
  module proxy into `build/agent-bridge/src/` (idempotent). Patched heart goes to `src/heart`,
  pristine heart (with `--unpatched`) to `src/heart-unpatched`.
- `heart-harness/build.sh [--unpatched]` fetches the sources, builds `libtantivy_go.a` with cargo
  if missing (about 4 minutes), and builds `build/agent-bridge/bin/harness`
  (or `harness-unpatched`). Needs Go (GOTOOLCHAIN=auto fetches the pinned toolchain), a Rust
  toolchain and a C compiler.
- `heart-harness/run.sh [--unpatched] [-root DIR]` boots heart with a fresh local-only account and
  prints `BASE_URL=`, `API_KEY=`, `APP_HASH=`, `CONTROL_URL=`, `SPACE_ID=` and `READY`, then keeps
  running until SIGINT/SIGTERM.

Control endpoints on `CONTROL_URL`:

- `POST /changeaddr?addr=`
- `POST /createapp?name=&perm=rw|r&spaces=<comma-separated full space ids, empty for all>`
  returns `{"appKey","appHash"}`
- `GET /listapps`, `POST /revoke?hash=`
- `POST /createspace?name=` returns `{"spaceId"}`

Check: with the patched harness `GET $BASE_URL/v2/spaces` with `Authorization: Bearer $API_KEY`
returns 200; with `run.sh --unpatched` it returns 500.

## Contract tests against real heart

`run-contract-tests.sh` starts the harness from a temp dir (it writes `account_stop.json` into its
cwd on exit), exports `ANYTYPE_HEART_BASE_URL/API_KEY/CONTROL_URL/SPACE_ID` and runs the
`*realheart*` tests of `:agent-bridge-client`. Without those env vars the tests are skipped
(`Assume`), so plain `testDebugUnitTest` stays green.

Behaviour verified with curl against heart v0.51.6 (patched):

- Keyless `/v1/spaces`, `/v2/spaces`, `/mcp/small`: 401 `missing authorization header`.
- Revoked key: 401 `{"object":"error","status":401,"code":"unauthorized","message":"invalid api key"}`.
- Foreign `Origin`: 403 `request origin is not allowed`. The JDK `HttpURLConnection` silently drops a
  caller-set `Origin` (restricted header), so the test uses a raw socket.
- `changeaddr?addr=` (empty) closes the listener; reopening with `127.0.0.1:<old port>` works, so the
  test restores the original port instead of `:0` to keep the env-provided base URL valid.
- /v2 returns short space ids (last 6 chars before the dot of the full id, e.g. `znefhm`); MCP
  `spaces`/`find` also use short ids but accept the full id as `space`.
- Narrowed key: `/v1/*` 403 code `v1_not_available_for_scoped_keys`; other space 403 code
  `space_not_granted` (on `/v2/spaces/<short>` and `/objects`); `/v2/spaces` lists only granted spaces
  (`has_not_granted_spaces: true`).
- Read-only key: MCP `create` returns HTTP 200 with `result.isError=true` and text
  "the HTTP API is a write and the key's grant is read-only..."; REST write is 403 `write_not_granted`.
- MCP tool results carry `structuredContent` (`create` -> `{id,type,etag}`, `find` -> `{handles:[{n,id,name,type}]}`);
  `find` handle numbers are per MCP session, so `read` must use the same `McpClient`. `find` is
  eventually consistent (full-text index), so the test polls.
- MCP args (from `tools/list`): create{space,type,name,markdown,properties}, find{space,query,type,filter,limit},
  read{object,space,mode}, describe{space,type,...}, set_properties{object,...}, add_blocks{object,markdown,after,under,space},
  edit_text{object,find,replace,block,space}.

## Building the patched heart for Android

`build-heart-android.sh [--abis arm64-v8a,x86_64]` fetches heart, applies `heart-mobile-api.patch`,
builds tantivy for each Android target, runs `gomobile bind` and publishes the AAR to Maven Local
as `io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1`. All four ABIs take 30-60+ minutes.
`mavenLocal()` is the first repository, and `middlewareVersion` in `gradle/libs.versions.toml`
points at that version. Needs Go, Rust, the Android NDK and Maven.

- `--abis` narrows the build for a quicker local try. A narrowed AAR is published under its own
  version, `v0.51.6-agentbridge.1-<goarch>...` (for example `v0.51.6-agentbridge.1-arm64-amd64`),
  so it never overwrites the full artifact; set `middlewareVersion` to it by hand to use it.
- Keys: upstream CI stamps two keys into heart. The script does the same when the environment
  variables are set, and warns when they are not. Never commit their values.
  - `UNSPLASH_KEY` sets `util/unsplash.DefaultToken`. Without it the Unsplash cover picker does
    not work.
  - `INHOUSE_KEY` sets `metrics.DefaultInHouseKey`. Without it in-house analytics stay silent.

## Building and testing the app

Gradle needs JDK 17 and, in this environment, `--no-configuration-cache`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
G="./gradlew --no-configuration-cache"
$G :app:assembleDebug :agent-bridge-testapp:assembleDebug
scripts/agent-bridge/run-contract-tests.sh
```

On an emulator or phone (Android 15+):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk        # io.anytype.app.dev
adb install -r agent-bridge-testapp/build/outputs/apk/debug/*.apk
$G :agent-bridge-testapp:connectedDebugAndroidTest
```

The test app can pin the Anytype build's signing certificate (optional):
`adb shell am start -n io.anytype.agentbridge.testapp/.MainActivity --es pinned_cert <sha-256 hex>`
(get the digest with `apksigner verify --print-certs app-debug.apk`). It then lists and binds only
builds signed with that certificate.

Log in to a (local-only) account in Anytype first. The instrumented test drives the approval screen
and a read/write flow through the bridge. It has been compiled but not run yet (no emulator in the
build environment).

## Upgrading heart

1. Bump the version in `fetch-heart-src.sh` and `build-heart-android.sh` (`HEART_VERSION`,
   `VERSION`), and `middlewareVersion` in `gradle/libs.versions.toml`.
2. Check the patch still applies; rebuild the harness (`heart-harness/build.sh`) and the AAR.
3. Rerun `run-contract-tests.sh`.
4. Regenerate `docs/agent-bridge/small-tools-manifest.json` (see `docs/agent-bridge/agent-integration.md`).

## Dropping the patch

When upstream calls `api.SetMiddlewareParams(mw)` in `clientlibrary/service`, delete
`heart-mobile-api.patch` and the patch step in `fetch-heart-src.sh`, and depend on the official
`anytype-heart-android` artifact. The unpatched harness (`run.sh --unpatched`) shows whether a
given heart version still has the bug (500 means yes). The upstream issue draft is
`docs/agent-bridge/heart-issue-draft.md` (not filed).

## Accepted loopback exposure

While any app is bound to the bridge service, heart listens on `127.0.0.1:<port>`. Any local app
can reach that port. Without a key it gets 401/403 and the `Anytype-Version` header. Heart's keyless
pairing challenge cannot complete because the Android app never shows the approval it needs. The
listener closes when the last binding closes, whichever app holds it: an unapproved app that stays
bound can keep the listener open, but it learns no key and reaches only that same 401/403 surface. Contract tests assert that keyless REST and MCP calls are
rejected.
