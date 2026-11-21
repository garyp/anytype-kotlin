# Draft upstream issue: mobile library never wires the JSON API to the middleware

Not filed. The fork owner decides whether to file it at anyproto/anytype-heart.

## Title

`clientlibrary/service`: JSON API returns an empty HTTP 500 on every authenticated request (`api.SetMiddlewareParams` is never called)

## Symptom

In the mobile library (the gomobile AAR used by Android, and the same code path on iOS), the local
JSON API starts and reports success, for example through `ApiStartServer` / `ApiChangeListenAddr`.
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

The patch the fork uses is `scripts/agent-bridge/heart-mobile-api.patch`.

## Reproduction

The fork has a native Linux harness that boots heart with a fresh local-only account, starts the
JSON API on loopback and creates an app key (`scripts/agent-bridge/heart-harness`, see the README
in `scripts/agent-bridge/`).

```bash
scripts/agent-bridge/heart-harness/build.sh --unpatched   # pristine v0.51.6
scripts/agent-bridge/heart-harness/run.sh --unpatched     # prints BASE_URL= and API_KEY=
curl -i -H "Authorization: Bearer $API_KEY" "$BASE_URL/v2/spaces"   # HTTP 500, empty body

scripts/agent-bridge/heart-harness/build.sh               # with the one-line patch
scripts/agent-bridge/heart-harness/run.sh
curl -i -H "Authorization: Bearer $API_KEY" "$BASE_URL/v2/spaces"   # HTTP 200, the spaces
```

Patched: 200. Unpatched: 500.

## Impact

Nothing built on the mobile library can use the JSON API or MCP, even though the listener can be
started. A regression test that boots the mobile `service` package and makes one authenticated
request would catch it.
