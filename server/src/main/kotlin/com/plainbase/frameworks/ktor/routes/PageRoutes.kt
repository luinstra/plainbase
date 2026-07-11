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
        // The constant `by-path` segment outranks the `{id}` parameter in Ktor's resolution, so
        // this never shadows a real id (no canonical-shape UUID equals "by-path" anyway).
        // C3 root grammar, API style (D-C3-3): a known-root first segment scopes the remainder; any
        // other tail RESOLVES under main directly - never a 301 (a REST hop would tax every legacy
        // agent client for zero canonicalization benefit; the body's `url` field is canonical). The
        // SPA's intercepted-click surface (lib/links.ts routes in-app /docs/... anchors through the
        // router, so they land here, never on the server's /docs handler) works across legacy URLs
        // precisely BECAUSE of this resolve - do not later "simplify" by-path to a redirect without
        // re-deciding that surface.
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
                // A bare known root is a well-formed MISS (the SPA's folder-landing fallthrough
                // branches on 404 only), never a malformed path.
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
        // Phase 5 `validate_links` (PB-READ-2). The constant `validate-links` suffix outranks the bare `{id}` by
        // Ktor specificity, exactly like `html` above — no registration-order hazard.
        get("/{id}/validate-links") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.pageId() ?: return@guarded
                val report = ctx.read.validateLinks(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                call.respondRest(ValidateLinksResponse.serializer(), report.toDto())
            }
        }
        // Phase 5 `get_page_metadata` (PB-READ-2). The server's TYPED projection (content_hash/commit/url/title/
        // headings) — distinct from `read_file`'s raw body. Constant `metadata` suffix outranks bare `{id}`.
        get("/{id}/metadata") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.pageId() ?: return@guarded
                val page = ctx.read.pageMetadata(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                call.respondRest(PageMetadataResponse.serializer(), page.toMetadataDto())
            }
        }
    }
}
