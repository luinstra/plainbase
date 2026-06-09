package com.plainbase.domain.principal

/**
 * Port for password hashing (humans authenticate with argon2-hashed passwords, §3).
 * Implemented by `frameworks/security/Argon2PasswordHasher`.
 */
interface PasswordHasher {

    /** Hashes [plain] and returns a self-describing encoded string (PHC format). */
    fun hash(plain: CharArray): String

    /** Verifies [plain] against a previously [hash]-produced encoded string. */
    fun verify(plain: CharArray, encoded: String): Boolean
}
