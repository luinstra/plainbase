package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.config.PlainbaseConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** The `/healthz` liveness payload: a fixed `ok` status and the running server version. */
@Serializable
data class HealthStatus(val status: String, val version: String)

/** Registers the unauthenticated `GET /healthz` liveness probe. */
fun Route.healthRoute() {
    get("/healthz") {
        call.respond(HealthStatus(status = "ok", version = PlainbaseConfig.VERSION))
    }
}
