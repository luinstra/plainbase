package com.plainbase.frameworks.security

import com.plainbase.domain.principal.PasswordHasher

/**
 * A fixed, precomputed dummy argon2 PHC for the login anti-enumeration verify (§6): an unknown OR disabled user's
 * login still runs ONE argon2 `verify` against THIS hash, so the unknown-user path does byte-for-byte the
 * wrong-password path's work and "this username exists" is not observable in the response timing. Hashed once at
 * construction from a fixed throwaway passphrase (never a real credential) with the SAME [PasswordHasher] params
 * the real hashes use, so the dummy verify costs the same as a real one. `LoginService` takes the result.
 */
fun dummyPasswordHash(hasher: PasswordHasher): String =
    hasher.hash("plainbase-anti-enumeration-dummy".toCharArray())
