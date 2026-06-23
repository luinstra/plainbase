package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.principal.encodeTokenSecret
import com.plainbase.domain.service.LoginOutcome
import com.plainbase.frameworks.ktor.LoginRateLimiter
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.SESSION_COOKIE_NAME
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.LoginRequest
import com.plainbase.frameworks.ktor.dto.LoginResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A4a login + logout (`frameworks/ktor/routes`). `POST /api/v1/login` is PUBLIC pre-auth (WI-7): it calls NO
 * facade `check*` (PolicyService denies Anonymous under enforced mode, so routing login through `check*` would make
 * auth impossible). It is still secure-context-gated (the credential-conditional seam fires when a cookie is
 * present) and rate-limited per-IP + per-(IP,username); a successful login mints a FRESH session (§5 — no inbound
 * cookie is adopted) and sets the `pb_session` cookie. `POST /api/v1/logout` is cookie-auth + CSRF-protected.
 */
fun Route.authRoutes(ctx: RouteContext) {
    post("/api/v1/login") {
        // A credential MAY ride the request (a stale cookie); run the secure-context gate, but NOT check* — login
        // is pre-auth by construction. A refused-insecure credential is the 421 (resolveOrRefuse answers it).
        if (ctx.resolveOrRefuse(call) == null) return@post
        // The password rides the BODY (not a cookie/bearer), so the credential-conditional seam above does NOT fire
        // for it; gate the transport credential-agnostically BEFORE reading + verifying it (WI-9). 421 on a leaky one.
        if (call.refuseIfInsecureContext(ctx.trustedProxyCidrs)) return@post
        val ip = call.request.local.remoteAddress

        val request = call.receiveAuthRequest(LoginRequest.serializer()) ?: return@post
        if (call.refuseIfBlank("username" to request.username, "password" to request.password)) return@post

        when (val decision = ctx.auth.rateLimiter.check(ip, request.username)) {
            is LoginRateLimiter.Decision.Throttle -> {
                // CIO-safe (NOT Thread.sleep); slows a brute-force without blocking the loop. coerceAtLeast guards a
                // negative Duration (clock skew / an already-elapsed window) — delay(negative) is harmless but the
                // coerce is the defensive contract.
                delay(decision.backoff.coerceAtLeast(Duration.ZERO))
                return@post call.respondError(
                    HttpStatusCode.TooManyRequests,
                    ErrorCodes.RATE_LIMITED,
                    "Too many login attempts; retry later",
                )
            }
            LoginRateLimiter.Decision.Allowed -> Unit
        }

        val password = request.password.toCharArray()
        val outcome = try {
            ctx.auth.login.login(request.username, password)
        } finally {
            password.fill(' ') // best-effort zero (the source String can't be zeroed — documented limitation)
        }
        when (outcome) {
            is LoginOutcome.Success -> {
                call.sessions.set(SESSION_COOKIE_NAME, outcome.session.plaintext)
                call.respondRest(LoginResponse.serializer(), LoginResponse(csrfToken = encodeTokenSecret(outcome.session.csrfToken)))
            }
            // Disabled maps to the SAME 401 as InvalidCredentials — never an oracle (WI-4/WI-9).
            LoginOutcome.InvalidCredentials, LoginOutcome.Disabled -> {
                ctx.auth.rateLimiter.recordFailure(ip, request.username)
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Invalid username or password")
            }
        }
    }

    post("/api/v1/logout") {
        val resolved = ctx.resolveOrRefuse(call) ?: return@post
        if (!ctx.enforceCsrf(call, resolved)) return@post
        // Revoke the current session (idempotent for an absent/garbage cookie) and clear the cookie.
        call.currentSessionCookie()?.let(ctx.auth.session::revoke)
        call.sessions.clear(SESSION_COOKIE_NAME)
        call.respondNoContent()
    }
}

/** The raw `pb_session` cookie value the current request carries, or null. */
internal fun ApplicationCall.currentSessionCookie(): String? = sessions.get(SESSION_COOKIE_NAME) as? String
