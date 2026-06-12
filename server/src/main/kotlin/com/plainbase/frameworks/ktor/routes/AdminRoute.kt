package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.RescanResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /api/v1/admin/rescan` — the §C4 rescan hook: runs the full index pass and publishes the
 * new snapshot (which also invalidates the memoized tree JSON, keyed on snapshot identity).
 *
 * TEMPORARY and UNAUTHENTICATED by accepted risk R7: Phase 1 ships no auth layer, and the worst
 * an abuser can trigger is redundant rebuild work. The auth phase puts this behind admin
 * credentials; the route must not grow any other capability before then.
 */
fun Route.adminRoute(services: RestServices) {
    post("/api/v1/admin/rescan") {
        val snapshot = services.indexBuilder.rebuild()
        call.respondRest(RescanResponse.serializer(), RescanResponse(status = "ok", pages = snapshot.pages.size))
    }
}
