package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenMeta
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.ApiTokenRow
import kotlin.time.Instant

/**
 * SQLDelight adapter for [ApiTokenRepository] over the `api_tokens` table (landed by `4.sqm`).
 *
 * All columns are primitive, so the table needs no custom column adapter (it is absent from
 * [DatabaseFactory.createDatabase]): [AgentMode] is stored as its enum name ([AgentMode.name] / [valueOf]) and
 * timestamps as epoch-millis `Long` ([Instant.toEpochMilliseconds] / [Instant.fromEpochMilliseconds]) — the
 * one-site mapping the `Stage`/`DirtyPage` adapter uses, kept here at the SQL boundary so the domain row stays
 * a clean `Instant`/enum type.
 */
class SqlDelightApiTokenRepository(private val db: PlainbaseDb) : ApiTokenRepository {

    private val queries get() = db.apiTokensQueries

    override fun insert(row: ApiTokenRow) {
        queries.insert(
            id = row.id,
            secretHash = row.secretHash,
            agentLabel = row.agentLabel,
            issuer = row.issuer,
            externalId = row.externalId,
            mode = row.mode.name,
            createdAt = row.createdAt.toEpochMilliseconds(),
            lastUsedAt = row.lastUsedAt?.toEpochMilliseconds(),
            expiresAt = row.expiresAt?.toEpochMilliseconds(),
            revokedAt = row.revokedAt?.toEpochMilliseconds(),
        )
    }

    override fun findById(id: String): ApiTokenRow? =
        queries.selectById(id).executeAsOneOrNull()?.toRow()

    override fun modeOf(id: String): AgentMode? =
        queries.modeOf(id).executeAsOneOrNull()?.let(AgentMode::valueOf)

    override fun agentLabelById(id: String): String? =
        queries.agentLabelById(id).executeAsOneOrNull()

    override fun touchIfActive(id: String, at: Instant): Boolean =
        // SQLDelight mutations return the affected-row count in QueryResult.value (synchronous JDBC driver);
        // exactly one row means the token was still active when the conditional UPDATE committed.
        queries.touchIfActive(now = at.toEpochMilliseconds(), id = id).value == 1L

    override fun revoke(id: String, at: Instant) {
        queries.revoke(revokedAt = at.toEpochMilliseconds(), id = id)
    }

    override fun all(): List<ApiTokenMeta> = queries.selectAllMeta(::toMeta).executeAsList()

    private fun Api_tokens.toRow() = ApiTokenRow(
        id = id,
        secretHash = secret_hash,
        agentLabel = agent_label,
        issuer = issuer,
        externalId = external_id,
        mode = AgentMode.valueOf(mode),
        createdAt = Instant.fromEpochMilliseconds(created_at),
        lastUsedAt = last_used_at?.let(Instant::fromEpochMilliseconds),
        expiresAt = expires_at?.let(Instant::fromEpochMilliseconds),
        revokedAt = revoked_at?.let(Instant::fromEpochMilliseconds),
    )

    @Suppress("LongParameterList")
    private fun toMeta(
        id: String,
        agentLabel: String,
        issuer: String,
        externalId: String,
        mode: String,
        createdAt: Long,
        lastUsedAt: Long?,
        expiresAt: Long?,
        revokedAt: Long?,
    ) = ApiTokenMeta(
        id = id,
        agentLabel = agentLabel,
        issuer = issuer,
        externalId = externalId,
        mode = AgentMode.valueOf(mode),
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        lastUsedAt = lastUsedAt?.let(Instant::fromEpochMilliseconds),
        expiresAt = expiresAt?.let(Instant::fromEpochMilliseconds),
        revokedAt = revokedAt?.let(Instant::fromEpochMilliseconds),
    )
}
