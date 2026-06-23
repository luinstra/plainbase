package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.ReadFacade
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.security.ProxyCsrf
import io.ktor.server.application.ApplicationCall

/**
 * The one dependency holder the routing layer receives (A3) — replacing the flat `RestServices` raw-mutator
 * bundle the choke-point synthesis abolished. It exposes ONLY the guarded facades + the extraction inputs + the
 * surviving wire glue; it NEVER exposes `WritePipeline`/`ContentStore`/`IndexBuilder` directly, so a route
 * physically cannot reach a raw mutator (`ChokePointArchitectureTest` enforces this structurally).
 *
 * [extract] is the per-route principal source: in production the real A1/A2
 * [extractPrincipal][com.plainbase.frameworks.ktor.extractPrincipal] over [tokens] + [trustedProxyCidrs]; tests
 * may supply a fixed-`Principal` source (a test-construction choice, NOT a production auth weakening — auth is
 * never turned off, the harness presents a real role-appropriate principal). The `RouteContext` builder defaults
 * [extract] to the real extraction.
 */
class RouteContext(
    val read: ReadFacade,
    val mutate: MutatingFacade,
    val tokens: ApiTokenService,
    /** A4a auth services (session/login/setup/admin/rate-limit) the auth routes + the cookie seam share. */
    val auth: AuthServices,
    val trustedProxyCidrs: List<String>,
    /** PB-WRITE-1 (W2) id mint for `POST /api/v1/pages` — injected so tests mint deterministically. */
    val idProvider: IdProvider,
    /** PB-WRITE-1 body cap (forwarded from config) — route-wire config, not a mutator. */
    val maxWriteBodyBytes: Long,
    /** W3b asset upload cap (forwarded from config). */
    val maxAssetBytes: Long,
    /**
     * A4a (WI-7): true ONLY in `auth.mode=builtin`. Gates the builtin auth surface — the `pb_session` cookie source
     * in [extract] is consulted only when true (in OFF/PROXY a stray cookie is ignored), and `plainbaseModule`
     * registers the login/session/setup/admin-user routes only when true.
     */
    val builtinAuthEnabled: Boolean = true,
    /**
     * A4b: true ONLY in `auth.mode=proxy`. Gates the proxy identity source in [extract] + the proxy `GET /session`
     * + (with [builtinAuthEnabled]) the mode-independent admin token/audit/role routes.
     */
    val proxyAuthEnabled: Boolean = false,
    /** A4b: the REQUIRED proxy shared secret (proxy mode); null outside proxy mode. */
    val proxySecret: String? = null,
    /** A4b: the operator-configurable proxy identity header name (default `X-Forwarded-User`). */
    val proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
    /**
     * A4b: the `Secure` attribute for the `pb_proxy_csrf` cookie (mirrors [PlainbaseConfig.secureCookie] — TLS-fronted
     * iff a non-loopback bind OR a trusted proxy is declared). Defaults false (loopback-dev/test).
     */
    val secureCookie: Boolean = false,
    /**
     * A4b: the stateless proxy-CSRF double-submit minter/validator (built from the persisted server key). Always
     * present (even outside proxy mode) so `enforceCsrf` needn't null-check; only the [Source.PROXY] branch uses it.
     */
    val proxyCsrf: ProxyCsrf,
    /**
     * The per-route principal source; defaults to the real A1/A2/A4a/A4b extraction over [tokens] + the `pb_session`
     * cookie (gated by [builtinAuthEnabled]) + the proxy identity header (gated by [proxyAuthEnabled]) +
     * [trustedProxyCidrs].
     */
    val extract: ApplicationCall.() -> PrincipalExtraction =
        {
            extractPrincipal(
                tokens,
                trustedProxyCidrs,
                auth.session,
                builtinAuthEnabled,
                proxyAuthEnabled = proxyAuthEnabled,
                proxySecret = proxySecret,
                proxyIdentityHeader = proxyIdentityHeader,
            )
        },
)
