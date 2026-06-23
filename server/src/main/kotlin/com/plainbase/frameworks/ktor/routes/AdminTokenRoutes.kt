package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenMeta
import com.plainbase.domain.repository.AuditEntry
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.SubjectRoleRow
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.AuditEntryResponse
import com.plainbase.frameworks.ktor.dto.AuditListResponse
import com.plainbase.frameworks.ktor.dto.CreatedTokenResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.GrantRoleRequest
import com.plainbase.frameworks.ktor.dto.MintTokenRequest
import com.plainbase.frameworks.ktor.dto.RoleListResponse
import com.plainbase.frameworks.ktor.dto.RoleResponse
import com.plainbase.frameworks.ktor.dto.TokenListResponse
import com.plainbase.frameworks.ktor.dto.TokenMetaResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * A4b admin TOKEN + AUDIT + ROLE management — `manage`-gated, mode-INDEPENDENT (registered in builtin AND proxy; a
 * proxy admin needs them too). Every handler resolves the principal then calls the `checkManage`-gated
 * [com.plainbase.domain.service.AdminFacade] (which mints a `ManageGrant`, audits, throws `AccessDenied` on deny →
 * [guarded] maps it to 401/403). The route reaches the token/audit/role surface ONLY through that facade — NEVER a
 * raw repo (the `ChokePointArchitectureTest` invariant). Mutations (mint/revoke/grant) go through
 * [mutatingPrincipalOrRefuse], so a proxy admin's mutation requires the double-submit CSRF token.
 *
 * No list/read response carries a secret — only the one-time mint plaintext (`CreatedTokenResponse`) does.
 */
fun Route.adminTokenRoutes(ctx: RouteContext) {
    get("/api/v1/admin/tokens") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            call.respondRest(TokenListResponse.serializer(), TokenListResponse(ctx.auth.admin.listTokens(principal).map { it.toDto() }))
        }
    }

    post("/api/v1/admin/tokens") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val request = call.receiveAuthRequest(MintTokenRequest.serializer()) ?: return@guarded
            if (call.refuseIfBlank("label" to request.label)) return@guarded
            val mode = parseMode(request.mode)
                ?: return@guarded call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_AUTH_REQUEST,
                    "Unknown token mode: '${request.mode}'",
                )
            val created = ctx.auth.admin.mintToken(principal, request.label, mode)
            call.respondRest(
                CreatedTokenResponse.serializer(),
                CreatedTokenResponse(id = created.id, plaintext = created.plaintext),
                HttpStatusCode.Created,
            )
        }
    }

    post("/api/v1/admin/tokens/{id}/revoke") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            ctx.auth.admin.revokeToken(principal, call.parameters["id"].orEmpty())
            call.respondNoContent()
        }
    }

    get("/api/v1/admin/audit") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_AUDIT_LIMIT) ?: DEFAULT_AUDIT_LIMIT
            call.respondRest(
                AuditListResponse.serializer(),
                AuditListResponse(
                    ctx.auth.admin.recentAudit(principal, limit).map {
                        it.toDto()
                    },
                ),
            )
        }
    }

    get("/api/v1/admin/roles") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            call.respondRest(RoleListResponse.serializer(), RoleListResponse(ctx.auth.admin.listRoles(principal).map { it.toDto() }))
        }
    }

    post("/api/v1/admin/roles") {
        val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
        call.guarded {
            val request = call.receiveAuthRequest(GrantRoleRequest.serializer()) ?: return@guarded
            if (call.refuseIfBlank("issuer" to request.issuer, "external_id" to request.externalId)) return@guarded
            val role = parseRole(request.role)
                ?: return@guarded call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_AUTH_REQUEST,
                    "Unknown role: '${request.role}'",
                )
            ctx.auth.admin.grantRole(principal, request.issuer, request.externalId, role)
            call.respondNoContent()
        }
    }
}

private const val DEFAULT_AUDIT_LIMIT = 100
private const val MAX_AUDIT_LIMIT = 1000

private fun ApiTokenMeta.toDto() = TokenMetaResponse(
    id = id,
    label = agentLabel,
    mode = mode.name.lowercase(),
    createdAt = createdAt.toString(),
    lastUsedAt = lastUsedAt?.toString(),
    expiresAt = expiresAt?.toString(),
    revokedAt = revokedAt?.toString(),
)

private fun AuditEntry.toDto() = AuditEntryResponse(
    id = id,
    ts = ts.toString(),
    principalKind = principalKind,
    issuer = issuer,
    externalId = externalId,
    action = action,
    resource = resource,
    decision = decision,
)

private fun SubjectRoleRow.toDto() = RoleResponse(
    issuer = issuer,
    externalId = externalId,
    role = role.name.lowercase(),
    createdAt = createdAt.toString(),
)

/** Parses an agent-mode arg (any case, `-`/`_` interchangeable) → [AgentMode], or null for an unknown value. */
private fun parseMode(raw: String): AgentMode? {
    val token = raw.trim().uppercase().replace('-', '_')
    return AgentMode.entries.firstOrNull { it.name == token }
}

/** Parses a role arg (any case) → [Role], or null for an unknown value. */
private fun parseRole(raw: String): Role? = Role.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
