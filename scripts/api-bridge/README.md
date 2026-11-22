# API bridge tooling

The app depends on `io.anyproto:anytype-heart-android:v0.51.6-apibridge.1` (`middlewareVersion`
in `gradle/libs.versions.toml`). Upstream heart's mobile library never calls
`api.SetMiddlewareParams`, so its JSON API answers every authenticated request with an empty 500.
The fixed heart is built from the fork
[garyp/anytype-heart](https://github.com/garyp/anytype-heart), branch `apibridge`. Nothing in this
repository builds heart.

## The heart fork

Branch `apibridge` is upstream tag `v0.51.6` plus these commits:

1. `clientlibrary: set JSON API middleware params in the mobile library` (a25d5c3): the one-line
   fix in `clientlibrary/service/lib.go` and the regression test
   `TestMobileLibrarySetsApiMiddlewareParams` in `clientlibrary/service/lib_test.go`.
2. `ci: publish to the fork's own package registry ...`: `build.gradle` publishes to
   `https://maven.pkg.github.com/${GITHUB_REPOSITORY}`, the `anytype-suite` and `anytype-cli`
   triggers run only for `anyproto`, and the `ANYTYPE_PAT` rewrite runs only when that secret is
   set. The rest of upstream `build.yml` is unchanged.
3. `cmd/apibridge-harness`: the native Linux harness used by the contract tests (below); a later
   commit gives it its current name.
4. `Bump tantivy-go to v1.0.7` (cherry-picked from upstream `develop`, `2e54309f6`): Go 1.26.11,
   which the `Build` workflow's `setup-go` resolves, refuses v1.0.6's prebuilt libraries.
5. CI-only commits: a 240-minute job timeout, a `platform` input for manual runs, and NDK 28 on
   the Linux runner (see below).

To publish, run the fork's `Build` workflow manually (Actions tab, `Build`, "Run workflow" on
`apibridge`) with `platform = android` and `alpha_version = <version>`, for example
`v0.51.6-apibridge.1`. An `android` run builds only the AAR, on `ubuntu-24.04`, in about 10
minutes, and publishes `io.anyproto:anytype-heart-android:<version>` to the fork's GitHub
Packages, `https://maven.pkg.github.com/garyp/anytype-heart`. Pushing a `v*.*.*` tag also works,
but it builds every platform on macOS and takes over two hours. A version can be published only
once per package; publish a new `-apibridge.N` rather than reusing one.

Setup of the fork (once):

- **Actions must be enabled** on the fork (GitHub disables workflows on new forks: Actions tab,
  "I understand my workflows, go ahead and enable them").
- **Repository secrets** (Settings, Secrets and variables, Actions):
  - `UNSPLASH_KEY` sets `util/unsplash.DefaultToken`. Without it the Unsplash cover picker does
    not work.
  - `INHOUSE_KEY` sets `metrics.DefaultInHouseKey`. Without it in-house analytics stay silent.
  - `ANYTYPE_PAT` is probably not needed (the workflow skips it when empty); add it only if the
    build fails to fetch a private dependency.
  Never commit the values.

### Resolving the artifact

`settings.gradle` has a `HeartFork` repository, `https://maven.pkg.github.com/garyp/anytype-heart`,
restricted to the module `io.anyproto:anytype-heart-android`, ahead of the anyproto repository
(which stays for other artifacts). It uses the same `github.properties` credentials
(`gpr.usr`, `gpr.key`). GitHub Packages needs a token even for public packages: the
`gpr.key` token must have `read:packages` and be able to read `garyp/anytype-heart`.

History: the bridge was first named for agents, and so were the fork's branch, tags and harness;
the first tag under that name failed the fork's `Build` on Go 1.26.11, hence commit 4 above.

`mavenLocal()` stays the first repository, so a copy of the same coordinates in Maven Local wins
over the fork's package. Delete `~/.m2/repository/io/anyproto/anytype-heart-android/<version>` to
force the download.

### Bumping heart

1. Fetch the upstream tags first; a fork clone does not have them
   (`git fetch https://github.com/anyproto/anytype-heart tag vX.Y.Z --no-tags`, and the same for
   `v0.51.6` if it is missing). Then rebase the fork's `apibridge` branch onto the new upstream
   tag (`git rebase --onto vX.Y.Z v0.51.6 apibridge`). Drop the fix commit if upstream now calls
   `api.SetMiddlewareParams(mw)` in `clientlibrary/service` (see "Dropping the fork").
2. Push the branch and run the `Build` workflow with `platform = android` and
   `alpha_version = vX.Y.Z-apibridge.N` (see above). Tag the published commit
   `vX.Y.Z-apibridge.N` for `run-contract-tests.sh`.
3. Set `middlewareVersion` in `gradle/libs.versions.toml` to `vX.Y.Z-apibridge.N`, and the tag
   that `run-contract-tests.sh` checks (`HEART_TAG`).
4. Rerun `run-contract-tests.sh` against the new tag.
5. Regenerate `docs/api-bridge/small-tools-manifest.json` (see
   `docs/api-bridge/integration.md`).

### Dropping the fork

When upstream calls `api.SetMiddlewareParams(mw)` in `clientlibrary/service`, depend on the
official `anytype-heart-android` artifact, delete the `HeartFork` repository from
`settings.gradle`, and build the harness from a checkout that has `cmd/apibridge-harness` on top
of upstream. The upstream issue draft is `docs/api-bridge/heart-issue-draft.md` (not filed).

## Native heart harness

Contract tests run against the real heart built as a native Linux binary:
`cmd/apibridge-harness` in the fork. It boots heart through the same `clientlibrary/service`
entry points the AAR exposes, with a fresh local-only account, and prints `BASE_URL=`, `API_KEY=`,
`APP_HASH=`, `CONTROL_URL=`, `SPACE_ID=` and `READY`, then keeps running until SIGINT/SIGTERM.
`-root DIR` sets the data root.

Control endpoints on `CONTROL_URL`:

- `POST /changeaddr?addr=`
- `POST /createapp?name=&perm=rw|r&spaces=<comma-separated full space ids, empty for all>`
  returns `{"appKey","appHash"}`
- `GET /listapps`, `POST /revoke?hash=`
- `POST /createspace?name=` returns `{"spaceId"}`
- `GET /events`: the `Account.JsonApiStatus` events seen so far

## Contract tests against real heart

```bash
HEART_DIR=../anytype-heart scripts/api-bridge/run-contract-tests.sh [extra gradle args]
```

`HEART_DIR` (default `../anytype-heart`) is a checkout of the fork at tag `v0.51.6-apibridge.1`
or on branch `apibridge`; the script refuses anything else. It needs Go (`GOTOOLCHAIN=auto`
fetches the toolchain in `go.mod`), a Rust toolchain and a C compiler.

1. tantivy-go: heart links `libtantivy_go.a` through cgo. The script takes the tantivy-go source
   at the version in the fork's `go.mod` from the Go module cache (`go mod download`), copies it
   to `build/api-bridge/tantivy-go-<version>/`, and builds the library with cargo (about 4
   minutes, once; the result is cached there).
2. It builds `build/api-bridge/bin/apibridge-harness` from `$HEART_DIR`.
3. It starts the harness from a temp dir (the harness writes `account_stop.json` into its cwd on
   exit), exports `ANYTYPE_HEART_BASE_URL/API_KEY/CONTROL_URL/SPACE_ID` and runs the
   `FlowRunnerRealHeartTest` of `:api-bridge-testapp` (`--rerun`). Without those env vars the
   test is skipped (`Assume`), so plain `testDebugUnitTest` stays green.

What the contract tests check, on every run:

- `flowAgainstRealHeart`: the test app's `FlowRunner` (OkHttp for REST, the MCP Kotlin SDK for MCP)
  runs its whole flow with the harness's app key and ends with `ALL OK`.
- `keylessRestIsRejected`: `GET /v2/spaces` without a key gets 401.
- `keylessMcpIsRejected`: `POST /mcp/small` with an MCP `initialize` and no key gets 401.

Nothing else is automated. The rest below was verified once by hand, with curl against heart
v0.51.6 with the fix, and is not rechecked:

- Keyless `/v1/spaces`, `/v2/spaces`, `/mcp/small`: 401 `missing authorization header`.
- Revoked key: 401 `{"object":"error","status":401,"code":"unauthorized","message":"invalid api key"}`.
- Foreign `Origin`: 403 `request origin is not allowed`. (The JDK `HttpURLConnection` silently drops
  a caller-set `Origin`, a restricted header; curl sends it.)
- `changeaddr?addr=` (empty) closes the listener; reopening with `127.0.0.1:<old port>` works.
- /v2 returns short space ids (last 6 chars before the dot of the full id, e.g. `znefhm`); MCP
  `spaces`/`find` also use short ids but accept the full id as `space`.
- Narrowed key: `/v1/*` 403 code `v1_not_available_for_scoped_keys`; other space 403 code
  `space_not_granted` (on `/v2/spaces/<short>` and `/objects`); `/v2/spaces` lists only granted spaces
  (`has_not_granted_spaces: true`).
- Read-only key: MCP `create` returns HTTP 200 with `result.isError=true` and text
  "the HTTP API is a write and the key's grant is read-only..."; REST write is 403 `write_not_granted`.
- MCP tool results carry `structuredContent` (`create` -> `{id,type,etag}`, `find` -> `{handles:[{n,id,name,type}]}`);
  `find` handle numbers are per MCP session, so `read` must use the same MCP session. `find` is
  eventually consistent (full-text index), so the test polls.
- MCP args (from `tools/list`): create{space,type,name,markdown,properties}, find{space,query,type,filter,limit},
  read{object,space,mode}, describe{space,type,...}, set_properties{object,...}, add_blocks{object,markdown,after,under,space},
  edit_text{object,find,replace,block,space}.

## Building and testing the app

Gradle needs JDK 17 and, in this environment, `--no-configuration-cache`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
G="./gradlew --no-configuration-cache"
$G :app:assembleDebug :api-bridge-testapp:assembleDebug
HEART_DIR=../anytype-heart scripts/api-bridge/run-contract-tests.sh
```

On an emulator or phone (Android 15+):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk        # io.anytype.app.dev
adb install -r api-bridge-testapp/build/outputs/apk/debug/*.apk
$G :api-bridge-testapp:connectedDebugAndroidTest
```

The test app can pin the Anytype build's signing certificate (optional):
`adb shell am start -n io.anytype.apibridge.testapp/.MainActivity --es pinned_cert <sha-256 hex>`
(get the digest with `apksigner verify --print-certs app-debug.apk`). It then lists and binds only
builds signed with that certificate.

Log in to a (local-only) account in Anytype first. The instrumented test drives the approval screen
and a read/write flow through the bridge. It has been compiled but not run yet (no emulator in the
build environment).

## Accepted loopback exposure

While any app is bound to the bridge service, heart listens on `127.0.0.1:<port>`. Any local app
can reach that port. Without a key it gets 401/403 and the `Anytype-Version` header. Heart's keyless
pairing challenge cannot complete because the Android app never shows the approval it needs. The
listener closes when the last binding closes, whichever app holds it: an unapproved app that stays
bound can keep the listener open, but it learns no key and reaches only that same 401/403 surface.
The contract tests assert that keyless REST (`GET /v2/spaces`) and MCP (`POST /mcp/small`) calls
get 401; the `Origin` 403 was checked once by hand (above).
