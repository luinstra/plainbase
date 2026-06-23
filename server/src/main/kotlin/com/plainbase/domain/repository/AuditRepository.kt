package com.plainbase.domain.repository

import kotlin.time.Instant

/**
 * The at-rest authorization-audit store (A3). Lives in the APP database (`plainbase.db`) — security truth is app
 * state — NEVER disposable `search.db` (ADR-0004). `PolicyService` records ONE pre-effect decision row per
 * MUTATING `check()` (allowed AND denied); reads are not audited (volume).
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightAuditRepository`.
 */
interface AuditRepository {

    /** Persist ONE pre-effect authorization-decision row (allowed AND denied), MUTATING actions only. */
    fun record(entry: AuditEntry)

    /** The most recent [limit] decisions, newest-first (the A4b audit-view read surface). */
    fun recent(limit: Int): List<AuditEntry>
}

/**
 * One `audit_log` row: WHO ([principalKind]/[issuer]/[externalId] — issuer/externalId null for an Anonymous
 * principal), WHAT ([action]/[resource] — the facade method's intrinsic descriptors), and the [decision]
 * (`"allowed"`/`"denied"`). [resource] is a plain string (a page id, an asset path, or a fixed token).
 */
data class AuditEntry(
    val id: String,
    val ts: Instant,
    val principalKind: String,
    val issuer: String?,
    val externalId: String?,
    val action: String,
    val resource: String,
    val decision: String,
)
