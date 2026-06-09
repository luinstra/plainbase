package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.routes.healthRoute
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * Ktor on the CIO engine — the only engine Plainbase will ever use
 * (pure-Kotlin coroutines, native-image friendly; Netty is banned, §3).
 */
class KtorServer(private val config: PlainbaseConfig) {

    fun start(wait: Boolean) {
        embeddedServer(CIO, host = config.host, port = config.port) {
            plainbaseModule()
        }.start(wait = wait)
    }
}

/** Shared between the real server and `testApplication` tests. */
fun Application.plainbaseModule() {
    install(ContentNegotiation) {
        // kotlinx.serialization is the only serializer in the tree (§3).
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    routing {
        healthRoute()
        // Built SPA shell, embedded as static resources by the :server build.
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
