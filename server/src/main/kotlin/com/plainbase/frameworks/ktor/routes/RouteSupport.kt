package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.AbsenceUnverified
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.DenyReason
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.ktor.CsrfGuard
import com.plainbase.frameworks.ktor.PrincipalExtraction
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.Source
import com.plainbase.frameworks.ktor.dto.BodyTooLargeBody
import com.plainbase.frameworks.ktor.dto.BodyTooLargeEnvelope
import com.plainbase.frameworks.ktor.dto.ErrorBody
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ErrorEnvelope
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.isSecureContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.charset
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Responds [value] through the scoped [RestJson] serializer (present-null guaranteed, §A4). */
internal suspend fun <T> ApplicationCall.respondRest(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(RestJson.encodeToString(serializer, value), ContentType.Application.Json, status)
}

/** A bodiless 204 (logout / no-content admin actions). */
internal suspend fun ApplicationCall.respondNoContent() {
    respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
}

/**
 * Reads + parses a small A4a auth JSON request body ([LoginRequest]/setup/reset/change/admin DTOs) through the
 * scoped [RestJson] (the `PageCreateRoutes.parseCreateRequest` idiom — manual decode, NOT content-negotiation), or
 * itself responds 400 `invalid_auth_request` and returns null. A strict-UTF8 decode rejects bad bytes (JSON is
 * defined over valid Unicode); a malformed body is the route's 400, never a Ktor-default 500. The 1 MiB write cap
 * is reused as a generous bound — an auth body is tiny.
 */
internal suspend fun <T : Any> ApplicationCall.receiveAuthRequest(serializer: KSerializer<T>): T? {
    val raw = receiveBodyCapped(MAX_AUTH_BODY_BYTES) ?: run {
        respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_AUTH_REQUEST, "Request body too large")
        return null
    }
    val text = strictUtf8Decode(raw)
    val parsed = text?.let { runCatching { RestJson.decodeFromString(serializer, it) }.getOrNull() }
    if (parsed == null) {
        respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_AUTH_REQUEST, "Malformed request body")
    }
    return parsed
}

/**
 * Strict UTF-8 decode (the [com.plainbase.domain.content.PercentCoding] idiom): null on any malformed/unmappable
 * input. The ONE shared route-layer decoder — the auth, create, and proposal request parsers all reject bad
 * bytes through it (JSON is defined over valid Unicode; a lenient U+FFFD substitution would corrupt content).
 */
internal fun strictUtf8Decode(bytes: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/** A generous bound for an auth JSON body (credentials/tokens are tiny); the same stream-count cap as a write. */
private const val MAX_AUTH_BODY_BYTES: Long = 64 * 1024

/**
 * Sets the PB-WRITE-1 read-half of the round-trip: `ETag: "<content_hash>"` — an RFC 7232 STRONG
 * entity-tag (double-quoted, no `W/`), so the value a client `GET`s is byte-for-byte the `If-Match`
 * the next `PUT` requires. The quotes are part of the frozen value; [contentHash] is the bare
 * unquoted value. Shared by [pageRoutes] (read) and [pageCreateRoutes] (the 201 create response).
 */
internal fun ApplicationCall.setContentHashETag(contentHash: String) {
    response.header(HttpHeaders.ETag, "\"$contentHash\"")
}

/**
 * The §A4 canonical id shape: the 36-char hyphenated UUID form, ANY case (an UPPERCASE path param
 * resolves to the same lowercase id — RestGoldenTest). Deliberately STRICTER than [PageId.of] /
 * `Uuid.parseOrNull`, which also accepts the 32-char hyphenless hex form: the HTTP boundary admits
 * only the canonical hyphenated shape, so a `1-1-1-1-1` AND a 32-hex-no-hyphen id are both
 * `invalid_page_id`, never silently routed to the index lookup. The regex decides 400-vs-404, never
 * JDK leniency.
 */
internal val CANONICAL_PAGE_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * Parses the `{id}` path parameter via the §A4 canonical-shape gate, or itself responds 400
 * `invalid_page_id` and returns null. Shared by [pageRoutes] (read) and [pageWriteRoutes] (PUT) so
 * both gates are byte-identical. The boundary check is the [CANONICAL_PAGE_ID] regex (the §A4
 * canonical hyphenated shape) BEFORE [PageId.of]: a shape-valid id then parses to a [PageId]; a
 * non-canonical one (including the JDK-lenient hex-32 form `PageId.of` would otherwise accept) is
 * 400, never 404.
 */
internal suspend fun ApplicationCall.pageId(): PageId? {
    val raw = parameters["id"].orEmpty()
    val id = raw.takeIf(CANONICAL_PAGE_ID::matches)?.let(PageId::of)
    if (id == null) respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PAGE_ID, "Not a canonical-shape UUID: '$raw'")
    return id
}

/**
 * Redirects to [target], carrying the request's RAW query string through verbatim — so a direct hit
 * (cold load / refresh / pasted link) on `/docs/<alias>?mode=edit` lands on the canonical URL still
 * in edit mode, not the read view. The query is appended unparsed (the SPA, not us, owns its grammar)
 * and only when present, so a no-query redirect stays a clean `Location` with no trailing `?`. The
 * client-side canonical redirect preserves the query the same way (router.history.replace); this is
 * the server-side half of the same rename-stability guarantee, applied to every `/docs`-path hop.
 */
internal suspend fun ApplicationCall.respondRedirectPreservingQuery(target: String, permanent: Boolean) {
    val query = request.queryString()
    respondRedirect(if (query.isEmpty()) target else "$target?$query", permanent)
}

/**
 * The bidi/directional-override controls (`U+202A`-`U+202E`, `U+2066`-`U+2069`) a spoofed name would
 * smuggle — e.g. `gpj.exe` rendered as a reversed `.exe` via U+202E. Shared by the asset-filename gate
 * ([pageWriteRoutes]) and the create-folder gate ([pageCreateRoutes]) so neither drifts: both an asset
 * name and a folder name must reject these on top of [Char.isISOControl].
 */
internal fun Char.isBidiControl(): Boolean = this in Char(0x202A)..Char(0x202E) || this in Char(0x2066)..Char(0x2069)

/** Responds the frozen error envelope `{"error":{"code":…,"message":…}}` (§A4). */
internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respondRest(ErrorEnvelope.serializer(), ErrorEnvelope(ErrorBody(code, message)), status)
}

/**
 * The A3 principal extraction at the top of every GATED route (the A1/A2 seam's first live consumer). Returns the
 * resolved [Principal], or responds the refusal itself and returns null when a `pb_` credential was presented over
 * a non-secure transport ([PrincipalExtraction.InsecureTransportRefused] — 421, refused before the secret was
 * touched). The caller does `val principal = ctx.principalOrRefuse(call) ?: return@get`.
 */
internal suspend fun RouteContext.principalOrRefuse(call: ApplicationCall): Principal? =
    resolveOrRefuse(call)?.principal

/**
 * Like [principalOrRefuse] but surfaces the FULL [PrincipalExtraction.Resolved] (principal + the cookie session's
 * CSRF token), or null after answering 421. The cookie-auth mutating routes (logout, password change) need the
 * resolved session's CSRF token to run [enforceCsrf]; a bearer/anonymous resolution carries a null csrf and is
 * CSRF-exempt by [CsrfGuard.requiresCsrf].
 */
internal suspend fun RouteContext.resolveOrRefuse(call: ApplicationCall): PrincipalExtraction.Resolved? =
    when (val extraction = call.extract()) {
        is PrincipalExtraction.Resolved -> extraction
        PrincipalExtraction.InsecureTransportRefused -> {
            call.respondTransportInsecure()
            null
        }
        is PrincipalExtraction.ProxyIdentityRejected -> {
            call.respondProxyIdentityRejected()
            null
        }
    }

/**
 * The principal source for a MUTATING route (A4a §3): resolve (answering 421 on an insecure-transport credential),
 * THEN enforce CSRF — a cookie-authenticated [Principal.Human] mutation must carry a valid `X-CSRF-Token` (+
 * same-origin when present); a `pb_` bearer [Principal.Agent] is EXEMPT (no ambient cookie), and an anonymous
 * principal is exempt (it has no session — the facade's `check*` will 401 it). Returns the principal, or null after
 * answering 421/403. Every cookie-auth state mutation (page PUT/POST, asset upload, admin actions, logout, password
 * change) routes through this so the CSRF rule lives in ONE place.
 */
internal suspend fun RouteContext.mutatingPrincipalOrRefuse(call: ApplicationCall): Principal? {
    val resolved = resolveOrRefuse(call) ?: return null
    if (!enforceCsrf(call, resolved)) return null
    return resolved.principal
}

/**
 * The CSRF gate for a state mutation, branching on the credential [Source] (A4a §3 + A4b). A cookie-sourced
 * [Principal.Human] runs the A4a SYNCHRONIZER token (the session row's `csrf_token`, unchanged); a proxy-sourced
 * Human (A4b) runs the STATELESS DOUBLE-SUBMIT (`pb_proxy_csrf` cookie == `X-CSRF-Token` header, HMAC-verified by
 * [ProxyCsrf] — no `sessions` row); a `pb_` bearer [Principal.Agent], an [Principal.Anonymous], and a test
 * fixed-principal (`source == null`) are EXEMPT (no ambient credential to forge). Both Human paths also require a
 * same-origin `Origin`/`Referer` when present (fail-closed-WHEN-PRESENT). On failure this responds 403 itself and
 * returns false (the caller does `if (!enforceCsrf(call, resolved)) return@post`).
 */
internal suspend fun RouteContext.enforceCsrf(call: ApplicationCall, resolved: PrincipalExtraction.Resolved): Boolean {
    if (!CsrfGuard.requiresCsrf(resolved.principal)) return true
    return when (resolved.source) {
        Source.COOKIE -> mapCsrfOutcome(call, CsrfGuard.validate(call, resolved.csrfToken!!, trustedProxyCidrs))
        Source.PROXY -> {
            val tokenOk = proxyCsrf.validate(
                presentedCookie = call.request.cookies["pb_proxy_csrf"],
                presentedHeader = call.request.headers["X-CSRF-Token"],
            )
            // The double-submit token is primary; the Origin secondary stays fail-closed-WHEN-PRESENT (reused). Behind
            // a trusted proxy the Origin matches the EXTERNAL host via X-Forwarded-Host (port-agnostic), not the hop.
            val outcome = if (!tokenOk) CsrfGuard.Outcome.TokenMismatch else CsrfGuard.validateOrigin(call, trustedProxyCidrs)
            mapCsrfOutcome(call, outcome)
        }
        // A Human with no source is a test fixed-principal (production never produces one) — exempt, as A4a.
        null -> true
    }
}

/** Maps a [CsrfGuard.Outcome] to true (Ok) or a 403 (token/origin), the shared shape for both CSRF mechanisms. */
private suspend fun mapCsrfOutcome(call: ApplicationCall, outcome: CsrfGuard.Outcome): Boolean = when (outcome) {
    CsrfGuard.Outcome.Ok -> true
    CsrfGuard.Outcome.TokenMismatch -> {
        call.respondError(HttpStatusCode.Forbidden, ErrorCodes.CSRF_FAILED, "Missing or invalid X-CSRF-Token")
        false
    }
    CsrfGuard.Outcome.CrossOrigin -> {
        call.respondError(HttpStatusCode.Forbidden, ErrorCodes.CROSS_ORIGIN, "Cross-origin request rejected")
        false
    }
}

/**
 * The 421 refusal for an insecure-transport credential — the SINGLE rule for [PrincipalExtraction
 * .InsecureTransportRefused] across EVERY gated route, including those with CUSTOM principal handling (the
 * `/docs`, `/p`, `/browse` redirect / SPA-shell-fallback arms). A credential presented over a non-secure
 * transport must be REFUSED (421), never silently downgraded to anonymous and served the shell/redirect — the
 * route-specific deny behavior (serve shell / 302 / 301 / null) applies ONLY to a normal [AccessDenied], not to
 * an insecure-transport refusal. Use [principalOrRefuseToShell] in a route whose deny behavior is "fall through
 * to the public arm".
 */
internal suspend fun ApplicationCall.respondTransportInsecure() {
    // 421 Misdirected Request — not a named constant in this Ktor; the credential (bearer OR cookie) was refused
    // before it was touched (a non-secure transport leaks it), so the request hit the wrong scheme/host.
    respondError(
        HttpStatusCode(421, "Misdirected Request"),
        ErrorCodes.TRANSPORT_INSECURE,
        "A credential was presented over a non-secure transport; it was refused before being honored",
    )
}

/**
 * The secure-context gate for a PUBLIC pre-auth route that carries its credential in the BODY (login, setup-consume,
 * reset-consume). The credential-conditional seam ([PrincipalExtraction]) only fires when a `pb_` bearer or
 * `pb_session` cookie is PRESENT, so a body credential would otherwise slip past it and be read+verified over a leaky
 * transport. This evaluates the SAME credential-AGNOSTIC [isSecureContext] predicate over the SAME socket-peer source
 * the seam uses ([request.local.remoteAddress] — never a client header) + ALL `X-Forwarded-Proto` values + the
 * configured [trustedProxyCidrs]; on a non-secure transport it responds 421 [respondTransportInsecure] and returns
 * true (the caller does `if (call.refuseIfInsecureContext(ctx.trustedProxyCidrs)) return@post` BEFORE reading the body
 * / calling the service). The ONE implementation the three body-credential routes share.
 */
internal suspend fun ApplicationCall.refuseIfInsecureContext(trustedProxyCidrs: List<String>): Boolean {
    val secure = isSecureContext(
        remoteHost = request.local.remoteAddress,
        forwardedProtoValues = request.headers.getAll("X-Forwarded-Proto") ?: emptyList(),
        trustedProxyCidrs = trustedProxyCidrs,
    )
    if (!secure) respondTransportInsecure()
    return !secure
}

/**
 * The route-layer non-blank guard for the A4a auth bodies: the `invalid_auth_request` contract documents a
 * blank field as malformed, but the DTO decode accepts blank strings. Each route passes the fields it requires
 * non-blank ((name → value) pairs) — `username`/`token` always; `password`/`newPassword`/`currentPassword` where a
 * blank secret is never valid (setup-consume always requires a password; reset/change set a NEW one). On the first
 * blank field this responds 400 `invalid_auth_request` and returns true (the caller does
 * `if (call.refuseIfBlank(…)) return@post` BEFORE calling the service). One implementation across login/setup/reset/
 * change/admin-create.
 */
internal suspend fun ApplicationCall.refuseIfBlank(vararg fields: Pair<String, String>): Boolean {
    val blank = fields.firstOrNull { it.second.isBlank() } ?: return false
    respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_AUTH_REQUEST, "${blank.first} must not be blank")
    return true
}

/**
 * The principal source for a route whose deny behavior is to fall through to a PUBLIC arm (serve the SPA shell,
 * 404, etc.) rather than 401/403. Maps the [PrincipalExtraction]: an insecure-transport credential is ALWAYS the
 * 421 refusal (returns [ExtractedPrincipal.Refused] — the route returns immediately); a [PrincipalExtraction
 * .Resolved] yields the principal for the route to proceed with. This is what stops a credential over plaintext
 * from being silently downgraded to anonymous and served the shell instead of 421.
 */
internal suspend fun RouteContext.principalOrRefuseToShell(call: ApplicationCall): ExtractedPrincipal =
    when (val extraction = call.extract()) {
        is PrincipalExtraction.Resolved -> ExtractedPrincipal.Resolved(extraction.principal)
        PrincipalExtraction.InsecureTransportRefused -> {
            call.respondTransportInsecure()
            ExtractedPrincipal.Refused
        }
        is PrincipalExtraction.ProxyIdentityRejected -> {
            call.respondProxyIdentityRejected()
            ExtractedPrincipal.Refused
        }
    }

/**
 * The 400 for a malformed proxy identity header ([PrincipalExtraction.ProxyIdentityRejected], A4b): a trusted
 * proxy passed the secret+transport gate but sent a malformed subject — operator MISCONFIG, not an attacker, so 400
 * not 401/421. The message names the CLASS of problem; it NEVER echoes the offending value (the reason category is in
 * the operator log, not the wire).
 */
private suspend fun ApplicationCall.respondProxyIdentityRejected() {
    respondError(
        HttpStatusCode.BadRequest,
        ErrorCodes.INVALID_PROXY_IDENTITY,
        "Proxy identity header is malformed; check the reverse-proxy configuration",
    )
}

/**
 * The result of [principalOrRefuseToShell]: either a [Resolved] principal to proceed with, or [Refused] (the
 * route already answered 421 and must return). Distinct from the raw [PrincipalExtraction] so the route's
 * `when` is total and the 421 response is already sent.
 */
internal sealed interface ExtractedPrincipal {
    data class Resolved(val principal: Principal) : ExtractedPrincipal

    data object Refused : ExtractedPrincipal
}

/**
 * Runs [body] under the A3 choke point, mapping the two facade-thrown conditions to their frozen envelopes — and it
 * is the ONE place either mapping lives, so a route never catches or re-mints them:
 *  - [AccessDenied] with [DenyReason.POLICY] → 401 `unauthorized` for an [Principal.Anonymous] (no credential),
 *    403 `forbidden` for an authenticated-but-unauthorized principal (the role×action matrix denied it);
 *  - [AccessDenied] with [DenyReason.ROOT_NOT_EDITABLE] → 403 `root_not_editable` (the root refuses page writes in
 *    EVERY auth mode — but only ever AFTER authn has passed, so anonymous still sees 401 and the flag never leaks);
 *  - [RootUnavailable] → 503 `root_unavailable` + [ROOT_UNAVAILABLE_RETRY_AFTER_SECONDS];
 *  - [AbsenceUnverified] → 503 `absence_unverified` + [ABSENCE_UNVERIFIED_RETRY_AFTER_SECONDS] (C1) — a HEALTHY
 *    root holding ONE page whose absence nobody has proven. A separate code, because it is a separate fact and a
 *    separate remedy: nothing to restore, nothing to restart, and it clears itself.
 *
 * All are thrown BEFORE any resolve/membership work (or, for availability, immediately after the gate passes), so
 * a denied read never leaks page existence and an unauthenticated prober never learns a root's topology.
 */
internal suspend inline fun ApplicationCall.guarded(body: () -> Unit) {
    try {
        body()
    } catch (denied: AccessDenied) {
        when {
            // The REASON is checked BEFORE the principal type, and that ordering is load-bearing. A
            // ROOT_NOT_EDITABLE deny can only ever be produced AFTER authn has passed (the gate's own arms run
            // authn -> editable -> matrix), so 401 is never the right answer for it - and under `auth.mode = off`,
            // where Anonymous is a legitimate principal and the server is not asking for credentials at all, a 401
            // would be nonsense. The enforced-anonymous case still gets its 401, because there the AUTHN arm denies
            // first and arrives here as POLICY.
            denied.reason == DenyReason.ROOT_NOT_EDITABLE ->
                respondError(
                    HttpStatusCode.Forbidden,
                    ErrorCodes.ROOT_NOT_EDITABLE,
                    "This root is configured read-only (editable = false); page writes are not accepted here",
                )
            denied.principal is Principal.Anonymous ->
                respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
            else -> respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "You do not have permission for this action")
        }
    } catch (unavailable: RootUnavailable) {
        response.header(HttpHeaders.RetryAfter, ROOT_UNAVAILABLE_RETRY_AFTER_SECONDS.toString())
        respondError(
            HttpStatusCode.ServiceUnavailable,
            ErrorCodes.ROOT_UNAVAILABLE,
            // The MESSAGE carries the difference between the causes, because the operator ACTION differs: a vanished
            // root wants its disk back; a detached one wants its name back in roots{}. The CODE stays one.
            "Root '${unavailable.root.value}' is not serving (${unavailable.reason.name.lowercase()}). Nothing was " +
                "written. The page still exists - do not discard it. Restore the root and restart the server.",
        )
    } catch (unverified: AbsenceUnverified) {
        response.header(HttpHeaders.RetryAfter, ABSENCE_UNVERIFIED_RETRY_AFTER_SECONDS.toString())
        respondError(
            HttpStatusCode.ServiceUnavailable,
            ErrorCodes.ABSENCE_UNVERIFIED,
            "The page '${unverified.subject}' is still bound in root '${unverified.root.value}' and its content cannot be " +
                "read right now. It is NOT deleted - nothing here has proven that, and nothing was written. Do not discard " +
                "the page or its citations; retry shortly. If its file really is gone for good, the page converges once an " +
                "absence proof arrives (a git commit, a completed observation, or `plainbase root reconcile`).",
        )
    }
}

/**
 * The `Retry-After` a 503 `root_unavailable` advertises. Recovery is an operator restart, not a transient blip, so
 * five minutes balances agent politeness against how long a fixed deployment sits unnoticed.
 */
internal const val ROOT_UNAVAILABLE_RETRY_AFTER_SECONDS: Int = 300

/**
 * The `Retry-After` a 503 `absence_unverified` advertises (C1) - SHORTER than the root-unavailable one, and for a
 * reason rather than as a nicety: limbo is derived per pass and SELF-HEALS the moment the page is witnessed again.
 * A page that comes back with the next watcher-driven rebuild is serving in seconds, with no operator in the loop,
 * so a five-minute window would leave an agent sitting out an outage that had already ended.
 */
internal const val ABSENCE_UNVERIFIED_RETRY_AFTER_SECONDS: Int = 30

/**
 * Reads the request body as a stream, counting bytes, and returns the buffered bytes — or null the
 * moment the count would exceed [limit] (so an over-cap body aborts BEFORE the whole thing is
 * buffered). `Content-Length` is never trusted: it can lie, so the stream count is the only
 * authority. Shared by the PB-WRITE-1 PUT (raw save) and POST (create) routes.
 */
internal suspend fun ApplicationCall.receiveBodyCapped(limit: Long): ByteArray? {
    val channel: ByteReadChannel = receiveChannel()
    val out = Buffer()
    var count = 0L
    while (!channel.isClosedForRead) {
        // Read at most one chunk PAST the limit so an over-cap body aborts before the whole thing is
        // buffered; Content-Length is never consulted (it can lie).
        val chunk = channel.readRemaining(BODY_READ_CHUNK).readByteArray()
        count += chunk.size
        if (count > limit) {
            channel.cancel(BodyTooLargeCancellation)
            return null
        }
        out.write(chunk)
    }
    return out.readByteArray()
}

/** The cancellation cause when the body exceeds the cap — never surfaced; the route answers 413 itself. */
private val BodyTooLargeCancellation = kotlinx.io.IOException("PB-WRITE-1 body exceeds the configured cap")

private const val BODY_READ_CHUNK: Long = 64 * 1024

/** The frozen 413 `body_too_large` envelope (`max_bytes` authoritative) — shared by PUT and POST. */
internal suspend fun ApplicationCall.respondBodyTooLarge(maxBytes: Long) {
    respondText(
        RestJson.encodeToString(
            BodyTooLargeEnvelope.serializer(),
            BodyTooLargeEnvelope(
                BodyTooLargeBody(
                    code = ErrorCodes.BODY_TOO_LARGE,
                    message = "Request body exceeds the $maxBytes-byte limit",
                    maxBytes = maxBytes,
                ),
            ),
        ),
        ContentType.Application.Json,
        HttpStatusCode.PayloadTooLarge,
    )
}

/**
 * The FROZEN PB-SEARCH-1 message for a malformed percent-escape in the query string (`?q=%`,
 * `?q=100%`), naming the offending §A1 parameter when one of them is the culprit (the realistic
 * case the golden pins) and the query string itself otherwise (a malformed UNKNOWN parameter —
 * §A1 would ignore its value, but Ktor's routing decodes the whole query string eagerly, so the
 * request is undecodable as delivered and 400 is the only honest answer). One source for the
 * literal: the search route's defensive decode and the `StatusPages` net both answer with it.
 */
internal fun malformedQueryMessage(raw: Parameters): String {
    val parameter = listOf("q", "limit", "offset").firstOrNull { name ->
        raw.getAll(name).orEmpty().any { runCatching { it.decodeURLQueryComponent(plusIsSpace = true) }.isFailure }
    }
    return "${parameter ?: "query string"} contains malformed percent-encoding"
}

/**
 * The RAW (still percent-encoded) request path after [prefix] — the input `PercentCoding.decodeOnce`
 * expects. Ktor's own decoded routing parameters are deliberately NOT used for path data: that
 * decoder has different rules (no `%2F` rejection, lenient UTF-8) and would be the forbidden second
 * decoder (chunk 1.5 rule).
 *
 * Null when the request carries no tail at all — a `{path...}` route also matches its bare mount
 * point (`GET /assets`, `GET /assets/`), and echoing the mount point back as a candidate path made
 * for misleading 400 messages; callers answer those with a message naming the expected form instead.
 */
internal fun ApplicationCall.rawPathAfter(prefix: String): String? =
    request.uri.substringBefore('?').substringBefore('#')
        .takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { it.isNotEmpty() }

/**
 * Decodes a raw URL tail ONCE per PB-LINK-1 ([PercentCoding.decodeOnce]) and validates it as a
 * content-relative [TreePath] (NFC by construction). Null means the input can never name content:
 * malformed/over-encoded escapes, `%2F`, invalid strict UTF-8, traversal (`..`), absoluteness, or
 * empty segments — the caller's 400 `invalid_path`.
 */
internal fun decodedTreePath(raw: String): TreePath? {
    val decoded = PercentCoding.decodeOnce(raw) as? PercentCoding.DecodeResult.Success ?: return null
    return TreePath.of(decoded.value)
}

/**
 * The ONE first-segment rule every root-scoped route grammar shares (C3, ADR-0011 D3): if the
 * decoded tail's first segment names a [known] registry root, that root scopes the remainder
 * (null remainder = a bare-root tail); otherwise null - the WHOLE tail is a legacy main-relative
 * path. One implementation so the docs/assets/by-path/browse grammars can never drift; how a
 * non-root answer responds (301 on browser surfaces, resolve-under-main on API surfaces) is the
 * caller's decision, never encoded here.
 */
internal fun splitRootTail(path: TreePath, known: Set<RootName>): Pair<RootName, TreePath?>? {
    val root = RootName.of(path.segments.first())?.takeIf { it in known } ?: return null
    val remainder = path.segments.drop(1).takeIf { it.isNotEmpty() }?.let { TreePath.require(it.joinToString("/")) }
    return root to remainder
}

/**
 * Serves the embedded SPA shell (the Phase-0 `frontend/dist` index.html bundled under `static/`).
 * The shell is one immutable resource per binary, so its bytes are read once and cached.
 */
internal suspend fun ApplicationCall.respondSpaShell() {
    val shell = SpaShell.bytes
    if (shell == null) {
        respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "SPA shell is not bundled")
    } else {
        respondBytes(shell, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
}

private object SpaShell {
    val bytes: ByteArray? by lazy {
        SpaShell::class.java.classLoader.getResourceAsStream("static/index.html")?.use { it.readBytes() }
    }
}

/**
 * An embedded SPA bundle file under `static/assets/` (where the Vite build emits its js/css) for a
 * [TreePath]-validated `/assets/`-relative path, or null. The asset route's bundle-wins lookup — it runs
 * BEFORE the content-tree read, so a real bundle name is always served the embedded bytes (a content asset
 * can never shadow a `<script src>`/`<link href>` slot). The [TreePath] validation upstream guarantees the
 * lookup cannot traverse out of `static/assets/`.
 */
internal fun staticResourceBytes(relativePath: String): ByteArray? =
    SpaShell::class.java.classLoader.getResourceAsStream("static/assets/$relativePath")?.use { it.readBytes() }

/**
 * The `Content-Security-Policy` header name (not a named constant in this Ktor). ONE literal, shared by
 * the per-asset sandbox header ([assetRoute]) and the shell-CSP plugin ([shellSecurityHeadersPlugin]) so
 * the two never drift.
 */
internal const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

/** The MIME-sniff defense header (not a named constant in this Ktor); shared by the asset + shell paths. */
internal const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

/** The small extension → content-type map for served assets (§A4); unknown → octet-stream. */
internal fun assetContentType(fileName: String): ContentType {
    val type = ASSET_CONTENT_TYPES[fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()]
        ?: ContentType.Application.OctetStream
    // Text types get an explicit charset; assets in the tree are UTF-8 by the content conventions.
    return if (type.contentType == "text" && type.charset() == null) type.withCharset(Charsets.UTF_8) else type
}

/**
 * Types that are non-executable as a TOP-LEVEL document — served without a sandbox CSP. Anything NOT
 * listed here (svg, js/mjs, pdf, any future scriptable [ASSET_CONTENT_TYPES] entry) is sandboxed by
 * default — inert-unless-proved-safe. Derived DELIBERATELY from the map: every map value is either listed
 * inert here or is intentionally scriptable, so a future map addition not added here is sandboxed (safe).
 */
private val INERT_ASSET_TYPES: Set<ContentType> = setOf(
    ContentType.Image.PNG, ContentType.Image.JPEG, ContentType.Image.GIF,
    ContentType.parse("image/webp"), ContentType.parse("image/x-icon"),
    ContentType.Text.CSS, ContentType.Text.Plain, ContentType.Text.CSV,
    ContentType.Application.Json, ContentType.parse("application/yaml"),
    ContentType.Application.OctetStream,
    ContentType.parse("font/woff"), ContentType.parse("font/woff2"),
)

/**
 * True ⇒ the asset response needs the per-asset sandbox CSP (item 6). Compares without charset params so
 * `text/plain; charset=UTF-8` (the charset [assetContentType] appends to text types) matches `text/plain`.
 */
internal fun assetNeedsSandbox(contentType: ContentType): Boolean =
    contentType.withoutParameters() !in INERT_ASSET_TYPES

private val ASSET_CONTENT_TYPES: Map<String, ContentType> = mapOf(
    "svg" to ContentType.Image.SVG,
    "png" to ContentType.Image.PNG,
    "jpg" to ContentType.Image.JPEG,
    "jpeg" to ContentType.Image.JPEG,
    "gif" to ContentType.Image.GIF,
    "webp" to ContentType.parse("image/webp"),
    "ico" to ContentType.parse("image/x-icon"),
    "css" to ContentType.Text.CSS,
    "js" to ContentType.parse("text/javascript"),
    "mjs" to ContentType.parse("text/javascript"),
    "json" to ContentType.Application.Json,
    "yaml" to ContentType.parse("application/yaml"),
    "yml" to ContentType.parse("application/yaml"),
    "txt" to ContentType.Text.Plain,
    "csv" to ContentType.Text.CSV,
    "pdf" to ContentType.Application.Pdf,
    "woff" to ContentType.parse("font/woff"),
    "woff2" to ContentType.parse("font/woff2"),
)
