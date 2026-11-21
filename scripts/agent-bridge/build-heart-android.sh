#!/usr/bin/env bash
# Builds the patched anytype-heart as an Android library (gomobile AAR), publishes it to Maven Local
# as io.anyproto:anytype-heart-android:v0.51.6-agentbridge.1 (all ABIs), and syncs heart's protos into
# protocol/src/main/proto/.
#
#   build-heart-android.sh [--abis arm64-v8a,x86_64]
#
# --abis takes Android ABI names (armeabi-v7a, arm64-v8a, x86, x86_64) or Go arch names
# (arm, arm64, 386, amd64). The default is all four ABIs heart ships. A narrowed build is
# published under its own version, v0.51.6-agentbridge.1-<goarch>-<goarch>... (for example
# v0.51.6-agentbridge.1-arm64-amd64), so it never overwrites the full artifact the app depends on;
# point middlewareVersion at it by hand to try it.
#
# Optional environment (never commit the values): UNSPLASH_KEY and INHOUSE_KEY are stamped into
# heart the way upstream CI does (util/unsplash.DefaultToken, metrics.DefaultInHouseKey). Without
# UNSPLASH_KEY the Unsplash cover picker does not work; without INHOUSE_KEY in-house analytics stay
# silent.
# Mirrors heart's makefiles/android.mk (gomobile bind) and tantivy-go's rust/Makefile (cargo per
# Android target). The govvv version stamping of android.mk is replaced by static -X ldflags.
# Takes 30-60+ minutes for all four ABIs.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/build/agent-bridge"
HEART="$OUT/src/heart"
TANTIVY="$OUT/src/tantivy-go"

HEART_VERSION=v0.51.6
VERSION=v0.51.6-agentbridge.1
GROUP_ID=io.anyproto
ARTIFACT_ID=anytype-heart-android
TAGS="nogrpcserver gomobile nowatchdog nosigar timetzdata rasterizesvg"
ANDROID_API=26

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.1.13356709}"
MVN="${MVN:-$(command -v mvn || echo /opt/maven/bin/mvn)}"
export PATH="$HOME/.cargo/bin:$OUT/tools:$PATH" GOTOOLCHAIN=auto CGO_ENABLED=1

ABIS="armeabi-v7a,arm64-v8a,x86,x86_64"
NARROWED=""
while [ $# -gt 0 ]; do
  case "$1" in
    --abis) ABIS="$2"; NARROWED=1; shift 2 ;;
    --abis=*) ABIS="${1#--abis=}"; NARROWED=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Go arch, Rust triple, NDK clang prefix (API level is appended)
abi_info() {
  case "$1" in
    armeabi-v7a|arm) echo "arm armv7-linux-androideabi armv7a-linux-androideabi" ;;
    arm64-v8a|arm64) echo "arm64 aarch64-linux-android aarch64-linux-android" ;;
    x86|386)         echo "386 i686-linux-android i686-linux-android" ;;
    x86_64|amd64)    echo "amd64 x86_64-linux-android x86_64-linux-android" ;;
    *) echo "unknown ABI: $1" >&2; return 1 ;;
  esac
}

# A narrowed build gets its own version, so it never replaces the full artifact in Maven Local.
if [ -n "$NARROWED" ]; then
  suffix=""
  for abi in ${ABIS//,/ }; do
    info="$(abi_info "$abi")" || exit 2
    read -r goarch _ _ <<<"$info"
    suffix="$suffix-$goarch"
  done
  VERSION="$VERSION$suffix"
fi

NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$NDK_BIN" ] || { echo "NDK not found: $ANDROID_NDK_HOME" >&2; exit 1; }

"$HERE/fetch-heart-src.sh" >&2

# 1. libtantivy_go.a per ABI, into tantivy-go/libs/android-<goarch>/ (tantivy-go's rust/Makefile layout)
GOTARGETS=()
for abi in ${ABIS//,/ }; do
  read -r goarch triple clang <<<"$(abi_info "$abi")"
  GOTARGETS+=("android/$goarch")
  lib="$TANTIVY/libs/android-$goarch/libtantivy_go.a"
  if [ -f "$lib" ]; then echo "tantivy: $lib exists" >&2; continue; fi
  rustup target add "$triple" >&2
  cc="$NDK_BIN/${clang}${ANDROID_API}-clang"
  t="${triple//-/_}"; T="$(echo "$t" | tr '[:lower:]' '[:upper:]')"
  echo "tantivy: building $triple" >&2
  (cd "$TANTIVY/rust" && env \
      "CARGO_TARGET_${T}_LINKER=$cc" "CC_${t}=$cc" "AR_${t}=$NDK_BIN/llvm-ar" \
      cargo build --release --target "$triple")
  mkdir -p "$(dirname "$lib")"
  cp "$TANTIVY/rust/target/$triple/release/libtantivy_go.a" "$lib"
done

# 2. Heart's ftsearch package links -ltantivy_go from heart/deps/libs/android-<goarch>
#    (tantivy.mk's download-tantivy-local copies tantivy-go/libs/* there). gomobile bind passes one
#    environment to every target, so a per-ABI CGO_LDFLAGS path cannot be used.
mkdir -p "$HEART/deps/libs"
for t in "${GOTARGETS[@]}"; do
  rm -rf "$HEART/deps/libs/android-${t#android/}"
  cp -r "$TANTIVY/libs/android-${t#android/}" "$HEART/deps/libs/"
done

# 3. gomobile and gobind at the version pinned in heart's go.mod
mkdir -p "$OUT/tools"
(cd "$HEART" && go build -o "$OUT/tools/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)
gomobile init

# 4. gomobile bind (android.mk; govvv replaced by static version stamping)
export CGO_CFLAGS="-Wno-deprecated-non-prototype -Wno-unknown-warning-option -Wno-deprecated-declarations -Wno-xor-used-as-pow -Wno-single-bit-bitfield-constant-conversion"
VCS=github.com/anyproto/anytype-heart/util/vcs
LDFLAGS="-X $VCS.GitSummary=$VERSION -X $VCS.GitBranch=agentbridge -X $VCS.GitCommit=$HEART_VERSION -X $VCS.BuildDate=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Upstream CI (heart's .github/workflows/build.yml) also stamps these two keys. Never echo LDFLAGS.
if [ -n "${UNSPLASH_KEY:-}" ]; then
  LDFLAGS="$LDFLAGS -X github.com/anyproto/anytype-heart/util/unsplash.DefaultToken=$UNSPLASH_KEY"
else
  echo "warning: UNSPLASH_KEY is not set; the Unsplash cover picker will not work" >&2
fi
if [ -n "${INHOUSE_KEY:-}" ]; then
  LDFLAGS="$LDFLAGS -X github.com/anyproto/anytype-heart/metrics.DefaultInHouseKey=$INHOUSE_KEY"
else
  echo "warning: INHOUSE_KEY is not set; in-house analytics stay silent" >&2
fi
TARGET="$(IFS=,; echo "${GOTARGETS[*]}")"
AAR="$OUT/android/lib.aar"
mkdir -p "$OUT/android"
echo "gomobile bind -target=$TARGET" >&2
(cd "$HEART" && gomobile bind -tags "$TAGS" -ldflags "$LDFLAGS" -target="$TARGET" -androidapi "$ANDROID_API" \
    -o "$AAR" github.com/anyproto/anytype-heart/clientlibrary/service github.com/anyproto/anytype-heart/core)

# 5. Publish to Maven Local
"$MVN" -q install:install-file -Dfile="$AAR" -DgroupId="$GROUP_ID" -DartifactId="$ARTIFACT_ID" \
  -Dversion="$VERSION" -Dpackaging=aar -DgeneratePom=true
echo "published: $HOME/.m2/repository/${GROUP_ID//.//}/$ARTIFACT_ID/$VERSION/$ARTIFACT_ID-$VERSION.aar" >&2
if [ -n "$NARROWED" ]; then
  echo "narrowed ABIs: set middlewareVersion = \"$VERSION\" in gradle/libs.versions.toml to use it" >&2
fi

# 6. Proto sync: only the files the repo already has (heart's snapshot.proto is not used by the app)
PROTO="$ROOT/protocol/src/main/proto"
for f in "$HEART"/pb/protos/*.proto "$HEART"/pkg/lib/pb/model/protos/*.proto; do
  if [ -f "$PROTO/$(basename "$f")" ]; then cp "$f" "$PROTO/"; fi
done
# normalize-imports.sh is written for BSD sed (`sed -i '' ...`); on GNU sed, drop the empty suffix.
SHIM=""
if sed --version >/dev/null 2>&1; then
  SHIM="$(mktemp -d)"
  cat > "$SHIM/sed" <<'EOF'
#!/usr/bin/env bash
args=()
while [ $# -gt 0 ]; do
  if [ "$1" = "-i" ] && [ "${2-x}" = "" ]; then args+=("-i"); shift 2; continue; fi
  args+=("$1"); shift
done
exec "$(PATH="${PATH#*:}" command -v sed)" "${args[@]}"
EOF
  chmod +x "$SHIM/sed"
fi
(cd "$ROOT" && PATH="${SHIM:+$SHIM:}$PATH" bash scripts/mw/normalize-imports.sh)
if [ -n "$SHIM" ]; then rm -rf "$SHIM"; fi
echo "done" >&2
