#!/usr/bin/env bash
# Fetches anytype-heart and tantivy-go from the Go module proxy into build/agent-bridge/src/.
#   fetch-heart-src.sh              -> src/heart (patched with heart-mobile-api.patch), src/tantivy-go
#   fetch-heart-src.sh --unpatched  -> src/heart-unpatched (pristine), src/tantivy-go
# Idempotent: an existing, complete directory is left alone.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$HERE/../.." && pwd)/build/agent-bridge"
HEART_VERSION=v0.51.6
TANTIVY_VERSION=v1.0.6
PROXY=https://proxy.golang.org/github.com/anyproto

PATCHED=1
[ "${1:-}" = "--unpatched" ] && PATCHED=0

DL="$OUT/dl"; SRC="$OUT/src"
mkdir -p "$DL" "$SRC"

fetch() { # name module version dest-dir-name
  local zip="$DL/$1-$3.zip" dest="$SRC/$4" tmp
  [ -f "$dest/.fetched" ] && return 0
  [ -s "$zip" ] || curl -fL --retry 3 -o "$zip" "$PROXY/$2/@v/$3.zip"
  tmp="$(mktemp -d "$SRC/.unzip.XXXXXX")"
  unzip -q "$zip" -d "$tmp"
  rm -rf "$dest"
  mv "$tmp/github.com/anyproto/$2@$3" "$dest"
  rm -rf "$tmp"
  chmod -R u+w "$dest"
  touch "$dest/.fetched"
}

fetch tantivy-go tantivy-go "$TANTIVY_VERSION" tantivy-go
# rustup would otherwise try to install every cross-compilation target listed in this file
TC="$SRC/tantivy-go/rust/rust-toolchain.toml"
[ -f "$TC" ] && mv "$TC" "$TC.orig"

if [ "$PATCHED" = 1 ]; then
  if [ ! -f "$SRC/heart/.patched" ]; then
    rm -rf "$SRC/heart"
    fetch anytype-heart anytype-heart "$HEART_VERSION" heart
    (cd "$SRC/heart" && patch -p1 < "$HERE/heart-mobile-api.patch")
    touch "$SRC/heart/.patched"
  fi
  echo "heart (patched): $SRC/heart"
else
  fetch anytype-heart anytype-heart "$HEART_VERSION" heart-unpatched
  echo "heart (unpatched): $SRC/heart-unpatched"
fi
echo "tantivy-go: $SRC/tantivy-go"
