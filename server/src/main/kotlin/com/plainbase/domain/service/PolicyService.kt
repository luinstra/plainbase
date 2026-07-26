package com.plainbase.domain.service

import com.plainbase.domain.principal.ApproveGrant
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
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
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
 * **Rooted resource strings (multi-root C4).** The WRITE gates take a [RootedResource] and audit through its
 * ONE formatting rule (`{root}:{resource}`, or the bare resource when no registered root owns the target).
 * `checkRead`/`checkApprove`/`checkManage` keep their plain-`String` resource: a rooted CALL SITE formats it
 * via `RootedResource.audit` (URL-space and path resources gain the root prefix), while an ID-addressed
 * resource stays the bare id. That is NOT because an id is unique across roots - since ADR-0012 it may name a
 * page in several roots at once - but because read policy is global and does not audit; the rooted context
 * that a decision has to be recorded against arrives through the WRITE gates.
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
    /**
     * Whether a root permits page-mutation writes (ADR-0011 D6) - the registry's `editable` flag, wired
     * as a narrow function so this domain service never holds the registry. Fails CLOSED on an unknown
     * name (defense in depth behind the wire-level `invalid_root` check). Defaulted `true` = pre-C4
     * semantics, so a single-root construction stays terse and inert.
     */
    private val editableOf: (RootName) -> Boolean = { true },
) {

    /** READ gate: throws [AccessDenied] on deny (no grant type for reads per the owner decision). Not audited. */
    fun checkRead(principal: Principal, resource: String) {
        if (!allows(principal, Action.READ)) throw AccessDenied(Action.READ, resource, principal)
    }

    /**
     * EDIT gate: mints + returns an [EditGrant] on success; records the decision row + throws on deny.
     * Serves BOTH EDIT-action write classes - [WriteClass.PageEdit] and [WriteClass.AssetWrite] - and the
     * caller passes the one it means, because a per-root policy discriminates on the CLASS, not the action.
     */
    fun checkEdit(principal: Principal, writeClass: WriteClass, resource: RootedResource): EditGrant =
        gate(principal, Action.EDIT, writeClass, resource) { EditGrant() }

    /** CREATE gate: mints + returns a [CreateGrant] on success; records the decision row + throws on deny. */
    fun checkCreate(principal: Principal, writeClass: WriteClass, resource: RootedResource): CreateGrant =
        gate(principal, Action.CREATE, writeClass, resource) { CreateGrant() }

    /** MANAGE gate (rescan/reindex): mints + returns a [ManageGrant]; records the decision row + throws on deny. */
    fun checkManage(principal: Principal): ManageGrant =
        gate(principal, Action.MANAGE, writeClass = null, resource = RootedResource(null, MANAGE_RESOURCE)) { ManageGrant() }

    /**
     * APPROVE gate (the proposal status transition, P1a): mints + returns an [ApproveGrant]; records the decision
     * row + throws on deny. ADMIN-only via the [permits] matrix (`Role.ADMIN -> true` already covers it; VIEWER/
     * EDITOR exclude it, so an agent — PROPOSE/COMMIT -> EDITOR — can never approve its own proposal, D1).
     */
    fun checkApprove(principal: Principal, resource: String): ApproveGrant =
        gate(principal, Action.APPROVE, writeClass = null, resource = RootedResource(null, resource)) { ApproveGrant() }

    /**
     * Read-only, NON-auditing: the live [AgentMode] for a [Principal.Agent] (null for non-agents OR a revoked/expired
     * token at [clock]`.now()`), so [com.plainbase.frameworks.ktor.GuardedMutatingFacade] can decide direct-vs-degrade
     * (P5) WITHOUT an audit side effect — the audit fires later via `checkEdit` on the chosen branch. Reuses the SAME
     * live revoked/expired re-check [roleFor] rides; does NOT touch [audit].
     */
    fun agentModeFor(principal: Principal): AgentMode? =
        (principal as? Principal.Agent)?.let { apiTokens.modeOf(it.tokenId, clock.now()) }

    /**
     * Record a DENIED [action] decision row for [principal]/[resource], then throw [AccessDenied] — the mint-free deny
     * for a FACADE-level gate that refuses a request WITHOUT consulting the role×action matrix. (P5 used it for the
     * agent-create glob gate, refusing an out-of-glob create because create-apply did not yet exist; C1 added
     * create-apply, so that path now DEGRADES to a create-proposal that applies on approval — leaving this the general
     * facade-level deny primitive.) Audit stays in this ONE choke point: the denial records exactly one denied row,
     * like a matrix deny, and never mints a grant the caller would then refuse.
     */
    fun deny(principal: Principal, action: Action, resource: String, reason: DenyReason = DenyReason.POLICY): Nothing =
        denied(principal, action, resource, reason)

    /**
     * Whether [root] permits page-mutation writes - the [gate]'s EDITABLE arm as a read-only PREDICATE, minting
     * nothing and auditing nothing.
     *
     * It exists so a caller can ORDER the editable answer ahead of an availability throw without paying for a grant
     * it is not ready to use yet. `editable = false` is TOPOLOGY and is knowable with the disk face-down; "the root
     * is not serving" is a RETRYABLE condition. A surface that checks availability first would answer a permanently
     * read-only root with "try again once it is back", which is a promise no retry can keep.
     */
    fun editable(root: RootName): Boolean = editableOf(root)

    /**
     * The shared mutating gate: record the ONE pre-effect decision row, then mint the grant or throw
     * [AccessDenied]. Exactly one `audit_log` row per call, allowed or denied - the invariant the agent
     * decide-first path and the proposal degrade both depend on.
     *
     * The three arms evaluate in THIS order, and the order is the decision (ADR-0011 D6):
     *  1. **AUTHN/ROLE** (enforced modes only): an Anonymous caller, or one with no subject-role/token row,
     *     denies with [DenyReason.POLICY] exactly as it does today. Authn precedes topology, so a root's
     *     `editable` bit can never leak to an unauthenticated prober and anonymous semantics do not change.
     *  2. **EDITABLE** (EVERY mode, `off` included): `editable = false` is TOPOLOGY, not authorization -
     *     gating it behind [enforced] would leave the flag unexercised in the loopback-dev default and in
     *     CI, which runs auth-off. A NULL root SKIPS this arm (no registered root owns the target, so there
     *     is no topology to consult - and every unrooted arm 404s before it can write).
     *  3. **MATRIX** (enforced modes only): the role×action grid, [DenyReason.POLICY].
     *
     * So: enforced anonymous × non-editable root = 401 (unchanged); off-mode anyone × non-editable root =
     * 403 `root_not_editable`; enforced VIEWER × non-editable root = 403 `root_not_editable` (authenticated,
     * so the bit may show).
     */
    private inline fun <G> gate(
        principal: Principal,
        action: Action,
        writeClass: WriteClass?,
        resource: RootedResource,
        mint: () -> G,
    ): G {
        val role = roleFor(principal)
        if (enforced && role == null) denied(principal, action, resource.audit, DenyReason.POLICY)
        val root = resource.root
        if (writeClass != null && writeClass.gatedByEditable && root != null && !editableOf(root)) {
            denied(principal, action, resource.audit, DenyReason.ROOT_NOT_EDITABLE)
        }
        if (enforced && !permits(role, action)) denied(principal, action, resource.audit, DenyReason.POLICY)
        audit.record(decisionRow(principal, action, resource.audit, allowed = true))
        return mint()
    }

    /** Record the DENIED decision row, then throw - the ONE deny path, so every deny audits exactly once. */
    private fun denied(principal: Principal, action: Action, resource: String, reason: DenyReason): Nothing {
        audit.record(decisionRow(principal, action, resource, allowed = false))
        throw AccessDenied(action, resource, principal, reason)
    }

    /** OFF (loopback-dev) opens everything; enforced modes consult the role×action matrix. */
    private fun allows(principal: Principal, action: Action): Boolean =
        !enforced || permits(roleFor(principal), action)

    /**
     * The role of [principal] from the DB/token row ONLY (the non-escalation guarantee), or null (→ default deny):
     *  - [Principal.Human] → its `subject_role` row.
     *  - [Principal.Agent] → its token `mode` mapped onto the role axis (READ_ONLY → VIEWER; PROPOSE/COMMIT →
     *    EDITOR — A3 grants both the EDIT/CREATE capability; the propose-vs-direct-commit ENFORCEMENT is LIVE in
     *    [com.plainbase.frameworks.ktor.GuardedMutatingFacade] via the `agentDirectCommit.globs` gate, Phase 5).
     *    `modeOf` re-checks the active predicate (not revoked, not expired) at [clock]`.now()` on EVERY call: a REST
     *    request re-auths its bearer per call (A2 seam), but a LIVE MCP SSE session authenticates once at connect and
     *    reuses the captured Agent — so a token revoked/expired mid-session resolves to null mode → denied next call.
     *  - [Principal.Anonymous] → null → deny.
     */
    private fun roleFor(principal: Principal): Role? = when (principal) {
        is Principal.Human -> roles.roleOf(principal.issuer, principal.externalId)
        is Principal.Agent -> apiTokens.modeOf(principal.tokenId, clock.now())?.toRole()
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

        /**
         * VIEWER: READ. EDITOR: READ + EDIT + CREATE. ADMIN: all (incl. MANAGE + APPROVE — the proposal
         * status transition rides `Role.ADMIN -> true`, D1, no new arm). Anonymous / no-row: deny everything.
         */
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

/** The authZ verbs. READ is gated by the ReadFacade; EDIT/CREATE/MANAGE/APPROVE require a typed grant. */
enum class Action { READ, EDIT, CREATE, MANAGE, APPROVE }

/**
 * A denied authorization decision (A3) — thrown by [PolicyService] AFTER the denied audit row is written. The
 * guarded FACADE catches it and maps it to 401 ([Principal.Anonymous] — no credential) / 403 (an
 * authenticated-but-unauthorized principal), keeping [PolicyService] transport-free. Throwing (vs a nullable
 * grant) means a caller cannot accidentally ignore a deny and still get a grant — there is no grant on this path.
 *
 * [reason] distinguishes the role×action matrix deny ([DenyReason.POLICY], today's 401/403) from the
 * per-root topology deny ([DenyReason.ROOT_NOT_EDITABLE], a 403 with its own code). It defaults to POLICY,
 * so every pre-C4 throw site is unchanged.
 */
class AccessDenied(
    val action: Action,
    val resource: String,
    val principal: Principal,
    val reason: DenyReason = DenyReason.POLICY,
) : RuntimeException("access denied: $action on '$resource' for ${principal::class.simpleName} ($reason)")

/** WHY an [AccessDenied] fired: the role×action matrix, or the target root's `editable = false` topology. */
enum class DenyReason { POLICY, ROOT_NOT_EDITABLE }

/**
 * A rooted operation whose root is NOT SERVING (ADR-0011 D5): its disk vanished, its watcher died, it was
 * already gone at boot, or its name is DETACHED from `roots {}`. Mapped in ONE place - the `guarded {}`
 * funnel and the two MCP catch funnels - to 503 `root_unavailable` + `Retry-After`.
 *
 * **A `RootUnavailable` may only become a RESPONSE on a path whose gate has already passed.** On any path
 * that cannot produce a response - a boot reconcile, a background rebuild - it must be CONTAINED, and the
 * containment must be named where the throw is. That is what keeps anonymous behavior byte-identical to
 * today (shell/401/redirect) and stops availability leaking to an unauthenticated prober: authn precedes
 * topology on every surface.
 *
 * [reason] is the TYPED health vocabulary, never a free-text string; the wire token is derived at the
 * mapping sites so the 503 envelope and the `/healthz` payload speak the same words. (It is `reason`, not
 * `cause`: `Throwable.cause` is taken, and it means something else entirely.)
 */
class RootUnavailable(val root: RootName, val reason: UnavailableCause) :
    RuntimeException("root '${root.value}' is not serving ($reason)")
