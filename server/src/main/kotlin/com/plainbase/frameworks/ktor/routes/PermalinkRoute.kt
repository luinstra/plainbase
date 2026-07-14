package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.PermalinkResolution
import com.plainbase.frameworks.ktor.RouteContext
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
 * id → 400 `invalid_page_id`; shape-valid unknown → 404 `page_not_found` (regex decides, §A4);
 * **RETIRED → 410 `page_retired`** (C0), naming the last-known path. The 410 is the entire reason
 * `retired_binding` exists: without a tombstone, a deleted page's permalink degrades to a 404 that is
 * indistinguishable from "that id was never real", and every agent citation to it silently rots.
 *
 * **Collision losers (documented reading):** a path-space collision loser has `url = null` — there
 * is no canonical `/docs/...` to redirect to, yet §A4 promises the loser "remains fully reachable
 * via its `/p/{id}` permalink". We therefore serve the SPA shell directly at the permalink (200):
 * the permalink IS the loser's only human URL, and the API surface (`/api/v1/pages/{id}`) resolves
 * it regardless. Redirecting (nowhere to go) or 404ing (breaks the promise) would both be wrong.
 *
 * A3: `read`-gated — the resolution goes through the guarded facade, so the gate fires (401/403)
 * BEFORE the resolve and a redirect cannot leak page existence to an unauthorized caller.
 *
 * **A root that is not serving answers 503, never 404** (ADR-0011 D5): the facade throws and the existing
 * `guarded {}` wrap maps it, so this route needs no new arm. That is load-bearing for a page in a root that was
 * unavailable at BOOT - it was never scanned, so it is in no snapshot section, and only the persisted `id_map`
 * binding the facade consults can tell "the disk is unmounted" from "this page never existed".
 */
fun Route.permalinkRoute(ctx: RouteContext) {
    get("/p/{id}") { call.handlePermalink(ctx) }
    get("/p/{id}/{trailing...}") { call.handlePermalink(ctx) }
}

private suspend fun ApplicationCall.handlePermalink(ctx: RouteContext) {
    val principal = ctx.principalOrRefuse(this) ?: return
    guarded {
        val raw = parameters["id"].orEmpty()
        val id = PageId.of(raw)
            ?: return@guarded respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PAGE_ID, "Not a canonical-shape UUID: '$raw'")
        when (val resolution = ctx.read.permalink(principal, id)) {
            is PermalinkResolution.Found -> respondRedirectPreservingQuery(resolution.url, permanent = false)
            // collision loser: the permalink is its only human URL (see class doc)
            PermalinkResolution.LoserNoUrl -> respondSpaShell()
            // A RETIRED binding is GONE, not missing (C0). 410 names the last-known path, so a human or an agent
            // holding the citation learns what happened instead of being told the id never existed.
            is PermalinkResolution.Retired -> respondError(
                HttpStatusCode.Gone,
                ErrorCodes.PAGE_RETIRED,
                "Page ${id.value} was deleted; it last lived at ${resolution.lastKnownPath.path.value} in " +
                    "root '${resolution.lastKnownPath.root}'. The id is retired and will never name another page.",
            )
            PermalinkResolution.Unknown ->
                respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
        }
    }
}
