# Draft upstream issue: mobile library never wires the JSON API to the middleware

Not filed. The fork owner decides whether to file it at anyproto/anytype-heart.

## Title

`clientlibrary/service`: JSON API returns an empty HTTP 500 on every authenticated request (`api.SetMiddlewareParams` is never called)

## Symptom

In the mobile library (the gomobile AAR used by Android, and the same code path on iOS), the local
JSON API starts and reports success. It is started through the `jsonApiListenAddr` field of
`Rpc.Account.Create` / `Rpc.Account.Select`, or the `Rpc.Account.ChangeJsonApiAddr` RPC (gomobile
`Service.accountChangeJsonApiAddr`).
Every request that passes the key check then fails with an empty HTTP 500 and no body:

```
GET /v2/spaces
Authorization: Bearer <valid app key>
-> HTTP/1.1 500 Internal Server Error   (Content-Length: 0)
```

Requests without a key return a normal 401, so the listener itself works. Only authenticated
requests fail. REST `/v1`, REST `/v2` and MCP `/mcp/{small,large,full}` are all affected.

Checked in v0.51.6, v0.51.11 (latest release) and the 2026-11-21 nightly.

## Root cause

`core/api/service.go` keeps the middleware handle in a package variable that is set by
`SetMiddlewareParams` (`func SetMiddlewareParams(mw apicore.ClientCommands)`, near line 367).

- `cmd/grpcserver/grpc.go` calls `api.SetMiddlewareParams(mw)` (line 256).
- `clientlibrary/service/lib.go` builds `var mw = core.New()` and registers it in `init()`, but
  never calls `api.SetMiddlewareParams(mw)`.

So in the mobile library the handle stays nil. `ensureAuthenticated` in
`core/api/server/middleware.go` (line 118) takes that nil `mw`, calls `mw.WalletCreateSession`
for the bearer key, and nil-dereferences. gin's recovery middleware turns the panic into an empty
500.

## Fix

One call in `clientlibrary/service/lib.go`, inside `init()`, after `mw` is created:

```diff
 import (
 	"github.com/anyproto/anytype-heart/core"
+	"github.com/anyproto/anytype-heart/core/api"
 	"github.com/anyproto/anytype-heart/core/event"
 ...
+	api.SetMiddlewareParams(mw)
```

The fork carries this fix as commit
[a25d5c3](https://github.com/garyp/anytype-heart/commit/a25d5c3c2933db68e2ad4cc15332932e3b3a5903)
("clientlibrary: set JSON API middleware params in the mobile library") on branch `agentbridge`
of [garyp/anytype-heart](https://github.com/garyp/anytype-heart/tree/agentbridge) (tag
`v0.51.6-agentbridge.2`). Besides the call it adds `api.MiddlewareParamsSet()` to
`core/api/service.go` and the regression test `TestMobileLibrarySetsApiMiddlewareParams` in
`clientlibrary/service/lib_test.go`, which fails when the mobile library's `init()` does not call
`api.SetMiddlewareParams`. The test links heart, so cgo needs the host's `libtantivy_go.a` (build
it from the tantivy-go version in `go.mod`, as `scripts/agent-bridge/run-contract-tests.sh` in
anytype-kotlin does) and the warning flags that script sets:

```bash
export CGO_ENABLED=1 CGO_LDFLAGS="-L<dir containing libtantivy_go.a>"
export CGO_CFLAGS="-Wno-deprecated-non-prototype -Wno-unknown-warning-option -Wno-deprecated-declarations -Wno-xor-used-as-pow -Wno-single-bit-bitfield-constant-conversion"
go test -tags "nosigar nowatchdog" -run TestMobileLibrarySetsApiMiddlewareParams ./clientlibrary/service
```

## Reproduction

The fork's branch also has a native Linux harness, `cmd/agentbridge-harness`. It boots heart
through the same `clientlibrary/service` entry points gomobile exposes, with a fresh local-only
account, starts the JSON API on loopback and creates an app key. It prints `BASE_URL=` and
`API_KEY=`. It needs `libtantivy_go.a` for the host in `CGO_LDFLAGS` (see
`scripts/agent-bridge/run-contract-tests.sh` in anytype-kotlin for the full recipe).

```bash
git checkout agentbridge
go build -tags "nosigar nowatchdog" -o harness ./cmd/agentbridge-harness
(cd "$(mktemp -d)" && /path/to/harness)                             # prints BASE_URL= and API_KEY=
curl -i -H "Authorization: Bearer $API_KEY" "$BASE_URL/v2/spaces"   # HTTP 200, the spaces

git revert --no-commit a25d5c3                                      # without the fix
go build -tags "nosigar nowatchdog" -o harness ./cmd/agentbridge-harness
(cd "$(mktemp -d)" && /path/to/harness)
curl -i -H "Authorization: Bearer $API_KEY" "$BASE_URL/v2/spaces"   # HTTP 500, empty body
```

With the fix: 200. Without: 500.

## Impact

Nothing built on the mobile library can use the JSON API or MCP, even though the listener can be
started. A regression test in the mobile `service` package catches it; the fork's
`TestMobileLibrarySetsApiMiddlewareParams` asserts the wiring.
