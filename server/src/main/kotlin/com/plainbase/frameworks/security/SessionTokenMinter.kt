package com.plainbase.frameworks.security

import com.plainbase.domain.principal.MintedSession
import com.plainbase.domain.principal.SessionTokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.principal.encodeTokenSecret
import com.plainbase.domain.principal.hashCookie
import java.security.SecureRandom

/**
 * Mints a session cookie token + its CSRF token from `SecureRandom` (the only randomness, native-safe — the same
 * primitive [ApiTokenMinter] / [Argon2PasswordHasher] use). The session token is 32 random bytes (256 bits of
 * entropy) encoded base64url as the cookie VALUE; the `sessions` PK is the raw `SHA-256` of that cookie STRING
 * ([hashCookie]), so on read the service hashes the cookie string it receives and the digests match. The plaintext
 * is never re-derivable from a row. The CSRF token is a separate 32 random bytes, persisted RAW in the row and
 * compared constant-time (NOT hashed — it must round-trip to the client to be echoed back, and the raw-byte
 * compare is the synchronizer-token check).
 */
class SessionTokenMinter(
    private val hasher: TokenSecretHasher = TokenHasher(),
    private val random: SecureRandom = SecureRandom(),
) : SessionTokenMinter {

    override fun mint(): MintedSession {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val csrf = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val plaintext = encodeTokenSecret(token)
        return MintedSession(
            plaintext = plaintext,
            tokenHash = hasher.hashCookie(plaintext),
            csrfToken = csrf,
        )
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
