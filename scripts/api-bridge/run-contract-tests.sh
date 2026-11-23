#!/usr/bin/env bash
# Builds the native heart harness (harness/main.go) inside a checkout of the
# garyp/anytype-heart fork, starts it, exports ANYTYPE_HEART_* and runs the gated real-heart test
# of the test app (FlowRunnerRealHeartTest).
#
# Usage: [HEART_DIR=../anytype-heart] run-contract-tests.sh [extra gradle args]
#
# HEART_DIR (default: ../anytype-heart next to this repository) must be the fork at tag
# v0.51.6-apibridge.2 or on branch apibridge. Needs Go (GOTOOLCHAIN=auto fetches the version in
# go.mod), cargo (for tantivy-go's static library, ~4 min, built once and cached under
# build/api-bridge/) and a C toolchain.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
HEART_TAG="v0.51.6-apibridge.2"
HEART_BRANCH="apibridge"
HEART_DIR="$(cd "${HEART_DIR:-$REPO/../anytype-heart}" && pwd)"
OUT="$REPO/build/api-bridge"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$HOME/.cargo/bin:$PATH" GOTOOLCHAIN=auto CGO_ENABLED=1
G="${G:-$REPO/gradlew}"

# --- the fork checkout -------------------------------------------------------------------------
grep -q 'api.SetMiddlewareParams(mw)' "$HEART_DIR/clientlibrary/service/lib.go" 2>/dev/null || {
  echo "HEART_DIR=$HEART_DIR lacks the JSON API middleware fix: clone github.com/garyp/anytype-heart" \
       "and check out $HEART_TAG (or branch $HEART_BRANCH)" >&2; exit 1; }
HEAD_SHA="$(git -C "$HEART_DIR" rev-parse HEAD)"
TAG_SHA="$(git -C "$HEART_DIR" rev-parse -q --verify "refs/tags/$HEART_TAG^{commit}" || true)"
BRANCH="$(git -C "$HEART_DIR" rev-parse --abbrev-ref HEAD)"
if [ "$HEAD_SHA" != "$TAG_SHA" ] && [ "$BRANCH" != "$HEART_BRANCH" ]; then
  echo "HEART_DIR=$HEART_DIR is at $BRANCH ($HEAD_SHA); expected tag $HEART_TAG or branch $HEART_BRANCH" >&2
  exit 1
fi

# --- tantivy-go static library (cgo dependency of heart) ----------------------------------------
TANTIVY_VER="$(cd "$HEART_DIR" && go list -m -f '{{.Version}}' github.com/anyproto/tantivy-go)"
LIBDIR="$OUT/tantivy-go-$TANTIVY_VER/libs/linux-amd64-musl"
LIB="$LIBDIR/libtantivy_go.a"
if [ ! -f "$LIB" ]; then
  TSRC="$(cd "$HEART_DIR" && go mod download -json "github.com/anyproto/tantivy-go@$TANTIVY_VER" \
          | sed -n 's/^[[:space:]]*"Dir": "\(.*\)",$/\1/p')"
  [ -d "$TSRC/rust" ] || { echo "tantivy-go $TANTIVY_VER source not found (got '$TSRC')" >&2; exit 1; }
  BUILD="$OUT/tantivy-go-$TANTIVY_VER/src"
  rm -rf "$BUILD"; mkdir -p "$BUILD"
  cp -r "$TSRC/." "$BUILD/"; chmod -R u+w "$BUILD"   # the module cache is read-only
  # The pinned toolchain file names a nightly that is not needed for a host build.
  [ -f "$BUILD/rust/rust-toolchain.toml" ] && mv "$BUILD/rust/rust-toolchain.toml" "$BUILD/rust/rust-toolchain.toml.orig"
  (cd "$BUILD/rust" && cargo build --release) >&2
  mkdir -p "$LIBDIR"
  cp "$BUILD/rust/target/release/libtantivy_go.a" "$LIB"
fi

# --- the harness ---------------------------------------------------------------------------------
export CGO_CFLAGS="-Wno-deprecated-non-prototype -Wno-unknown-warning-option -Wno-deprecated-declarations -Wno-xor-used-as-pow -Wno-single-bit-bitfield-constant-conversion"
export CGO_LDFLAGS="-L$LIBDIR"
mkdir -p "$OUT/bin"
BIN="$OUT/bin/apibridge-harness"
echo "building $BIN in $HEART_DIR ($HEAD_SHA)" >&2
# The harness lives here, not in heart: the overlay compiles it as if it were heart's
# cmd/apibridge-harness, with heart's go.mod and replaces, without writing into the checkout.
OVERLAY="$OUT/harness-overlay.json"
printf '{"Replace":{"%s":"%s"}}\n' "$HEART_DIR/cmd/apibridge-harness/main.go" "$HERE/harness/main.go" >"$OVERLAY"
(cd "$HEART_DIR" && go build -overlay "$OVERLAY" -tags "nosigar nowatchdog" -o "$BIN" ./cmd/apibridge-harness)

# The harness writes account_stop.json into its cwd on exit: run it from a throwaway dir.
WORK="$(mktemp -d)"
HPID=""
cleanup() {
  if [ -n "$HPID" ]; then kill "$HPID" 2>/dev/null || true; wait "$HPID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

cd "$WORK"
"$BIN" -root "$WORK/root" >"$WORK/out.txt" 2>"$WORK/err.txt" &
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
# The test app's FlowRunner (OkHttp + MCP SDK) against this heart; the API key is an app key from
# the harness. --rerun: the env vars are not Gradle task inputs, so a cached skipped run must not
# be reused.
"$G" :api-bridge-testapp:testDebugUnitTest --tests '*RealHeart*' --rerun "$@"
