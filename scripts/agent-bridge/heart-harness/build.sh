#!/usr/bin/env bash
# Builds the native heart harness: build/agent-bridge/bin/harness (patched heart), or with
# --unpatched bin/harness-unpatched (pristine heart, used to demonstrate the mobile-API bug).
# Fetches the sources and builds libtantivy_go.a (cargo, ~4 min) when missing.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$HERE/../../.." && pwd)/build/agent-bridge"
export PATH="$HOME/.cargo/bin:$PATH" GOTOOLCHAIN=auto CGO_ENABLED=1

UNPATCHED=0; HEART=heart; BIN=harness
if [ "${1:-}" = "--unpatched" ]; then UNPATCHED=1; HEART=heart-unpatched; BIN=harness-unpatched; fi

"$HERE/../fetch-heart-src.sh" $([ "$UNPATCHED" = 1 ] && echo --unpatched) >&2

LIB="$OUT/src/tantivy-go/libs/linux-amd64-musl/libtantivy_go.a"
if [ ! -f "$LIB" ]; then
  (cd "$OUT/src/tantivy-go/rust" && cargo build --release)
  mkdir -p "$(dirname "$LIB")"
  cp "$OUT/src/tantivy-go/rust/target/release/libtantivy_go.a" "$LIB"
fi

export CGO_CFLAGS="-Wno-deprecated-non-prototype -Wno-unknown-warning-option -Wno-deprecated-declarations -Wno-xor-used-as-pow -Wno-single-bit-bitfield-constant-conversion"
export CGO_LDFLAGS="-L$(dirname "$LIB")"
mkdir -p "$OUT/bin"

MODARGS=()
if [ "$UNPATCHED" = 1 ]; then
  # same go.mod/go.sum, with the heart replace pointing at the pristine copy
  MF="$OUT/go-unpatched.mod"
  sed "s#src/heart\$#src/$HEART#" "$HERE/go.mod" > "$MF"
  cp "$HERE/go.sum" "$OUT/go-unpatched.sum"
  MODARGS=(-modfile="$MF")
fi
(cd "$HERE" && go build "${MODARGS[@]}" -tags "nosigar nowatchdog" -o "$OUT/bin/$BIN" .)
echo "built $OUT/bin/$BIN" >&2
