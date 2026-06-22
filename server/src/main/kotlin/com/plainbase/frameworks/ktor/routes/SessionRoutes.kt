package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.encodeTokenSecret
import com.plainbase.frameworks.ktor.RouteContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * A4a `GET /api/v1/session` — PUBLIC (WI-7): the SPA reads the current cookie-auth state + a FRESH copy of the
 * session's CSRF token to use on subsequent mutations. It runs the secure-context gate (a cookie MAY be present)
 * but calls NO facade `check*` (an anonymous read of "am I logged in?" must not 401). `authenticated=false` for an
 * anonymous/bearer caller (no human session); the username is the resolved [Principal.Human.externalId] (the
 * builtin user id — the SPA looks up the display handle elsewhere if needed).
 */
fun Route.sessionRoutes(ctx: RouteContext) {
    get("/api/v1/session") {
        val resolved = ctx.resolveOrRefuse(call) ?: return@get
        val human = resolved.principal as? Principal.Human
        val csrf = resolved.csrfToken
        call.respondRest(
            com.plainbase.frameworks.ktor.dto.SessionResponse.serializer(),
            com.plainbase.frameworks.ktor.dto.SessionResponse(
                authenticated = human != null,
                username = human?.externalId,
                csrfToken = csrf?.let(::encodeTokenSecret),
            ),
        )
    }
}
