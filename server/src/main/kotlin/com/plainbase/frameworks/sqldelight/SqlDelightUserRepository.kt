package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.DuplicateUsernameException
import com.plainbase.domain.repository.UserMeta
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.repository.UserRow
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import kotlin.time.Instant

/**
 * SQLDelight adapter for [UserRepository] over the `users` table (landed by `6.sqm`).
 *
 * All columns are primitive, so the table needs no custom column adapter (it is absent from
 * [DatabaseFactory.createDatabase]): `disabled` is stored as `0`/`1` (Boolean ↔ Long) and timestamps as
 * epoch-millis `Long` — the one-site mapping the `ApiToken`/`SubjectRole` adapters use, kept here at the SQL
 * boundary so the domain row stays a clean `Boolean`/`Instant` type.
 */
class SqlDelightUserRepository(private val db: PlainbaseDb) : UserRepository {

    private val queries get() = db.usersQueries

    override fun insert(row: UserRow) {
        try {
            queries.insert(
                id = row.id,
                username = row.username,
                passwordHash = row.passwordHash,
                displayName = row.displayName,
                disabled = if (row.disabled) 1 else 0,
                createdAt = row.createdAt.toEpochMilliseconds(),
                updatedAt = row.updatedAt.toEpochMilliseconds(),
            )
        } catch (e: SQLiteException) {
            // Translate the username UNIQUE-constraint violation into the framework-free domain signal so a racing
            // duplicate create maps to a 409, never a raw 500. Any other constraint (e.g. the id PK) is a real bug —
            // rethrow it untouched.
            if (e.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE && "users.username" in e.message.orEmpty()) {
                throw DuplicateUsernameException(row.username)
            }
            throw e
        }
    }

    override fun findByUsername(username: String): UserRow? =
        queries.selectByUsername(username).executeAsOneOrNull()?.toRow()

    override fun findById(id: String): UserRow? =
        queries.selectById(id).executeAsOneOrNull()?.toRow()

    // The query column is nullable, so a found row with a NULL display_name and an unknown id both surface as null —
    // identical to the labeler's externalId fallback for both (the ambiguity is harmless, as the port doc notes).
    override fun displayNameById(id: String): String? =
        queries.displayNameById(id).executeAsOneOrNull()?.display_name

    override fun setPasswordHash(id: String, hash: String, at: Instant) {
        queries.setPasswordHash(passwordHash = hash, updatedAt = at.toEpochMilliseconds(), id = id)
    }

    override fun setDisabled(id: String, disabled: Boolean, at: Instant) {
        queries.setDisabled(disabled = if (disabled) 1 else 0, updatedAt = at.toEpochMilliseconds(), id = id)
    }

    override fun all(): List<UserMeta> = queries.selectAllMeta(::toMeta).executeAsList()

    override fun countEnabledAdmins(): Long = queries.countEnabledAdmins().executeAsOne()

    private fun Users.toRow() = UserRow(
        id = id,
        username = username,
        passwordHash = password_hash,
        displayName = display_name,
        disabled = disabled != 0L,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        updatedAt = Instant.fromEpochMilliseconds(updated_at),
    )

    private fun toMeta(
        id: String,
        username: String,
        displayName: String?,
        disabled: Long,
        createdAt: Long,
        updatedAt: Long,
    ) = UserMeta(
        id = id,
        username = username,
        displayName = displayName,
        disabled = disabled != 0L,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    )
}
