package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PreviewResponse
import com.plainbase.frameworks.ktor.dto.toDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /api/v1/preview` (W3b, PRIVATE / NON-CONTRACTUAL): a read-only render of a SUBMITTED Markdown
 * buffer. The RAW `text/markdown` body is the document buffer; the server renders it through the SAME
 * renderer the index uses ([com.plainbase.domain.service.IndexBuilder.renderPreview] — the §3
 * single-renderer rule), with PB-SLUG-1 heading ids + PB-LINK-1 link rewriting resolved against the
 * CURRENT published snapshot. Returns sanitized HTML + the document-order headings (the editor TOC).
 *
 * READ-ONLY: no CAS, no write, no persistence, no id, no reindex, no snapshot swap. Body cap via the
 * shared [receiveBodyCapped] (the same `maxWriteBodyBytes` PUT uses — a preview buffer is the same
 * size class as a saved document). NOT in `ForeverApiGoldenSuite`; no byte-equal-to-`/html` claim.
 */
fun Route.previewRoute(services: RestServices) {
    post("/api/v1/preview") {
        // (1) Media type — the RAW body is a Markdown document (mirror the PUT guard).
        if (call.request.contentType().withoutParameters() != ContentType.parse("text/markdown")) {
            return@post call.respondError(
                HttpStatusCode.UnsupportedMediaType,
                ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                "Preview requires Content-Type: text/markdown (the raw document buffer)",
            )
        }

        // (2) Stream the body counting bytes to limit+1; over the cap aborts BEFORE buffering it all.
        val bytes = call.receiveBodyCapped(services.maxWriteBodyBytes)
            ?: return@post call.respondBodyTooLarge(services.maxWriteBodyBytes)

        // (3) The buffer's notional location for relative-href resolution: an optional ?path= (validated
        // content-relative), else a fixed synthetic root path for a not-yet-saved buffer.
        val sourcePath = call.previewPath()

        // (4) Single-renderer reuse against the current published snapshot (READ-ONLY).
        val rendered = services.indexBuilder.renderPreview(sourcePath, bytes)

        call.respondRest(
            PreviewResponse.serializer(),
            PreviewResponse(html = rendered.html, headings = rendered.headings.map { it.toDto() }),
        )
    }
}

/** The fixed synthetic content-root location used when a preview supplies no `?path=` (an unsaved buffer). */
private val SYNTHETIC_PREVIEW_PATH: TreePath = TreePath.require("preview.md")

/**
 * The buffer's relative-link resolution context: the optional `?path=` (a content-relative MULTI-segment
 * path), or [SYNTHETIC_PREVIEW_PATH] when absent or unparseable. Preview is non-contractual, so an unusable
 * `?path=` degrades to the synthetic root rather than erroring — relative links are merely less faithful,
 * never a 400.
 *
 * Read from Ktor's EAGER-decoded [queryParameters] (it turns `%2F`→`/`) and parsed via [TreePath.of] —
 * single-decode (Ktor decodes once; `TreePath.of` does not decode again, it splits on `/` + NFC-normalizes).
 * The strict-decoder / "forbidden second decoder" rule that protects the asset filename's single-segment gate
 * does NOT apply here: `sourcePath` is ONLY a link-resolution base for the renderer — no containment / filesystem
 * decision rides on it (preview reads nothing from disk) — and a multi-segment relative path legitimately carries
 * its `/` separators encoded as `%2F` (a standard `URLSearchParams` encodes `path=guides%2Fx.md`), which the
 * strict per-segment decoder would reject as a smuggled separator, silently falling back to the synthetic root.
 */
private fun ApplicationCall.previewPath(): TreePath =
    request.queryParameters["path"]?.let(TreePath::of) ?: SYNTHETIC_PREVIEW_PATH
