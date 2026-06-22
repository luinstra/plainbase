package com.plainbase.domain.principal

// The session-cookie + CSRF value types (A4a). Pure domain: the mint (SecureRandom) lives in
// `frameworks/security/SessionTokenMinter`, an I/O concern, exactly as the `pb_` token mint does.
//
// Unlike the `pb_<id>_<secret>` agent token, a SESSION has ONE opaque token (§1): the 32-byte cookie value, whose
// raw `SHA-256` is the `sessions` PRIMARY KEY — no public id prefix, no id+secret split. The indexed PK lookup IS
// the credential match, so there is no constant-time `verify` on the session path (a missing row is "no session";
// the CSRF token, by contrast, IS compared constant-time — that compare DOES transfer because it is a raw-byte
// equality of two known-length arrays, not a hash lookup).

/**
 * A freshly minted session: the one-time [plaintext] cookie value (base64url, never stored/logged/re-returned),
 * the raw 32-byte [tokenHash] (`SHA-256(plaintext)` — the `sessions` PK persisted at rest), and the raw 32-byte
 * [csrfToken] (the per-session synchronizer, persisted + returned to the client). A plain class, not `data` — the
 * [ByteArray] fields would make a generated `equals`/`toString` both wrong and a hygiene hazard (the [MintedToken]
 * house-style note).
 */
class MintedSession(val plaintext: String, val tokenHash: ByteArray, val csrfToken: ByteArray)

/**
 * Port for minting a session token + its CSRF token from a CSPRNG. Implemented by
 * `frameworks/security/SessionTokenMinter`. The mint is the only randomness on the session path; the hashing
 * reuses the raw-SHA-256 [TokenSecretHasher].
 */
interface SessionTokenMinter {

    /** Mints a fresh [MintedSession]; the [MintedSession.plaintext] rides out ONCE and is not re-derivable from the hash. */
    fun mint(): MintedSession
}

/**
 * The ONE place a session cookie STRING becomes its `sessions` PK: the raw `SHA-256` of the cookie value's UTF-8
 * bytes (the same raw scheme the `pb_` token secret uses — a session token is high-entropy from `SecureRandom`, so
 * a slow KDF buys nothing). Shared by the minter (which stores this hash at mint) and `SessionService.authenticate`
 * (which hashes the raw cookie on read), so the minted PK and the read-side lookup key are computed identically —
 * the indexed PK lookup IS the credential match (§1). Lives in `domain/` (the [TokenSecretHasher] is a domain port)
 * so the framework-free service can call it.
 */
fun TokenSecretHasher.hashCookie(cookie: String): ByteArray = hash(cookie.encodeToByteArray())
