package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.DuplicateUsernameException
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UserMeta
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.repository.UserRow
import com.plainbase.domain.service.AdminFacade
import com.plainbase.domain.service.CreateUserOutcome
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService
import kotlin.time.Clock

/**
 * The frameworks-side [AdminFacade] impl (A4a): it holds the raw user/role repos + [SetupService] + [SessionService]
 * + [PolicyService] as PRIVATE deps. Every method calls `PolicyService.checkManage` FIRST (which mints the
 * unforgeable `ManageGrant` + records the pre-effect audit row, throwing `AccessDenied` on deny), then delegates.
 * No route can reach the raw repos — the route reaches a mutator ONLY through this `checkManage`-gated facade (the
 * `ChokePointArchitectureTest` invariant; raw repos are NEVER exposed on `RouteContext`).
 */
class GuardedAdminFacade(
    private val policy: PolicyService,
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val setup: SetupService,
    private val sessions: SessionService,
    private val passwordHasher: PasswordHasher,
    private val idProvider: IdProvider,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) : AdminFacade {

    override fun createUser(principal: Principal, username: String, displayName: String?, role: Role): CreateUserOutcome {
        policy.checkManage(principal)
        // The preflight is only a fast 409: the `users.username UNIQUE` constraint is the real authority, so two
        // concurrent creates that both pass the preflight still resolve correctly — the loser's insert throws
        // DuplicateUsernameException (rolling back the txn) which maps to the SAME 409, never a 500.
        if (users.findByUsername(username) != null) return CreateUserOutcome.UsernameExists
        // Insert the user + grant the role in ONE transaction; the user starts with an UNUSABLE random password
        // (no plaintext exists for it), and an admin-issued reset token is the ONLY way to set a real one — so the
        // create response conveys that token ONCE and the new user logs in by consuming it.
        val userId = try {
            transactions.inTransaction {
                val id = idProvider.next().value
                val now = clock.now()
                users.insert(
                    UserRow(
                        id = id,
                        username = username,
                        passwordHash = passwordHasher.hash(unusablePassword()),
                        displayName = displayName,
                        disabled = false,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                roles.upsert(SessionService.BUILTIN_ISSUER, id, role, now)
                id
            }
        } catch (_: DuplicateUsernameException) {
            return CreateUserOutcome.UsernameExists
        }
        // mintResetToken issues a RESET token + revokes sessions (none yet) — the new user has no usable password
        // until they consume it. Non-null because we just inserted the user.
        val resetToken = requireNotNull(setup.mintResetToken(userId)) { "just-created user must exist" }
        return CreateUserOutcome.Created(id = userId, username = username, resetToken = resetToken.plaintext)
    }

    override fun listUsers(principal: Principal): List<UserMeta> {
        policy.checkManage(principal)
        return users.all()
    }

    override fun disableUser(principal: Principal, userId: String): Boolean {
        policy.checkManage(principal)
        if (users.findById(userId) == null) return false
        users.setDisabled(userId, disabled = true, at = clock.now())
        sessions.revokeAllForUser(userId) // the lock takes effect immediately — kill any live session
        return true
    }

    override fun resetUser(principal: Principal, userId: String): String? {
        policy.checkManage(principal)
        return setup.mintResetToken(userId)?.plaintext
    }

    override fun revokeSessions(principal: Principal, userId: String) {
        policy.checkManage(principal)
        sessions.revokeAllForUser(userId)
    }

    override fun grantRole(principal: Principal, issuer: String, externalId: String, role: Role) {
        policy.checkManage(principal)
        roles.upsert(issuer, externalId, role, clock.now())
    }

    /** A throwaway high-entropy passphrase for a not-yet-activated user — never conveyed, never usable to log in. */
    private fun unusablePassword(): CharArray = idProvider.next().value.toCharArray()
}
