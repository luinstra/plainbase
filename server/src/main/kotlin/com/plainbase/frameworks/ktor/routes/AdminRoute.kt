package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.ReindexResult
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ReindexResponse
import com.plainbase.frameworks.ktor.dto.RescanResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The admin surface (§C4 / §A5). A3 puts BOTH routes behind the `manage` action: each extracts the
 * [com.plainbase.domain.principal.Principal] and calls the guarded [com.plainbase.domain.service.MutatingFacade]
 * `rescan`/`reindex`, which `checkManage()`-gate (mint a `ManageGrant`, record the audit row), so only an ADMIN
 * (or loopback-dev) reaches the rebuild. [guarded] maps a deny to 401/403.
 *
 *  - `POST /api/v1/admin/rescan` — the rescan hook: runs the full index pass (`IndexBuilder.rebuild(grant)`)
 *    and republishes the snapshot, whose publication listeners then DIFF-SYNC the search engine.
 *  - `POST /api/v1/admin/reindex` — forces a full generation-swap rebuild of the search engine over the
 *    CURRENT published snapshot (`IndexBuilder.rebuildSearchIndex(grant)`). The §A5 single-flight lives INSIDE
 *    the facade (never exposed): a concurrent call returns [ReindexResult.InFlight] → 409 `reindex_in_flight`.
 */
fun Route.adminRoute(ctx: RouteContext) {
    post("/api/v1/admin/rescan") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val snapshot = ctx.mutate.rescan(principal)
            call.respondRest(RescanResponse.serializer(), RescanResponse(status = "ok", pages = snapshot.pages.size))
        }
    }

    post("/api/v1/admin/reindex") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            // Blocking JDBC must never park a CIO event-loop thread (mirrors SearchRoute): the manage check +
            // single-flight + generation-swap rebuild all run on Dispatchers.IO inside the facade's reindex.
            when (val result = withContext(Dispatchers.IO) { ctx.mutate.reindex(principal) }) {
                is ReindexResult.Done ->
                    call.respondRest(ReindexResponse.serializer(), ReindexResponse(status = "ok", pages = result.pages))
                ReindexResult.InFlight ->
                    call.respondError(HttpStatusCode.Conflict, ErrorCodes.REINDEX_IN_FLIGHT, "a reindex is already running")
            }
        }
    }
}
