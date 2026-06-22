package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.SetupTokenPurpose
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.SetupTokenRow
import kotlin.time.Instant

/**
 * SQLDelight adapter for [SetupTokenRepository] over the `setup_tokens` table (landed by `6.sqm`).
 *
 * All columns are primitive, so the table needs no custom column adapter: [SetupTokenPurpose] is stored as its
 * enum name ([SetupTokenPurpose.name] / [valueOf]), the `token_hash` BLOB rides as a raw [ByteArray], and
 * timestamps as epoch-millis `Long`. [markUsed] reads `.value == 1L` like the other atomic conditional updates —
 * exactly one row means the token was unused + unexpired when the UPDATE committed (the single-use consume, §5).
 */
class SqlDelightSetupTokenRepository(private val db: PlainbaseDb) : SetupTokenRepository {

    private val queries get() = db.setupTokensQueries

    override fun insert(row: SetupTokenRow) {
        queries.insert(
            tokenHash = row.tokenHash,
            purpose = row.purpose.name,
            userId = row.userId,
            createdAt = row.createdAt.toEpochMilliseconds(),
            expiresAt = row.expiresAt.toEpochMilliseconds(),
            usedAt = row.usedAt?.toEpochMilliseconds(),
        )
    }

    override fun findByTokenHash(tokenHash: ByteArray): SetupTokenRow? =
        queries.selectByTokenHash(tokenHash).executeAsOneOrNull()?.toRow()

    override fun markUsed(tokenHash: ByteArray, now: Instant): Boolean =
        queries.markUsed(now = now.toEpochMilliseconds(), tokenHash = tokenHash).value == 1L

    override fun prune(now: Instant) {
        queries.pruneDead(now = now.toEpochMilliseconds())
    }

    private fun Setup_tokens.toRow() = SetupTokenRow(
        tokenHash = token_hash,
        purpose = SetupTokenPurpose.valueOf(purpose),
        userId = user_id,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        expiresAt = Instant.fromEpochMilliseconds(expires_at),
        usedAt = used_at?.let(Instant::fromEpochMilliseconds),
    )
}
