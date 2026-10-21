package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.routes.CACHE_CONTROL_NO_CACHE
import com.plainbase.frameworks.ktor.routes.CACHE_CONTROL_NO_STORE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.path
import io.ktor.server.response.header

/** Supplies reader cache policy only when the responding route has not supplied its own. */
internal val ReaderCacheControl = createApplicationPlugin("ReaderCacheControl") {
    on(ResponseBodyReadyForSend) { call, content ->
        if (call.response.headers[HttpHeaders.CacheControl] != null || content.headers[HttpHeaders.CacheControl] != null) {
            return@on
        }
        val path = call.request.path()
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        val directive = when {
            path.startsWith("/p/") || path.startsWith("/browse/") -> CACHE_CONTROL_NO_STORE
            status == HttpStatusCode.OK || status == HttpStatusCode.NotFound || status == HttpStatusCode.ServiceUnavailable ->
                CACHE_CONTROL_NO_CACHE
            else -> CACHE_CONTROL_NO_STORE
        }
        call.response.header(HttpHeaders.CacheControl, directive)
    }
}
