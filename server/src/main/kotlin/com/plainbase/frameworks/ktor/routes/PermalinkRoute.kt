package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.page.PageId
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /p/{id}` and `/p/{id}/{anything}` — the permanent ID permalink, the §A4 durability layer.
 *
 * **302**, never 301: the target moves with the page and must never be cached permanently. The
 * trailing segment is tolerated and ignored (stale slugs in old links keep working). Shape-invalid
 * id → 400 `invalid_page_id`; shape-valid unknown → 404 `page_not_found` (regex decides, §A4).
 *
 * **Collision losers (documented reading):** a path-space collision loser has `url = null` — there
 * is no canonical `/docs/...` to redirect to, yet §A4 promises the loser "remains fully reachable
 * via its `/p/{id}` permalink". We therefore serve the SPA shell directly at the permalink (200):
 * the permalink IS the loser's only human URL, and the API surface (`/api/v1/pages/{id}`) resolves
 * it regardless. Redirecting (nowhere to go) or 404ing (breaks the promise) would both be wrong.
 */
fun Route.permalinkRoute(services: RestServices) {
    get("/p/{id}") { call.handlePermalink(services) }
    get("/p/{id}/{trailing...}") { call.handlePermalink(services) }
}

private suspend fun ApplicationCall.handlePermalink(services: RestServices) {
    val raw = parameters["id"].orEmpty()
    val id = PageId.of(raw)
        ?: return respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PAGE_ID, "Not a canonical-shape UUID: '$raw'")
    val page = services.indexBuilder.current.byId[id]
        ?: return respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
    when (val url = page.url) {
        null -> respondSpaShell() // collision loser: the permalink is its only human URL (see class doc)
        else -> respondRedirectPreservingQuery(url, permanent = false)
    }
}
