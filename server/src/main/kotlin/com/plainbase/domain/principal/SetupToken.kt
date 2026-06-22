package com.plainbase.domain.principal

// The one-time setup/reset token value types (A4a). Pure domain: the mint (SecureRandom) lives in
// `frameworks/security/SetupTokenMinter`. ONE opaque token like a session token (§1/§5): the base64url plaintext
// is conveyed out-of-band ONCE (CLI stdout for bootstrap, admin-conveyed for reset); only its raw `SHA-256` (the
// `setup_tokens` PK) persists, so the plaintext is never re-derivable from a row.

/**
 * A freshly minted setup/reset token: the one-time [plaintext] (base64url, never stored/logged-as-secret) and the
 * raw 32-byte [tokenHash] (`SHA-256(plaintext)` — the `setup_tokens` PK at rest). A plain class, not `data` (the
 * [ByteArray] field — the [MintedToken] house-style note).
 */
class MintedSetupToken(val plaintext: String, val tokenHash: ByteArray)

/** Port for minting a one-time setup/reset token from a CSPRNG. Implemented by `frameworks/security/SetupTokenMinter`. */
interface SetupTokenMinter {

    /** Mints a fresh [MintedSetupToken]; the plaintext rides out ONCE and is not re-derivable from the hash. */
    fun mint(): MintedSetupToken
}
