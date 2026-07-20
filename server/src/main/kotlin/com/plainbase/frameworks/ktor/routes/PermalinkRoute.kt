package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.PermalinkResolution
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.AmbiguousCandidate
import com.plainbase.frameworks.ktor.dto.AmbiguousPageIdBody
import com.plainbase.frameworks.ktor.dto.AmbiguousPageIdEnvelope
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /p/{id}` (+ trailing) — the permanent ID permalink, the §A4 durability layer — and `GET /p/r/{root}/{id}`
 * (+ trailing), its root-pinned form (C4). Both **302**, never 301: the target moves with the page. Shape-invalid id → 400
 * `invalid_page_id`; shape-valid unknown → 404 `page_not_found`; **RETIRED → 410 `page_retired`** naming the
 * last-known path.
 *
 * **The bare `/p/{id}` is id_map-FIRST (Option B, C4):** it resolves the owning root from the durable index, so a
 * bare id held by MORE THAN ONE root answers **300 Multiple Choices** with one `Link: rel="alternate"` per candidate
 * (the `/p/r/{root}/{id}` disambiguation targets, rank order) - FAKE-only under `UNIQUE(id)`, but the shape ships in
 * C4. The rooted `/p/r/{root}/{id}` is the disambiguation surface itself: a malformed root slug → 400 `invalid_root`
 * (the sole pre-auth exception, a SYNTAX error), an unregistered/detached root → 404 AFTER `checkRead`; it is a
 * COHERENT-STALE snapshot-first read (the pinned-READ split) and never itself Ambiguous.
 *
 * **The bare `/p/{id}` IGNORES `?root=` on purpose** - `/p/r/{root}/{id}` IS the pin surface here, and the 300 above
 * hands out exactly that form, so a second spelling would be a second grammar for one decision. A `?root=` sent to
 * the bare form is simply not read: it rides through into the 302 target with the rest of the query string
 * ([respondRedirectPreservingQuery]), which is harmless - the target is a canonical url, not another id lookup.
 *
 * **Collision losers (documented reading):** a path-space collision loser has `url = null`; §A4 promises the loser
 * "remains fully reachable via its `/p/{id}` permalink", so we serve the SPA shell directly at the permalink (200).
 *
 * A3: `read`-gated — the resolution goes through the guarded facade, so the gate fires (401/403) BEFORE the resolve.
 * **A root that is not serving answers 503, never 404** (ADR-0011 D5): the facade throws and `guarded {}` maps it.
 */
fun Route.permalinkRoute(ctx: RouteContext) {
    // The constant `r` segment outranks the `{id}` parameter (same specificity rule as by-path/html), so this never
    // shadows a bare `/p/{id}` and must be registered so the constant is seen.
    get("/p/r/{root}/{id}") { call.handleRootedPermalink(ctx) }
    // Both forms tolerate a decorative trailing slug and ignore it. The rooted one needs its own tailcard or it
    // falls into the bare one below with id='r' and answers 400 - and the 300 above hands clients exactly the
    // `/p/r/{root}/{id}` URLs they are apt to decorate.
    get("/p/r/{root}/{id}/{trailing...}") { call.handleRootedPermalink(ctx) }
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
            is PermalinkResolution.Retired -> respondRetired(id, resolution)
            PermalinkResolution.Unknown ->
                respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            // C4: a bare id held by more than one root - 300 with a Link per disambiguation target (FAKE-only).
            is PermalinkResolution.Ambiguous -> respondAmbiguousPermalink(id, resolution.candidates)
        }
    }
}

private suspend fun ApplicationCall.handleRootedPermalink(ctx: RouteContext) {
    val principal = ctx.principalOrRefuse(this) ?: return
    guarded {
        // Malformed root slug -> 400 invalid_root (the sole pre-auth exception, a syntax error); a well-formed but
        // unregistered root is a 404 AFTER checkRead (deferred registration, 5.2a #3).
        val rawRoot = parameters["root"].orEmpty()
        val root = RootName.of(rawRoot)
            ?: return@guarded respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_ROOT, "Not a valid root name: '$rawRoot'")
        // The shared §A4 canonical gate (RouteSupport.pageId): STRICTER than the lenient PageId.of the bare
        // /p/{id} keeps (shipped pre-C4) - the hyphenless hex-32 form is 400 here, same as every REST id route.
        val id = pageId() ?: return@guarded
        when (val resolution = ctx.read.permalinkAt(principal, root, id)) {
            is PermalinkResolution.Found -> respondRedirectPreservingQuery(resolution.url, permanent = false)
            PermalinkResolution.LoserNoUrl -> respondSpaShell()
            is PermalinkResolution.Retired -> respondRetired(id, resolution)
            PermalinkResolution.Unknown ->
                respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            // A root-pinned resolution is never Ambiguous (the root is already named). Structurally unreachable; a bare
            // ISE here would surface as a clean 500 rather than silently serving a wrong root.
            is PermalinkResolution.Ambiguous -> error("permalinkAt is root-pinned and never Ambiguous")
        }
    }
}

/** 410 Gone naming the last-known path (C0) - so a human or agent holding the citation learns the page was deleted. */
private suspend fun ApplicationCall.respondRetired(id: PageId, resolution: PermalinkResolution.Retired) = respondError(
    HttpStatusCode.Gone,
    ErrorCodes.PAGE_RETIRED,
    "Page ${id.value} was deleted; it last lived at ${resolution.lastKnownPath.path.value} in " +
        "root '${resolution.lastKnownPath.root}'. The id is retired and will never name another page.",
)

/**
 * 300 Multiple Choices for a bare id held by more than one root (C4): one `Link: </p/r/{root}/{id}>; rel="alternate"`
 * per candidate in rank order, plus a body of the same disambiguation URLs. A retired-Ambiguous answers the same 300
 * (every candidate then answers 410 - the 300 disambiguates WHICH tombstone).
 */
private suspend fun ApplicationCall.respondAmbiguousPermalink(id: PageId, candidates: List<RootName>) {
    candidates.forEach { response.header(HttpHeaders.Link, "</p/r/${it.value}/${id.value}>; rel=\"alternate\"") }
    // Ambiguity is TRANSIENT - it ends the moment the duplicate is resolved - but 300 is heuristically cacheable, so an
    // intermediary would keep serving "pick a root" long after there is only one.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondRest(
        AmbiguousPageIdEnvelope.serializer(),
        AmbiguousPageIdEnvelope(
            AmbiguousPageIdBody(
                code = ErrorCodes.AMBIGUOUS_PAGE_ID,
                message = ambiguousMessage(id),
                candidates = candidates.map { AmbiguousCandidate(root = it.value, url = "/p/r/${it.value}/${id.value}") },
            ),
        ),
        HttpStatusCode.MultipleChoices,
    )
}
