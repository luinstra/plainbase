package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.ktor.dto.BodyTooLargeBody
import com.plainbase.frameworks.ktor.dto.BodyTooLargeEnvelope
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.RestJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.charset
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

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
 * The `?root=` query pin shape (C4, the 5.2a auth-defer sweep). GRAMMAR only: a [Named] carries a legal slug
 * (whether or not it names a REGISTERED root - registration/ownership is deferred to AFTER the auth gate, in the
 * facade), and only a [Malformed] slug or a [Repeated] parameter is answered pre-auth (400 `invalid_root`).
 * [Absent] is a bare read/write.
 */
internal sealed interface RootPin {
    data object Absent : RootPin

    data class Named(val root: RootName) : RootPin

    data class Malformed(val raw: String) : RootPin

    /** `?root=a&root=b` - two pins, one decision. Both may be perfectly legal slugs; that is not the problem. */
    data object Repeated : RootPin
}

/**
 * Classifies the optional `?root=` query parameter by GRAMMAR ([RootName.of]); never consults the registry.
 *
 * A REPEATED parameter is its own refusal rather than a first-value read. This is the one surface whose entire job
 * is disambiguation, so silently keeping `a` out of `?root=a&root=b` would answer a question the caller did not ask
 * - and answer it about which root's disk a write lands on. Two pins is a malformed request, not a preference.
 */
internal fun ApplicationCall.rootPin(): RootPin {
    val values = request.queryParameters.getAll("root") ?: return RootPin.Absent
    if (values.size > 1) return RootPin.Repeated
    val raw = values.single()
    return RootName.of(raw)?.let(RootPin::Named) ?: RootPin.Malformed(raw)
}

/**
 * The `?root=` pin as a plain [RootName]? for the id-addressed reads/writes: [RootPin.Absent] -> null (a bare
 * read), [RootPin.Named] -> its root (registration deferred to the facade), and a [RootPin.Malformed] slug or a
 * [RootPin.Repeated] parameter responds 400 `invalid_root` here (both syntax errors, the sole pre-auth exception)
 * and returns null. The caller does `val pin = call.pinnedRootOrRefuse() ?: return@guarded` (null ==
 * already-answered) and then reads `pin.root`, distinguished from a legal absent pin because absent yields
 * [PinResolved]`(null)`, not null.
 */
internal suspend fun ApplicationCall.pinnedRootOrRefuse(): PinResolved? = when (val pin = rootPin()) {
    RootPin.Absent -> PinResolved(null)
    is RootPin.Named -> PinResolved(pin.root)
    is RootPin.Malformed -> {
        respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_ROOT, "?root must be a valid root name: '${pin.raw}'")
        null
    }
    RootPin.Repeated -> {
        respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_ROOT, "?root was given more than once; name exactly one root")
        null
    }
}

/** A resolved (grammar-valid or absent) `?root=` pin: [root] is null for an absent pin. */
internal data class PinResolved(val root: RootName?)

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
