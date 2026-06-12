package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.page.PageId
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PageHtmlResponse
import com.plainbase.frameworks.ktor.dto.PageResponse
import com.plainbase.frameworks.ktor.dto.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
 */
fun Route.pageRoutes(services: RestServices) {
    route("/api/v1/pages") {
        // The constant `by-path` segment outranks the `{id}` parameter in Ktor's resolution, so
        // this never shadows a real id (no canonical-shape UUID equals "by-path" anyway).
        get("/by-path/{path...}") {
            val raw = call.rawPathAfter("/api/v1/pages/by-path/")
                ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_PATH,
                    "Expected a page path: /api/v1/pages/by-path/{path}",
                )
            val path = decodedTreePath(raw)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid page path: '$raw'")
            val payload = services.pageService.byUrlPath(path)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page at path ${path.value}")
            call.respondRest(PageResponse.serializer(), payload.toDto())
        }
        get("/{id}") {
            val id = call.pageId() ?: return@get
            val payload = services.pageService.byId(id)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            call.respondRest(PageResponse.serializer(), payload.toDto())
        }
        get("/{id}/html") {
            val id = call.pageId() ?: return@get
            val payload = services.pageService.htmlById(id)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            call.respondRest(PageHtmlResponse.serializer(), payload.toDto())
        }
    }
}

/** Parses the `{id}` parameter or responds 400 `invalid_page_id` (and returns null) itself. */
private suspend fun ApplicationCall.pageId(): PageId? {
    val raw = parameters["id"].orEmpty()
    val id = PageId.of(raw)
    if (id == null) respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PAGE_ID, "Not a canonical-shape UUID: '$raw'")
    return id
}
