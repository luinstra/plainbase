# Reverse-proxy SSO (`auth.mode=proxy`)

Plainbase's proxy mode trusts a reverse proxy in front of it to authenticate users (via your
IdP — OIDC/SAML through oauth2-proxy, Oathkeeper, or equivalent) and assert the identity in a
request header. Plainbase itself does NO password login in this mode; it maps the asserted
subject to a role from its `subject_role` table and enforces the role × action matrix.

The reference topology is `deploy/proxy/` (a Caddy + oauth2-proxy **standalone** Caddy stack — NOT
an overlay on the base `docker-compose.yml`, which is the host-published local-dev tier). It is
**reference material**, validated only by `docker compose config`, not a turnkey `docker compose up`
stack — you supply your IdP and hostname. See also the TLS-termination decision:
[`decisions/0008-tls-terminates-at-an-external-reverse-proxy.md`](../decisions/0008-tls-terminates-at-an-external-reverse-proxy.md).

Run it on its **own** (a single `-f`, never layered on the base — layering would inherit the dev
tier's host-loopback publish), from the **repo root** (the project dir compose resolves the
`build:`/bind-mount paths against), loading the env file beside it (`deploy/proxy/.env`, seeded from
`deploy/proxy/.env.example`):

```
docker compose -f deploy/proxy/docker-compose.proxy.yml --env-file deploy/proxy/.env config
```

## The seven load-bearing points

1. **Never publish the app directly.** Only the reverse proxy is a published service; in this
   stack the `plainbase` service has NO `ports:` and is reachable only on the internal network.
   (This is why proxy mode runs as a STANDALONE stack, not an overlay on the base
   `docker-compose.yml` — the base is the local-dev tier and publishes to the host loopback.) If
   the app is reachable without traversing the proxy, the trust model is void — a client could
   stamp the identity header itself.

2. **`PLAINBASE_TRUSTED_PROXY` must be narrow.** Ideally the proxy's `/32`; in the compose
   reference it is a dedicated, pinned-subnet network's CIDR. It scopes which socket peers may
   even attempt the trusted path. It is NOT a wide subnet shared with untrusted workloads.

3. **`PLAINBASE_PROXY_SECRET` is REQUIRED and is the real trust anchor.** A CIDR alone trusts a
   whole subnet — a sibling container on a shared compose/k8s network could stamp the identity
   header. The shared secret (header `X-Plainbase-Proxy-Secret`, set by the proxy, compared
   constant-time by the app) is what a sibling without the secret cannot forge. Plainbase
   **refuses to boot** in proxy mode without both a CIDR allowlist and a secret. Supply the secret
   via env (`PLAINBASE_PROXY_SECRET`) or a secret store, never inline in the compose file. The
   `auth.proxySecret` field in `plainbase.conf` IS read as a fallback, but a config file is not a
   good home for secrets — keep env / the secret store the steered path.

4. **The proxy MUST strip-then-set the trusted headers.** Delete any client-supplied copy of
   `X-Forwarded-User` and `X-Plainbase-Proxy-Secret` FIRST, then set them from the authenticated
   subject and the secret. This is the only safe pattern — see `deploy/proxy/Caddyfile`
   (`header_up -X-Forwarded-User` before `header_up X-Forwarded-User …`). The proxy must also set
   `X-Forwarded-Proto: https`.

5. **Map a STABLE IdP subject, not email.** The identity header carries the IdP's stable subject
   id (e.g. oauth2-proxy's `X-Auth-Request-User`), not an email address — email reassignment
   would silently transfer a departed user's roles to whoever inherits the address. The header
   name is configurable via `PLAINBASE_PROXY_IDENTITY_HEADER` (default `X-Forwarded-User`).

6. **Seed the first proxy admin via the CLI.** A proxy identity starts with no role
   (deny-by-default). Grant the first admin out-of-band:

   ```
   plainbase admin grant-role proxy <idp-subject> admin
   ```

   Subsequent grants can be done in the admin UI (or the `POST /api/v1/admin/roles` API).

7. **Config is restart-only (§0.9).** `PLAINBASE_AUTH_MODE`, the CIDR, the secret, and the
   identity-header name are read at boot. Change them, then restart.

## What proxy mode does NOT change

- **Agent `pb_` bearer tokens** authenticate in every mode (proxy included) — mint/list/revoke
  them in the admin UI or `plainbase admin mint-token` / `list-tokens` / `revoke-token`.
- **Builtin password login, setup, and user CRUD** are builtin-only — they return 404 in proxy
  mode. There is no app session for a proxy user (no "sign out" — the proxy owns the session);
  mutations carry a stateless double-submit CSRF token the SPA reads from `GET /api/v1/session`.
- **The authorization choke point** (`PolicyService` + the guarded facades + the audit log) is
  identical across modes — only the identity *source* differs.
