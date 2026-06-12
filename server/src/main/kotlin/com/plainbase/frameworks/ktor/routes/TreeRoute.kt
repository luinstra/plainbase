package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RestServices
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/v1/tree` (§A4, frozen as SHAPE; child ordering is documented-not-frozen). The JSON is
 * memoized per published snapshot ([RestServices.treeJson], §C4), so steady-state requests serve a
 * cached string.
 */
fun Route.treeRoute(services: RestServices) {
    get("/api/v1/tree") {
        call.respondText(services.treeJson.current(), ContentType.Application.Json)
    }
}
