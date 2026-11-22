# API Bridge Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename "agent bridge" to "API bridge" in code and docs, and to "connected apps" in user-facing text, across anytype-kotlin and the heart fork, with no behaviour change.

**Architecture:** A pure rename following the spec's mapping tables, done in two repositories: the heart fork first (branch, harness directory, tag), then anytype-kotlin (modules, packages, actions, types, resources, stored names, docs, scripts, version).

**Tech Stack:** Gradle/Kotlin/AIDL, git, Go.

**Spec:** `docs/superpowers/specs/2026-11-22-api-bridge-rename-design.md`

## Global Constraints

- **Mapping:** follow the spec's mapping tables exactly. No behaviour changes. Leave `docs/superpowers/**` unchanged.
- **Environment:** run Gradle with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk` and `--no-configuration-cache`. Lint has `abortOnError=false`, so read the text reports.
- **anytype-kotlin commits** end with the `Co-Authored-By` / `Claude-Session` footer.
- **Heart fork commits** carry no `GO-` prefix (the user's decision).
- **No pushes in either repository.** The controller pushes.
- **Never commit** local secret files or `build/`.

## Review Focus

1. **A missed string.** A leftover `agentbridge`, `agent-bridge`, `agent_bridge` or `agent:` in a manifest, intent action, AIDL descriptor, ProGuard rule or script would break binding or resolution at runtime, not at compile time. (Task 2: a grep gate.)
2. **Consumer ProGuard rules** must name the new AIDL package, or release builds of agent apps strip the stubs. (Task 2: the grep, plus `assembleRelease` of the two libraries.)
3. **The test app's applicationId changes,** so the instrumented test's package constants must follow. (Task 2: covered by the grep.)

---

### Task 1: Heart fork, branch `apibridge`

**Files:** in `/home/user/anytype-heart`.
- Create the branch `apibridge` from `agentbridge` (HEAD `38aae06`).
- `git mv cmd/agentbridge-harness cmd/apibridge-harness`, and rename the "agent bridge"/"agent-bridge" wording inside `main.go` (comments and usage) to "API bridge".
- Delete the local tag `v0.51.6-agentbridge.2`. Create the annotated tag `v0.51.6-apibridge.1` on the new head.

- [ ] **Step 1:** Make the branch, the move and the wording edits, then build. Run: `PATH=/root/.cargo/bin:$PATH GOTOOLCHAIN=auto CGO_ENABLED=1 CGO_CFLAGS="-Wno-deprecated-non-prototype -Wno-unknown-warning-option -Wno-deprecated-declarations -Wno-xor-used-as-pow -Wno-single-bit-bitfield-constant-conversion" CGO_LDFLAGS=-L/home/user/anytype-kotlin/build/agent-bridge/tantivy-go-v1.0.7/libs/linux-amd64-musl go build -tags "nosigar nowatchdog" -o /tmp/apibridge-harness ./cmd/apibridge-harness`. Expected: exit 0.
- [ ] **Step 2:** Commit with the message `cmd/apibridge-harness: rename from agentbridge-harness`. Then delete the old tag and create the new one.

### Task 2: anytype-kotlin rename

**Files:** everything the spec's mapping tables cover in `/home/user/anytype-kotlin`:
- `settings.gradle` and the module directories (use `git mv`);
- the `build.gradle` namespaces;
- `app/build.gradle` and `app/src/main/AndroidManifest.xml` (`dataExtractionRules`);
- the `:app` wiring files: `AndroidApplication`, `MainComponent`, `ComponentManager` and the component/installer files;
- `middleware/.../middleware/agent` → `localapi`;
- AIDL, manifests and resources, consumer ProGuard rules, tests and androidTest;
- `gradle/libs.versions.toml`: `middlewareVersion = "v0.51.6-apibridge.1"`;
- `scripts/agent-bridge` → `scripts/api-bridge`: the contract script uses `HEART_TAG v0.51.6-apibridge.1`, branch `apibridge` and `cmd/apibridge-harness`, and writes to `build/api-bridge/`;
- `docs/agent-bridge` → `docs/api-bridge`, with `agent-integration.md` → `integration.md` rewritten for any app developer (agents stay one use case), plus the README and the issue draft;
- user-facing strings: "Connected apps" and "Manage connected apps".

- [ ] **Step 1:** Do the mapping.
- [ ] **Step 2: Grep gate.** Run: `git grep -n -i -E "agent[-_ ]?bridge|agentbridge|\"agent:|IAgentBridge|AgentEndpoint|AgentApproval|AgentAccess" -- ':!docs/superpowers'`. Expected: no matches. Any remaining "agent" must refer to AI agents as a use case, in docs only.
- [ ] **Step 3: Verify.**
  - Run: `./gradlew --no-configuration-cache :api-bridge-client:testDebugUnitTest :api-bridge:testDebugUnitTest :api-bridge-testapp:testDebugUnitTest :middleware:testDebugUnitTest :app:testDebugUnitTest --tests '*ApiBridgeInstallerTest*' :api-bridge-client:lintDebug :api-bridge:lintDebug :api-bridge-testapp:lintDebug :api-bridge-client:assembleRelease :api-bridge:assembleRelease :agent-bridge-testapp:assembleDebugAndroidTest` (use the new module name `:api-bridge-testapp`). Expected: PASS, with lint clean.
  - `:app:assembleDebug` cannot resolve `v0.51.6-apibridge.1` until the fork publishes it. Verify `:app` with `:app:compileDebugKotlin` after temporarily copying the Maven Local `v0.51.6-agentbridge.1` artifact under the new version in a throwaway local repository. Better: just run `:app:compileDebugKotlin -PmiddlewareVersionOverride` if no such property exists, skip this, and say so in the report.
  - Run: `HEART_DIR=/home/user/anytype-heart scripts/api-bridge/run-contract-tests.sh`. Expected: 3/3 pass.
- [ ] **Step 4: Commit** with the message `Rename agent bridge to API bridge ("connected apps" in the UI)`.
