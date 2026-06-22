package com.plainbase.domain.service

import com.plainbase.domain.principal.MintedSession
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SessionTokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.principal.hashCookie
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SessionRow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The single mint / authenticate / revoke path for server-side human sessions (A4a, the §1 + §2 core). The
 * cookie-extraction seam (the [authenticate] read), the login/logout/admin routes ([create]/[revoke]), and the
 * password-change/reset path ([revokeAllForUser]) all share it. Composes the [SessionTokenMinter] (SecureRandom),
 * the raw-SHA-256 [TokenSecretHasher], the [SessionRepository], an injectable [Clock], and the idle/absolute TTLs.
 *
 * Transport-agnostic (the [ApiTokenService] discipline): the secure-context refusal is the Ktor extraction point's
 * job, not the service's — this service never sees a socket peer. The cookie is hashed HERE on read (§1), so the
 * plaintext never reaches the repository/DB.
 *
 * Session id ROTATES (§5): every [create] mints a fresh opaque token; login/change/reset revoke the old session
 * (or all of the user's) and call [create] — an inbound attacker-supplied cookie value is NEVER adopted, killing
 * session fixation.
 */
class SessionService(
    private val minter: SessionTokenMinter,
    private val hasher: TokenSecretHasher,
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val idleTtl: Duration = DEFAULT_IDLE_TTL,
    private val absoluteTtl: Duration = DEFAULT_ABSOLUTE_TTL,
) {

    /**
     * Mints + persists a fresh session for [userId], returning the one-time cookie plaintext + the CSRF token in
     * [MintedSession]. `created_at = now`, `idle_expires_at = now + idle`, `absolute_expires_at = now + absolute`.
     */
    fun create(userId: String): MintedSession {
        val minted = minter.mint()
        val now = clock.now()
        sessions.insert(
            SessionRow(
                tokenHash = minted.tokenHash,
                userId = userId,
                csrfToken = minted.csrfToken,
                createdAt = now,
                idleExpiresAt = now + idleTtl,
                absoluteExpiresAt = now + absoluteTtl,
                revokedAt = null,
            ),
        )
        return minted
    }

    /**
     * Resolves a presented session cookie to an [Authenticated], or null when there is no valid session. The §2
     * atomic touch IS the sole validity gate: hash the raw cookie (SERVICE layer, §1), call
     * [SessionRepository.touchIfActive] (the ONE conditional UPDATE — slides idle, re-checks revoked + both
     * expiries); iff it returns true, read the now-validated row for `user_id` + `csrf_token`. The follow-up read
     * never decides validity — it cannot resurrect a row the conditional UPDATE just failed, and a revoke
     * committing AFTER a successful touch is the next request's concern (this in-flight request was correctly
     * granted). NO skip-window.
     */
    fun authenticate(rawCookie: String): Authenticated? {
        val tokenHash = hasher.hashCookie(rawCookie)
        if (!sessions.touchIfActive(tokenHash, clock.now(), idleTtl)) return null
        val row = sessions.findByTokenHash(tokenHash) ?: return null
        return Authenticated(Principal.Human(BUILTIN_ISSUER, row.userId), row.csrfToken)
    }

    /** Revoke the session a cookie names (logout) — idempotent; hashes the cookie in the SERVICE layer (§1). */
    fun revoke(rawCookie: String) {
        sessions.revokeByTokenHash(hasher.hashCookie(rawCookie), clock.now())
    }

    /** Revoke EVERY active session for [userId] — the §7 password-change/reset/admin-revoke hook. */
    fun revokeAllForUser(userId: String) {
        sessions.revokeAllForUser(userId, clock.now())
    }

    /** A resolved human session: the [principal] (issuer="builtin") + the row's [csrfToken] for the CSRF guard. */
    class Authenticated(val principal: Principal.Human, val csrfToken: ByteArray)

    companion object {
        /** The login subject's issuer — the `subject_role` + `Principal.Human` key A4a's builtin login produces. */
        const val BUILTIN_ISSUER = "builtin"

        /** Build default: sliding idle TTL (7 days). Restart-only knob if config-surfaced (§0.9). */
        val DEFAULT_IDLE_TTL: Duration = 7.days

        /** Build default: absolute hard cap (30 days), never extended by activity. */
        val DEFAULT_ABSOLUTE_TTL: Duration = 30.days
    }
}
