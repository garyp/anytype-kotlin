# Rename: agent bridge → API bridge / connected apps

Status: approved by the user, 2026-11-22.

## Why

Nothing in the bridge is specific to AI agents. Any app the user approves gets heart's local REST
and MCP API with a scoped key. The code takes the name "API bridge". Anything the user sees says
"connected apps".

## Names (decided by the user)

- **Code:** "API bridge" (`api-bridge`, `apibridge`, `ApiBridge…`).
- **User-facing text:** "connected apps". The access screen title is "Connected apps", and the
  consent footer reads "Manage connected apps". The consent dialog keeps "Allow X to access Anytype?".

## Mapping (decided by the controller; the builder follows it)

### Modules

| Before | After |
|---|---|
| `:agent-bridge` | `:api-bridge` |
| `:agent-bridge-client` | `:api-bridge-client` |
| `:agent-bridge-testapp` | `:api-bridge-testapp` |

### Packages and namespaces

| Before | After |
|---|---|
| `com.anytypeio.anytype.agentbridge` (module and `:app` wiring) | `com.anytypeio.anytype.apibridge` |
| `io.anytype.agentbridge` (AIDL) | `io.anytype.apibridge` |
| `io.anytype.agentbridge.client` | `io.anytype.apibridge.client` |
| `io.anytype.agentbridge.testapp` (also the test app's applicationId) | `io.anytype.apibridge.testapp` |
| `com.anytypeio.anytype.middleware.agent` | `com.anytypeio.anytype.middleware.localapi` |

### Intent actions

| Before | After |
|---|---|
| `io.anytype.agentbridge.BIND` | `io.anytype.apibridge.BIND` |
| `io.anytype.agentbridge.APPROVE` | `io.anytype.apibridge.APPROVE` |

### Types

| Before | After |
|---|---|
| `IAgentBridge` | `IApiBridge` |
| `AgentEndpoint` | `ApiEndpoint` |
| `AgentBridgeService` | `ApiBridgeService` |
| `AgentBridge` | `ApiBridge` |
| `AgentApprovalActivity` | `ConnectApprovalActivity` |
| `AgentAccessActivity` | `ConnectedAppsActivity` |
| `AgentApprovals` | `AppApprovals` |
| `AgentBridgeComponent` | `ApiBridgeComponent` |
| `AgentBridgeInstaller` | `ApiBridgeInstaller` |
| `AgentBridgeDependencies` | `ApiBridgeDependencies` |

Other `Agent*` and `agent*` identifiers follow the same pattern ("agent" becomes "app", "api" or
"connected", whichever reads right). `AnytypeBridge` (client), `BridgeController`,
`CallerVerifier`, `LocalApiControl` and `KeyNames` keep their names.

### Resources and stored names

| Before | After |
|---|---|
| `@bool/agent_bridge_enabled` | `@bool/api_bridge_enabled` |
| `agent_bridge_data_extraction_rules.xml` | `api_bridge_data_extraction_rules.xml` |
| prefs file `agent_bridge` | `api_bridge` |
| heart key names `agent:<pkg>:<cert8>` | `app:<pkg>:<cert8>` |

Old approvals and old `agent:` keys on test devices are abandoned; there is no migration. Nothing
has been released.

### Repository paths

| Before | After |
|---|---|
| `docs/agent-bridge/` | `docs/api-bridge/` |
| `agent-integration.md` | `integration.md` |
| `scripts/agent-bridge/` | `scripts/api-bridge/` |
| `build/agent-bridge/` | `build/api-bridge/` |

`integration.md` is rewritten for any app developer. AI agents and the `/mcp/small` tier stay in
it as one use case.

### Heart fork

- The branch becomes `apibridge`, pushed as a new branch with the same commits plus the harness
  rename. The `agentbridge` branch and PR garyp/anytype-heart#1 are superseded: open a new PR
  from `apibridge` and close #1 with a pointer.
- `cmd/agentbridge-harness` becomes `cmd/apibridge-harness`.
- The release tag becomes `v0.51.6-apibridge.1`, so `middlewareVersion = "v0.51.6-apibridge.1"`.
  The `v0.51.6-agentbridge.2` tag was never created on the remote; delete it locally.
- Fork commit messages keep no `GO-` prefix. This is the user's decision; the cherry-picked
  upstream commit keeps its own prefix.

## Out of scope

- No behaviour changes.
- Historical design and plan docs under `docs/superpowers/` keep their old wording.
