package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.ktor.dto.ErrorBody
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ErrorEnvelope
import com.plainbase.frameworks.ktor.dto.RestJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.serialization.KSerializer

/** Responds [value] through the scoped [RestJson] serializer (present-null guaranteed, §A4). */
internal suspend fun <T> ApplicationCall.respondRest(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(RestJson.encodeToString(serializer, value), ContentType.Application.Json, status)
}

/** Responds the frozen error envelope `{"error":{"code":…,"message":…}}` (§A4). */
internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respondRest(ErrorEnvelope.serializer(), ErrorEnvelope(ErrorBody(code, message)), status)
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
