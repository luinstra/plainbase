package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.config.PlainbaseConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthStatus(val status: String, val version: String)

fun Route.healthRoute() {
    get("/healthz") {
        call.respond(HealthStatus(status = "ok", version = PlainbaseConfig.VERSION))
    }
}
