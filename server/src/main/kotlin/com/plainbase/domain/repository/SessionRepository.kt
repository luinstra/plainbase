package com.plainbase.domain.repository

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The at-rest store for server-side human sessions (A4a). Lives in the APP database (`plainbase.db`) — security
 * truth is app state — NEVER disposable `search.db` (ADR-0004). A row is keyed by the raw `SHA-256` of the ONE
 * opaque cookie token ([SessionRow.tokenHash]); the indexed PK lookup IS the credential match (no public handle,
 * no id+secret split, §1). The plaintext token is never stored.
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightSessionRepository`.
 */
interface SessionRepository {

    /** Persist a freshly minted session row (its [SessionRow.tokenHash] + [SessionRow.csrfToken] already computed). */
    fun insert(row: SessionRow)

    /** The by-hash lookup: the row for [tokenHash] (incl. `user_id` + `csrf_token`), or null if unknown. */
    fun findByTokenHash(tokenHash: ByteArray): SessionRow?

    /**
     * The §2 atomic authenticate stamp (TOCTOU-safe): slides `idle_expires_at` to `[now] + [idleTtl]` in ONE
     * conditional UPDATE that fires ONLY while the session is — AT THIS instant — still valid (not revoked, within
     * the absolute cap, not idle-expired), and returns whether a row was updated. A concurrent revoke/expiry
     * committing first makes this a no-op (returns false), so a stale read can never grant access. The caller
     * grants Human ONLY when this returns true (the atomic touch IS the validity decision — NO skip-window).
     */
    fun touchIfActive(tokenHash: ByteArray, now: Instant, idleTtl: Duration): Boolean

    /** Revoke a single session by its [tokenHash] (idempotent — a no-op if already revoked/unknown). */
    fun revokeByTokenHash(tokenHash: ByteArray, at: Instant)

    /** Revoke EVERY still-active session for [userId] (the §7 password-change/reset/admin-revoke hook). */
    fun revokeAllForUser(userId: String, at: Instant)

    /**
     * Startup-time prune: delete DEAD session rows (revoked, or past the absolute cap at [now]); a live session is
     * never touched. The table is insert/update-only, so without this dead rows accumulate forever. Called ONCE at
     * boot inside the DataDirLock — never per-write (that would amplify every login write).
     */
    fun prune(now: Instant)
}

/**
 * One `sessions` row — a plain class, not `data` (the [tokenHash]/[csrfToken] [ByteArray] fields would make a
 * generated `equals`/`hashCode`/`toString` both wrong and a hygiene hazard — the [SessionRow] is read one-shot, so
 * no value equality is warranted; the [MintedToken][com.plainbase.domain.principal.MintedToken] house-style note).
 * [tokenHash] is the SHA-256 of the token (the plaintext is never held); [csrfToken] is the per-session
 * synchronizer token. The repository maps `Instant <-> Long` at its boundary.
 */
class SessionRow(
    val tokenHash: ByteArray,
    val userId: String,
    val csrfToken: ByteArray,
    val createdAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val revokedAt: Instant?,
)
