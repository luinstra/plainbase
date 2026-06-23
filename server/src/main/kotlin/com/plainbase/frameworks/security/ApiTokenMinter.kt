package com.plainbase.frameworks.security

import com.plainbase.domain.principal.MintedToken
import com.plainbase.domain.principal.TOKEN_PREFIX
import com.plainbase.domain.principal.TokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.principal.encodeTokenId
import com.plainbase.domain.principal.encodeTokenSecret
import java.security.SecureRandom

/**
 * Mints a fresh `pb_<id>_<secret>` agent token from `SecureRandom` (the only randomness, native-safe — the same
 * primitive [Argon2PasswordHasher] uses). The `id` is 8 random bytes (64 bits of public lookup-key space, hex
 * for an unambiguous `_`-free boundary) and the `secret` 32 random bytes (256 bits of entropy, base64url), so
 * the token is a clean single-line string. The plaintext rides [MintedToken] ONCE and is never re-derivable
 * from the persisted [MintedToken.secretHash].
 */
class ApiTokenMinter(
    private val hasher: TokenSecretHasher = TokenHasher(),
    private val random: SecureRandom = SecureRandom(),
) : TokenMinter {

    override fun mint(): MintedToken {
        val idBytes = ByteArray(ID_BYTES).also(random::nextBytes)
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val id = encodeTokenId(idBytes)
        return MintedToken(
            id = id,
            plaintext = "$TOKEN_PREFIX${id}_${encodeTokenSecret(secret)}",
            secretHash = hasher.hash(secret),
        )
    }

    private companion object {
        const val ID_BYTES = 8
        const val SECRET_BYTES = 32
    }
}
