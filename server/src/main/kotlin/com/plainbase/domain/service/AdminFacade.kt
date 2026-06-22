package com.plainbase.domain.service

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.UserMeta

/**
 * The guarded ADMIN-management surface (A4a, the choke point). Every method takes a [Principal], calls
 * `PolicyService.checkManage` FIRST (mints a `ManageGrant`, records the pre-effect audit row, throws
 * [AccessDenied] on deny), then delegates to the raw user/role/session repositories + [SetupService]. The
 * route NEVER sees a raw `UserRepository`/`RoleRepository`/`SessionService` — it reaches a mutator ONLY through
 * this `checkManage`-gated facade (the `ChokePointArchitectureTest` invariant). The impl
 * ([com.plainbase.frameworks.ktor.GuardedAdminFacade]) holds the raw deps as PRIVATE fields and owns the
 * [AccessDenied] → 401/403 mapping at the route via `guarded`.
 */
interface AdminFacade {

    /**
     * MANAGE: create a user with [role] and mint a one-time reset token the admin conveys out-of-band (no password
     * in the create). Returns [CreateUserOutcome.UsernameExists] if the handle is taken, else
     * [CreateUserOutcome.Created] with the id + the reset-token plaintext (returned ONCE).
     */
    fun createUser(principal: Principal, username: String, displayName: String?, role: Role): CreateUserOutcome

    /** MANAGE: the user list — metadata only ([UserMeta], no secret). */
    fun listUsers(principal: Principal): List<UserMeta>

    /** MANAGE: soft-lock a user; their sessions are revoked so the lock takes effect immediately. Null = unknown id. */
    fun disableUser(principal: Principal, userId: String): Boolean

    /** MANAGE: admin-issue a reset token for [userId] (revokes the user's sessions on issue). Null = unknown id. */
    fun resetUser(principal: Principal, userId: String): String?

    /** MANAGE: revoke ALL of a target user's sessions (the admin-revoke hook). */
    fun revokeSessions(principal: Principal, userId: String)

    /** MANAGE: grant/regrant [role] to the `(issuer, externalId)` identity (the same upsert path the CLI uses). */
    fun grantRole(principal: Principal, issuer: String, externalId: String, role: Role)
}

/** The outcome of [AdminFacade.createUser]. [Created.resetToken] is the one-time plaintext (returned ONCE). */
sealed interface CreateUserOutcome {
    data class Created(val id: String, val username: String, val resetToken: String) : CreateUserOutcome

    data object UsernameExists : CreateUserOutcome
}
