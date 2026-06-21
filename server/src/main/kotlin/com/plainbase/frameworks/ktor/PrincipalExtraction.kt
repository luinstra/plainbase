package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TOKEN_PREFIX
import com.plainbase.domain.service.ApiTokenService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest

/**
 * The seam identity sources plug into. A1 established it returning [Principal.Anonymous] — no source was
 * registered yet. A2 adds the `pb_` bearer source as the parameterized [extractPrincipal] overload below; A4a/
 * A4b add the cookie/proxy-header sources. It is a plain helper, deliberately NOT installed as a global
 * interceptor: A2 changes the behavior of NO existing route (A2 has no protected route — A3 owns route gating);
 * callers (the future A3 choke point + the A2 tests) invoke it from their own extraction point.
 *
 * SECURE-CONTEXT CONTRACT (ADR-0008): at the point a source EXTRACTS a credential (a session cookie or an
 * `Authorization: Bearer pb_…`), it MUST first evaluate [isSecureContext] over the SOCKET remote address +
 * `X-Forwarded-Proto` + the configured trusted-proxy CIDRs, and refuse the request if it is false — presenting
 * a credential over plain non-loopback HTTP LEAKS it. A2 is the gate's FIRST LIVE CONSUMER.
 */
@Suppress("UnusedReceiverParameter")
fun ApplicationCall.extractPrincipal(): Principal = Principal.Anonymous

/**
 * The A2 outcome of resolving a request's credential: a [Resolved] principal, OR an [InsecureTransportRefused]
 * — DISTINCT from `Resolved(Anonymous)` so the route maps a refused-insecure bearer to a refusal status rather
 * than conflating it with "no credential presented".
 */
sealed interface PrincipalExtraction {

    /** A resolved principal: [Principal.Agent] on a verified bearer, [Principal.Anonymous] on no/non-`pb_`/bad bearer. */
    data class Resolved(val principal: Principal) : PrincipalExtraction

    /** A `pb_` bearer was presented over a NON-secure transport; the secret was NOT touched (never honored). */
    data object InsecureTransportRefused : PrincipalExtraction
}

/**
 * A2: resolve an `Authorization: Bearer pb_…` to [Principal.Agent], else [Principal.Anonymous] — gated by the
 * credential-CONDITIONAL secure-context (ADR-0008). The gate is credential-CONDITIONAL: a request with NO
 * `pb_` bearer is [PrincipalExtraction.Resolved] `Anonymous` regardless of transport (an anonymous request to
 * a public route over plain HTTP stays fine). Only a request CARRYING a `pb_` bearer triggers the gate; over a
 * non-secure transport it is [PrincipalExtraction.InsecureTransportRefused] and [tokens] is NEVER consulted —
 * the secret must not be touched over a leaky transport.
 *
 * Source identity is the SOCKET peer ([ApplicationRequest.socketRemoteHost], never `X-Forwarded-For`/`origin`);
 * the proto verdict reads ALL `X-Forwarded-Proto` values (§0.10).
 */
fun ApplicationCall.extractPrincipal(
    tokens: ApiTokenService,
    trustedProxyCidrs: List<String>,
): PrincipalExtraction = decidePrincipalExtraction(
    bearer = request.bearerToken(),
    remoteHost = request.socketRemoteHost,
    forwardedProtoValues = request.headers.getAll("X-Forwarded-Proto") ?: emptyList(),
    trustedProxyCidrs = trustedProxyCidrs,
    authenticate = tokens::authenticate,
)

/**
 * The pure gate decision (no Ktor types), so the secure-context refusal + the "secret not touched on refusal"
 * property are unit-testable with controlled inputs (the test engine cannot easily spoof a socket peer). The
 * Ktor overload above feeds it the SOCKET peer + headers; A2's tests feed it cases directly.
 *
 * Credential-CONDITIONAL: a null/non-`pb_` [bearer] → `Resolved(Anonymous)` regardless of transport. A `pb_`
 * [bearer] over a non-secure transport → [PrincipalExtraction.InsecureTransportRefused] and [authenticate] is
 * NEVER invoked. A `pb_` bearer over a secure transport → `Resolved(authenticate(bearer))`.
 */
fun decidePrincipalExtraction(
    bearer: String?,
    remoteHost: String,
    forwardedProtoValues: List<String>,
    trustedProxyCidrs: List<String>,
    authenticate: (String) -> Principal,
): PrincipalExtraction {
    if (bearer == null || !bearer.startsWith(TOKEN_PREFIX)) {
        return PrincipalExtraction.Resolved(Principal.Anonymous) // gate does not fire — no pb_ credential present
    }
    if (!isSecureContext(remoteHost, forwardedProtoValues, trustedProxyCidrs)) {
        return PrincipalExtraction.InsecureTransportRefused // refuse BEFORE the secret is touched
    }
    return PrincipalExtraction.Resolved(authenticate(bearer))
}

/** The raw token of an `Authorization: Bearer <token>` header, or null (no header / not a Bearer scheme). */
private fun ApplicationRequest.bearerToken(): String? {
    val header = headers["Authorization"]?.trim() ?: return null
    if (!header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
    return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
}

/**
 * The SOCKET peer address — the actual TCP remote, NEVER proxy/header-derived. `request.local` is the raw
 * connection point (`request.origin` is proxy-aware and would honor `X-Forwarded-For`/`Forwarded`, so it must
 * NOT be used for source identity — §0.10).
 */
private val ApplicationRequest.socketRemoteHost: String get() = local.remoteAddress

private const val BEARER_PREFIX = "Bearer "
