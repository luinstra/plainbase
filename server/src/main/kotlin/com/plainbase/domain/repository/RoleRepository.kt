package com.plainbase.domain.repository

import kotlin.time.Instant

/**
 * The at-rest subject → role store (A3, the authZ choke point). Lives in the APP database (`plainbase.db`) —
 * security truth is app state like the A2 `api_tokens` table — NEVER disposable `search.db` (ADR-0004). The
 * lookup key is the SAME `(issuer, externalId)` pair `Principal.Human` carries / the agent token defaults to.
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightRoleRepository`. The table stores
 * only WHICH role a subject has — the role×action MATRIX is hardcoded in `PolicyService`, never read from here
 * (the non-escalation guarantee).
 */
interface RoleRepository {

    /** The role for an identity, or null when the subject has no row (→ the matrix's default-deny baseline). */
    fun roleOf(issuer: String, externalId: String): Role?

    /** Insert-or-replace a subject's role — the A4b admin UI's grant path and the test-seeding path. */
    fun upsert(issuer: String, externalId: String, role: Role, at: Instant)

    /** Every subject's role row (the future A4b list surface). */
    fun all(): List<SubjectRoleRow>
}

/**
 * The role axis the hardcoded `PolicyService` matrix maps over: VIEWER → read; EDITOR → read + edit + create;
 * ADMIN → all (incl. manage). An agent token's `AgentMode` maps onto this SAME axis in `check()` — A3 adds no
 * agent-specific role table.
 */
enum class Role { VIEWER, EDITOR, ADMIN }

/** One `subject_role` row. */
data class SubjectRoleRow(val issuer: String, val externalId: String, val role: Role, val createdAt: Instant)
