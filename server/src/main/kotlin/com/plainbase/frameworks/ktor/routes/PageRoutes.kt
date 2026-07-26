package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PageHtmlResponse
import com.plainbase.frameworks.ktor.dto.PageMetadataResponse
import com.plainbase.frameworks.ktor.dto.PageResponse
import com.plainbase.frameworks.ktor.dto.ValidateLinksResponse
import com.plainbase.frameworks.ktor.dto.toDto
import com.plainbase.frameworks.ktor.dto.toMetadataDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * PB-REST-1 page endpoints (§A4, frozen):
 *  - `GET /api/v1/pages/{id}` — full page payload; the `{id}` parameter accepts any case under the
 *    canonical-shape rule, responses always carry lowercase.
 *  - `GET /api/v1/pages/by-path/{path}` — identical shape; `{path}` is the URL-slugified
 *    `/docs/`-relative form INCLUDING the root segment (`main/guides/deploy-guide`), percent-decoded
 *    ONCE (PB-LINK-1), matched case-sensitively against canonical paths first, then the alias
 *    registry. A legacy rootless tail resolves under main (C3, D-C3-3).
 *  - `GET /api/v1/pages/{id}/html` — sanitized HTML + the document-order `headings` array.
 *  - `GET /api/v1/pages/{id}/validate-links` — the page's broken links + anchors (PB-READ-2, frozen).
 *  - `GET /api/v1/pages/{id}/metadata` — the server-derived metadata projection (PB-READ-2, frozen).
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
        byPathRoute(ctx)
        pageByIdRoute(ctx)
        pageHtmlRoute(ctx)
        validateLinksRoute(ctx)
        pageMetadataRoute(ctx)
    }
}

private fun Route.byPathRoute(ctx: RouteContext) {
    // The constant `by-path` segment outranks the `{id}` parameter in Ktor's resolution.
    get("/by-path/{path...}") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val raw = call.rawPathAfter("/api/v1/pages/by-path/")
                ?: return@guarded call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_PATH,
                    "Expected a page path: /api/v1/pages/by-path/{path}",
                )
            val decoded = decodedTreePath(raw)
                ?: return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid page path: '$raw'")
            val (root, path) = splitRootTail(decoded, ctx.roots) ?: (RootName.MAIN to decoded)
            val payload = path?.let { ctx.read.pageByUrlPath(principal, root, it) }
                ?: return@guarded call.respondError(
                    HttpStatusCode.NotFound,
                    ErrorCodes.PAGE_NOT_FOUND,
                    "No page at path ${decoded.value}",
                )
            val dto = payload.toDto()
            call.setContentHashETag(dto.contentHash)
            call.respondRest(PageResponse.serializer(), dto)
        }
    }
}

private fun Route.pageByIdRoute(ctx: RouteContext) {
    get("/{id}") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val id = call.pageId() ?: return@guarded
            val pin = call.pinnedRootOrRefuse() ?: return@guarded
            val payload = ctx.read.pageById(principal, id, pin.root)
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            val dto = payload.toDto()
            call.setContentHashETag(dto.contentHash)
            call.respondRest(PageResponse.serializer(), dto)
        }
    }
}

private fun Route.pageHtmlRoute(ctx: RouteContext) {
    get("/{id}/html") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val id = call.pageId() ?: return@guarded
            val pin = call.pinnedRootOrRefuse() ?: return@guarded
            val payload = ctx.read.pageHtml(principal, id, pin.root)
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            call.respondRest(PageHtmlResponse.serializer(), payload.toDto())
        }
    }
}

private fun Route.validateLinksRoute(ctx: RouteContext) {
    get("/{id}/validate-links") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val id = call.pageId() ?: return@guarded
            val pin = call.pinnedRootOrRefuse() ?: return@guarded
            val report = ctx.read.validateLinks(principal, id, pin.root)
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            call.respondRest(ValidateLinksResponse.serializer(), report.toDto())
        }
    }
}

private fun Route.pageMetadataRoute(ctx: RouteContext) {
    get("/{id}/metadata") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val id = call.pageId() ?: return@guarded
            val pin = call.pinnedRootOrRefuse() ?: return@guarded
            val page = ctx.read.pageMetadata(principal, id, pin.root)
                ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            call.respondRest(PageMetadataResponse.serializer(), page.toMetadataDto())
        }
    }
}
