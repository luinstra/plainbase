package com.plainbase.domain.repository

import kotlin.time.Instant

/**
 * The at-rest store for agent `pb_` API tokens (A2, §0.2). Lives in the APP database (`plainbase.db`) — security
 * truth is app state, like the §B3 checkpoint and the W1 dirty-page journal — NEVER disposable `search.db`
 * (ADR-0004). The lookup key is the PUBLIC `id` prefix; the secret never lives here, only its raw SHA-256
 * ([ApiTokenRow.secretHash]). `all()` returns metadata only — there is no plaintext column to leak.
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightApiTokenRepository`. A token is
 * immutable once minted — there is no `secret_hash` update; rotation is mint-new + revoke-old.
 */
interface ApiTokenRepository {

    /** Persist a freshly minted token row (its [ApiTokenRow.secretHash] already computed). */
    fun insert(row: ApiTokenRow)

    /** The prefix lookup: the row for [id], or null if unknown. The ONLY path that returns [secretHash]. */
    fun findById(id: String): ApiTokenRow?

    /**
     * The agent token's [AgentMode], or null if unknown — A3 role resolution reads ONLY the mode (→ Role) for an
     * already-authenticated [com.plainbase.domain.principal.Principal.Agent], never the secret hash. A narrow
     * projection so the authZ path never loads the at-rest secret (defense-in-depth).
     */
    fun modeOf(id: String): AgentMode?

    /**
     * The agent token's `agent_label`, or null if unknown — C4 author labeling reads ONLY the label (→ the snapshot
     * attribution) for an already-authenticated [com.plainbase.domain.principal.Principal.Agent], never the secret
     * hash. A narrow projection (the [modeOf] discipline) so the non-auth propose path never loads the at-rest secret.
     */
    fun agentLabelById(id: String): String?

    /**
     * The atomic authenticate stamp (TOCTOU-safe): sets [id]'s `last_used_at` to [at] in ONE conditional write
     * that fires ONLY while the token is still active (not revoked, not expired at [at]), and returns whether a
     * row was updated. A concurrent revoke/expiry committing first makes this a no-op (returns false), so a
     * stale read can never grant access. The caller grants Agent only when the secret verified AND this is true.
     */
    fun touchIfActive(id: String, at: Instant): Boolean

    /** Revoke [id] by setting its `revoked_at` to [at]. */
    fun revoke(id: String, at: Instant)

    /** Every token's metadata (NO secret hash — the list/CLI surface has no secret to leak). */
    fun all(): List<ApiTokenMeta>
}

/**
 * One `api_tokens` row INCLUDING the [secretHash] — returned only by [ApiTokenRepository.findById], the
 * authenticate lookup that needs it for the constant-time verify. A plain class, not `data` — the [secretHash]
 * [ByteArray] would make a generated `equals`/`hashCode` wrong (the WriteIntent/MintedToken house-style note).
 * [secretHash] is the raw 32-byte SHA-256 of the secret; the plaintext is never stored.
 */
class ApiTokenRow(
    val id: String,
    val secretHash: ByteArray,
    val agentLabel: String,
    val issuer: String,
    val externalId: String,
    val mode: AgentMode,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

/**
 * A token's metadata WITHOUT the `secret_hash` — the [ApiTokenRepository.all] list/CLI surface. Deliberately a
 * distinct type from [ApiTokenRow] so the at-rest secret can never ride the list path (the return type itself
 * cannot leak it, defending future callers). A `data` class is fine here: no [ByteArray] field.
 */
data class ApiTokenMeta(
    val id: String,
    val agentLabel: String,
    val issuer: String,
    val externalId: String,
    val mode: AgentMode,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

/**
 * What an agent token is permitted to do. The FIELD lands now (A2); ENFORCEMENT of
 * direct-commit-vs-propose is Phase 5 (§0.7). Stored as the enum name in TEXT (the [Stage] idiom).
 */
enum class AgentMode { READ_ONLY, PROPOSE, COMMIT }
