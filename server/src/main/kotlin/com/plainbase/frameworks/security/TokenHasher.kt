package com.plainbase.frameworks.security

import com.plainbase.domain.principal.TokenSecretHasher
import java.security.MessageDigest

/**
 * The at-rest scheme for agent `pb_` token secrets (A2, §0.2): a RAW SHA-256 of the secret bytes (NOT the
 * `"sha256:"`-prefixed content-hash form — that is a citation string; a token hash is a raw 32-byte digest
 * stored as a BLOB), and a constant-time verify via [MessageDigest.isEqual]. NOT argon2 — the secret is
 * high-entropy from `SecureRandom`, so a slow KDF buys nothing. (The [Sha256TokenHasher] alias names the
 * concrete scheme at wiring sites.)
 *
 * The verifier never logs the secret, the candidate, or the stored hash (secret hygiene, §WI-9).
 */
class TokenHasher : TokenSecretHasher {

    override fun hash(secret: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(secret)

    /**
     * Both compared digests are always 32-byte arrays (the candidate SHA-256 vs the stored hash), so `isEqual`
     * is constant-time — its only short-circuit is a length mismatch, which cannot occur here. The stored hash
     * is normalized to the dummy when it is absent (unknown id) OR not exactly 32 bytes: the schema's
     * `BLOB NOT NULL` cannot bound length, so a malformed/imported row would otherwise reintroduce a
     * length-sensitive compare. A wrong-length stored hash thus verifies false via the dummy compare, never an
     * early `return false` — the unknown-id path does byte-for-byte the wrong-secret path's work.
     */
    override fun verify(secret: ByteArray, storedHash: ByteArray?): Boolean {
        val comparisonHash = storedHash?.takeIf { it.size == HASH_BYTES } ?: DUMMY_HASH
        return MessageDigest.isEqual(hash(secret), comparisonHash)
    }

    private companion object {
        const val HASH_BYTES = 32
        val DUMMY_HASH = ByteArray(HASH_BYTES)
    }
}
