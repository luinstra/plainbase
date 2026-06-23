package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.SubjectRoleRow
import kotlin.time.Instant

/**
 * SQLDelight adapter for [RoleRepository] over the `subject_role` table (landed by `5.sqm`).
 *
 * All columns are primitive, so the table needs no custom column adapter (it is absent from
 * [DatabaseFactory.createDatabase]): [Role] is stored as its enum name ([Role.name] / [valueOf]) and the
 * timestamp as epoch-millis `Long` — the one-site mapping the `ApiToken`/`Stage` adapters use, kept here at the
 * SQL boundary so the domain row stays a clean `Role`/`Instant` type.
 */
class SqlDelightRoleRepository(private val db: PlainbaseDb) : RoleRepository {

    private val queries get() = db.subjectRoleQueries

    override fun roleOf(issuer: String, externalId: String): Role? =
        queries.selectByIdentity(issuer = issuer, externalId = externalId)
            .executeAsOneOrNull()
            ?.let { Role.valueOf(it.role) }

    override fun upsert(issuer: String, externalId: String, role: Role, at: Instant) {
        queries.upsert(issuer = issuer, externalId = externalId, role = role.name, createdAt = at.toEpochMilliseconds())
    }

    override fun all(): List<SubjectRoleRow> = queries.selectAll(::toRow).executeAsList()

    private fun toRow(issuer: String, externalId: String, role: String, createdAt: Long) =
        SubjectRoleRow(
            issuer = issuer,
            externalId = externalId,
            role = Role.valueOf(role),
            createdAt = Instant.fromEpochMilliseconds(createdAt),
        )
}
