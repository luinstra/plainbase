package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SessionRow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * SQLDelight adapter for [SessionRepository] over the `sessions` table (landed by `6.sqm`).
 *
 * All columns are primitive, so the table needs no custom column adapter: the `token_hash`/`csrf_token` BLOBs ride
 * as raw [ByteArray]s and timestamps as epoch-millis `Long` (the boundary mapping the other adapters use).
 * [touchIfActive] reads `.value == 1L` like [SqlDelightApiTokenRepository.touchIfActive] — the synchronous JDBC
 * driver returns the affected-row count, and exactly one row means the session was still valid when the
 * conditional UPDATE committed (the §2 atomic gate; NO skip-window).
 */
class SqlDelightSessionRepository(private val db: PlainbaseDb) : SessionRepository {

    private val queries get() = db.sessionsQueries

    override fun insert(row: SessionRow) {
        queries.insert(
            tokenHash = row.tokenHash,
            userId = row.userId,
            csrfToken = row.csrfToken,
            createdAt = row.createdAt.toEpochMilliseconds(),
            idleExpiresAt = row.idleExpiresAt.toEpochMilliseconds(),
            absoluteExpiresAt = row.absoluteExpiresAt.toEpochMilliseconds(),
            revokedAt = row.revokedAt?.toEpochMilliseconds(),
        )
    }

    override fun findByTokenHash(tokenHash: ByteArray): SessionRow? =
        queries.selectByTokenHash(tokenHash).executeAsOneOrNull()?.toRow()

    override fun touchIfActive(tokenHash: ByteArray, now: Instant, idleTtl: Duration): Boolean =
        queries.touchIfActive(
            idleExpiresAt = (now + idleTtl).toEpochMilliseconds(),
            tokenHash = tokenHash,
            now = now.toEpochMilliseconds(),
        ).value == 1L

    override fun revokeByTokenHash(tokenHash: ByteArray, at: Instant) {
        queries.revokeByTokenHash(revokedAt = at.toEpochMilliseconds(), tokenHash = tokenHash)
    }

    override fun revokeAllForUser(userId: String, at: Instant) {
        queries.revokeAllForUser(revokedAt = at.toEpochMilliseconds(), userId = userId)
    }

    override fun prune(now: Instant) {
        queries.pruneDead(now = now.toEpochMilliseconds())
    }

    private fun Sessions.toRow() = SessionRow(
        tokenHash = token_hash,
        userId = user_id,
        csrfToken = csrf_token,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        idleExpiresAt = Instant.fromEpochMilliseconds(idle_expires_at),
        absoluteExpiresAt = Instant.fromEpochMilliseconds(absolute_expires_at),
        revokedAt = revoked_at?.let(Instant::fromEpochMilliseconds),
    )
}
