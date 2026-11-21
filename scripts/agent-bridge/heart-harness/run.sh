#!/usr/bin/env bash
# Starts real anytype-heart in-process with a fresh local-only account.
# stdout: BASE_URL=, API_KEY=, APP_HASH=, CONTROL_URL=, SPACE_ID=, READY (preceded by ROOT_DIR= and
# *_RESPONSE json lines). Heart logs go to stderr. Runs until SIGINT/SIGTERM.
# Usage: run.sh [--unpatched] [-root DIR] [-app-name NAME]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$HERE/../../.." && pwd)/build/agent-bridge"
BIN=harness; BARG=()
if [ "${1:-}" = "--unpatched" ]; then BIN=harness-unpatched; BARG=(--unpatched); shift; fi
[ -x "$OUT/bin/$BIN" ] || "$HERE/build.sh" "${BARG[@]}" >&2
exec "$OUT/bin/$BIN" "$@"
