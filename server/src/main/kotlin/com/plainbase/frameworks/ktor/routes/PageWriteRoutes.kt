package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.WriteIntent
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.UnsupportedEditBody
import com.plainbase.frameworks.ktor.dto.UnsupportedEditEnvelope
import com.plainbase.frameworks.ktor.dto.WriteWire
import com.plainbase.frameworks.ktor.dto.toWire
import com.plainbase.frameworks.ktor.dto.unsupportedEditCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * PB-WRITE-1 (chunk W3a, FROZEN): `PUT /api/v1/pages/{id}` — the one content-mutating save route.
 * A thin wire mapping over W1's [com.plainbase.domain.service.WritePipeline.write]; W1 owns the
 * correctness (disk-authoritative CAS, the rename guard, the write-ahead dirty mark), W3a pins the
 * outcome→wire mapping forever.
 *
 * The request is RAW: the body is the EXACT document bytes (`Content-Type: text/markdown`, written
 * VERBATIM — BOM and leniently-accepted invalid UTF-8 included), and `base_hash` rides the
 * `If-Match` header as an RFC 7232 STRONG entity-tag `"sha256:<64 lowercase hex>"`. RAW makes a
 * `GET`'s `ETag` the next `PUT`'s `If-Match` by construction — JSON would have round-tripped the
 * document through a string and made a BOM/invalid-UTF-8 file un-`PUT`-able forever.
 *
 * Every rejection is explicit and golden-pinnable — never a Ktor-default 500. The order matters:
 * id-shape → media type → `If-Match` shape → body-cap (streamed) → index lookup → route-layer id
 * check → pipeline.
 */
fun Route.pageWriteRoutes(services: RestServices) {
    route("/api/v1/pages") {
        put("/{id}") {
            val id = call.pageId() ?: return@put

            // (2) Media type — RAW body must be text/markdown.
            if (call.request.contentType().withoutParameters() != ContentType.parse("text/markdown")) {
                return@put call.respondError(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                    "PUT requires Content-Type: text/markdown (the raw document bytes)",
                )
            }

            // (3) If-Match base_hash — must be a present, double-quoted, strong `"sha256:<64-hex>"`.
            val baseHash = call.parseIfMatchBaseHash()
                ?: return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_BASE_HASH,
                    "If-Match must be a strong entity-tag \"sha256:<64 lowercase hex>\" (the base_hash you last saw)",
                )

            // (4) Stream the body counting bytes to limit+1; over the cap aborts BEFORE buffering it all.
            val bytes = call.receiveBodyCapped(services.maxWriteBodyBytes)
                ?: return@put call.respondBodyTooLarge(services.maxWriteBodyBytes)

            // (5) Path-param id is the identity authority (R1): an id absent from the index is 404 —
            // the route never invents a path.
            val current = services.indexBuilder.current.byId[id]
                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")

            // (6) Route-layer id-tamper check (R1): the IDENTITY the submitted buffer would be assigned
            // must equal the page's currently-honored frontmatter identity. The comparison runs through
            // the SAME grammar identity ASSIGNMENT uses — `PageId.of(readIdValue(bytes))`
            // (`PageIdentityService.kt:61`, `IndexBuilder.kt:284`): the lenient-decoded raw id line
            // parsed as a canonical UUID. That makes the check quote-tolerant (`id: "<uuid>"` and
            // `id: <uuid>` denote the SAME PageId, never a false 422) and keeps it lenient (no strict-UTF-8
            // trap). The page's honored identity is `current.id` when it was materialized from frontmatter
            // (FRONTMATTER source ⇒ materialized, `IndexBuilder.kt:291`), else there is no honored on-disk
            // id and the buffer must declare none. Adding/changing/removing the honored id is a rename →
            // 422, before the pipeline runs; the path-param `{id}` stays the identity authority (R1).
            val submittedId = PATCHER.readIdValue(bytes)?.let(PageId::of)
            val honoredId = current.id.takeIf { current.materialized }
            if (submittedId != honoredId) {
                return@put call.respondUnsupportedEdit("id")
            }

            // (7) The pipeline owns the write; render the outcome via the frozen wire mapping, applying
            // the retry-idempotency shim (stale base_hash but on-disk == submitted → 200 no-op).
            val submittedHash = services.citations.contentHash(bytes)
            val outcome = services.writePipeline.write(WriteIntent(id, current.path, baseHash, bytes))
            call.respondWriteWire(outcome.toWire(submittedHash))
        }
    }
}

/** The single frontmatter id-detection grammar (lenient decode — the id-inspection trap is closed in W3a). */
private val PATCHER = FrontmatterPatcher()

/** The frozen `If-Match` form: a double-quoted strong entity-tag wrapping `sha256:` + 64 lowercase hex. */
private val IF_MATCH_BASE_HASH = Regex("\"(sha256:[0-9a-f]{64})\"")

/**
 * Parses the `If-Match` header to the bare `sha256:<64-hex>` base_hash, or null when it is missing,
 * unquoted, weak (`W/"…"`), or not the frozen shape. Only the SHAPE is validated here: a shape-valid
 * hash that simply doesn't match disk is the 409 drift path, not a 400.
 */
private fun ApplicationCall.parseIfMatchBaseHash(): String? =
    request.headers[HttpHeaders.IfMatch]?.let { IF_MATCH_BASE_HASH.matchEntire(it)?.groupValues?.get(1) }

private suspend fun ApplicationCall.respondUnsupportedEdit(field: String) {
    respondText(
        RestJson.encodeToString(
            UnsupportedEditEnvelope.serializer(),
            UnsupportedEditEnvelope(
                UnsupportedEditBody(
                    code = unsupportedEditCode(field),
                    field = field,
                    message = "The submitted body's frontmatter id does not match the page id — identity is immutable.",
                ),
            ),
        ),
        ContentType.Application.Json,
        HttpStatusCode.UnprocessableEntity,
    )
}

private suspend fun ApplicationCall.respondWriteWire(wire: WriteWire) {
    respondText(wire.encode(), ContentType.Application.Json, wire.status)
}
