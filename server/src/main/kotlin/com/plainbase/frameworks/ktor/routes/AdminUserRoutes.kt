package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.CreateUserOutcome
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.CreateUserRequest
import com.plainbase.frameworks.ktor.dto.CreatedUserResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.SessionRevokeRequest
import com.plainbase.frameworks.ktor.dto.UserListResponse
import com.plainbase.frameworks.ktor.dto.UserResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * A4a admin user/role/session management — GATED (`manage`): every handler resolves the principal then calls the
 * `checkManage`-gated [com.plainbase.domain.service.AdminFacade] (which mints a `ManageGrant`, audits, and throws
 * `AccessDenied` on deny → [guarded] maps it to 401/403). The route reaches a user/role/session mutator ONLY
 * through that facade — NEVER a raw repo (the `ChokePointArchitectureTest` invariant). The role-grant write goes
 * through the SAME `RoleRepository.upsert` path the WI-11 CLI uses.
 */
fun Route.adminUserRoutes(ctx: RouteContext) {
    post("/api/v1/admin/users") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val request = call.receiveAuthRequest(CreateUserRequest.serializer()) ?: return@guarded
            if (call.refuseIfBlank("username" to request.username)) return@guarded
            val role = parseRole(request.role)
                ?: return@guarded call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_AUTH_REQUEST,
                    "Unknown role: '${request.role}'",
                )
            when (val outcome = ctx.auth.admin.createUser(principal, request.username, request.displayName, role)) {
                is CreateUserOutcome.Created ->
                    call.respondRest(
                        CreatedUserResponse.serializer(),
                        CreatedUserResponse(id = outcome.id, username = outcome.username, resetToken = outcome.resetToken),
                        HttpStatusCode.Created,
                    )
                CreateUserOutcome.UsernameExists ->
                    call.respondError(HttpStatusCode.Conflict, ErrorCodes.USERNAME_EXISTS, "A user with that username already exists")
            }
        }
    }

    get("/api/v1/admin/users") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val users = ctx.auth.admin.listUsers(principal).map {
                UserResponse(id = it.id, username = it.username, displayName = it.displayName, disabled = it.disabled)
            }
            call.respondRest(UserListResponse.serializer(), UserListResponse(users))
        }
    }

    post("/api/v1/admin/users/{id}/disable") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val id = call.parameters["id"].orEmpty()
            if (ctx.auth.admin.disableUser(principal, id)) {
                call.respondNoContent()
            } else {
                call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such user")
            }
        }
    }

    post("/api/v1/admin/users/{id}/reset") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val id = call.parameters["id"].orEmpty()
            val token = ctx.auth.admin.resetUser(principal, id)
            if (token != null) {
                // No display username needed here — the admin already knows the target id.
                call.respondRest(
                    CreatedUserResponse.serializer(),
                    CreatedUserResponse(id = id, username = "", resetToken = token),
                )
            } else {
                call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such user")
            }
        }
    }

    post("/api/v1/admin/sessions/revoke") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val request = call.receiveAuthRequest(SessionRevokeRequest.serializer()) ?: return@guarded
            ctx.auth.admin.revokeSessions(principal, request.userId)
            call.respondNoContent()
        }
    }
}

/** Parses a role arg (any case) → [Role], or null for an unknown value. */
private fun parseRole(raw: String): Role? = Role.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
