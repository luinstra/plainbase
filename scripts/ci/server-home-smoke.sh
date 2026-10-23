#!/usr/bin/env bash
# Server home selection through an absolute JVM/native launcher. No inherited HOME or DATA_DIR.
# Usage: scripts/ci/server-home-smoke.sh /absolute/path/to/plainbase
# JVM distribution launcher: export JAVA_HOME to the intended JDK before invoking this hermetic script.
# Native binary: no JVM or JAVA_HOME is required.
set -euo pipefail
BIN=${1:?absolute launcher path required}
case "$BIN" in
  /*) ;;
  *) echo 'launcher must be absolute' >&2
     exit 1 ;;
esac
[ -x "$BIN" ] || { echo 'launcher must be executable' >&2; exit 1; }

tmp=""
temp_parent=""
CHILD_PID=""
stop_child() {
  if [ -n "$CHILD_PID" ]; then
    kill "$CHILD_PID" 2>/dev/null || true
    for _ in $(seq 1 50); do
      kill -0 "$CHILD_PID" 2>/dev/null || break
      sleep 0.1
    done
    kill -9 "$CHILD_PID" 2>/dev/null || true
    wait "$CHILD_PID" 2>/dev/null || true
    CHILD_PID=""
  fi
}
cleanup() {
  stop_child
  # tmp is assigned only from our mktemp call. Also require its exact owned template and entry kind.
  case "$tmp" in
    "$temp_parent"/plainbase-server-home-smoke.????????)
      if [ -d "$tmp" ]; then
        if [ ! -L "$tmp" ]; then
          rm -rf -- "$tmp"
        fi
      fi
      ;;
  esac
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
temp_parent=$(
  cd "${TMPDIR:-/tmp}" || exit 1
  pwd -P
)
tmp=$(mktemp -d "$temp_parent/plainbase-server-home-smoke.XXXXXXXX")
fail() { echo "FAIL: $1" >&2; exit 1; }

# An owned process and bounded polling avoid platform-specific timeout dependencies.
run_cli() {
  local expected=$1
  shift
  (
    cd "$tmp/cwd"
    exec env -i PATH=/usr/bin:/bin JAVA_HOME="${JAVA_HOME:-}" "$@"
  ) >"$tmp/stdout" 2>"$tmp/stderr" &
  CHILD_PID=$!
  local finished="" code=0
  for _ in $(seq 1 300); do
    if ! kill -0 "$CHILD_PID" 2>/dev/null; then
      finished=1
      break
    fi
    sleep 0.1
  done
  [ "$finished" = 1 ] || fail 'CLI exceeded 30 second deadline'
  wait "$CHILD_PID" || code=$?
  CHILD_PID=""
  if [ "$code" != "$expected" ]; then
    cat "$tmp/stdout" "$tmp/stderr" >&2
    fail "expected exit $expected, got $code"
  fi
}

mkdir -p "$tmp/cwd" "$tmp/home/.plainbase" "$tmp/content" "$tmp/extra"
cat >"$tmp/home/.plainbase/plainbase.conf" <<CONF
git.enabled = false
CONF
cat >"$tmp/home/.plainbase/roots.conf" <<CONF
roots { homecheck { path = "$tmp/extra" } }
CONF
run_cli 0 HOME="$tmp/home" CONTENT_DIR="$tmp/content" "$BIN" root list
grep -Eq '^homecheck .* roots.conf' "$tmp/stdout" || fail 'home roots.conf was not selected'
[ ! -e "$tmp/cwd/data" ] || fail 'root list created CWD data'
echo 'ok 1 - home-config root list'

mkdir "$tmp/cwd/data"
printf '/* legacy theme-only state */\n' >"$tmp/cwd/data/custom.css"
run_cli 1 HOME="$tmp/absent-home" CONTENT_DIR="$tmp/content" "$BIN" root add extra "$tmp/extra"
grep -q 'root: .*Legacy state' "$tmp/stderr" || fail 'missing concise legacy diagnostic'
grep -q 'absolute DATA_DIR' "$tmp/stderr" || fail 'missing explicit override remedy'
grep -Fq "$tmp/absent-home/.plainbase" "$tmp/stderr" || fail 'missing proposed path'
[ ! -e "$tmp/absent-home" ] || fail 'legacy refusal created home state'
[ ! -e "$tmp/cwd/data/roots.lock" ] || fail 'legacy refusal acquired roots lock'
[ ! -e "$tmp/cwd/data/plainbase.db" ] || fail 'legacy refusal created database'
echo 'ok 2 - legacy refusal exits 1 without new state'

run_cli 0 DATA_DIR="$tmp/home/.plainbase" CONTENT_DIR="$tmp/content" "$BIN" root list
grep -Eq '^homecheck .* roots.conf' "$tmp/stdout" || fail 'explicit DATA_DIR without HOME did not select config'
echo 'ok 3 - explicit override works with HOME absent'
echo 'server-home-smoke: all 3 assertions passed'
