package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.git.UnknownRevisionException
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.DiffResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.HistoryResponse
import com.plainbase.frameworks.ktor.dto.toDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * W5 per-page history read surface (non-frozen, D-4/D-5):
 *  - `GET /api/v1/pages/{id}/history` — the file's commits newest-first.
 *  - `GET /api/v1/pages/{id}/diff?from={sha}&to={sha}` — the file's unified diff between two commits.
 *
 * Both answer **empty 200 with `git_enabled:false`** when Git mode is off (the SPA owns the "history
 * requires Git mode" copy) — never a 404 for the FEATURE; a 404 here always means the page id is unknown
 * (distinct concern, mirrors [pageRoutes]). The `git_enabled` flag comes from [com.plainbase.domain
 * .history.HistoryProvider.enabled], never type-sniffing. A bad/unknown `from`/`to` SHA in `diff` is a
 * 404 `not_found` (D-6: "no such thing", the unknown-asset family); a malformed (non-hex) param is a 400
 * `invalid_query`. The git reads run off the CIO event loop on `Dispatchers.IO`.
 *
 * KNOWN LIMIT (owner-deferred 2026-06-18, review-burst security lens): each request is bounded per-call
 * (GitExecutor's stdout byte cap + the process timeout), but there is NO concurrency cap across requests —
 * N simultaneous `/history`/`/diff` calls spawn N git subprocesses. Acceptable for the single-binary
 * self-hosted model; a bounded git-read semaphore (+ 503 on saturation) is a tracked follow-up hardening
 * item, deliberately NOT bolted onto W5 (concurrency-limiting is a cross-cutting server concern).
 */
fun Route.historyRoutes(ctx: RouteContext) {
    route("/api/v1/pages") {
        get("/{id}/history") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.pageId() ?: return@guarded
                val page = ctx.read.pageById(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                // Bound the response by default (defense-in-depth over the GitExecutor byte cap): a page with very
                // deep history would otherwise return an unbounded list. DEFAULT_HISTORY_LIMIT newest-first commits
                // is plenty for the UI; cursor/pagination is the documented future grow (not built here).
                val commits = withContext(Dispatchers.IO) { ctx.read.history(principal, page.page.path, DEFAULT_HISTORY_LIMIT) }
                call.respondRest(
                    HistoryResponse.serializer(),
                    HistoryResponse(gitEnabled = ctx.read.gitEnabled(principal), commits = commits.map { it.toDto() }),
                )
            }
        }
        get("/{id}/diff") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.pageId() ?: return@guarded
                val from = call.commitRef("from") ?: return@guarded
                val to = call.commitRef("to") ?: return@guarded
                val page = ctx.read.pageById(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                // Off Git the no-op provider returns an empty diff; the flag tells the client to render its own
                // "Git off" copy rather than treat the empty diff as a real one.
                // Only an UNRESOLVABLE ref is a 404 (W5 P2): an operational diff failure (timeout, corrupt repo,
                // unsupported flag) is a plain GitCommandException that propagates to the default 500 path —
                // collapsing it to 404 would hide a real failure as "no diff / not found".
                val diff = try {
                    withContext(Dispatchers.IO) { ctx.read.diff(principal, from, to, page.page.path) }
                } catch (e: UnknownRevisionException) {
                    logger.debug(e) { "diff $from..$to of ${page.page.path.value} failed (unknown/unresolvable ref)" }
                    return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No diff between $from and $to")
                }
                call.respondRest(DiffResponse.serializer(), diff.toDto(gitEnabled = ctx.read.gitEnabled(principal)))
            }
        }
    }
}

private val COMMIT_REF = Regex("[0-9a-fA-F]{7,64}")
private val logger = KotlinLogging.logger {}

/** Default `/history` page size — the newest N commits. Bounds the response; pagination is a future grow. */
private const val DEFAULT_HISTORY_LIMIT = 100

/**
 * Reads a `from`/`to` commit-ref query parameter, gating it on a hex-shape regex (a 7-to-64-hex abbrev or
 * full object id) BEFORE it reaches `git` — a malformed value is a 400 `invalid_query`, never a shell of a
 * git error. Responds the 400 itself and returns null on a missing/malformed value.
 */
private suspend fun ApplicationCall.commitRef(name: String): String? {
    val raw = request.queryParameters[name]
    if (raw == null || !COMMIT_REF.matches(raw)) {
        respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_QUERY, "$name must be a hex commit ref")
        return null
    }
    return raw
}
