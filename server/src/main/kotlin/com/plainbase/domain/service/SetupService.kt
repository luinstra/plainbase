package com.plainbase.domain.service

import com.plainbase.domain.principal.MintedSetupToken
import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.principal.SetupTokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.principal.hashCookie
import com.plainbase.domain.repository.DuplicateUsernameException
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.SetupTokenPurpose
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.SetupTokenRow
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.repository.UserRow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The setup-token / reset machinery (A4a, the §5 + §7 core): first-admin bootstrap, admin-issued reset, and the
 * self-service password change — all timestamps on the injectable [Clock]. The bootstrap is ONE atomic
 * transaction (kills the count-then-insert TOCTOU); every password change/reset REVOKES ALL of the user's
 * sessions (the §7 force-re-login). Pure domain — the BUILTIN-mode gate + the CLI/route surface live above.
 */
class SetupService(
    private val minter: SetupTokenMinter,
    private val hasher: TokenSecretHasher,
    private val setupTokens: SetupTokenRepository,
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val sessions: SessionService,
    private val passwordHasher: PasswordHasher,
    private val idProvider: IdProvider,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val tokenTtl: Duration = DEFAULT_TOKEN_TTL,
) {

    /** Mints a BOOTSTRAP setup token (no user yet), hashed at rest; returns the plaintext ONCE (CLI surfaces it). */
    fun mintBootstrapToken(): MintedSetupToken = mintToken(SetupTokenPurpose.BOOTSTRAP, userId = null)

    /**
     * Consumes a bootstrap token to create the first admin, in ONE transaction (§5): the token's purpose MUST be
     * BOOTSTRAP — the shared `setup_tokens` table also holds RESET tokens (admin-issued for any user, any role), and
     * without this check a RESET token would mint a brand-new ADMIN (privilege escalation). Then atomically mark it
     * used (iff it returns true the token was unused + unexpired — else [BootstrapOutcome.TokenInvalid]), insert the
     * user (argon2-hashed password), and grant `builtin/<id>=ADMIN`. The purpose check + mark-first conditional + the
     * single transaction is what kills both the cross-purpose escalation and the count-then-insert TOCTOU — the
     * token's single-use consume IS the gate, never a "is the DB empty?" check. The caller owns + zeroes [password].
     *
     * The CHEAP token pre-check (hash + lookup + purpose/expiry/unused) runs BEFORE the expensive argon2 password
     * hash (fix E): the public `/setup/consume` endpoint is not behind the login rate limiter, so an attacker POSTing
     * bogus tokens must not force an argon2 per request. The pre-check is only an optimization — the ATOMIC
     * `markUsed` inside the transaction remains the authority (single-use + purpose-bound), so a token racing valid
     * between the pre-check and the transaction is still admitted exactly once.
     */
    fun consumeBootstrap(rawToken: String, username: String, password: CharArray): BootstrapOutcome {
        val now = clock.now()
        val tokenHash = hasher.hashCookie(rawToken)
        if (!looksConsumable(tokenHash, SetupTokenPurpose.BOOTSTRAP, now)) return BootstrapOutcome.TokenInvalid
        val passwordHash = passwordHasher.hash(password)
        // A duplicate username surfaces from `users.insert` as a thrown DuplicateUsernameException — let it propagate
        // OUT of the transaction so `markUsed` rolls back too (the token is NOT burned; the admin retries with a free
        // name), then map it to the 409 outcome here (the class: every `users.insert` path maps the unique violation).
        return try {
            transactions.inTransaction {
                val row = setupTokens.findByTokenHash(tokenHash)
                if (row == null || row.purpose != SetupTokenPurpose.BOOTSTRAP) return@inTransaction BootstrapOutcome.TokenInvalid
                if (!setupTokens.markUsed(tokenHash, now)) return@inTransaction BootstrapOutcome.TokenInvalid
                val userId = idProvider.next().value
                users.insert(
                    UserRow(
                        id = userId,
                        username = username,
                        passwordHash = passwordHash,
                        displayName = null,
                        disabled = false,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                roles.upsert(SessionService.BUILTIN_ISSUER, userId, Role.ADMIN, now)
                BootstrapOutcome.Created(userId)
            }
        } catch (_: DuplicateUsernameException) {
            BootstrapOutcome.UsernameExists
        }
    }

    /**
     * Self-service change: verify [current] against the stored hash FIRST; on success set the new hash THEN revoke
     * ALL the user's sessions (§7 force-re-login). A wrong current password leaves the hash + sessions untouched.
     * The caller owns + zeroes both arrays.
     */
    fun changePassword(userId: String, current: CharArray, new: CharArray): ChangeOutcome {
        val user = users.findById(userId) ?: return ChangeOutcome.UserNotFound
        if (!passwordHasher.verify(current, user.passwordHash)) return ChangeOutcome.WrongCurrentPassword
        users.setPasswordHash(userId, passwordHasher.hash(new), clock.now())
        sessions.revokeAllForUser(userId)
        return ChangeOutcome.Changed
    }

    /**
     * Admin-issued reset: mint a RESET token for [userId] AND revoke the user's sessions on issue (an admin reset
     * is a suspected compromise, §7). Returns null when the user is unknown.
     */
    fun mintResetToken(userId: String): MintedSetupToken? {
        if (users.findById(userId) == null) return null
        val minted = mintToken(SetupTokenPurpose.RESET, userId = userId)
        sessions.revokeAllForUser(userId)
        return minted
    }

    /**
     * Consumes a reset token: atomically mark it used (single-use), set the new hash for the token's `user_id`,
     * then revoke ALL the user's sessions (§7). The caller owns + zeroes [newPassword].
     *
     * Like [consumeBootstrap], the CHEAP token pre-check runs BEFORE the argon2 hash (fix E) — the public
     * `/setup/consume` reset path must not force an argon2 per bogus token. The atomic `markUsed` in the transaction
     * stays the authority.
     */
    fun consumeReset(rawToken: String, newPassword: CharArray): ResetOutcome {
        val now = clock.now()
        val tokenHash = hasher.hashCookie(rawToken)
        if (!looksConsumable(tokenHash, SetupTokenPurpose.RESET, now)) return ResetOutcome.TokenInvalid
        val newHash = passwordHasher.hash(newPassword)
        return transactions.inTransaction {
            val row = setupTokens.findByTokenHash(tokenHash)
            if (row == null || row.purpose != SetupTokenPurpose.RESET || row.userId == null) {
                return@inTransaction ResetOutcome.TokenInvalid
            }
            if (!setupTokens.markUsed(tokenHash, now)) return@inTransaction ResetOutcome.TokenInvalid
            users.setPasswordHash(row.userId, newHash, now)
            sessions.revokeAllForUser(row.userId)
            ResetOutcome.Reset(row.userId)
        }
    }

    /**
     * The CHEAP pre-check (fix E): a token by [tokenHash] exists, has the expected [purpose], is unused, and is
     * unexpired at [now] — matching the atomic `markUsed` guard (used_at IS NULL AND expires_at > now). Not the
     * authority (that is the atomic [SetupTokenRepository.markUsed] in the transaction): it only avoids the argon2
     * hash for an obviously-bad token before the real consume.
     */
    private fun looksConsumable(tokenHash: ByteArray, purpose: SetupTokenPurpose, now: Instant): Boolean {
        val row = setupTokens.findByTokenHash(tokenHash) ?: return false
        return row.purpose == purpose && row.usedAt == null && row.expiresAt > now
    }

    private fun mintToken(purpose: SetupTokenPurpose, userId: String?): MintedSetupToken {
        val minted = minter.mint()
        val now = clock.now()
        setupTokens.insert(
            SetupTokenRow(
                tokenHash = minted.tokenHash,
                purpose = purpose,
                userId = userId,
                createdAt = now,
                expiresAt = now + tokenTtl,
                usedAt = null,
            ),
        )
        return minted
    }

    private companion object {
        /** Setup/reset tokens are short-lived single-use (default 24h) — enough to convey out-of-band, not linger. */
        val DEFAULT_TOKEN_TTL: Duration = 24.hours
    }
}

/** The outcome of [SetupService.consumeBootstrap]. */
sealed interface BootstrapOutcome {
    /** The first admin was created; [userId] is the new builtin user id (also its `external_id`). */
    data class Created(val userId: String) : BootstrapOutcome

    /** The chosen username is already taken — the `users.username UNIQUE` violation, mapped to 409 (never a 500). */
    data object UsernameExists : BootstrapOutcome

    /** The token was unknown, already used, or expired — single-use consume failed. */
    data object TokenInvalid : BootstrapOutcome
}

/** The outcome of [SetupService.changePassword]. */
sealed interface ChangeOutcome {
    data object Changed : ChangeOutcome

    data object WrongCurrentPassword : ChangeOutcome

    data object UserNotFound : ChangeOutcome
}

/** The outcome of [SetupService.consumeReset]. */
sealed interface ResetOutcome {
    data class Reset(val userId: String) : ResetOutcome

    data object TokenInvalid : ResetOutcome
}
