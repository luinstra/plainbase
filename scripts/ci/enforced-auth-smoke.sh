#!/usr/bin/env bash
# Enforced-builtin auth/CSRF smoke — the real-boot half of C1c (ci-runs-auth-off-blind).
#
# Boots the given plainbase launcher ($1: JVM dist or native binary — binary-agnostic) with
# auth.mode=builtin on LOOPBACK and asserts the nine-step auth/CSRF matrix with curl + jq.
#
# Why loopback: a loopback bind with no trusted-proxy CIDRs means secureCookie() is false
# (PlainbaseConfig.secureCookie), so pb_session is NOT `Secure` and a plain curl cookie jar
# (-c/-b) replays it over http. The non-loopback legs (421 transport refusal, proxy mode) need a
# real non-loopback socket peer and live in enforced-auth-docker-smoke.sh.
#
# Usage: enforced-auth-smoke.sh <plainbase-launcher> [port (default 8080)]
# Run from the repo root (needs fixtures/demo-docs). Exits non-zero on the first failed assertion.
set -euo pipefail

for cmd in jq curl awk; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 1; }
done

BIN=$1
PORT=${2:-8080}
BASE="http://127.0.0.1:$PORT"

tmp=$(mktemp -d)
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$tmp"' EXIT

# Isolation: assertion 7's PUT mutates CONTENT_DIR — always a throwaway copy, never the checkout
# (the smoke-server.mjs rationale). No .git in the copy => git auto-detect stays off.
cp -r fixtures/demo-docs "$tmp/content"
mkdir -p "$tmp/data"

export CONTENT_DIR="$tmp/content"
export DATA_DIR="$tmp/data"
export PLAINBASE_AUTH_MODE=builtin
export PLAINBASE_HOST=127.0.0.1
export PLAINBASE_PORT="$PORT"

step=0
pass() { step=$((step + 1)); echo "ok $step - $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
expect_status() { [ "$2" = "$1" ] || fail "$3: expected HTTP $1, got $2"; }
expect_error_code() { # file expected-code desc — pins the frozen envelope path .error.code
  jq -e --arg c "$2" '.error.code == $c' "$1" >/dev/null || fail "$3: expected error code '$2', got: $(cat "$1")"
}

# Both setup-token and mint-token print the plaintext on the line immediately BEFORE the
# "store this now" hint (the smoke-server.mjs parse contract); fail LOUD if the hint is absent.
parse_token() {
  awk '/^store this now/ { print prev; found = 1; exit } NF { prev = $0 }
       END { if (!found) { print "no store-this-now hint in CLI output" > "/dev/stderr"; exit 1 } }'
}

# Seed BEFORE boot: the CLI takes the DataDirLock and refuses against a live server, and each
# invocation releases it on exit before the next acquires it (the smoke-server.mjs precedent).
SETUP_TOKEN=$("$BIN" admin setup-token | parse_token)
[ -n "$SETUP_TOKEN" ] || fail "could not parse setup-token from 'admin setup-token' output"
AGENT_TOKEN=$("$BIN" admin mint-token ci-agent read-only | parse_token)
[ -n "$AGENT_TOKEN" ] || fail "could not parse agent token from 'admin mint-token' output"

"$BIN" serve &
SERVER_PID=$!
ok=""
for _ in $(seq 1 30); do
  if curl -fsS "$BASE/healthz" >/dev/null 2>&1; then ok=1; break; fi
  sleep 1
done
[ "$ok" = "1" ] || fail "server did not become healthy on $BASE"

JAR="$tmp/cookies.txt"

# 1. Anonymous write -> 401 (the audited enforced-mode deny; AuthMatrixTest is the JVM twin).
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/admin/rescan")
expect_status 401 "$code" "anonymous POST /api/v1/admin/rescan"
pass "anonymous write -> 401"

# 2. Bootstrap -> session. The JSON body is built with jq (never string-interpolated) so the
#    token can never produce an invalid literal; 201 sets pb_session + returns csrf_token.
code=$(jq -n --arg t "$SETUP_TOKEN" '{token: $t, username: "ci-admin", password: "ci-pass-123456"}' \
  | curl -s -o "$tmp/setup.json" -w '%{http_code}' -c "$JAR" \
      -H 'Content-Type: application/json' --data-binary @- "$BASE/api/v1/setup/consume")
expect_status 201 "$code" "POST /api/v1/setup/consume"
pass "bootstrap consume -> 201 + session cookie"

# 3. Session state: authenticated builtin session with a CSRF token to use on mutations.
code=$(curl -s -o "$tmp/session.json" -w '%{http_code}' -b "$JAR" "$BASE/api/v1/session")
expect_status 200 "$code" "GET /api/v1/session"
jq -e '.authenticated == true and .auth_mode == "builtin" and .csrf_token != null' "$tmp/session.json" >/dev/null \
  || fail "session state: expected authenticated builtin session with csrf_token, got: $(cat "$tmp/session.json")"
CSRF=$(jq -re '.csrf_token' "$tmp/session.json")
pass "session state: authenticated + auth_mode=builtin + csrf_token"

# 4. Session write WITHOUT X-CSRF-Token -> 403 csrf_failed.
code=$(curl -s -o "$tmp/r4.json" -w '%{http_code}' -X POST -b "$JAR" "$BASE/api/v1/admin/rescan")
expect_status 403 "$code" "session write without CSRF token"
expect_error_code "$tmp/r4.json" csrf_failed "session write without CSRF token"
pass "session write without CSRF token -> 403 csrf_failed"

# 5. WITH the token -> 200 (no Origin header: absent-is-allowed).
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$JAR" -H "X-CSRF-Token: $CSRF" "$BASE/api/v1/admin/rescan")
expect_status 200 "$code" "session write with CSRF token"
pass "session write with CSRF token -> 200"

# 6. Cross-origin -> 403 cross_origin (direct-branch host:port compare).
code=$(curl -s -o "$tmp/r6.json" -w '%{http_code}' -X POST -b "$JAR" \
  -H "X-CSRF-Token: $CSRF" -H "Origin: https://evil.example" "$BASE/api/v1/admin/rescan")
expect_status 403 "$code" "cross-origin session write"
expect_error_code "$tmp/r6.json" cross_origin "cross-origin session write"
pass "cross-origin session write -> 403 cross_origin"

# 7. Real page write (PB-WRITE-1): GET for id + content_hash, then PUT with the strong entity-tag
#    If-Match — literal DOUBLE QUOTES around "sha256:<64-hex>" — and a modified markdown body.
#    The body is the GET's own raw markdown plus an appended line, so any materialized `id:`
#    frontmatter matches (no id-tamper 422).
code=$(curl -s -o "$tmp/page.json" -w '%{http_code}' -b "$JAR" "$BASE/api/v1/pages/by-path/guides/deploy-guide")
expect_status 200 "$code" "GET /api/v1/pages/by-path/guides/deploy-guide (admin session)"
PAGE_ID=$(jq -re '.id' "$tmp/page.json")
CONTENT_HASH=$(jq -re '.content_hash' "$tmp/page.json")
code=$(jq -rj '.markdown + "\nEnforced-smoke edit.\n"' "$tmp/page.json" \
  | curl -s -o "$tmp/put.json" -w '%{http_code}' -X PUT -b "$JAR" \
      -H "X-CSRF-Token: $CSRF" -H 'Content-Type: text/markdown' -H "If-Match: \"$CONTENT_HASH\"" \
      --data-binary @- "$BASE/api/v1/pages/$PAGE_ID")
expect_status 200 "$code" "PUT /api/v1/pages/$PAGE_ID (PB-WRITE-1 save)"
pass "PB-WRITE-1 page write with quoted strong If-Match -> 200"

# 8. Agent bearer read -> 200 (loopback is a secure context; the REAL extraction path).
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $AGENT_TOKEN" \
  "$BASE/api/v1/pages/by-path/guides/deploy-guide")
expect_status 200 "$code" "agent bearer read"
pass "agent bearer read -> 200"

# 9. Revoke via the REST admin route (not the CLI: it would contend for the DataDirLock against
#    the live server, and the REST path adds admin-route enforced coverage for free) -> the same
#    bearer is now 401 end-to-end.
code=$(curl -s -o "$tmp/tokens.json" -w '%{http_code}' -b "$JAR" "$BASE/api/v1/admin/tokens")
expect_status 200 "$code" "GET /api/v1/admin/tokens"
tid=$(jq -re '.tokens[] | select(.label == "ci-agent") | .id' "$tmp/tokens.json")
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$JAR" -H "X-CSRF-Token: $CSRF" \
  "$BASE/api/v1/admin/tokens/$tid/revoke")
case "$code" in 2??) ;; *) fail "POST /api/v1/admin/tokens/$tid/revoke: expected 2xx, got $code" ;; esac
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $AGENT_TOKEN" \
  "$BASE/api/v1/pages/by-path/guides/deploy-guide")
expect_status 401 "$code" "revoked agent bearer read"
pass "REST revoke -> revoked bearer 401"

echo "enforced-auth-smoke: all $step assertions passed"
