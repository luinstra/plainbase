#!/usr/bin/env bash
# Enforced-mode NON-LOOPBACK legs — the transport-refusal + proxy half of C1c.
#
# A published-port connection enters the container from the bridge network's gateway — a REAL
# non-loopback socket peer — so these legs fire what a loopback boot never can: the builtin
# transport-refusal 421, the in-CIDR proto-missing 421, the isSecureContext CIDR+proto admit
# branch, proxy identity extraction, and the trusted-proxy X-Forwarded-Host Origin branch. (No leg
# sends proxy identity from a NON-CIDR peer, so the CIDR-miss refusal is NOT asserted here.) The
# pinned subnet makes the peer's CIDR membership deterministic; the assertions never depend on the
# exact gateway octet (a local Docker variant may present a different in-subnet peer — the /24
# still matches).
#
#   Leg B1 (builtin over the bridge): a credential over the insecure non-loopback transport is
#     refused 421 BEFORE the secret is touched — and PLAINBASE_INSECURE_HTTP does NOT weaken it.
#   Leg B2 (proxy mode): the full proxy path over an in-CIDR peer — proto-missing 421, happy
#     session + Secure pb_proxy_csrf, double-submit CSRF, forwarded-host Origin, wrong-secret.
#
# Residual (documented, not overclaimed): a REAL reverse proxy stamping the headers — curl sends
# byte-identical headers, so no server code path stays unexercised; the deploy/proxy/ Caddy stack
# itself remains a manual/compose-tier check.
#
# Usage: enforced-auth-docker-smoke.sh [image-tag (default plainbase:ci)]
# Run from the repo root (needs fixtures/demo-docs). Exits non-zero on the first failed assertion.
set -euo pipefail

for cmd in jq curl openssl docker; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 1; }
done

IMAGE=${1:-plainbase:ci}
# Per-run unique names (like the volume) so a prior run that crashed before its trap fired can
# never collide with this one.
NETWORK="pb-enforced-$$"
B1_CONTAINER="pb-builtin-$$"
B2_CONTAINER="pb-proxy-$$"
SUBNET=172.28.0.0/24
PB_DATA_VOL="pb-proxy-data-$$"
B1_BASE=http://127.0.0.1:8081
B2_BASE=http://127.0.0.1:8082

tmp=$(mktemp -d)
cleanup() {
  docker rm -f "$B1_CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$B2_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  docker volume rm "$PB_DATA_VOL" >/dev/null 2>&1 || true
  rm -rf "$tmp" || true
}
trap cleanup EXIT

step=0
pass() { step=$((step + 1)); echo "ok $step - $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
expect_status() { [ "$2" = "$1" ] || fail "$3: expected HTTP $1, got $2"; }
expect_error_code() { # file expected-code desc — pins the frozen envelope path .error.code
  jq -e --arg c "$2" '.error.code == $c' "$1" >/dev/null || fail "$3: expected error code '$2', got: $(cat "$1")"
}
wait_healthy() { # base-url
  local up=""
  for _ in $(seq 1 30); do
    if curl -fsS "$1/healthz" >/dev/null 2>&1; then up=1; break; fi
    sleep 2
  done
  [ "$up" = "1" ] || fail "server did not become healthy on $1"
}

# Idempotent network create: a leftover network from a failed prior run must not abort the script.
docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create --subnet "$SUBNET" "$NETWORK" >/dev/null

# Content isolation: throwaway fixture copies under the teardown-owned mktemp root (B2 WRITES).
cp -r fixtures/demo-docs "$tmp/content-b1"
cp -r fixtures/demo-docs "$tmp/content-b2"

# ---- Leg B1: builtin over the bridge — the transport refusal is REAL ----
docker run -d --name "$B1_CONTAINER" --network "$NETWORK" -p 127.0.0.1:8081:8080 \
  -e PLAINBASE_AUTH_MODE=builtin -e PLAINBASE_HOST=0.0.0.0 -e PLAINBASE_INSECURE_HTTP=true \
  -v "$tmp/content-b1":/content "$IMAGE" >/dev/null
wait_healthy "$B1_BASE"

# B1-1. No credential -> served (the gate never fires without a credential).
code=$(curl -s -o /dev/null -w '%{http_code}' "$B1_BASE/healthz")
expect_status 200 "$code" "B1 anonymous /healthz"
curl -fsS "$B1_BASE/" | grep -q '<div id="root">' || fail "B1 anonymous / : SPA shell not served"
pass "B1 anonymous healthz + SPA shell -> 200"

# B1-2. A credential over the insecure non-loopback transport -> 421 transport_insecure. A
#       syntactically pb_-prefixed garbage bearer suffices: the refusal fires BEFORE any lookup
#       (the secret is never touched), and INSECURE_HTTP does not weaken the credential gate.
code=$(curl -s -o "$tmp/b1-2.json" -w '%{http_code}' -H 'Authorization: Bearer pb_deadbeef_bogus' "$B1_BASE/api/v1/session")
expect_status 421 "$code" "B1 credential over insecure non-loopback transport"
expect_error_code "$tmp/b1-2.json" transport_insecure "B1 credential over insecure non-loopback transport"
pass "B1 credential over insecure bridge transport -> 421 transport_insecure"

# ---- Leg B2: proxy mode — the full path over an in-CIDR peer ----
SECRET=$(openssl rand -hex 32) # minted per run; never echoed
proxy_env=(-e PLAINBASE_AUTH_MODE=proxy -e PLAINBASE_HOST=0.0.0.0
  -e PLAINBASE_TRUSTED_PROXY="$SUBNET" -e PLAINBASE_PROXY_SECRET="$SECRET")

# Seed BEFORE boot (DataDirLock): grant-role is the proxy first-admin seam; the ENTRYPOINT is the
# launcher, so trailing args dispatch subcommands. The named volume carries the seeded DB to serve.
docker run --rm -v "$PB_DATA_VOL":/data "${proxy_env[@]}" "$IMAGE" admin grant-role proxy alice admin >/dev/null

docker run -d --name "$B2_CONTAINER" --network "$NETWORK" -p 127.0.0.1:8082:8080 \
  -v "$PB_DATA_VOL":/data -v "$tmp/content-b2":/content "${proxy_env[@]}" "$IMAGE" >/dev/null
wait_healthy "$B2_BASE"

# The identity trio all subsequent happy-path requests carry (user + secret + proto https).
identity=(-H 'X-Forwarded-User: alice' -H "X-Plainbase-Proxy-Secret: $SECRET")
trio=("${identity[@]}" -H 'X-Forwarded-Proto: https')

# B2-1. Identity headers WITHOUT X-Forwarded-Proto -> 421 (in-CIDR peer, proto not https).
code=$(curl -s -o "$tmp/b2-1.json" -w '%{http_code}' "${identity[@]}" "$B2_BASE/api/v1/session")
expect_status 421 "$code" "B2 proxy identity without X-Forwarded-Proto"
expect_error_code "$tmp/b2-1.json" transport_insecure "B2 proxy identity without X-Forwarded-Proto"
pass "B2 proto-missing -> 421 transport_insecure"

# B2-2. Happy session: authenticated proxy session + a Secure pb_proxy_csrf cookie. Parse the
#       Set-Cookie from -D headers — a Secure cookie won't replay from a curl jar over http, so
#       later requests send it manually.
code=$(curl -s -o "$tmp/b2-2.json" -w '%{http_code}' -D "$tmp/b2-2.headers" "${trio[@]}" "$B2_BASE/api/v1/session")
expect_status 200 "$code" "B2 proxy session"
jq -e '.authenticated == true and .auth_mode == "proxy" and .csrf_token != null' "$tmp/b2-2.json" >/dev/null \
  || fail "B2 proxy session state: got $(cat "$tmp/b2-2.json")"
grep -i '^set-cookie: pb_proxy_csrf=' "$tmp/b2-2.headers" | grep -qi 'secure' \
  || fail "B2 proxy session: pb_proxy_csrf Set-Cookie missing or not Secure: $(cat "$tmp/b2-2.headers")"
T=$(jq -re '.csrf_token' "$tmp/b2-2.json")
pass "B2 happy proxy session -> 200 + Secure pb_proxy_csrf"

# B2-3. Write WITHOUT the double-submit -> 403 csrf_failed (alice is admin; the GET is 200).
code=$(curl -s -o "$tmp/page.json" -w '%{http_code}' "${trio[@]}" "$B2_BASE/api/v1/pages/by-path/docs/guides/deploy-guide")
expect_status 200 "$code" "B2 GET by-path (proxy admin)"
PAGE_ID=$(jq -re '.id' "$tmp/page.json")
CONTENT_HASH=$(jq -re '.content_hash' "$tmp/page.json")
code=$(jq -rj '.markdown + "\nProxy-smoke edit.\n"' "$tmp/page.json" \
  | curl -s -o "$tmp/b2-3.json" -w '%{http_code}' -X PUT "${trio[@]}" \
      -H 'Content-Type: text/markdown' -H "If-Match: \"$CONTENT_HASH\"" \
      --data-binary @- "$B2_BASE/api/v1/pages/$PAGE_ID")
expect_status 403 "$code" "B2 PUT without double-submit"
expect_error_code "$tmp/b2-3.json" csrf_failed "B2 PUT without double-submit"
pass "B2 write without double-submit -> 403 csrf_failed"

# B2-4. Write WITH the double-submit + a forwarded-host-matched external Origin -> 200. This
#       fires the trusted-proxy Origin branch for real: forwardedHost present + peer in CIDR.
code=$(jq -rj '.markdown + "\nProxy-smoke edit.\n"' "$tmp/page.json" \
  | curl -s -o "$tmp/b2-4.json" -w '%{http_code}' -X PUT "${trio[@]}" \
      -H "Cookie: pb_proxy_csrf=$T" -H "X-CSRF-Token: $T" \
      -H 'X-Forwarded-Host: docs.example.com' -H 'Origin: https://docs.example.com' \
      -H 'Content-Type: text/markdown' -H "If-Match: \"$CONTENT_HASH\"" \
      --data-binary @- "$B2_BASE/api/v1/pages/$PAGE_ID")
expect_status 200 "$code" "B2 PUT with double-submit + forwarded-host Origin"
pass "B2 write with double-submit + X-Forwarded-Host-matched Origin -> 200"

# B2-5. Cross-origin -> 403 cross_origin (fresh If-Match from a re-GET — B2-4 changed the hash).
code=$(curl -s -o "$tmp/page2.json" -w '%{http_code}' "${trio[@]}" "$B2_BASE/api/v1/pages/by-path/docs/guides/deploy-guide")
expect_status 200 "$code" "B2 re-GET by-path"
CONTENT_HASH=$(jq -re '.content_hash' "$tmp/page2.json")
code=$(jq -rj '.markdown + "\nEvil edit.\n"' "$tmp/page2.json" \
  | curl -s -o "$tmp/b2-5.json" -w '%{http_code}' -X PUT "${trio[@]}" \
      -H "Cookie: pb_proxy_csrf=$T" -H "X-CSRF-Token: $T" \
      -H 'X-Forwarded-Host: docs.example.com' -H 'Origin: https://evil.example' \
      -H 'Content-Type: text/markdown' -H "If-Match: \"$CONTENT_HASH\"" \
      --data-binary @- "$B2_BASE/api/v1/pages/$PAGE_ID")
expect_status 403 "$code" "B2 PUT with evil Origin"
expect_error_code "$tmp/b2-5.json" cross_origin "B2 PUT with evil Origin"
pass "B2 cross-origin write -> 403 cross_origin"

# B2-6. Wrong secret -> anonymous, never an oracle: the session says unauthenticated (200, not an
#       error that would confirm the header name), and a write is a plain 401.
wrong=(-H 'X-Forwarded-User: alice' -H 'X-Plainbase-Proxy-Secret: wrong-secret' -H 'X-Forwarded-Proto: https')
code=$(curl -s -o "$tmp/b2-6.json" -w '%{http_code}' "${wrong[@]}" "$B2_BASE/api/v1/session")
expect_status 200 "$code" "B2 session with wrong secret"
jq -e '.authenticated == false' "$tmp/b2-6.json" >/dev/null || fail "B2 wrong secret: expected anonymous, got $(cat "$tmp/b2-6.json")"
code=$(printf 'x' | curl -s -o /dev/null -w '%{http_code}' -X PUT "${wrong[@]}" \
  -H 'Content-Type: text/markdown' -H "If-Match: \"$CONTENT_HASH\"" \
  --data-binary @- "$B2_BASE/api/v1/pages/$PAGE_ID")
expect_status 401 "$code" "B2 PUT with wrong secret"
pass "B2 wrong secret -> anonymous session + 401 write"

echo "enforced-auth-docker-smoke: all $step assertions passed"
