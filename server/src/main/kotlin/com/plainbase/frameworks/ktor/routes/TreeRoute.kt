package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/v1/tree` (§A4, frozen as SHAPE; child ordering is documented-not-frozen). The JSON is
 * memoized per published snapshot (inside [com.plainbase.frameworks.ktor.GuardedReadFacade], §C4), so
 * steady-state requests serve a cached string. A3: `read`-gated through the facade.
 */
fun Route.treeRoute(ctx: RouteContext) {
    get("/api/v1/tree") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            call.respondText(ctx.read.tree(principal), ContentType.Application.Json)
        }
    }
}
