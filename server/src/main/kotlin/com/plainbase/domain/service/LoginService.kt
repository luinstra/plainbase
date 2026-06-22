package com.plainbase.domain.service

import com.plainbase.domain.principal.MintedSession
import com.plainbase.domain.principal.PasswordHasher
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
 */
class LoginService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val sessions: SessionService,
    private val dummyHash: String,
) {

    /**
     * Looks up [username]; if absent OR disabled, verifies [password] against [dummyHash] (timing parity) and
     * returns the failure; if present + enabled, verifies against the real hash and on success mints a fresh
     * session ([SessionService.create]). The [password] array is the caller's to zero.
     */
    fun login(username: String, password: CharArray): LoginOutcome {
        val user = users.findByUsername(username)
        if (user == null || user.disabled) {
            // Run the same argon2 work the real path does, then return the failure — no early-out, no timing leak.
            passwordHasher.verify(password, dummyHash)
            return if (user == null) LoginOutcome.InvalidCredentials else LoginOutcome.Disabled
        }
        if (!passwordHasher.verify(password, user.passwordHash)) return LoginOutcome.InvalidCredentials
        return LoginOutcome.Success(sessions.create(user.id))
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
