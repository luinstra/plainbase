#!/usr/bin/env bash
# Native startup bound — master criterion: exec-to-first-200-/healthz median < 1 s (C2 item 3).
#
# Boots the given plainbase launcher 5 times against a fresh DATA_DIR per run and gates on the
# MEDIAN wall time (the shell dialect of the R14 warmup/percentile discipline — one noisy run
# cannot fail it). This measures process start + Koin + DB open/migrate + the startup rebuild of
# fixtures/demo-docs + the poll granularity itself — NOT the README's ~3 ms first-handler figure,
# and NOT the 1 s-granularity health smoke in ci.yml (which cannot assert this bound).
#
# Usage: native-startup-bound.sh <plainbase-launcher> [port (default 8082)] [budget-ms (default 1000)]
# Run from the repo root (needs fixtures/demo-docs). Exits non-zero when the median breaches the budget.
set -euo pipefail

for cmd in curl perl; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 1; }
done

BIN=${1:-}
[ -x "$BIN" ] || { echo "launcher not found or not executable: '$BIN'" >&2; exit 1; }
PORT=${2:-8082}
BUDGET=${3:-1000}
BASE="http://127.0.0.1:$PORT"

tmp=$(mktemp -d)
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$tmp"' EXIT

# Isolation: a throwaway content copy keeps the checkout's ancestor .git away from git auto-detect
# (the enforced-auth-smoke.sh rationale), so every run boots the same deterministic shape.
cp -r fixtures/demo-docs "$tmp/content"

export CONTENT_DIR="$tmp/content"
export PLAINBASE_HOST=127.0.0.1
export PLAINBASE_PORT="$PORT"

# Millisecond clock portable across ubuntu-CI and macOS dev machines (macOS date has no %N and
# macOS bash is 3.2, so no $EPOCHREALTIME) — hence perl in the tool preflight above.
now_ms() { perl -MTime::HiRes=time -e 'printf "%d", time()*1000'; }

runs=()
for i in 1 2 3 4 5; do
  # A fresh DATA_DIR per run: first-boot DB open/migrate every time, no warm-state flattery.
  rm -rf "$tmp/data"
  mkdir -p "$tmp/data"
  export DATA_DIR="$tmp/data"

  t0=$(now_ms)
  "$BIN" serve &
  SERVER_PID=$!
  deadline=$((t0 + 10000)) # absolute per-run poll deadline: a hung boot must not spin forever
  elapsed=""
  while :; do
    if curl -fsS "$BASE/healthz" >/dev/null 2>&1; then
      elapsed=$(($(now_ms) - t0))
      break
    fi
    [ "$(now_ms)" -lt "$deadline" ] || { echo "run $i: server not healthy within 10 s" >&2; exit 1; }
    sleep 0.02
  done

  # Strict inter-run sequencing: kill, then wait — the process must be CONFIRMED exited BEFORE
  # $tmp/data is wiped and the SAME port re-bound (wiping a live DB under a still-dying process,
  # or racing the next bind against a lingering listener, is exactly the inter-run flake).
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  runs+=("$elapsed")
done

median=$(printf '%s\n' "${runs[@]}" | sort -n | sed -n '3p')
echo "startup-perf: runs ${runs[*]} ms; median $median ms (budget ${BUDGET} ms)"
[ "$median" -lt "$BUDGET" ] || { echo "FAIL: median startup ${median} ms >= budget ${BUDGET} ms" >&2; exit 1; }
