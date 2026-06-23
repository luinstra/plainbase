package com.plainbase.domain.service

import com.plainbase.domain.principal.MintedSession
import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UserRepository

/**
 * Verifies a username/password against `users.password_hash` and, on success, rotates to a FRESH session (§6 +
 * §5). Anti-enumeration: an unknown OR disabled user still runs ONE argon2 `verify` against a fixed [dummyHash]
 * (the constant-time-parity discipline the A2 token path uses, transposed to argon2) — so "this username exists"
 * is not observable in the response timing. The route maps both [Disabled] and [InvalidCredentials] to the SAME
 * 401, so the disabled state is not an oracle either.
 *
 * Pure domain (the rate-limit + the throttle `delay` live at the route — they need the socket peer + a coroutine
 * dispatcher). The caller owns + zeroes the password [CharArray] after [login] returns.
 *
 * Disable-during-login TOCTOU (B2a): the argon2 `verify` window (~100ms) is long enough for an admin to
 * disable+revoke the user; without an atomic re-check the post-verify `sessions.create` would mint a fresh session
 * the revoke-all already passed over. The session is therefore minted INSIDE one transaction that first RE-READS
 * the user — a concurrent disable either commits before the re-read (login aborts, no session) or after the insert
 * (its `revokeAllForUser` covers the just-inserted session). The same re-read also guards password-hash drift: a
 * change/reset committing in the verify window already revoked all sessions, so a hash mismatch aborts the login too.
 * The verify stays OUTSIDE the txn (the app DB is a single write connection; holding argon2 in the txn would
 * serialize every other writer for that window).
 */
class LoginService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val sessions: SessionService,
    private val transactions: TransactionRunner,
    private val dummyHash: String,
) {

    /**
     * Looks up [username]; if absent OR disabled, verifies [password] against [dummyHash] (timing parity) and
     * returns the failure; if present + enabled, verifies against the real hash (OUTSIDE the txn) and on success
     * mints a fresh session ([SessionService.create]) inside ONE transaction that re-reads the user first — so a
     * disable racing the verify window cannot leave a surviving session. The [password] array is the caller's to
     * zero.
     */
    fun login(username: String, password: CharArray): LoginOutcome {
        val user = users.findByUsername(username)
        if (user == null || user.disabled) {
            // Run the same argon2 work the real path does, then return the failure — no early-out, no timing leak.
            passwordHasher.verify(password, dummyHash)
            return if (user == null) LoginOutcome.InvalidCredentials else LoginOutcome.Disabled
        }
        if (!passwordHasher.verify(password, user.passwordHash)) return LoginOutcome.InvalidCredentials
        return transactions.inTransaction {
            // Re-read inside the txn: a disable that commits before this read aborts the login (no session minted);
            // one that commits after the insert is covered by its revokeAllForUser. A user vanished mid-login is
            // NOT distinguished from a bad credential (no existence leak) — same 401 as InvalidCredentials.
            val current = users.findById(user.id) ?: return@inTransaction LoginOutcome.InvalidCredentials
            if (current.disabled) return@inTransaction LoginOutcome.Disabled
            // Hash-drift guard (same TOCTOU class as disable): a password change/reset that committed during the
            // argon2 verify window already revoked all sessions, so the just-verified hash is stale — minting now
            // would resurrect a session the revoke-all passed over. Both hashes are server-side stored values (no
            // attacker secret), so plain `!=` is correct — do NOT route this through the password hasher.
            if (current.passwordHash != user.passwordHash) return@inTransaction LoginOutcome.InvalidCredentials
            LoginOutcome.Success(sessions.create(user.id))
        }
    }
}

/** The outcome of [LoginService.login]. The route maps [Disabled] to the SAME 401 as [InvalidCredentials]. */
sealed interface LoginOutcome {

    /** Credentials verified; [session] is a freshly minted session (the cookie + CSRF token ride out once). */
    data class Success(val session: MintedSession) : LoginOutcome

    /** Unknown user or wrong password — indistinguishable to the client (one 401, after the dummy/real verify). */
    data object InvalidCredentials : LoginOutcome

    /** A known but disabled user — mapped to the SAME 401 as [InvalidCredentials] at the route (not an oracle). */
    data object Disabled : LoginOutcome
}
