# 8. TLS terminates at an external reverse proxy (not in-process), with a fail-closed bind guard

- **Status:** Accepted
- **Date:** 2026-06-20
- **Deciders:** luinstra (after the Phase 4 human-auth design debate: Opus, Codex, Gemini 3.1 Pro, Sonnet 4.6)
- **Context:** Phase 4 (Principals, auth, roles, audit). The auth debate established that **built-in
  password login over plain HTTP is a credential-capture trap**, so a TLS stance must be recorded
  *before* the auth chunk loop. This also lands the long-open TLS-architecture question (a prior
  discussion never reached a decision); the binary serves plain HTTP today with no TLS code.

## Context

`KtorServer.kt` runs `embeddedServer(CIO, host = config.host, port = config.port)` over **plain HTTP**,
with `host` defaulting to **0.0.0.0** — there is no TLS/SSL/keystore code anywhere, and no committed TLS
decision on `main`. Phase 4 adds human authentication (built-in password login + an opt-in reverse-proxy
trusted-header adapter; see the Phase 4 plan). Credentials and session cookies on the wire make TLS a
prerequisite for any networked deployment, not an optional nicety.

Two ways to provide TLS:

- **Option A — in-process TLS** (a Ktor CIO `sslConnector` loading a keystore, or in-binary ACME).
- **Option B — terminate TLS at an external reverse proxy / tunnel** (Caddy auto-HTTPS as reference;
  nginx/Traefik/Cloudflare Tunnel/Access equally valid), with Plainbase serving plain HTTP behind it.

The tension is the project's load-bearing **GraalVM native-image** bet. In-process TLS pulls JSSE /
`SSLContext` / secure-random / (for ACME) an HTTP+crypto client into the closed-world image — reflection
and resource reachability that is **unverified** here (the native spike proves plain-HTTP loopback only;
cf. the same "outbound HTTPS is native-unproven" finding that ruled out in-app OIDC in the auth debate).
Option B keeps **zero TLS crypto in the binary** and fits the project's "compute/operational-heavy
concerns live in an external process" ethos (the Meilisearch pattern) — and it solves both TLS *and* the
auth debate's credential-capture trap in one well-trodden component.

## Decision

**Plainbase does not terminate TLS in-process. For any networked deployment, TLS is terminated by an
external reverse proxy or tunnel; Plainbase serves plain HTTP behind it.** **Caddy** (automatic HTTPS) is
the blessed reference; a reference `Caddyfile` ships in the docs. nginx/Traefik and Cloudflare
Tunnel/Access (zero inbound port) are documented alternatives.

A **fail-closed bind guard** makes the safe path the default (mirrors the `requireContentDir()` idiom in
`PlainbaseConfig`):

- If human auth is enabled and the server binds a **non-loopback** interface (e.g. `0.0.0.0`) **without**
  a declared TLS/trusted-proxy configuration → **refuse to start**, with an operator-actionable message.
  An explicit override (`PLAINBASE_INSECURE_HTTP=1`, name to be finalized in the build) exists for the
  operator who knowingly accepts plaintext exposure; it logs a loud unconditional warning.
- **Loopback HTTP is always allowed** (local dev / single-user behind the FS boundary).
- **Per-request secure context** on credential-bearing paths (login, password-reset, session creation,
  cookie-authenticated browser mutations): require the request to be loopback **or** carry
  `X-Forwarded-Proto: https` from an **allowlisted** proxy source. Forwarded headers from non-allowlisted
  remotes are never trusted. This catches a proxy misconfigured to forward plaintext after TLS was declared.

The recommended deployed topology: proxy terminates TLS on `:443`, forwards to Plainbase on
`127.0.0.1:8080`; Plainbase binds loopback. The proxy also strips/overwrites inbound `X-Forwarded-*` and
any `X-Plainbase-*` identity headers (relevant to the Phase 4 trusted-header adapter).

## Consequences

**Positive**

- The native-image bet is protected: no JSSE/keystore/ACME reachability metadata, no crypto-client
  transitive graph, no Phase-4-gated-on-a-TLS-spike risk.
- TLS is handled by purpose-built, auto-renewing tooling (Caddy/ACME) that does it better than we would.
- Closes the auth debate's credential-capture trap: built-in passwords are only ever served over TLS (or
  loopback), enforced by the guard rather than left to operator diligence.
- Lean binary; the whole TLS concern stays out of the shipped artifact.

**Trade-offs**

- A networked, authenticated deployment requires running a proxy — one more component than "just the
  binary." Accepted: TLS needs *something* regardless of who terminates it, a TLS terminator (Caddy,
  ~3 lines of config) is far lighter than an auth proxy + IdP, and the loopback/dev path needs nothing.
- Trusting `X-Forwarded-Proto` / forwarded identity requires a correctly configured, header-sanitizing
  proxy and an explicit trusted-source allowlist — documented, and fail-closed when unconfigured.

**Reversibility — high.** In-process TLS (a Ktor `sslConnector` + keystore, or in-binary ACME) can be
added later behind a dedicated **native-image TLS spike** — exactly as ADR-0006 parked JGit behind a
spike. The fail-closed guard simply gains "in-process TLS configured" as another condition that satisfies
it; no domain, contract, or architectural change. Self-hosted-anywhere is preserved either way.

Phase 4 auth debate synthesis (which surfaced the credential-capture trap driving this) archived at
`~/.claude-octopus/debates/<session>/phase4-human-auth/synthesis.md`.
