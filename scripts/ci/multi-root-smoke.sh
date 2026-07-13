#!/usr/bin/env bash
# Multi-root topology smoke - the real-boot half of the multi-root feature.
#
# Boots the given plainbase launcher ($1: JVM dist or native binary - binary-agnostic) against a
# THREE-root topology whose roots come from BOTH provenances (the operator's `plainbase.conf` and
# the CLI-managed `roots.conf`), and asserts the whole topology contract end to end: serving,
# per-root health, the `plainbase root` refusals, an unavailable root's 503 (never a 404), and the
# DETACHED-root WARN boot - the case this script exists for, because it is the one that proves a
# dropped root DEGRADES the server instead of exploding it.
#
# Why a shell script and not Playwright: this is a boot-log plus boot-SURVIVAL assertion across
# FOUR boots with config edits between them, and none of that is a browser concern.
#
# Usage: multi-root-smoke.sh <plainbase-launcher> [port (default 8080)]
# Run from the repo root (needs fixtures/demo-docs). Exits non-zero on the first failed assertion.
set -euo pipefail

for cmd in jq curl cmp; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 1; }
done

BIN=$1
PORT=${2:-8080}
BASE="http://127.0.0.1:$PORT"

tmp=$(mktemp -d)
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$tmp"' EXIT

step=0
pass() { step=$((step + 1)); echo "ok $step - $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
expect_status() { [ "$2" = "$1" ] || fail "$3: expected HTTP $1, got $2"; }
status_of() { curl -s -o "${2:-/dev/null}" -w '%{http_code}' "$1"; }

# Exit codes ARE the CLI contract (0 success / 1 runtime failure / 2 usage error), so every `root`
# invocation is asserted on its code. `set -e` would kill the script on the refusals we are here to
# smoke, hence the `|| code=$?` capture.
expect_exit() {
  local expected=$1 desc=$2
  shift 2
  local code=0
  "$@" >"$tmp/cli.out" 2>"$tmp/cli.err" || code=$?
  [ "$code" = "$expected" ] || fail "$desc: expected exit $expected, got $code
--- stdout ---
$(cat "$tmp/cli.out")
--- stderr ---
$(cat "$tmp/cli.err")"
}

# One boot, with stdout AND stderr captured to $1: the detached-root WARN rides the logging facade
# (logback's ConsoleAppender, i.e. stdout) while the CLI's refusals go to stderr, and a smoke that
# greps only one of the two would silently stop seeing the other.
boot() {
  "$BIN" serve >"$1" 2>&1 &
  SERVER_PID=$!
  local ok=""
  for _ in $(seq 1 30); do
    if curl -fsS "$BASE/healthz" >/dev/null 2>&1; then ok=1; break; fi
    kill -0 "$SERVER_PID" 2>/dev/null || fail "server exited during boot; log:
$(cat "$1")"
    sleep 1
  done
  [ "$ok" = "1" ] || fail "server did not become healthy on $BASE; log:
$(cat "$1")"
}

# WAIT for the exit, never just signal it: the next CLI command takes the DATA_DIR lock, and a
# half-dead server still holds it.
stop() {
  [ -n "$SERVER_PID" ] || return 0
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
}

# --- 1. Three content trees + a DATA_DIR, and the OPERATOR's two-root plainbase.conf. -----------
# Isolation: adopt --write-ids MUTATES the trees, so main is always a throwaway copy of the
# fixtures, never the checkout (the enforced-auth-smoke rationale). No .git in the copy.
cp -r fixtures/demo-docs "$tmp/main"
mkdir -p "$tmp/hand/notes" "$tmp/extra/notes" "$tmp/shadow" "$tmp/data"
printf '# Hand Note\n\nDeclared in plainbase.conf.\n' >"$tmp/hand/notes/hand-note.md"
printf '# Extra Note\n\nAdded by `plainbase root add`.\n' >"$tmp/extra/notes/extra-note.md"
printf '# Shadow\n\nA tree for the shadowing-name refusal.\n' >"$tmp/shadow/note.md"

# `main` MUST be declared here: a PRESENT roots {} block that omits it is a boot refusal.
cat >"$tmp/data/plainbase.conf" <<CONF
roots {
  main { path = "$tmp/main" }
  hand { path = "$tmp/hand" }
}
CONF
cp "$tmp/data/plainbase.conf" "$tmp/plainbase.conf.orig"

export DATA_DIR="$tmp/data"
export PLAINBASE_HOST=127.0.0.1
export PLAINBASE_PORT="$PORT"

# --- 2. The CLI-managed extra: the OTHER provenance, and the one the later steps remove. --------
expect_exit 0 "root add extra" "$BIN" root add extra "$tmp/extra"
[ -f "$tmp/data/roots.conf" ] || fail "root add extra: DATA_DIR/roots.conf was not written"
pass "root add extra -> exit 0 + roots.conf written"

# --- 3. Both provenances, reported. -------------------------------------------------------------
"$BIN" root list >"$tmp/list.out" 2>&1 || fail "root list: expected exit 0"
for row in "main .* plainbase.conf" "hand .* plainbase.conf" "extra .* roots.conf"; do
  grep -Eq "^$row" "$tmp/list.out" || fail "root list: no '$row' row in:
$(cat "$tmp/list.out")"
done
pass "root list -> 3 roots, provenance plainbase.conf / plainbase.conf / roots.conf"

# --- 4. All three roots serve. ------------------------------------------------------------------
boot "$tmp/serve-1.log"
curl -fsS "$BASE/healthz" -o "$tmp/health.json"
jq -e '(.roots | length) == 3 and all(.roots[]; .available)' "$tmp/health.json" >/dev/null \
  || fail "healthz: expected 3 available roots, got: $(cat "$tmp/health.json")"
# The API read is what proves a root is SERVING: /docs/{root}/... serves the SPA shell for any path
# (routing matrix), so a 200 there says nothing about the content tree behind it.
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/main/guides/deploy-guide")" "by-path read in main"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/hand/notes/hand-note")" "by-path read in hand"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/extra/notes/extra-note" "$tmp/extra.json")" "by-path read in extra"
# Cross-root resolution: the root segment selects the tree, so the SAME grammar answers from a root
# the operator declared and one the CLI added, each with its own canonical root-qualified url.
jq -e '.url == "/docs/extra/notes/extra-note"' "$tmp/extra.json" >/dev/null \
  || fail "cross-root by-path: expected extra's canonical url, got: $(cat "$tmp/extra.json")"
for root in main hand extra; do
  expect_status 200 "$(status_of "$BASE/docs/$root/notes")" "/docs/$root shell"
done
stop
pass "3 roots available; by-path reads resolve in each; cross-root url is root-qualified"

# --- 5. The refusals are part of the contract, so smoke them. -----------------------------------
expect_exit 1 "root remove hand (declared in plainbase.conf)" "$BIN" root remove hand
grep -q "declared in plainbase.conf" "$tmp/cli.err" || fail "root remove hand: refusal did not name plainbase.conf"
expect_exit 2 "root remove main (never CLI-managed)" "$BIN" root remove main
# `guides` is a top-level segment of main (fixtures/demo-docs/guides): adding it would re-point every
# circulating /docs/guides/... link into the NEW root.
expect_exit 1 "root add guides (shadows a main segment)" "$BIN" root add guides "$tmp/shadow"
[ -f "$tmp/data/roots.conf" ] || fail "root add guides: a REFUSED add must not delete roots.conf"
expect_exit 0 "root add guides --force" "$BIN" root add guides "$tmp/shadow" --force
expect_exit 0 "root remove guides" "$BIN" root remove guides
pass "refusals: remove hand -> 1, remove main -> 2, shadowing add -> 1, --force -> 0"

# --- 6. An unavailable root answers 503, NOT 404 - the distinction is the whole point. -----------
mv "$tmp/extra" "$tmp/extra-away"
boot "$tmp/serve-2.log"
curl -fsS "$BASE/healthz" -o "$tmp/health.json"
jq -e '.roots[] | select(.root == "extra") | .available == false and .reason == "missing_at_boot"' "$tmp/health.json" >/dev/null \
  || fail "healthz: expected extra unavailable/missing_at_boot, got: $(cat "$tmp/health.json")"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/main/guides/deploy-guide")" "main read while extra is down"
expect_status 503 "$(status_of "$BASE/api/v1/pages/by-path/extra/notes/extra-note" "$tmp/down.json")" "read into the missing root"
jq -e '.error.code == "root_unavailable"' "$tmp/down.json" >/dev/null \
  || fail "read into the missing root: expected root_unavailable, got: $(cat "$tmp/down.json")"
stop
pass "missing root -> healthz available:false/missing_at_boot, read -> 503 root_unavailable, main still serves"

# --- 7. The detached WARN boot: a dropped root DEGRADES, it does not explode. --------------------
mv "$tmp/extra-away" "$tmp/extra"
expect_exit 0 "adopt --write-ids" "$BIN" adopt --write-ids
expect_exit 0 "root remove extra (the CLI-managed one)" "$BIN" root remove extra
boot "$tmp/serve-3.log"
grep -q "id_map holds page bindings under root(s) absent from roots{}" "$tmp/serve-3.log" \
  || fail "detached boot: no detached-root WARN in the server log:
$(cat "$tmp/serve-3.log")"
jq -e '(.roots | length) == 2 and all(.roots[]; .available)' <(curl -fsS "$BASE/healthz") >/dev/null \
  || fail "detached boot: expected the 2 surviving roots, both available"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/main/guides/deploy-guide")" "main read after the detach"
stop
pass "detached root: the server BOOTS, WARNs by name, and keeps serving the survivors"

# --- 8. The two files, after all of it. ---------------------------------------------------------
[ ! -e "$tmp/data/roots.conf" ] || fail "roots.conf held only 'extra' - removing it must DELETE the file"
cmp -s "$tmp/data/plainbase.conf" "$tmp/plainbase.conf.orig" \
  || fail "plainbase.conf changed - \`plainbase root\` never writes the operator's file"
pass "roots.conf deleted with its last root; plainbase.conf byte-identical"

echo "multi-root-smoke: all $step assertions passed"
