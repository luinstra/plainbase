package com.plainbase.frameworks.security

/**
 * Names the concrete at-rest scheme for SESSION cookie tokens ([TokenHasher] = raw SHA-256) at wiring/test sites,
 * so a reader sees the session token uses the SAME raw-SHA-256 scheme as the `pb_` token secret (both are
 * high-entropy `SecureRandom` values, so a slow KDF buys nothing). The cookie-string → PK convention itself is
 * `domain/principal/hashCookie`; this alias just labels the hasher the minter/service compose. The port is
 * `domain/principal/TokenSecretHasher`.
 */
typealias SessionTokenHasher = TokenHasher
