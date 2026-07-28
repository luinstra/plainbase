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

# One boot, with stdout AND stderr captured to $1: operational logs and CLI refusals both use stderr
# locally, while command results use stdout. Merging both keeps this smoke independent of presentation.
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
# Isolation: adopt --write-ids MUTATES the trees, so docs is always a throwaway copy of the
# fixtures, never the checkout (the enforced-auth-smoke rationale). No .git in the copy.
cp -r fixtures/demo-docs "$tmp/docs"
mkdir -p "$tmp/hand/notes" "$tmp/extra/notes" "$tmp/refused" "$tmp/data"
printf '# Hand Note\n\nDeclared in plainbase.conf.\n' >"$tmp/hand/notes/hand-note.md"
printf '# Extra Note\n\nAdded by `plainbase root add`.\n' >"$tmp/extra/notes/extra-note.md"
printf '# Refused\n\nA real tree for the refused-add rows, so the refusal is the only reason they fail.\n' >"$tmp/refused/note.md"

# `docs` MUST be declared here: a PRESENT roots {} block that omits it is a boot refusal.
cat >"$tmp/data/plainbase.conf" <<CONF
roots {
  docs { path = "$tmp/docs" }
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
for row in "docs .* plainbase.conf" "hand .* plainbase.conf" "extra .* roots.conf"; do
  grep -Eq "^$row" "$tmp/list.out" || fail "root list: no '$row' row in:
$(cat "$tmp/list.out")"
done
pass "root list -> 3 roots, provenance plainbase.conf / plainbase.conf / roots.conf"

# --- 4. All three roots serve. ------------------------------------------------------------------
boot "$tmp/serve-1.log"
curl -fsS "$BASE/healthz" -o "$tmp/health.json"
jq -e '(.roots | length) == 3 and all(.roots[]; .available)' "$tmp/health.json" >/dev/null \
  || fail "healthz: expected 3 available roots, got: $(cat "$tmp/health.json")"
# The API read is what proves a root is SERVING: /{root}/... serves the SPA shell for any path
# (routing matrix), so a 200 there says nothing about the content tree behind it.
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/docs/guides/deploy-guide")" "by-path read in docs"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/hand/notes/hand-note")" "by-path read in hand"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/extra/notes/extra-note" "$tmp/extra.json")" "by-path read in extra"
# Cross-root resolution: the root segment selects the tree, so the SAME grammar answers from a root
# the operator declared and one the CLI added, each with its own canonical root-qualified url.
jq -e '.url == "/extra/notes/extra-note"' "$tmp/extra.json" >/dev/null \
  || fail "cross-root by-path: expected extra's canonical url, got: $(cat "$tmp/extra.json")"
for root in docs hand extra; do
  expect_status 200 "$(status_of "$BASE/$root/notes")" "/$root shell"
done
stop
pass "3 roots available; by-path reads resolve in each; cross-root url is root-qualified"

# --- 5. The refusals are part of the contract, so smoke them. -----------------------------------
expect_exit 1 "root remove hand (declared in plainbase.conf)" "$BIN" root remove hand
grep -q "declared in plainbase.conf" "$tmp/cli.err" || fail "root remove hand: refusal did not name plainbase.conf"
expect_exit 2 "root remove docs (never CLI-managed)" "$BIN" root remove docs
# `api` is a reserved top-level segment: Plainbase owns that URL space, so no root may take the name.
# A bad ARGUMENT, so exit 2 - refused before the lock and before anything reads the path.
expect_exit 2 "root add api (reserved segment)" "$BIN" root add api "$tmp/refused"
grep -q "reserved segment" "$tmp/cli.err" || fail "root add api: refusal did not name the reserved segment"
[ -f "$tmp/data/roots.conf" ] || fail "root add api: a REFUSED add must not delete roots.conf"
pass "refusals: remove hand -> 1, remove docs -> 2, reserved-segment add -> 2"

# --- 6. An unavailable root answers 503, NOT 404 - the distinction is the whole point. -----------
mv "$tmp/extra" "$tmp/extra-away"
boot "$tmp/serve-2.log"
curl -fsS "$BASE/healthz" -o "$tmp/health.json"
jq -e '.roots[] | select(.root == "extra") | .available == false and .reason == "missing_at_boot"' "$tmp/health.json" >/dev/null \
  || fail "healthz: expected extra unavailable/missing_at_boot, got: $(cat "$tmp/health.json")"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/docs/guides/deploy-guide")" "docs read while extra is down"
expect_status 503 "$(status_of "$BASE/api/v1/pages/by-path/extra/notes/extra-note" "$tmp/down.json")" "read into the missing root"
jq -e '.error.code == "root_unavailable"' "$tmp/down.json" >/dev/null \
  || fail "read into the missing root: expected root_unavailable, got: $(cat "$tmp/down.json")"
# The BROWSER surface answers with the shell, at the landing URL and at a canonical page URL alike: a
# bookmark or a refresh into a down root must render the SPA's outage view (which it draws from the tree's
# available:false), never a 503 JSON body as literal text in the tab. The honest 503 lives on the API above,
# which is where the SPA and the agents read it. CI boots auth.mode=off, so this is the authorized arm.
expect_status 200 "$(status_of "$BASE/extra")" "/{down-root} landing shell"
expect_status 200 "$(status_of "$BASE/extra/notes/extra-note")" "/{down-root}/{path} page shell"
stop
pass "missing root -> healthz available:false/missing_at_boot, API read -> 503 root_unavailable, /{root} -> shell, docs still serves"

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
expect_status 404 "$(status_of "$BASE/extra/notes/extra-note")" "removed root -> 404"
expect_status 200 "$(status_of "$BASE/api/v1/pages/by-path/docs/guides/deploy-guide")" "docs read after the detach"
stop
pass "detached root: the server BOOTS, WARNs by name, and keeps serving the survivors"

# --- 8. The two files, after all of it. ---------------------------------------------------------
[ ! -e "$tmp/data/roots.conf" ] || fail "roots.conf held only 'extra' - removing it must DELETE the file"
cmp -s "$tmp/data/plainbase.conf" "$tmp/plainbase.conf.orig" \
  || fail "plainbase.conf changed - \`plainbase root\` never writes the operator's file"
pass "roots.conf deleted with its last root; plainbase.conf byte-identical"

echo "multi-root-smoke: all $step assertions passed"
