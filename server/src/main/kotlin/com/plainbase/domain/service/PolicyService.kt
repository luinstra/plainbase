package com.plainbase.domain.service

import com.plainbase.domain.principal.CreateGrant
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.principal.ManageGrant
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditEntry
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import kotlin.time.Clock

/**
 * The authorization choke point (A3, the security HEART). Pure domain — it depends on the [RoleRepository] /
 * [ApiTokenRepository] / [AuditRepository] PORTS + an injectable [Clock] + an [IdProvider] (all domain ports),
 * never a framework type (`DomainPurityTest` covers it). The guarded facades call `check*` FIRST, then delegate
 * with the minted grant; the `AccessDenied` → HTTP-status mapping is the FACADE's concern, keeping this service
 * transport-free.
 *
 * Two structural guarantees:
 *  - **Non-escalation:** [roleFor] reads the role ONLY from the DB/token row — a Human's `subject_role`, an
 *    Agent's token `mode` — NEVER a header/claim/frontmatter. The [permits] matrix is hardcoded Kotlin (no
 *    data-driven escalation: `subject_role` stores only WHICH role, never what a role may do).
 *  - **Compile-time floor:** the EDIT/CREATE/MANAGE checks MINT an unforgeable typed grant on success; the ~4
 *    mutators require it. A bypassed check is a compile error. PolicyService is the ONLY production mint site.
 *
 * Audit is PRE-EFFECT and MUTATING-only: `checkEdit`/`checkCreate`/`checkManage` write ONE `audit_log` row
 * (allowed AND denied) BEFORE returning the grant / throwing; [checkRead] does NOT audit (per-request read
 * volume). The filesystem effect and the audit row cannot be one txn, so the guaranteed row is the DECISION.
 *
 * Mode-aware ([enforced]): under `auth.mode = off` (loopback-dev — the phase-4 plan's "open behavior") the
 * matrix is NOT consulted — every principal is authorized (a grant is minted, reads pass) AND a mutating
 * decision is still audited as `allowed`. Under `builtin`/`proxy` the role×action [permits] matrix decides. This
 * is a CONFIG decision, not a claim — non-escalation holds (an OFF deployment is the operator's explicit choice,
 * never a header/frontmatter input).
 */
class PolicyService(
    private val roles: RoleRepository,
    private val apiTokens: ApiTokenRepository,
    private val audit: AuditRepository,
    private val idProvider: IdProvider,
    private val clock: Clock,
    private val enforced: Boolean,
) {

    /** READ gate: throws [AccessDenied] on deny (no grant type for reads per the owner decision). Not audited. */
    fun checkRead(principal: Principal, resource: String) {
        if (!allows(principal, Action.READ)) throw AccessDenied(Action.READ, resource, principal)
    }

    /** EDIT gate: mints + returns an [EditGrant] on success; records the decision row + throws on deny. */
    fun checkEdit(principal: Principal, resource: String): EditGrant =
        gate(principal, Action.EDIT, resource) { EditGrant() }

    /** CREATE gate: mints + returns a [CreateGrant] on success; records the decision row + throws on deny. */
    fun checkCreate(principal: Principal, resource: String): CreateGrant =
        gate(principal, Action.CREATE, resource) { CreateGrant() }

    /** MANAGE gate (rescan/reindex): mints + returns a [ManageGrant]; records the decision row + throws on deny. */
    fun checkManage(principal: Principal): ManageGrant =
        gate(principal, Action.MANAGE, MANAGE_RESOURCE) { ManageGrant() }

    /** The shared mutating gate: record the pre-effect decision row, then mint the grant or throw [AccessDenied]. */
    private inline fun <G> gate(principal: Principal, action: Action, resource: String, mint: () -> G): G {
        val allowed = allows(principal, action)
        audit.record(decisionRow(principal, action, resource, allowed))
        if (!allowed) throw AccessDenied(action, resource, principal)
        return mint()
    }

    /** OFF (loopback-dev) opens everything; enforced modes consult the role×action matrix. */
    private fun allows(principal: Principal, action: Action): Boolean =
        !enforced || permits(roleFor(principal), action)

    /**
     * The role of [principal] from the DB/token row ONLY (the non-escalation guarantee), or null (→ default deny):
     *  - [Principal.Human] → its `subject_role` row.
     *  - [Principal.Agent] → its token `mode` mapped onto the role axis (READ_ONLY → VIEWER; PROPOSE/COMMIT →
     *    EDITOR — A3 grants both the EDIT/CREATE capability; the propose-vs-direct-commit ENFORCEMENT is Phase 5).
     *    A revoked/unknown token already resolved to [Principal.Anonymous] upstream (the A2 seam) → deny here too.
     *  - [Principal.Anonymous] → null → deny.
     */
    private fun roleFor(principal: Principal): Role? = when (principal) {
        is Principal.Human -> roles.roleOf(principal.issuer, principal.externalId)
        is Principal.Agent -> apiTokens.modeOf(principal.tokenId)?.toRole()
        Principal.Anonymous -> null
    }

    private fun decisionRow(principal: Principal, action: Action, resource: String, allowed: Boolean): AuditEntry {
        val (kind, issuer, externalId) = when (principal) {
            is Principal.Human -> Triple("human", principal.issuer, principal.externalId)
            is Principal.Agent -> Triple("agent", AGENT_ISSUER, principal.tokenId)
            Principal.Anonymous -> Triple("anonymous", null, null)
        }
        return AuditEntry(
            id = idProvider.next().value,
            ts = clock.now(),
            principalKind = kind,
            issuer = issuer,
            externalId = externalId,
            action = action.name,
            resource = resource,
            decision = if (allowed) "allowed" else "denied",
        )
    }

    private companion object {
        const val MANAGE_RESOURCE = "admin"
        const val AGENT_ISSUER = "agent"

        /** VIEWER: READ. EDITOR: READ + EDIT + CREATE. ADMIN: all. Anonymous / no-row: deny everything. */
        fun permits(role: Role?, action: Action): Boolean = when (role) {
            null -> false
            Role.VIEWER -> action == Action.READ
            Role.EDITOR -> action == Action.READ || action == Action.EDIT || action == Action.CREATE
            Role.ADMIN -> true
        }

        fun AgentMode.toRole(): Role = when (this) {
            AgentMode.READ_ONLY -> Role.VIEWER
            AgentMode.PROPOSE, AgentMode.COMMIT -> Role.EDITOR
        }
    }
}

/** The authZ verbs. READ is gated by the ReadFacade; EDIT/CREATE/MANAGE require a typed grant. */
enum class Action { READ, EDIT, CREATE, MANAGE }

/**
 * A denied authorization decision (A3) — thrown by [PolicyService] AFTER the denied audit row is written. The
 * guarded FACADE catches it and maps it to 401 ([Principal.Anonymous] — no credential) / 403 (an
 * authenticated-but-unauthorized principal), keeping [PolicyService] transport-free. Throwing (vs a nullable
 * grant) means a caller cannot accidentally ignore a deny and still get a grant — there is no grant on this path.
 */
class AccessDenied(val action: Action, val resource: String, val principal: Principal) :
    RuntimeException("access denied: $action on '$resource' for ${principal::class.simpleName}")
