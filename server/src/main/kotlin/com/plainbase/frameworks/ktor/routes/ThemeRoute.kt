package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.root.ServerTopLevel
import com.plainbase.frameworks.protocol.ErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/** Owns every method and descendant fail-closed until the named theme endpoints exist. */
fun Route.themeRoutes() {
    route("/${ServerTopLevel.PLAINBASE}/{...}") {
        handle {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such theme endpoint")
        }
    }
}
