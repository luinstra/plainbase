package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.AuditEntry
import com.plainbase.domain.repository.AuditRepository
import kotlin.time.Instant

/**
 * SQLDelight adapter for [AuditRepository] over the `audit_log` table (landed by `5.sqm`).
 *
 * All columns are primitive, so the table needs no custom column adapter (it is absent from
 * [DatabaseFactory.createDatabase]): the timestamp maps to epoch-millis `Long` at the SQL boundary (the
 * `ApiToken` idiom). `issuer`/`externalId` are nullable (NULL for an Anonymous principal).
 */
class SqlDelightAuditRepository(private val db: PlainbaseDb) : AuditRepository {

    private val queries get() = db.auditLogQueries

    override fun record(entry: AuditEntry) {
        queries.insert(
            id = entry.id,
            ts = entry.ts.toEpochMilliseconds(),
            principalKind = entry.principalKind,
            issuer = entry.issuer,
            externalId = entry.externalId,
            action = entry.action,
            resource = entry.resource,
            decision = entry.decision,
        )
    }

    override fun recent(limit: Int): List<AuditEntry> = queries.selectRecent(limit.toLong(), ::toEntry).executeAsList()

    @Suppress("LongParameterList")
    private fun toEntry(
        id: String,
        ts: Long,
        principalKind: String,
        issuer: String?,
        externalId: String?,
        action: String,
        resource: String,
        decision: String,
    ) = AuditEntry(
        id = id,
        ts = Instant.fromEpochMilliseconds(ts),
        principalKind = principalKind,
        issuer = issuer,
        externalId = externalId,
        action = action,
        resource = resource,
        decision = decision,
    )
}
