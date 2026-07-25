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
 * The single `/p/{segments...}` dispatcher owns bare `/p/{id}` and rooted `/p/{root}/{id}` permalinks. Both answer
 * 302, never 301, because the target moves with the page. Shape-invalid ids answer 400 `invalid_page_id`,
 * shape-valid unknown ids answer 404 `page_not_found`, and retired ids answer 410 `page_retired`.
 *
 * Bare classification is lenient and runs first, so a valid page id plus decorative trailing segments always stays
 * bare. Rooted classification requires a legal root followed by a strict canonical page id. The raw request tail is
 * split without percent-decoding: canonical UUID characters are URL-unreserved, and encoded ids or slashes are 400.
 * Contiguous trailing empty segments are decorative; leading or interior empty segments are malformed.
 *
 * **The bare `/p/{id}` is id_map-FIRST (Option B, C4):** it resolves the owning root from the durable index, so a
 * bare id held by MORE THAN ONE root answers **300 Multiple Choices** with one `Link: rel="alternate"` per candidate
 * (the `/p/{root}/{id}` disambiguation targets, rank order) - genuinely reachable now that `UNIQUE(id, root)` (C5)
 * legalizes the same id in several roots. The rooted form is the disambiguation surface itself: a malformed root slug returns 400 `invalid_root`
 * (the sole pre-auth exception, a SYNTAX error), while an unregistered or detached root returns 404 AFTER `checkRead`. It is a
 * COHERENT-STALE snapshot-first read (the pinned-READ split) and never itself Ambiguous.
 *
 * **The bare `/p/{id}` IGNORES `?root=` on purpose** - `/p/{root}/{id}` IS the pin surface here, and the 300 above
 * hands out exactly that form, so a second spelling would be a second grammar for one decision. A `?root=` sent to
 * the bare form is simply not read: it rides through into the 302 target with the rest of the query string
 * ([respondRedirectPreservingQuery]), which is harmless - the target is a canonical url, not another id lookup.
 *
 * **Collision losers (documented reading):** a path-space collision loser has `url = null`; §A4 promises the loser
 * "remains fully reachable via its permalink" (the rooted `/p/{root}/{id}`, and the bare form too),
 * so we serve the SPA shell directly at the permalink (200).
 *
 * A3: `read`-gated — the resolution goes through the guarded facade, so the gate fires (401/403) BEFORE the resolve.
 * **A root that is not serving answers 503, never 404** (ADR-0011 D5): the facade throws and `guarded {}` maps it.
 */
fun Route.permalinkRoute(ctx: RouteContext) {
    get("/p/{segments...}") { call.handlePermalinkDispatch(ctx) }
}

private suspend fun ApplicationCall.handlePermalinkDispatch(ctx: RouteContext) {
    val principal = ctx.principalOrRefuse(this) ?: return
    guarded {
        val raw = rawPathAfter("/p/")
            ?: return@guarded respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.INVALID_PAGE_ID,
                "Not a canonical-shape UUID: ''",
            )
        val segments = raw.split("/").dropLastWhile { it.isEmpty() }
        if (segments.isEmpty() || segments.any { it.isEmpty() }) {
            return@guarded respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.INVALID_PAGE_ID,
                "Not a canonical-shape permalink path: '$raw'",
            )
        }

        val first = segments[0]
        val bareId = PageId.of(first)
        if (bareId != null) {
            return@guarded when (val resolution = ctx.read.permalink(principal, bareId)) {
                is PermalinkResolution.Found -> respondRedirectPreservingQuery(resolution.url, permanent = false)
                PermalinkResolution.LoserNoUrl -> respondSpaShell()
                is PermalinkResolution.Retired -> respondRetired(bareId, resolution)
                PermalinkResolution.Unknown ->
                    respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${bareId.value}")
                is PermalinkResolution.Ambiguous ->
                    respondAmbiguousPermalink(bareId, resolution.candidates, resolution.hasRetiredCandidate)
            }
        }

        val root = RootName.of(first)
        if (segments.size >= 2 && root != null) {
            val id = canonicalPageId(segments[1])
                ?: return@guarded respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_PAGE_ID,
                    "Not a canonical-shape UUID: '${segments[1]}'",
                )
            return@guarded when (val resolution = ctx.read.permalinkAt(principal, root, id)) {
                is PermalinkResolution.Found -> respondRedirectPreservingQuery(resolution.url, permanent = false)
                PermalinkResolution.LoserNoUrl -> respondSpaShell()
                is PermalinkResolution.Retired -> respondRetired(id, resolution)
                PermalinkResolution.Unknown ->
                    respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                is PermalinkResolution.Ambiguous -> error("permalinkAt is root-pinned and never Ambiguous")
            }
        }

        if (segments.size == 1) {
            respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PAGE_ID, "Not a canonical-shape UUID: '$first'")
        } else {
            respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_ROOT, "Not a valid root name: '$first'")
        }
    }
}

// This route has no `{id}` parameter, so it parses the raw segment with the shared strict canonical gate.
private fun canonicalPageId(segment: String): PageId? =
    segment.takeIf(CANONICAL_PAGE_ID::matches)?.let(PageId::of)

/** 410 Gone naming the requested root and last-known path, with alternate live roots in Link headers only. */
private suspend fun ApplicationCall.respondRetired(id: PageId, resolution: PermalinkResolution.Retired) {
    resolution.liveElsewhere.forEach {
        response.header(HttpHeaders.Link, "</p/${it.value}/${id.value}>; rel=\"alternate\"")
    }
    // Retirement is reversible when the same (root, path) reclaims the id, so a cached 410 could mask its return.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondError(
        HttpStatusCode.Gone,
        ErrorCodes.PAGE_RETIRED,
        "Page ${id.value} was deleted from root '${resolution.lastKnownPath.root}'; it last lived at " +
            "${resolution.lastKnownPath.path.value} there." +
            if (resolution.liveElsewhere.isNotEmpty()) {
                " It may still name a live page in another root; see the Link: rel=\"alternate\" header(s)."
            } else {
                ""
            },
    )
}

/**
 * 300 Multiple Choices for a bare id the resolver refuses to pick one root for (C5): one
 * `Link: </p/{root}/{id}>; rel="alternate"` per candidate in rank order, plus a body of the same disambiguation URLs.
 * [hasRetiredCandidate] widens the message (STATUS-NEUTRAL) when a candidate is a tombstone - the mixed live+retired
 * fail-closed arm, or a purely-retired multi-tombstone id (each candidate then answers 410).
 */
private suspend fun ApplicationCall.respondAmbiguousPermalink(
    id: PageId,
    candidates: List<RootName>,
    hasRetiredCandidate: Boolean,
) {
    candidates.forEach { response.header(HttpHeaders.Link, "</p/${it.value}/${id.value}>; rel=\"alternate\"") }
    // Ambiguity is TRANSIENT - it ends the moment the duplicate is resolved - but 300 is heuristically cacheable, so an
    // intermediary would keep serving "pick a root" long after there is only one.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondRest(
        AmbiguousPageIdEnvelope.serializer(),
        AmbiguousPageIdEnvelope(
            AmbiguousPageIdBody(
                code = ErrorCodes.AMBIGUOUS_PAGE_ID,
                message = ambiguousMessage(id, hasRetiredCandidate = hasRetiredCandidate),
                candidates = candidates.map { AmbiguousCandidate(root = it.value, url = "/p/${it.value}/${id.value}") },
            ),
        ),
        HttpStatusCode.MultipleChoices,
    )
}
