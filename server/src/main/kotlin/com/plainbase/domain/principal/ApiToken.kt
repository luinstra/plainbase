package com.plainbase.domain.principal

import kotlin.io.encoding.Base64

// The `pb_<id>_<secret>` agent-token value types (A2). Pure domain: the wire FORMAT and its parse live here,
// beside Principal/PasswordHasher; the SecureRandom mint lives in frameworks/security (an I/O concern). The
// public `id` (8 random bytes) is the lookup prefix, stored verbatim; the `secret` (32 random bytes) is
// high-entropy — only its SHA-256 is ever persisted, so the plaintext is never re-derivable from a row.
//
// `id` is LOWERCASE HEX and `secret` is base64url-without-padding (kotlin.io.encoding.Base64.UrlSafe, honoring
// 9c78ca0). The two encodings are deliberate: the secret is the high-entropy part (base64url packs 256 bits
// into ~43 chars), while a HEX id keeps the id/secret boundary UNAMBIGUOUS — base64url's alphabet includes
// `_`, so a base64url id could collide with the structural `_` separator (`split('_')` would misparse it). Hex
// (`[0-9a-f]`) never contains `_`/`-`, so the FIRST `_` after the prefix is always the true id↔secret boundary,
// and the id stays greppable/case-insensitive. The whole token is a clean single-line URL/header-safe string.

/** The literal token prefix — for greppability and a fail-fast reject of a non-`pb_` bearer (§0.2). */
const val TOKEN_PREFIX: String = "pb_"

/** The public id is exactly 8 random bytes (the minter's `ID_BYTES`), so its lowercase-hex form is 16 chars. */
private const val ID_HEX_LENGTH = 16

/** The secret is exactly 32 random bytes (the minter's `SECRET_BYTES`) — the only length we will hash/compare. */
private const val SECRET_BYTES = 32

/** 32 bytes in base64url WITHOUT padding is exactly 43 chars — the only encoded length we will decode. */
private const val SECRET_BASE64_LENGTH = 43

/** Matches the minter's lowercase-hex id EXACTLY (no Unicode, no overlong, no uppercase) — the lookup key. */
private val idPattern = Regex("[0-9a-f]{$ID_HEX_LENGTH}")

/** base64url WITHOUT padding for the secret (URL/header-safe, single-line, no `=`). */
private val secretBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * A presented bearer parsed into its public [id] prefix and the raw [secret] bytes. One-shot, so no
 * `equals`/`hashCode` override for the [ByteArray] is warranted (the house-style note carried by the other
 * `ByteArray`-bearing row types). Construct only via [parseToken]; a structurally invalid bearer yields null.
 */
class ParsedToken(val id: String, val secret: ByteArray)

/**
 * Parses `pb_<id>_<secret>` into a [ParsedToken], or null on ANY deviation (the null-on-deviation idiom
 * [com.plainbase.frameworks.security.Argon2PasswordHasher] uses for PHC strings). Fail-closed: a non-`pb_`
 * bearer, a missing/empty segment, or an undecodable base64 secret all return null — the caller maps null to
 * [Principal.Anonymous], never an exception. The id↔secret boundary is the FIRST `_` after the prefix (the hex
 * id is `_`-free); the [id] is kept verbatim (it is the stored lookup key), only the secret is decoded.
 *
 * Hard bounds are enforced BEFORE decode against the minter's exact shape: the id must be exactly 16 lowercase
 * hex chars and the encoded secret exactly 43 base64url chars (32 bytes), with the decoded secret confirmed at
 * 32 bytes. This rejects non-hex / Unicode / overlong ids and any wrong secret length up front, so a hostile
 * oversized bearer cannot drive avoidable substring/base64/hash work — a real token still parses unchanged.
 */
fun parseToken(raw: String): ParsedToken? {
    if (!raw.startsWith(TOKEN_PREFIX)) return null
    val body = raw.removePrefix(TOKEN_PREFIX)
    val boundary = body.indexOf('_')
    if (boundary <= 0 || boundary == body.lastIndex) return null // empty id or empty secret
    val id = body.substring(0, boundary)
    if (!idPattern.matches(id)) return null // exactly 16 lowercase-hex chars — the minter's id shape
    val encodedSecret = body.substring(boundary + 1)
    if (encodedSecret.length != SECRET_BASE64_LENGTH) return null // reject before the decode/hash work
    val secret = runCatching { secretBase64.decode(encodedSecret) }
        .getOrNull()?.takeIf { it.size == SECRET_BYTES } ?: return null
    return ParsedToken(id = id, secret = secret)
}

/** Encodes the public id bytes as lowercase hex (the `_`-free id alphabet — see the file note). */
fun encodeTokenId(idBytes: ByteArray): String = idBytes.toHexString()

/** Encodes the secret bytes as base64url-without-padding (the high-entropy secret alphabet). */
fun encodeTokenSecret(secret: ByteArray): String = secretBase64.encode(secret)

/**
 * A freshly minted token: the public [id] (the stored lookup prefix), the one-time [plaintext]
 * `pb_<id>_<secret>` (NEVER stored, NEVER logged, NEVER re-returned), and the [secretHash] (the raw 32-byte
 * SHA-256 persisted at rest). A plain class, not `data` — the [ByteArray] field would make a generated
 * `equals`/`toString` both wrong and a hygiene hazard (a `toString` that prints the plaintext).
 */
class MintedToken(val id: String, val plaintext: String, val secretHash: ByteArray)

/**
 * Port for minting a `pb_<id>_<secret>` token from a CSPRNG. Implemented by
 * `frameworks/security/ApiTokenMinter`.
 */
interface TokenMinter {

    /** Mints a fresh token; the plaintext rides [MintedToken] ONCE and is never re-derivable from the hash. */
    fun mint(): MintedToken
}

/**
 * Port for the at-rest token-secret scheme (§0.2): a raw SHA-256 hash + a constant-time verify. Implemented by
 * `frameworks/security/TokenHasher` (the `Sha256TokenHasher` alias names the concrete scheme).
 */
interface TokenSecretHasher {

    /** The raw 32-byte SHA-256 of [secret] — the `secret_hash` stored at rest. */
    fun hash(secret: ByteArray): ByteArray

    /**
     * Constant-time verify of [secret] against [storedHash] — or, when the id was UNKNOWN (no row), against a
     * fixed dummy hash so the unknown-id path runs the SAME single compare and returns false with no early-out
     * (anti-enumeration, §WI-6).
     */
    fun verify(secret: ByteArray, storedHash: ByteArray?): Boolean
}
