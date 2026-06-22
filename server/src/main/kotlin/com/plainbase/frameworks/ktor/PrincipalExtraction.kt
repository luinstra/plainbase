package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TOKEN_PREFIX
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.SessionService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

/**
 * The seam identity sources plug into. A1 established it returning [Principal.Anonymous] — no source was
 * registered yet. A2 added the `pb_` bearer source; A4a adds the `pb_session` COOKIE source through the SAME
 * decision + the SAME credential-CONDITIONAL secure-context gate; A4b adds the proxy header.
 *
 * SECURE-CONTEXT CONTRACT (ADR-0008): at the point a source EXTRACTS a credential (a session cookie OR an
 * `Authorization: Bearer pb_…`), it MUST first evaluate [isSecureContext] over the SOCKET remote address +
 * `X-Forwarded-Proto` + the configured trusted-proxy CIDRs, and refuse if it is false — presenting a credential
 * over plain non-loopback HTTP LEAKS it. The cookie is NEVER hashed/looked-up over a leaky transport.
 *
 * PRECEDENCE (pinned, A4a): if a request carries BOTH a `pb_` bearer AND a `pb_session` cookie, the BEARER WINS —
 * an agent call carrying a stray cookie is still an agent (and a bearer-resolved principal is CSRF-exempt). Tested
 * in `SessionCookieExtractionTest`.
 */
@Suppress("UnusedReceiverParameter")
fun ApplicationCall.extractPrincipal(): Principal = Principal.Anonymous

/**
 * The outcome of resolving a request's credential: a [Resolved] principal, OR an [InsecureTransportRefused] —
 * DISTINCT from `Resolved(Anonymous)` so the route maps a refused-insecure credential to a refusal status rather
 * than conflating it with "no credential presented".
 */
sealed interface PrincipalExtraction {

    /**
     * A resolved principal: [Principal.Agent] on a verified bearer, [Principal.Human] on a valid session cookie,
     * [Principal.Anonymous] on no/bad credential. [csrfToken] is the session row's CSRF token — present ONLY for a
     * cookie-sourced [Principal.Human] (the CSRF guard's expected value), null otherwise.
     */
    data class Resolved(val principal: Principal, val csrfToken: ByteArray? = null) : PrincipalExtraction

    /** A credential was presented over a NON-secure transport; the secret was NOT touched (never honored). */
    data object InsecureTransportRefused : PrincipalExtraction
}

/**
 * A2/A4a: resolve an `Authorization: Bearer pb_…` to [Principal.Agent] OR a `pb_session` cookie to
 * [Principal.Human] — gated by the credential-CONDITIONAL secure-context (ADR-0008). A request with NO credential
 * (no bearer, no cookie) is [PrincipalExtraction.Resolved] `Anonymous` regardless of transport. A credential over
 * a non-secure transport is [PrincipalExtraction.InsecureTransportRefused] and neither [tokens] nor [sessions] is
 * consulted — the secret must not be touched over a leaky transport. Bearer wins over cookie (see file note).
 *
 * [builtinAuthEnabled] gates the cookie source: the `pb_session` cookie is consulted ONLY in BUILTIN mode (WI-7).
 * In OFF/PROXY the auth routes are never registered, so no session is ever minted — but a leftover/stray cookie
 * from a prior BUILTIN run must NOT resolve to a `Principal.Human` that bypasses the proxy/off identity path; when
 * builtin auth is disabled the cookie is ignored (resolved as if absent), so only the bearer source remains.
 *
 * Source identity is the SOCKET peer ([ApplicationRequest.socketRemoteHost], never `X-Forwarded-For`/`origin`);
 * the proto verdict reads ALL `X-Forwarded-Proto` values (§0.10).
 */
fun ApplicationCall.extractPrincipal(
    tokens: ApiTokenService,
    trustedProxyCidrs: List<String>,
    sessions: SessionService? = null,
    builtinAuthEnabled: Boolean = sessions != null,
): PrincipalExtraction = decidePrincipalExtraction(
    bearer = request.bearerToken(),
    cookie = sessions?.let { this.sessions.get(SESSION_COOKIE_NAME) as? String },
    remoteHost = request.socketRemoteHost,
    forwardedProtoValues = request.headers.getAll("X-Forwarded-Proto") ?: emptyList(),
    trustedProxyCidrs = trustedProxyCidrs,
    authenticateBearer = tokens::authenticate,
    authenticateCookie = { raw -> sessions?.authenticate(raw) },
    builtinAuthEnabled = builtinAuthEnabled,
)

/**
 * The pure gate decision (no Ktor types), so the secure-context refusal + the "secret not touched on refusal"
 * property are unit-testable with controlled inputs. The Ktor overload feeds it the SOCKET peer + headers + the
 * session cookie value; tests feed it cases directly.
 *
 * Credential-CONDITIONAL: a null/non-`pb_` [bearer] AND a null [cookie] → `Resolved(Anonymous)` regardless of
 * transport. A credential over a non-secure transport → [PrincipalExtraction.InsecureTransportRefused] and NEITHER
 * authenticate is invoked. Over a secure transport: try the bearer FIRST (bearer-wins) — if it resolves a
 * non-anonymous Agent, return it; otherwise try the cookie. A valid cookie → `Resolved(Human, csrf)`; an
 * absent/garbage/revoked cookie with no valid bearer → `Resolved(Anonymous)`.
 *
 * [builtinAuthEnabled] (WI-7): when false (OFF/PROXY mode) the [cookie] is dropped up-front — a leftover
 * `pb_session` from a prior BUILTIN run must never resolve to a `Principal.Human` that bypasses the proxy/off
 * identity path. The bearer source is mode-independent (agents authenticate in every mode), so only the cookie is
 * gated.
 */
@Suppress("LongParameterList")
fun decidePrincipalExtraction(
    bearer: String?,
    cookie: String?,
    remoteHost: String,
    forwardedProtoValues: List<String>,
    trustedProxyCidrs: List<String>,
    authenticateBearer: (String) -> Principal,
    authenticateCookie: (String) -> SessionService.Authenticated?,
    builtinAuthEnabled: Boolean = true,
): PrincipalExtraction {
    val pbBearer = bearer?.takeIf { it.startsWith(TOKEN_PREFIX) }
    val cookie = cookie?.takeIf { builtinAuthEnabled } // OFF/PROXY: ignore a stray session cookie (resolve as absent)
    if (pbBearer == null && cookie == null) {
        return PrincipalExtraction.Resolved(Principal.Anonymous) // gate does not fire — no credential present
    }
    if (!isSecureContext(remoteHost, forwardedProtoValues, trustedProxyCidrs)) {
        return PrincipalExtraction.InsecureTransportRefused // refuse BEFORE any secret is touched
    }
    // Bearer wins: an agent call carrying a stray cookie is still an agent.
    if (pbBearer != null) {
        val agent = authenticateBearer(pbBearer)
        if (agent !is Principal.Anonymous) return PrincipalExtraction.Resolved(agent)
    }
    if (cookie != null) {
        val session = authenticateCookie(cookie)
        if (session != null) return PrincipalExtraction.Resolved(session.principal, session.csrfToken)
    }
    return PrincipalExtraction.Resolved(Principal.Anonymous)
}

/** The session cookie name — the ONE definition shared by the seam read + the `Sessions` install. */
const val SESSION_COOKIE_NAME = "pb_session"

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
