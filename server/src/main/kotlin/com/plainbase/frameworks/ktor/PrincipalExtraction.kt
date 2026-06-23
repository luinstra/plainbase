package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TOKEN_PREFIX
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.SessionService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.security.MessageDigest

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
 * The outcome of resolving a request's credential: a [Resolved] principal, an [InsecureTransportRefused], OR
 * (A4b proxy mode) a [ProxyIdentityRejected] — each DISTINCT from `Resolved(Anonymous)` so the route maps a
 * refused-insecure credential / a malformed proxy header to a refusal status rather than conflating it with
 * "no credential presented".
 */
sealed interface PrincipalExtraction {

    /**
     * A resolved principal from one of THREE sources: [Source.COOKIE] (the A4a `pb_session` login), [Source.PROXY]
     * (the A4b trusted-proxy identity header), or a bearer Agent / Anonymous (no source, `source == null`).
     * [csrfToken] is the CSRF guard's expected value — the cookie session row's synchronizer token for a
     * [Source.COOKIE] Human, or the A4b double-submit token for a [Source.PROXY] Human; null for a bearer/anonymous.
     */
    data class Resolved(
        val principal: Principal,
        val csrfToken: ByteArray? = null,
        val source: Source? = null,
    ) : PrincipalExtraction

    /** A credential was presented over a NON-secure transport; the secret was NOT touched (never honored). */
    data object InsecureTransportRefused : PrincipalExtraction

    /**
     * A4b: a trusted proxy passed the secret+transport gate but sent a malformed identity header (multi-value,
     * blank, control chars, or oversized). This is a MISCONFIG signal — a trusted proxy should never send a
     * malformed subject — so the route answers 400, NOT 401/421. DISTINCT from a wrong/missing secret, which is
     * "no identity" → `Resolved(Anonymous)`.
     */
    data class ProxyIdentityRejected(val reason: ProxyRejectReason) : PrincipalExtraction
}

/** Which credential source resolved a [PrincipalExtraction.Resolved] Human — the CSRF mechanism branches on it. */
enum class Source { COOKIE, PROXY }

/** Why a proxy identity header was rejected (→ 400 `invalid_proxy_identity`); never echoes the offending value. */
enum class ProxyRejectReason { MULTI_VALUE, BLANK, CONTROL_CHARS, OVERSIZED }

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
@Suppress("LongParameterList")
fun ApplicationCall.extractPrincipal(
    tokens: ApiTokenService,
    trustedProxyCidrs: List<String>,
    sessions: SessionService? = null,
    builtinAuthEnabled: Boolean = sessions != null,
    proxyAuthEnabled: Boolean = false,
    proxySecret: String? = null,
    proxyIdentityHeader: String = "X-Forwarded-User",
): PrincipalExtraction = decidePrincipalExtraction(
    bearer = request.bearerToken(),
    cookie = sessions?.let { this.sessions.get(SESSION_COOKIE_NAME) as? String },
    remoteHost = request.socketRemoteHost,
    forwardedProtoValues = request.headers.getAll("X-Forwarded-Proto") ?: emptyList(),
    trustedProxyCidrs = trustedProxyCidrs,
    authenticateBearer = tokens::authenticate,
    authenticateCookie = { raw -> sessions?.authenticate(raw) },
    builtinAuthEnabled = builtinAuthEnabled,
    proxyIdentityValues = request.headers.getAll(proxyIdentityHeader) ?: emptyList(),
    presentedProxySecrets = request.headers.getAll(PROXY_SECRET_HEADER) ?: emptyList(),
    configuredProxySecret = proxySecret,
    proxyAuthEnabled = proxyAuthEnabled,
)

/**
 * The fixed NAME of the shared-secret header a trusted proxy stamps (only the IDENTITY header name is
 * operator-configurable; the secret is a shared VALUE, not a name to vary). Read strictly multi-value (BLOCKING-1):
 * a client-appended duplicate alongside the proxy-set value would otherwise let Ktor's first/last pick decide auth.
 */
const val PROXY_SECRET_HEADER = "X-Plainbase-Proxy-Secret"

/** The longest identity (SSO subject) we accept; a real subject is well under this, so a longer one is misconfig. */
private const val MAX_PROXY_IDENTITY_LENGTH = 256

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
    proxyIdentityValues: List<String> = emptyList(),
    presentedProxySecrets: List<String> = emptyList(),
    configuredProxySecret: String? = null,
    proxyAuthEnabled: Boolean = false,
): PrincipalExtraction {
    val pbBearer = bearer?.takeIf { it.startsWith(TOKEN_PREFIX) }
    val effectiveCookie = cookie?.takeIf { builtinAuthEnabled } // OFF/PROXY: ignore a stray session cookie (resolve as absent)
    // The proxy identity header is the credential in PROXY mode, so the "any credential present?" short-circuit must
    // count it — otherwise a proxy request carrying ONLY the identity header would early-return Anonymous before the
    // transport gate fires (WI-3).
    val proxyIdentityPresent = proxyAuthEnabled && proxyIdentityValues.isNotEmpty()
    if (pbBearer == null && effectiveCookie == null && !proxyIdentityPresent) {
        return PrincipalExtraction.Resolved(Principal.Anonymous) // gate does not fire — no credential present
    }
    if (!isSecureContext(remoteHost, forwardedProtoValues, trustedProxyCidrs)) {
        // WI-6 diagnosability: a proxy request that fails the gate logs WHICH half failed (CIDR miss vs proto
        // mismatch) so an operator can tell a misrouted/spoofed peer from a missing `X-Forwarded-Proto`. The client
        // always sees the same 421 — no oracle. Never logs the secret/identity value.
        if (proxyIdentityPresent) logProxyGateFailure(remoteHost, forwardedProtoValues, trustedProxyCidrs)
        return PrincipalExtraction.InsecureTransportRefused // refuse BEFORE any secret/identity is touched
    }
    // Bearer wins: an agent call carrying a stray cookie/proxy header is still an agent.
    if (pbBearer != null) {
        val agent = authenticateBearer(pbBearer)
        if (agent !is Principal.Anonymous) return PrincipalExtraction.Resolved(agent)
    }
    if (effectiveCookie != null) {
        val session = authenticateCookie(effectiveCookie)
        if (session != null) return PrincipalExtraction.Resolved(session.principal, session.csrfToken, Source.COOKIE)
    }
    if (proxyIdentityPresent) {
        return resolveProxyIdentity(proxyIdentityValues, presentedProxySecrets, configuredProxySecret, remoteHost)
    }
    return PrincipalExtraction.Resolved(Principal.Anonymous)
}

private val logger = KotlinLogging.logger {}

/**
 * WI-6 diagnosability: distinguishes the two not-authenticated gate-failure reasons (the secret-mismatch reason
 * lives in [secretMatches]). A non-loopback peer outside [trustedProxyCidrs] is a CIDR miss; an in-CIDR peer whose
 * `X-Forwarded-Proto` isn't all-`https` is a proto mismatch. Operator log ONLY (the client sees an identical 421);
 * never logs the secret/identity value.
 */
private fun logProxyGateFailure(remoteHost: String, forwardedProtoValues: List<String>, trustedProxyCidrs: List<String>) {
    if (!RemoteAddress.isInAnyCidr(remoteHost, trustedProxyCidrs)) {
        logger.info { "proxy auth: CIDR miss — socket peer $remoteHost not in trustedProxyCidrs; refused (transport insecure)" }
    } else {
        logger.info {
            "proxy auth: proto mismatch — peer $remoteHost in-CIDR but X-Forwarded-Proto not all-https; refused (transport insecure)"
        }
    }
}

/**
 * The trust gate's identity step (the transport gate has already passed). The secret is REQUIRED and compared
 * constant-time over fixed-length digests (a wrong/missing/duplicate secret → not authenticated, NEVER a 400 — a
 * bad secret is "no identity"); only WITH the secret matched is the identity header validated, where a
 * malformed/duplicate/blank/control/oversized value is a misconfig → [PrincipalExtraction.ProxyIdentityRejected].
 * The three not-authenticated reasons log DISTINCT operator lines while the caller sees one [Principal.Anonymous]
 * (no oracle); the secret and identity VALUES never log (WI-6 hygiene).
 */
private fun resolveProxyIdentity(
    proxyIdentityValues: List<String>,
    presentedProxySecrets: List<String>,
    configuredProxySecret: String?,
    remoteHost: String,
): PrincipalExtraction {
    if (!secretMatches(presentedProxySecrets, configuredProxySecret)) {
        logger.info {
            "proxy auth: secret mismatch from $remoteHost (transport trusted, secret absent/wrong/duplicate) — not authenticated"
        }
        return PrincipalExtraction.Resolved(Principal.Anonymous)
    }
    // The secret matched: a malformed identity from a trusted proxy is operator misconfig → 400.
    if (proxyIdentityValues.size != 1) return PrincipalExtraction.ProxyIdentityRejected(ProxyRejectReason.MULTI_VALUE)
    val value = proxyIdentityValues.single().trim()
    return when {
        value.isBlank() -> PrincipalExtraction.ProxyIdentityRejected(ProxyRejectReason.BLANK)
        value.any(Char::isISOControl) -> PrincipalExtraction.ProxyIdentityRejected(ProxyRejectReason.CONTROL_CHARS)
        value.length > MAX_PROXY_IDENTITY_LENGTH -> PrincipalExtraction.ProxyIdentityRejected(ProxyRejectReason.OVERSIZED)
        else -> PrincipalExtraction.Resolved(Principal.Human(PROXY_ISSUER, value), source = Source.PROXY)
    }
}

/**
 * Constant-time secret check (MINOR fold-in): exactly ONE presented value (a duplicate header is an attack/misconfig
 * — treat as not authenticated, BLOCKING-1) AND a fixed-length-digest constant-time match. Both sides are SHA-256'd
 * to 32 bytes BEFORE [MessageDigest.isEqual] so a wrong/missing/odd-length presented secret all do identical work
 * (the [com.plainbase.frameworks.security.TokenHasher.verify] shape). A missing header compares the empty string,
 * so the timing is identical to a wrong one.
 */
private fun secretMatches(presentedProxySecrets: List<String>, configuredProxySecret: String?): Boolean {
    if (presentedProxySecrets.size > 1) return false // duplicate secret header — never first/last-pick
    if (configuredProxySecret == null) return false // the WI-2 startup guard makes this unreachable in proxy mode
    val presented = presentedProxySecrets.singleOrNull().orEmpty()
    val sha256 = MessageDigest.getInstance("SHA-256")
    return MessageDigest.isEqual(sha256.digest(presented.encodeToByteArray()), sha256.digest(configuredProxySecret.encodeToByteArray()))
}

/** The issuer for a proxy-asserted human — the `(issuer, externalId)` role key the grant-role CLI seeds. */
const val PROXY_ISSUER = "proxy"

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
