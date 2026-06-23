package com.plainbase.frameworks.security

import com.plainbase.domain.principal.MintedSetupToken
import com.plainbase.domain.principal.SetupTokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.principal.encodeTokenSecret
import com.plainbase.domain.principal.hashCookie
import java.security.SecureRandom

/**
 * Mints a one-time setup/reset token from `SecureRandom` (the only randomness, native-safe). 32 random bytes
 * (256 bits) base64url-encoded as the token value; the `setup_tokens` PK is the raw `SHA-256` of that token STRING
 * ([hashCookie] — the same cookie-string → PK convention the session token uses), so on consume the service hashes
 * the presented token string and the digests match. The plaintext is never re-derivable from a row.
 */
class SetupTokenMinter(
    private val hasher: TokenSecretHasher = TokenHasher(),
    private val random: SecureRandom = SecureRandom(),
) : SetupTokenMinter {

    override fun mint(): MintedSetupToken {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val plaintext = encodeTokenSecret(token)
        return MintedSetupToken(plaintext = plaintext, tokenHash = hasher.hashCookie(plaintext))
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
