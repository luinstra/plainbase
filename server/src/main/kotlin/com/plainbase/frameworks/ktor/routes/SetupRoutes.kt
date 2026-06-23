package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.encodeTokenSecret
import com.plainbase.domain.service.BootstrapOutcome
import com.plainbase.domain.service.ChangeOutcome
import com.plainbase.domain.service.ResetOutcome
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.SESSION_COOKIE_NAME
import com.plainbase.frameworks.ktor.dto.ChangePasswordRequest
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.LoginResponse
import com.plainbase.frameworks.ktor.dto.ResetConsumeRequest
import com.plainbase.frameworks.ktor.dto.SetupConsumeRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * A4a bootstrap + password reset/change. `POST /api/v1/setup/consume` and `POST /api/v1/password/reset/consume` are
 * PUBLIC pre-auth (WI-7 — no `check*`): the single-use token is the gate, and it is accepted ONLY from the POST
 * BODY (never URL/query/log). Consuming a bootstrap token logs the new admin in (sets the cookie + returns CSRF).
 * `POST /api/v1/password/change` is cookie-auth + CSRF-protected (a logged-in human changing their own password).
 */
fun Route.setupRoutes(ctx: RouteContext) {
    post("/api/v1/setup/consume") {
        if (ctx.resolveOrRefuse(call) == null) return@post // secure-context gate for a riding cookie; a stale one may ride
        // The token + password ride the BODY, so the credential-conditional seam above does NOT fire for them; gate the
        // transport credential-agnostically BEFORE reading + verifying the token (WI-9). 421 on a leaky transport.
        if (call.refuseIfInsecureContext(ctx.trustedProxyCidrs)) return@post
        val request = call.receiveAuthRequest(SetupConsumeRequest.serializer()) ?: return@post
        if (call.refuseIfBlank("token" to request.token, "username" to request.username, "password" to request.password)) return@post
        val password = request.password.toCharArray()
        val outcome = try {
            ctx.auth.setup.consumeBootstrap(request.token, request.username, password)
        } finally {
            password.fill(' ')
        }
        when (outcome) {
            is BootstrapOutcome.Created -> {
                // The new admin is logged in: mint a fresh session for them and set the cookie + return CSRF.
                val session = ctx.auth.session.create(outcome.userId)
                call.sessions.set(SESSION_COOKIE_NAME, session.plaintext)
                call.respondRest(
                    LoginResponse.serializer(),
                    LoginResponse(csrfToken = encodeTokenSecret(session.csrfToken)),
                    HttpStatusCode.Created,
                )
            }
            BootstrapOutcome.UsernameExists ->
                call.respondError(HttpStatusCode.Conflict, ErrorCodes.USERNAME_EXISTS, "A user with that username already exists")
            BootstrapOutcome.TokenInvalid ->
                call.respondError(HttpStatusCode.BadRequest, ErrorCodes.SETUP_TOKEN_INVALID, "Setup token is invalid, used, or expired")
        }
    }

    post("/api/v1/password/reset/consume") {
        if (ctx.resolveOrRefuse(call) == null) return@post
        // The token + new password ride the BODY (the seam above only sees a riding cookie); gate the transport
        // credential-agnostically BEFORE reading + verifying the token (WI-9). 421 on a leaky transport.
        if (call.refuseIfInsecureContext(ctx.trustedProxyCidrs)) return@post
        val request = call.receiveAuthRequest(ResetConsumeRequest.serializer()) ?: return@post
        if (call.refuseIfBlank("token" to request.token, "new_password" to request.newPassword)) return@post
        val newPassword = request.newPassword.toCharArray()
        val outcome = try {
            ctx.auth.setup.consumeReset(request.token, newPassword)
        } finally {
            newPassword.fill(' ')
        }
        when (outcome) {
            is ResetOutcome.Reset -> call.respondNoContent()
            ResetOutcome.TokenInvalid ->
                call.respondError(HttpStatusCode.BadRequest, ErrorCodes.SETUP_TOKEN_INVALID, "Reset token is invalid, used, or expired")
        }
    }

    post("/api/v1/password/change") {
        val resolved = ctx.resolveOrRefuse(call) ?: return@post
        if (!ctx.enforceCsrf(call, resolved)) return@post
        val human = resolved.principal as? Principal.Human
            ?: return@post call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
        val request = call.receiveAuthRequest(ChangePasswordRequest.serializer()) ?: return@post
        if (call.refuseIfBlank("current_password" to request.currentPassword, "new_password" to request.newPassword)) return@post
        val current = request.currentPassword.toCharArray()
        val new = request.newPassword.toCharArray()
        val outcome = try {
            ctx.auth.setup.changePassword(human.externalId, current, new)
        } finally {
            current.fill(' ')
            new.fill(' ')
        }
        when (outcome) {
            ChangeOutcome.Changed -> call.respondNoContent() // all sessions (incl. this one) are now revoked — re-login
            ChangeOutcome.WrongCurrentPassword ->
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Current password is incorrect")
            ChangeOutcome.UserNotFound ->
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Authentication required")
        }
    }
}
