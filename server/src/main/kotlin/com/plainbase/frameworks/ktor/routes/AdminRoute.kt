@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ReindexResponse
import com.plainbase.frameworks.ktor.dto.RescanResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The admin surface (§C4 / §A5). Both routes are TEMPORARY and UNAUTHENTICATED by accepted risk
 * R7: Phase 1/2 ship no auth layer, and the worst an abuser can trigger is redundant rebuild work.
 * The auth phase (Phase 4) puts BOTH behind admin credentials via the same route-walk; the routes
 * must not grow any other capability before then.
 *
 *  - `POST /api/v1/admin/rescan` — the rescan hook: runs the full index pass (`IndexBuilder.rebuild`)
 *    and republishes the snapshot, whose publication listeners then DIFF-SYNC the search engine
 *    (`SearchIndexer.sync`, engine-truth diffing). Also invalidates the memoized tree JSON.
 *  - `POST /api/v1/admin/reindex` — bypasses the diff and forces a full generation-swap rebuild of
 *    the search engine (`SearchProvider.rebuild`) over the CURRENT published snapshot — same
 *    snapshot, but the index is rebuilt from scratch rather than incrementally reconciled.
 */
fun Route.adminRoute(services: RestServices) {
    post("/api/v1/admin/rescan") {
        val snapshot = services.indexBuilder.rebuild()
        call.respondRest(RescanResponse.serializer(), RescanResponse(status = "ok", pages = snapshot.pages.size))
    }

    post("/api/v1/admin/reindex") {
        // Phase 4: gate behind manage (same TODO as rescan; the route-walk finds both).
        // compareAndSet(false, true) IS the §A5 single-flight: the first request flips the flag and
        // proceeds, any concurrent request sees the flip fail and gets 409 reindex_in_flight. The
        // finally release means a thrown rebuild never wedges the flag (a wedged flag would 409
        // forever — the one real hazard, closed here).
        if (!services.reindexInFlight.compareAndSet(expectedValue = false, newValue = true)) {
            return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.REINDEX_IN_FLIGHT, "a reindex is already running")
        }
        try {
            // Dispatchers.IO: SearchProvider.rebuild is blocking JDBC (SearchDb.write →
            // synchronized(writer)), which must never park a CIO event-loop thread (mirrors SearchRoute).
            val outcome = withContext(Dispatchers.IO) { services.reindex() }
            call.respondRest(ReindexResponse.serializer(), outcome)
        } finally {
            services.reindexInFlight.store(false)
        }
    }
}
