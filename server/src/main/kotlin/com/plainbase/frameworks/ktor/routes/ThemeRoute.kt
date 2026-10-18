package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.root.ServerTopLevel
import com.plainbase.domain.theming.ThemeMode
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.protocol.ErrorCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Serves public fixed theme reads, refuses other methods with 405 and returns JSON 404 for unknown namespace paths. */
fun Route.themeRoutes(ctx: RouteContext) {
    fun endpoint(path: String, read: () -> ByteArray) {
        route(path) {
            handle {
                if (call.request.httpMethod == HttpMethod.Get) {
                    call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_NO_STORE)
                    call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                    val bytes = withContext(Dispatchers.IO) { read() }
                    call.respondBytes(bytes, ContentType.Text.CSS.withCharset(Charsets.UTF_8))
                } else {
                    call.response.header(HttpHeaders.Allow, "GET")
                    call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_NO_STORE)
                    call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }
            }
        }
    }
    endpoint("/${ServerTopLevel.PLAINBASE}/theme-light.css") { ctx.themes.definition(ThemeMode.LIGHT) }
    endpoint("/${ServerTopLevel.PLAINBASE}/theme-dark.css") { ctx.themes.definition(ThemeMode.DARK) }
    endpoint("/${ServerTopLevel.PLAINBASE}/custom.css") { ctx.themes.override() }
    route("/${ServerTopLevel.PLAINBASE}/{...}") {
        handle {
            call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_NO_STORE)
            call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such theme endpoint")
        }
    }
}
