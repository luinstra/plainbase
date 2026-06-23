package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.encodeTokenSecret
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.SessionResponse
import io.ktor.http.CookieEncoding
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/v1/session` — PUBLIC (A4a WI-7 + A4b): the SPA reads the current auth state + a FRESH CSRF token to use
 * on subsequent mutations. It runs the secure-context gate (a credential MAY be present) but calls NO facade
 * `check*` (an anonymous "am I logged in?" must not 401). Registered in BOTH builtin AND proxy modes (login/setup/
 * admin-user stay builtin-only).
 *
 *  - BUILTIN: returns the cookie session's synchronizer CSRF token (A4a, unchanged).
 *  - PROXY: for a proxy-Human, issues a FRESH stateless double-submit token, sets it as a readable `pb_proxy_csrf`
 *    cookie (HttpOnly=false — the SPA echoes it as `X-CSRF-Token`; Secure mirrors the transport; SameSite=Lax), and
 *    returns it in `csrf_token`. The cookie is set via `call.response.cookies.append` — NOT the Ktor `Sessions`
 *    plugin (which is configured only for `pb_session` with the opaque serializer).
 */
fun Route.sessionRoutes(ctx: RouteContext) {
    get("/api/v1/session") {
        val resolved = ctx.resolveOrRefuse(call) ?: return@get
        val human = resolved.principal as? Principal.Human
        val csrf = if (ctx.proxyAuthEnabled) {
            human?.let {
                // The proxy double-submit token is STATELESS (no sessions row): it is non-revocable until the server
                // HMAC key rotates — acceptable because the trusted proxy owns the real session and re-asserts identity
                // per request, so this token only proves same-origin double-submit, not a revocable app session.
                val token = ctx.proxyCsrf.issue()
                call.response.cookies.append(
                    name = "pb_proxy_csrf",
                    value = token,
                    encoding = CookieEncoding.RAW,
                    httpOnly = false,
                    secure = ctx.secureCookie,
                    path = "/",
                    extensions = mapOf("SameSite" to "Lax"),
                )
                token
            }
        } else {
            resolved.csrfToken?.let(::encodeTokenSecret)
        }
        call.respondRest(
            SessionResponse.serializer(),
            SessionResponse(
                authenticated = human != null,
                username = human?.externalId,
                csrfToken = csrf,
                authMode = if (ctx.builtinAuthEnabled) {
                    "builtin"
                } else if (ctx.proxyAuthEnabled) {
                    "proxy"
                } else {
                    "off"
                },
            ),
        )
    }
}
