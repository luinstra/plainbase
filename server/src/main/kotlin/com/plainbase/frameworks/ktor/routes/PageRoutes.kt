package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PageHtmlResponse
import com.plainbase.frameworks.ktor.dto.PageResponse
import com.plainbase.frameworks.ktor.dto.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * PB-REST-1 page endpoints (§A4, frozen):
 *  - `GET /api/v1/pages/{id}` — full page payload; the `{id}` parameter accepts any case under the
 *    canonical-shape rule, responses always carry lowercase.
 *  - `GET /api/v1/pages/by-path/{path}` — identical shape; `{path}` is the URL-slugified
 *    `/docs/`-relative form, percent-decoded ONCE (PB-LINK-1), matched case-sensitively against
 *    canonical paths first, then the alias registry.
 *  - `GET /api/v1/pages/{id}/html` — sanitized HTML + the document-order `headings` array.
 *
 * 400 vs 404 is decided by the spec regex through [PageId.of], never by `UUID.fromString` leniency:
 * shape-invalid → 400 `invalid_page_id`; shape-valid-but-unknown (ANY version — opaque identity,
 * owner ruling) → 404 `page_not_found`.
 *
 * A3: each handler is `read`-gated — it extracts the [com.plainbase.domain.principal.Principal] via the A1 seam
 * and reads through the guarded [com.plainbase.domain.service.ReadFacade]; [guarded] maps a denied read to
 * 401/403 BEFORE the page lookup (no existence leak).
 */
fun Route.pageRoutes(ctx: RouteContext) {
    route("/api/v1/pages") {
        // The constant `by-path` segment outranks the `{id}` parameter in Ktor's resolution, so
        // this never shadows a real id (no canonical-shape UUID equals "by-path" anyway).
        get("/by-path/{path...}") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val raw = call.rawPathAfter("/api/v1/pages/by-path/")
                    ?: return@guarded call.respondError(
                        HttpStatusCode.BadRequest,
                        ErrorCodes.INVALID_PATH,
                        "Expected a page path: /api/v1/pages/by-path/{path}",
                    )
                val path = decodedTreePath(raw)
                    ?: return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid page path: '$raw'")
                val payload = ctx.read.pageByUrlPath(principal, path)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page at path ${path.value}")
                val dto = payload.toDto()
                call.setContentHashETag(dto.contentHash)
                call.respondRest(PageResponse.serializer(), dto)
            }
        }
        get("/{id}") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.pageId() ?: return@guarded
                val payload = ctx.read.pageById(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                val dto = payload.toDto()
                call.setContentHashETag(dto.contentHash)
                call.respondRest(PageResponse.serializer(), dto)
            }
        }
        get("/{id}/html") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.pageId() ?: return@guarded
                val payload = ctx.read.pageHtml(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                call.respondRest(PageHtmlResponse.serializer(), payload.toDto())
            }
        }
    }
}
