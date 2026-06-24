package com.plainbase.domain.repository

import kotlin.time.Instant

/**
 * The at-rest store for built-in human login users (A4a). Lives in the APP database (`plainbase.db`) — security
 * truth is app state, like the A2 `api_tokens` table and the A3 `subject_role` table — NEVER disposable
 * `search.db` (ADR-0004). [UserRow.id] is the `external_id` a `Principal.Human(issuer="builtin", externalId=id)`
 * and its `subject_role` grant key on. [all] returns metadata only ([UserMeta]) — there is no plaintext, and the
 * `password_hash` is deliberately absent from the list type so it can never ride that path.
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightUserRepository`.
 */
interface UserRepository {

    /**
     * Persist a freshly created user row (its argon2 [UserRow.passwordHash] already computed). Throws
     * [DuplicateUsernameException] if `username` is already taken — the `users.username UNIQUE` constraint is the
     * authority, so a preflight existence check is only a fast path: two concurrent creates can both pass it, and the
     * loser surfaces here as the constraint violation (which callers map to their 409 outcome, never a 500).
     */
    fun insert(row: UserRow)

    /** The login lookup: the row for [username] (incl. [UserRow.passwordHash] for the verify), or null if unknown. */
    fun findByUsername(username: String): UserRow?

    /** The by-id lookup (incl. [UserRow.passwordHash]) — the password-change/reset path resolves the user here. */
    fun findById(id: String): UserRow?

    /**
     * The user's `display_name` (nullable column), or null if the user is unknown — C4 author labeling reads ONLY the
     * display name (→ the snapshot attribution), never the `password_hash`. A narrow projection so the non-auth
     * propose path never loads the at-rest hash (the [findByUsername]/[findById] verify paths keep it; this omits it).
     * A null result is ambiguous (unknown user OR a user with no display name) — the labeler falls back to externalId
     * for both, so the ambiguity is harmless.
     */
    fun displayNameById(id: String): String?

    /** Set a new argon2 [hash] for [id], stamping `updated_at` to [at] (self-service change + admin reset). */
    fun setPasswordHash(id: String, hash: String, at: Instant)

    /** Soft-lock / unlock [id], stamping `updated_at` to [at] (a disabled user maps to the same 401 as a bad login). */
    fun setDisabled(id: String, disabled: Boolean, at: Instant)

    /** Every user's metadata (NO `password_hash` — the list/admin surface has no secret to leak). */
    fun all(): List<UserMeta>

    /** How many ENABLED builtin admins exist — the bootstrap/recovery gate (WI-13): mint without `--force` iff zero. */
    fun countEnabledAdmins(): Long
}

/**
 * One `users` row INCLUDING the argon2 [passwordHash] — returned only by [UserRepository.findByUsername] /
 * [UserRepository.findById], the authenticate lookups that need it for the constant-time verify. A `data` class is
 * fine: no [ByteArray] field, and the PHC hash is a self-describing string, not raw secret bytes — but it is never
 * logged (secret hygiene). The plaintext password is never stored.
 */
data class UserRow(
    val id: String,
    val username: String,
    val passwordHash: String,
    val displayName: String?,
    val disabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A user's metadata WITHOUT the `password_hash` — the [UserRepository.all] admin list surface. Deliberately a
 * distinct type from [UserRow] so the at-rest hash can never ride the list path (the return type itself cannot
 * leak it, defending future callers — the [ApiTokenMeta] discipline).
 */
data class UserMeta(
    val id: String,
    val username: String,
    val displayName: String?,
    val disabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The `users.username UNIQUE` constraint fired on [UserRepository.insert] — the handle is already taken. The
 * SQLDelight adapter translates the driver's constraint exception into this framework-free domain signal so callers
 * (admin create, bootstrap) map a duplicate to their 409 outcome instead of leaking a raw 500.
 */
class DuplicateUsernameException(username: String) : RuntimeException("username already exists: $username")
