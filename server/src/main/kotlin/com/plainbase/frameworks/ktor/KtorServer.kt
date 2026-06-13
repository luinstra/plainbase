package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.routes.adminRoute
import com.plainbase.frameworks.ktor.routes.apiFallbackRoute
import com.plainbase.frameworks.ktor.routes.assetRoute
import com.plainbase.frameworks.ktor.routes.browseRedirectRoute
import com.plainbase.frameworks.ktor.routes.docsRoutes
import com.plainbase.frameworks.ktor.routes.healthRoute
import com.plainbase.frameworks.ktor.routes.malformedQueryMessage
import com.plainbase.frameworks.ktor.routes.pageRoutes
import com.plainbase.frameworks.ktor.routes.permalinkRoute
import com.plainbase.frameworks.ktor.routes.respondError
import com.plainbase.frameworks.ktor.routes.searchRoute
import com.plainbase.frameworks.ktor.routes.treeRoute
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * Ktor on the CIO engine — the only engine Plainbase will ever use
 * (pure-Kotlin coroutines, native-image friendly; Netty is banned, §3).
 */
class KtorServer(
    private val config: PlainbaseConfig,
    private val services: RestServices,
) {

    fun start(wait: Boolean) {
        embeddedServer(CIO, host = config.host, port = config.port) {
            plainbaseModule(services)
        }.start(wait = wait)
    }
}

private val logger = KotlinLogging.logger {}

/** Shared between the real server and `testApplication` tests. */
fun Application.plainbaseModule(services: RestServices) {
    install(ContentNegotiation) {
        // kotlinx.serialization is the only serializer in the tree (§3). This is the app-wide
        // default; the PB-REST-1 response DTOs encode through the scoped `RestJson` instead
        // (present-null guaranteed there only, §A4).
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(StatusPages) {
        // Ktor's ROUTING layer percent-decodes path segments to match routes — BEFORE any handler
        // runs — and wraps a malformed escape (`/assets/%GG`, `/api/v1/pages/by-path/%`) in
        // BadRequestException (RoutingResolveContext catches URLDecodeException and rethrows it).
        // Map it to the same 400 `invalid_path` the routes answer for an undecodable path, in the
        // frozen envelope; without this it fell through to the catch-all as a 500 `internal_error`.
        exception<BadRequestException> { call, cause ->
            logger.debug(cause) { "rejected undecodable request ${call.request.local.uri}" }
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Malformed percent-encoding in request path")
        }
        // The QUERY-STRING decode is NOT covered by that wrapping (ktor#2559): once a route
        // matches, RoutingCall eagerly merges query+path parameters, so a malformed escape in
        // the query (`?q=%`, `?q=100%`) throws a bare URLDecodeException before ANY handler runs
        // — which used to fall to the catch-all as a 500, exactly what §A6's adversarial corpus
        // (lone `%`) bans. A request undecodable as delivered is the client's 400.
        exception<URLDecodeException> { call, cause ->
            logger.debug(cause) { "rejected undecodable query string ${call.request.local.uri}" }
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_QUERY, malformedQueryMessage(call.request.rawQueryParameters))
        }
        // Uncaught failures still answer in the frozen envelope; the code is an append to the
        // §A4 vocabulary (codes are append-only). Details go to the log, never the wire.
        exception<Throwable> { call, cause ->
            logger.error(cause) { "unhandled error serving ${call.request.local.uri}" }
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL_ERROR, "Internal server error")
        }
    }
    routing {
        // §A4 routing-matrix order: API → assets → permalinks/aliases/browse → /docs SPA shell →
        // static. Ktor resolves by specificity, and every surface below owns a distinct constant
        // prefix, so registration order and match order agree; the alias-before-shell ordering is
        // structural inside docsRoutes.
        healthRoute()
        pageRoutes(services)
        treeRoute(services)
        searchRoute(services)
        adminRoute(services)
        // Tailcard under /api: loses to every real API route by specificity, beats the static
        // fallback — an unknown API path must 404 in the envelope, never 200 the shell.
        apiFallbackRoute()
        assetRoute(services)
        permalinkRoute(services)
        browseRedirectRoute(services)
        docsRoutes(services)
        // Built SPA shell, embedded as static resources by the :server build.
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
