package com.plainbase.domain.repository

import kotlin.time.Instant

/**
 * The at-rest store for one-time setup + password-reset tokens (A4a). ONE table serves BOTH the first-admin
 * bootstrap AND admin-issued password resets (the synthesis §7 "reset reuses the setup-token mechanism" — a
 * [SetupTokenPurpose] column discriminates). Lives in the APP database (`plainbase.db`) — security truth is app
 * state — NEVER disposable `search.db` (ADR-0004). A row is keyed by the raw `SHA-256` of the one-time token
 * ([SetupTokenRow.tokenHash]); the PK lookup IS the match (no plaintext stored).
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightSetupTokenRepository`.
 */
interface SetupTokenRepository {

    /** Persist a freshly minted token row (its [SetupTokenRow.tokenHash] already computed). */
    fun insert(row: SetupTokenRow)

    /** The by-hash lookup: the row for [tokenHash], or null if unknown. */
    fun findByTokenHash(tokenHash: ByteArray): SetupTokenRow?

    /**
     * The atomic single-use consume (the `touchIfActive` shape): marks `used_at` to [now] in ONE conditional
     * UPDATE that fires ONLY while the token is unused AND unexpired at [now], returning whether a row was updated.
     * The caller proceeds iff this is true, so a concurrent double-consume admits exactly one (kills the
     * count-then-insert TOCTOU, §5).
     */
    fun markUsed(tokenHash: ByteArray, now: Instant): Boolean

    /**
     * Startup-time prune: delete DEAD token rows (already used, or expired at [now]); a live (unused, unexpired)
     * token is never touched. The table is insert/update-only, so without this dead rows accumulate forever. Called
     * ONCE at boot inside the DataDirLock — never per-write.
     */
    fun prune(now: Instant)
}

/** What a setup token authorizes: the first-admin bootstrap, or a password reset for an existing user. */
enum class SetupTokenPurpose { BOOTSTRAP, RESET }

/**
 * One `setup_tokens` row — a plain class, not `data` (the [tokenHash] [ByteArray] would make a generated
 * `equals`/`toString` wrong/leaky — the [MintedToken][com.plainbase.domain.principal.MintedToken] house note).
 * [userId] is null for a BOOTSTRAP token (no user yet) and set for a RESET (the target). [tokenHash] is the
 * SHA-256 of the one-time token; the plaintext is never stored.
 */
class SetupTokenRow(
    val tokenHash: ByteArray,
    val purpose: SetupTokenPurpose,
    val userId: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
)
