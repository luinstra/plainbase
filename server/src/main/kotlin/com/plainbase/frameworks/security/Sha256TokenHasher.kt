package com.plainbase.frameworks.security

/**
 * Names the concrete at-rest scheme ([TokenHasher] = raw SHA-256 + constant-time verify) at wiring/test sites,
 * so a reader sees the scheme without opening the impl. The port is `domain/principal/TokenSecretHasher`.
 */
typealias Sha256TokenHasher = TokenHasher
