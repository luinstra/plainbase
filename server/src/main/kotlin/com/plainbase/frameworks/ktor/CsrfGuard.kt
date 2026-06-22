package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
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
 * [Principal.Anonymous] is also exempt (no session to protect). The predicate keys off the principal TYPE because
 * neither [Principal] nor [PrincipalExtraction] carries a credential-SOURCE tag, and in A4a a cookie is the ONLY
 * thing that yields [Principal.Human] (A4b's proxy-header Human will need a source discriminator — see the WI-10
 * forward-flag; A4a is safe because every `Human` here IS cookie-sourced). Login is pre-session (no session yet) —
 * it is NOT in scope here (Origin + secure-context + rate-limit guard it instead, WI-9).
 */
object CsrfGuard {

    /** Whether [principal] is a cookie-authenticated human whose mutating requests require the CSRF token. */
    fun requiresCsrf(principal: Principal): Boolean = principal is Principal.Human

    /**
     * Validates the request's `X-CSRF-Token` against [expectedCsrf] (constant-time) AND, when present, its
     * `Origin`/`Referer` against the request host. Returns the [Outcome]; the route maps a failure to 403.
     */
    fun validate(call: ApplicationCall, expectedCsrf: ByteArray): Outcome {
        val presented = call.request.headers[CSRF_HEADER]?.let(::decodeCsrf)
        if (presented == null || !MessageDigest.isEqual(presented, expectedCsrf)) return Outcome.TokenMismatch
        // Origin is tertiary + fail-closed-WHEN-PRESENT: a present cross-origin Origin/Referer is rejected; an
        // absent one is NOT a hard fail (a trusted proxy may strip it).
        if (!originIsAcceptable(call)) return Outcome.CrossOrigin
        return Outcome.Ok
    }

    /** The verdict of [validate]: the route proceeds on [Ok], else answers 403 with the matching error code. */
    enum class Outcome { Ok, TokenMismatch, CrossOrigin }

    /** Decodes the base64url `X-CSRF-Token` to its raw bytes, or null on a malformed value. */
    private fun decodeCsrf(raw: String): ByteArray? =
        runCatching { kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).decode(raw) }
            .getOrNull()

    /**
     * True when there is no `Origin`/`Referer` (absent is allowed), or the present one's host:port matches the
     * request's own host:port. A malformed present header is rejected (false).
     */
    private fun originIsAcceptable(call: ApplicationCall): Boolean {
        val raw = call.request.headers["Origin"] ?: call.request.headers["Referer"] ?: return true
        val url = runCatching { Url(raw) }.getOrNull() ?: return false
        return url.host == call.request.host() && url.port == call.request.port()
    }

    private const val CSRF_HEADER = "X-CSRF-Token"
}
