# API bridge contract tests

`run-contract-tests.sh` runs the test app's flow against the real heart, built as a native Linux
binary. The rest of the bridge's maintainer notes are in
[docs/api-bridge/README.md](../../docs/api-bridge/README.md).

## Running them

```bash
HEART_DIR=../anytype-heart scripts/api-bridge/run-contract-tests.sh [extra gradle args]
```

`HEART_DIR` (default `../anytype-heart`) must be a checkout of the heart fork at tag `HEART_TAG`
(set in the script) or on branch `apibridge`; the script refuses anything else. It needs Go
(`GOTOOLCHAIN=auto` fetches the version in heart's `go.mod`), a Rust toolchain and a C compiler.

The script:

1. builds tantivy-go's static library, which heart links through cgo, from the version in heart's
   `go.mod`, with cargo (about 4 minutes the first time; cached under `build/api-bridge/`);
2. builds the harness (below) inside `$HEART_DIR`;
3. starts it, exports `ANYTYPE_HEART_BASE_URL`, `_API_KEY`, `_CONTROL_URL` and `_SPACE_ID`, and
   runs `FlowRunnerRealHeartTest` in `:api-bridge-testapp`. Without those variables the test is
   skipped, so a plain `testDebugUnitTest` stays green.

## What they check

- `flowAgainstRealHeart`: the test app's `FlowRunner` (OkHttp for REST, the Kotlin MCP SDK for
  MCP) runs its whole flow with the harness's key: list spaces, MCP initialize and tools/list,
  then create, find and read a page.
- `keylessRestIsRejected`: `GET /v2/spaces` without a key gets 401.
- `keylessMcpIsRejected`: an MCP `initialize` on `/mcp/small` without a key gets 401.

## The harness

`harness/main.go` boots heart through the same `clientlibrary/service` entry points the Android
library exposes, with a fresh local-only account, opens the local API on loopback and creates a
key. It prints `BASE_URL=`, `API_KEY=`, `APP_HASH=`, `CONTROL_URL=`, `SPACE_ID=` and `READY`, and
runs until SIGINT or SIGTERM (`-root DIR` sets the data root).

It lives here rather than in heart because only these tests use it. The script compiles it inside
the heart checkout with `go build -overlay`, as if it were heart's `cmd/apibridge-harness`, so it
uses heart's `go.mod` unchanged and writes nothing into the checkout.

Control endpoints on `CONTROL_URL`, each returning heart's response as JSON:

- `POST /changeaddr?addr=`
- `POST /createapp?name=&perm=rw|r&spaces=<comma-separated space ids, empty for all>` returns
  `{"appKey","appHash"}`
- `GET /listapps`, `POST /revoke?hash=`
- `POST /createspace?name=` returns `{"spaceId"}`
- `GET /events`: the `Account.JsonApiStatus` events seen so far
