package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.port
import java.security.MessageDigest

/**
 * The per-session CSRF synchronizer-token guard (A4a, the §3 core). A cookie-authenticated state mutation must
 * carry a valid `X-CSRF-Token` matching the session row's `csrf_token` (compared constant-time —
 * [MessageDigest.isEqual] on the raw 32 bytes DOES transfer here: it is a raw-byte equality of two known-length
 * arrays, not a hash lookup). When an `Origin`/`Referer` header is PRESENT it must be same-origin
 * (fail-closed-WHEN-PRESENT); an ABSENT Origin is NEVER a hard fail (a trusted proxy may strip it —
 * `SecureContext`/ADR-0008). SameSite=Lax (the cookie attribute, WI-8) is the secondary defense.
 *
 * EXEMPT: an agent `pb_` bearer ([Principal.Agent]) — it carries no ambient cookie, so it cannot be CSRF'd.
 * [Principal.Anonymous] is also exempt (no session to protect). [requiresCsrf] keys off the principal TYPE (every
 * [Principal.Human] mutation needs CSRF — cookie- AND proxy-sourced); the MECHANISM choice (the A4a synchronizer
 * token vs the A4b stateless double-submit) lives in `enforceCsrf`, branching on the [PrincipalExtraction.Resolved]
 * [Source]. Login is pre-session (no session yet) — it is NOT in scope here (Origin + secure-context + rate-limit
 * guard it instead, WI-9).
 */
object CsrfGuard {

    /** Whether [principal] is a human (cookie- OR proxy-sourced) whose mutating requests require a CSRF token. */
    fun requiresCsrf(principal: Principal): Boolean = principal is Principal.Human

    /**
     * Validates the request's `X-CSRF-Token` against [expectedCsrf] (constant-time) AND, when present, its
     * `Origin`/`Referer` against the request host. [trustedProxyCidrs] lets the Origin check accept a proxy-fronted
     * mutation (see [originIsAcceptable]). Returns the [Outcome]; the route maps a failure to 403.
     */
    fun validate(call: ApplicationCall, expectedCsrf: ByteArray, trustedProxyCidrs: List<String>): Outcome {
        val presented = call.request.headers[CSRF_HEADER]?.let(::decodeCsrf)
        if (presented == null || !MessageDigest.isEqual(presented, expectedCsrf)) return Outcome.TokenMismatch
        // Origin is tertiary + fail-closed-WHEN-PRESENT: a present cross-origin Origin/Referer is rejected; an
        // absent one is NOT a hard fail (a trusted proxy may strip it).
        if (!originIsAcceptable(call, trustedProxyCidrs)) return Outcome.CrossOrigin
        return Outcome.Ok
    }

    /**
     * The Origin/Referer secondary check in isolation (the A4b proxy double-submit reuses it after its own token
     * check): [Outcome.Ok] when absent or same-origin, [Outcome.CrossOrigin] when a present header is cross-origin
     * or malformed. Fail-closed-WHEN-PRESENT, exactly as inside [validate].
     */
    fun validateOrigin(call: ApplicationCall, trustedProxyCidrs: List<String>): Outcome =
        if (originIsAcceptable(call, trustedProxyCidrs)) Outcome.Ok else Outcome.CrossOrigin

    /** The verdict of [validate]: the route proceeds on [Ok], else answers 403 with the matching error code. */
    enum class Outcome { Ok, TokenMismatch, CrossOrigin }

    /** Decodes the base64url `X-CSRF-Token` to its raw bytes, or null on a malformed value. */
    private fun decodeCsrf(raw: String): ByteArray? =
        runCatching { kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).decode(raw) }
            .getOrNull()

    /** Pulls the Origin-check inputs off the [call] and delegates to the pure [originVerdict]. */
    private fun originIsAcceptable(call: ApplicationCall, trustedProxyCidrs: List<String>): Boolean =
        originVerdict(
            originOrReferer = call.request.headers["Origin"] ?: call.request.headers["Referer"],
            forwardedHost = call.request.headers["X-Forwarded-Host"],
            forwardedProtoValues = call.request.headers.getAll("X-Forwarded-Proto") ?: emptyList(),
            socketPeer = call.request.local.remoteAddress,
            requestHost = call.request.host(),
            requestPort = call.request.port(),
            trustedProxyCidrs = trustedProxyCidrs,
        )

    /**
     * The PURE Origin/Referer verdict (no Ktor types — the socket peer is a plain string, so the proxy-fronted path
     * is unit-testable; `testApplication` can't present a non-loopback peer, the documented A4a limit). True when:
     *  - there is no `Origin`/`Referer` ([originOrReferer] null) — absent is allowed (fail-closed-WHEN-PRESENT); OR
     *  - behind a TRUSTED proxy ([socketPeer] ∈ [trustedProxyCidrs]) with an [forwardedHost], the Origin host matches
     *    that EXTERNAL host and the scheme matches `X-Forwarded-Proto`, IGNORING the port — a TLS terminator on
     *    443→8080 legitimately rewrites host/port, so a naive host:port compare would false-reject it. `X-Forwarded-Host`
     *    is honored ONLY from an allowlisted peer, never a raw client header (a direct client can't smuggle one); OR
     *  - for a direct/loopback request, the Origin's host:port equals the request's own [requestHost]:[requestPort].
     *
     * A malformed present Origin is rejected. Origin is the SECONDARY defense; the synchronizer/double-submit token is
     * load-bearing.
     */
    fun originVerdict(
        originOrReferer: String?,
        forwardedHost: String?,
        forwardedProtoValues: List<String>,
        socketPeer: String,
        requestHost: String,
        requestPort: Int,
        trustedProxyCidrs: List<String>,
    ): Boolean {
        val url = (originOrReferer ?: return true).let { runCatching { Url(it) }.getOrNull() } ?: return false
        if (forwardedHost != null && RemoteAddress.isInAnyCidr(socketPeer, trustedProxyCidrs)) {
            // The proxy rewrote host/port (443→8080); match the EXTERNAL host the browser used + the https scheme,
            // port-agnostic. A present X-Forwarded-Proto that isn't all-https fails closed (a downgraded hop).
            val schemeOk = forwardedProtoValues.isEmpty() ||
                RemoteAddress.forwardedProtoIsHttps(forwardedProtoValues) == (url.protocol == URLProtocol.HTTPS)
            return url.host == forwardedHost.substringBefore(':').trim() && schemeOk
        }
        return url.host == requestHost && url.port == requestPort
    }

    private const val CSRF_HEADER = "X-CSRF-Token"
}
