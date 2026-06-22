package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.AccessDenied
import com.plainbase.frameworks.ktor.PrincipalExtraction
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.BodyTooLargeBody
import com.plainbase.frameworks.ktor.dto.BodyTooLargeEnvelope
import com.plainbase.frameworks.ktor.dto.ErrorBody
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ErrorEnvelope
import com.plainbase.frameworks.ktor.dto.RestJson
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

/** Responds [value] through the scoped [RestJson] serializer (present-null guaranteed, §A4). */
internal suspend fun <T> ApplicationCall.respondRest(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(RestJson.encodeToString(serializer, value), ContentType.Application.Json, status)
}

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
private val CANONICAL_PAGE_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

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
    when (val extraction = call.extract()) {
        is PrincipalExtraction.Resolved -> extraction.principal
        PrincipalExtraction.InsecureTransportRefused -> {
            call.respondTransportInsecure()
            null
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
    // 421 Misdirected Request — not a named constant in this Ktor; the bearer was refused before it was touched
    // (a non-secure transport leaks the credential), so the request hit the wrong scheme/host.
    respondError(
        HttpStatusCode(421, "Misdirected Request"),
        ErrorCodes.TRANSPORT_INSECURE,
        "A bearer credential was presented over a non-secure transport; it was refused before being honored",
    )
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
 * Runs [body] under the A3 choke point, mapping a [AccessDenied] thrown by a facade `check*` to the frozen
 * auth envelope: 401 `unauthorized` for an [Principal.Anonymous] (no credential), 403 `forbidden` for an
 * authenticated-but-unauthorized principal (the role×action matrix denied it). Every gated route wraps its
 * facade call(s) in this so the deny → status mapping lives in ONE place. The facade's [AccessDenied] is thrown
 * BEFORE any resolve/membership work, so a denied read never leaks page existence.
 */
internal suspend inline fun ApplicationCall.guarded(body: () -> Unit) {
    try {
        body()
    } catch (denied: AccessDenied) {
        if (denied.principal is Principal.Anonymous) {
            respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
        } else {
            respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "You do not have permission for this action")
        }
    }
}

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
 * [TreePath]-validated `/assets/`-relative path, or null. Used only as the asset route's fallback;
 * the [TreePath] validation upstream guarantees the lookup cannot traverse out of `static/assets/`.
 */
internal fun staticResourceBytes(relativePath: String): ByteArray? =
    SpaShell::class.java.classLoader.getResourceAsStream("static/assets/$relativePath")?.use { it.readBytes() }

/** The small extension → content-type map for served assets (§A4); unknown → octet-stream. */
internal fun assetContentType(fileName: String): ContentType {
    val type = ASSET_CONTENT_TYPES[fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()]
        ?: ContentType.Application.OctetStream
    // Text types get an explicit charset; assets in the tree are UTF-8 by the content conventions.
    return if (type.contentType == "text" && type.charset() == null) type.withCharset(Charsets.UTF_8) else type
}

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
