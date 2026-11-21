# Agent bridge tooling

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
