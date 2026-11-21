#!/usr/bin/env bash
# Starts a real anytype-heart (native harness), exports ANYTYPE_HEART_* and runs the gated
# contract tests. Usage: run-contract-tests.sh [extra gradle args]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
G="${G:-$REPO/gradlew}"

# The harness writes account_stop.json into its cwd on exit: run it from a throwaway dir.
WORK="$(mktemp -d)"
HPID=""
cleanup() {
  if [ -n "$HPID" ]; then kill "$HPID" 2>/dev/null || true; wait "$HPID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

cd "$WORK"
"$HERE/heart-harness/run.sh" -root "$WORK/root" >"$WORK/out.txt" 2>"$WORK/err.txt" &
HPID=$!
for _ in $(seq 120); do
  grep -q '^READY$' "$WORK/out.txt" && break
  kill -0 "$HPID" 2>/dev/null || { echo "harness died:" >&2; tail -20 "$WORK/err.txt" >&2; exit 1; }
  sleep 0.5
done
grep -q '^READY$' "$WORK/out.txt" || { echo "harness did not become READY" >&2; exit 1; }

val() { grep "^$1=" "$WORK/out.txt" | head -1 | cut -d= -f2-; }
export ANYTYPE_HEART_BASE_URL="$(val BASE_URL)"
export ANYTYPE_HEART_API_KEY="$(val API_KEY)"
export ANYTYPE_HEART_CONTROL_URL="$(val CONTROL_URL)"
export ANYTYPE_HEART_SPACE_ID="$(val SPACE_ID)"
echo "heart at $ANYTYPE_HEART_BASE_URL (control $ANYTYPE_HEART_CONTROL_URL)"

cd "$REPO"
# --rerun: the env vars are not Gradle task inputs, so a cached skipped run must not be reused.
"$G" :agent-bridge-client:testDebugUnitTest --tests '*realheart*' --rerun "$@"
