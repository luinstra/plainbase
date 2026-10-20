package com.plainbase.domain.service

import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.principal.ApproveGrant
import com.plainbase.domain.principal.CreateGrant
import com.plainbase.domain.principal.DiscussionGrant
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.principal.ManageGrant
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditEntry
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging
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
 *  - **Compile-time floor:** mutating checks mint typed grants required by their writers. PolicyService is the
 *    only production mint site.
 *
 * Outside READER, audit is PRE-EFFECT and MUTATING-only: mutating checks write ONE `audit_log` row
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
 * Under [PolicyMode.OPEN], humans and Anonymous receive grants without consulting the matrix; mutating
 * decisions are still audited. Discussion checks require a live agent token and its discussion capability.
 * Under [PolicyMode.ENFORCED], the role×action [permits] matrix decides. The mode is a deployment decision,
 * never a header or frontmatter claim.
 *
 * [PolicyMode.READER] admits READ for every principal without role or live-token lookups. Mutations are refused
 * with one DEBUG line and no audit row, so an unauthenticated caller cannot grow `audit_log`. Discussion preview
 * retains its topology refusal for roots with discussions disabled or an object backend.
 */
class PolicyService(
    private val roles: RoleRepository,
    private val apiTokens: ApiTokenRepository,
    private val audit: AuditRepository,
    private val idProvider: IdProvider,
    private val clock: Clock,
    private val mode: PolicyMode,
    /**
     * Whether a root permits page-mutation writes (ADR-0011 D6) - the registry's `editable` flag, wired
     * as a narrow function so this domain service never holds the registry. Fails CLOSED on an unknown
     * name (defense in depth behind the wire-level `invalid_root` check). Defaulted `true` = pre-C4
     * semantics, so a single-root construction stays terse and inert.
     */
    private val editableOf: (RootName) -> Boolean = { true },
    private val objectBackendOf: (RootName) -> Boolean = { false },
    /** Required so every composition explicitly wires the registry flag; a default could silently bypass configuration. */
    private val discussionsEnabledOf: (RootName) -> Boolean,
) {

    /** READ gate: throws [AccessDenied] on deny (no grant type for reads per the owner decision). Not audited. */
    fun checkRead(principal: Principal, resource: String) {
        if (!allows(principal, Action.READ)) throw AccessDenied(Action.READ, resource, principal)
    }

    /**
     * EDIT gate: mints an [EditGrant] on success; workspace calls audit the decision, including denials.
     * Serves BOTH EDIT-action write classes - [WriteClass.PageEdit] and [WriteClass.AssetWrite] - and the
     * caller passes the one it means, because a per-root policy discriminates on the CLASS, not the action.
     */
    fun checkEdit(principal: Principal, writeClass: WriteClass, resource: RootedResource): EditGrant =
        gate(principal, Action.EDIT, writeClass, resource) { EditGrant() }

    /** CREATE gate: mints a [CreateGrant] on success; workspace calls audit the decision, including denials. */
    fun checkCreate(principal: Principal, writeClass: WriteClass, resource: RootedResource): CreateGrant =
        gate(principal, Action.CREATE, writeClass, resource) { CreateGrant() }

    /** MANAGE gate (rescan/reindex): mints a [ManageGrant]; workspace calls audit the decision, including denials. */
    fun checkManage(principal: Principal): ManageGrant =
        gate(principal, Action.MANAGE, writeClass = null, resource = RootedResource(null, MANAGE_RESOURCE)) { ManageGrant() }

    /**
     * APPROVE gate: mints an [ApproveGrant]; workspace calls audit the decision, including denials.
     * ADMIN-only via the [permits] matrix (`Role.ADMIN -> true` already covers it; VIEWER/
     * EDITOR exclude it, so an agent — PROPOSE/COMMIT -> EDITOR — can never approve its own proposal, D1).
     */
    fun checkApprove(principal: Principal, resource: String): ApproveGrant =
        gate(principal, Action.APPROVE, writeClass = null, resource = RootedResource(null, resource)) { ApproveGrant() }

    fun checkDiscussion(
        principal: Principal,
        operation: DiscussionAction,
        facts: DiscussionFacts,
        resource: RootedResource,
    ): DiscussionGrant {
        if (mode == PolicyMode.READER) readerDenied(principal, operation.auditAction, resource.audit)
        val action = operation.auditAction
        val writeClass = if (operation == DiscussionAction.PURGE) WriteClass.DiscussionPurge else WriteClass.Discussion
        val agentMode = (principal as? Principal.Agent)?.let { apiTokens.modeOf(it.tokenId, clock.now()) }
        val role = if (principal is Principal.Agent) agentMode?.toRole() else roleFor(principal)
        if ((mode == PolicyMode.ENFORCED && role == null) || (principal is Principal.Agent && agentMode == null)) {
            denied(principal, action, resource.audit, DenyReason.POLICY)
        }
        resource.root?.let { discussionTopology(it, writeClass) }?.let { denied(principal, action, resource.audit, it) }
        if (principal is Principal.Agent && !agentCanDiscuss(agentMode, operation)) {
            denied(principal, action, resource.audit, DenyReason.POLICY)
        }
        if (mode == PolicyMode.ENFORCED && !permits(role, action)) denied(principal, action, resource.audit, DenyReason.POLICY)
        val ownership = ownershipFor(operation, role, mode == PolicyMode.OPEN && principal == Principal.Anonymous)
        val reliedOn = discussionReliedOn(principal, action, resource.audit, ownership, facts)
        audit.record(decisionRow(principal, action, resource.audit, allowed = true))
        return DiscussionGrant(resource.root, operation, reliedOn, ownership != DiscussionOwnership.NONE)
    }

    private fun discussionTopology(root: RootName, writeClass: WriteClass): DenyReason? = when {
        writeClass.gatedByEditable && !editableOf(root) -> DenyReason.ROOT_NOT_EDITABLE
        objectBackendOf(root) -> DenyReason.DISCUSSIONS_UNSUPPORTED
        !discussionsEnabledOf(root) -> DenyReason.DISCUSSIONS_DISABLED
        else -> null
    }

    private fun agentCanDiscuss(mode: AgentMode?, operation: DiscussionAction): Boolean =
        mode != null && mode != AgentMode.READ_ONLY && operation != DiscussionAction.REATTACH && operation != DiscussionAction.PURGE

    private fun ownershipFor(operation: DiscussionAction, role: Role?, bypass: Boolean): DiscussionOwnership = when {
        bypass -> DiscussionOwnership.NONE
        operation == DiscussionAction.EDIT || operation == DiscussionAction.RETRACT -> DiscussionOwnership.AUTHOR
        role == Role.VIEWER && operation in STARTER_ACTIONS -> DiscussionOwnership.STARTER
        else -> DiscussionOwnership.NONE
    }

    private fun discussionReliedOn(
        principal: Principal,
        action: Action,
        resource: String,
        ownership: DiscussionOwnership,
        facts: DiscussionFacts,
    ): ReliedOn {
        if (facts !is DiscussionFacts.Known || facts.state != "ok" || ownership == DiscussionOwnership.NONE) return ReliedOn()
        val subject = SubjectKey.of(principal)
        val expected = IdentityDigest.of(subject)
        return when (ownership) {
            DiscussionOwnership.NONE -> ReliedOn()
            DiscussionOwnership.AUTHOR -> facts.authorKey?.let {
                if (it != expected) denied(principal, action, resource, DenyReason.POLICY)
                ReliedOn(author = subject)
            } ?: ReliedOn()
            DiscussionOwnership.STARTER -> facts.starterKey?.let {
                if (it != expected) denied(principal, action, resource, DenyReason.POLICY)
                ReliedOn(starter = subject)
            } ?: ReliedOn()
        }
    }

    fun checkDiscussionRead(principal: Principal, resource: RootedResource, preview: Boolean = false): DenyReason? {
        if (mode != PolicyMode.READER && principal is Principal.Agent && agentModeFor(principal) == null) {
            throw AccessDenied(Action.READ, resource.audit, principal)
        }
        checkRead(principal, resource.audit)
        val root = resource.root ?: return null
        val reason = discussionTopology(root, WriteClass.Discussion)
        if (preview && reason != null) throw AccessDenied(Action.READ, resource.audit, principal, reason)
        return reason
    }

    /**
     * Read-only, NON-auditing: the live [AgentMode] for a [Principal.Agent] (null for non-agents OR a revoked/expired
     * token at [clock]`.now()`), so [com.plainbase.frameworks.ktor.GuardedMutatingFacade] can decide direct-vs-degrade
     * (P5) WITHOUT an audit side effect — the audit fires later via `checkEdit` on the chosen branch. Reuses the SAME
     * live revoked/expired re-check [roleFor] rides; does NOT touch [audit].
     * READER returns no mode without consulting the token store; the chosen write branch still reaches its gate.
     */
    fun agentModeFor(principal: Principal): AgentMode? =
        if (mode == PolicyMode.READER) null else (principal as? Principal.Agent)?.let { apiTokens.modeOf(it.tokenId, clock.now()) }

    /**
     * Refuse [action] for [principal]/[resource] without minting a grant. Workspace modes record one denied audit row;
     * READER emits one DEBUG line and leaves the audit store untouched. Throws [AccessDenied].
     * Facade-level refusals use this path without consulting the role×action matrix, keeping decision effects
     * in the same choke point as gate refusals.
     */
    fun deny(principal: Principal, action: Action, resource: String, reason: DenyReason = DenyReason.POLICY): Nothing =
        if (mode == PolicyMode.READER) readerDenied(principal, action, resource) else denied(principal, action, resource, reason)

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
     * The shared mutating gate: READER refuses before any role or topology lookup and never audits.
     * Workspace modes record one pre-effect decision row, then mint the grant or throw [AccessDenied].
     * Exactly one `audit_log` row per workspace call, allowed or denied, preserves the agent decision contract.
     *
     * The three arms evaluate in THIS order, and the order is the decision (ADR-0011 D6):
     *  1. **AUTHN/ROLE** ([PolicyMode.ENFORCED]): an Anonymous caller, or one with no subject-role/token row,
     *     denies with [DenyReason.POLICY] exactly as it does today. Authn precedes topology, so a root's
     *     `editable` bit can never leak to an unauthenticated prober and anonymous semantics do not change.
     *  2. **EDITABLE** (both workspace modes): `editable = false` is TOPOLOGY, not authorization -
     *     limiting it to [PolicyMode.ENFORCED] would leave the flag unexercised in the loopback-dev default and in
     *     CI, which runs auth-off. A NULL root SKIPS this arm (no registered root owns the target, so there
     *     is no topology to consult - and every unrooted arm 404s before it can write).
     *  3. **MATRIX** ([PolicyMode.ENFORCED]): the role×action grid, [DenyReason.POLICY].
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
        if (mode == PolicyMode.READER) readerDenied(principal, action, resource.audit)
        val role = roleFor(principal)
        if (mode == PolicyMode.ENFORCED && role == null) denied(principal, action, resource.audit, DenyReason.POLICY)
        val root = resource.root
        if (writeClass != null && writeClass.gatedByEditable && root != null && !editableOf(root)) {
            denied(principal, action, resource.audit, DenyReason.ROOT_NOT_EDITABLE)
        }
        if (mode == PolicyMode.ENFORCED && !permits(role, action)) denied(principal, action, resource.audit, DenyReason.POLICY)
        audit.record(decisionRow(principal, action, resource.audit, allowed = true))
        return mint()
    }

    /** Workspace denial path: record one denied decision row, then throw. */
    private fun denied(principal: Principal, action: Action, resource: String, reason: DenyReason): Nothing {
        audit.record(decisionRow(principal, action, resource, allowed = false))
        throw AccessDenied(action, resource, principal, reason)
    }

    /** DEBUG diagnostics use validated root:path or id resources and fixed labels, never user free text. */
    private fun readerDenied(principal: Principal, action: Action, resource: String): Nothing {
        logger.debug { "reader policy denied $action on '$resource' for ${principal::class.simpleName}" }
        throw AccessDenied(action, resource, principal, DenyReason.POLICY)
    }

    /** OPEN bypasses the matrix, ENFORCED consults it, and READER permits only READ. */
    private fun allows(principal: Principal, action: Action): Boolean = when (mode) {
        PolicyMode.OPEN -> true
        PolicyMode.ENFORCED -> permits(roleFor(principal), action)
        PolicyMode.READER -> action == Action.READ
    }

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
        val logger = KotlinLogging.logger {}
        const val MANAGE_RESOURCE = "admin"
        const val AGENT_ISSUER = "agent"
        val STARTER_ACTIONS = setOf(DiscussionAction.RESOLVE, DiscussionAction.REOPEN, DiscussionAction.REATTACH)

        /**
         * VIEWER: READ + DISCUSS. EDITOR: those plus EDIT + CREATE. ADMIN: all, including PURGE, MANAGE and APPROVE.
         * Anonymous / no-row: deny under PolicyMode.ENFORCED.
         */
        fun permits(role: Role?, action: Action): Boolean = when (role) {
            null -> false
            Role.VIEWER -> action == Action.READ || action == Action.DISCUSS
            Role.EDITOR -> action == Action.READ || action == Action.EDIT || action == Action.CREATE || action == Action.DISCUSS
            Role.ADMIN -> true
        }

        fun AgentMode.toRole(): Role = when (this) {
            AgentMode.READ_ONLY -> Role.VIEWER
            AgentMode.PROPOSE, AgentMode.COMMIT -> Role.EDITOR
        }
    }
}

private enum class DiscussionOwnership { NONE, AUTHOR, STARTER }

/** Authorization verbs; mutating checks mint typed grants, including DISCUSS/PURGE for discussions. */
enum class Action { READ, EDIT, CREATE, MANAGE, APPROVE, DISCUSS, PURGE }

enum class DiscussionAction(val auditAction: Action) {
    START(Action.DISCUSS),
    COMMENT(Action.DISCUSS),
    EDIT(Action.DISCUSS),
    RETRACT(Action.DISCUSS),
    RESOLVE(Action.DISCUSS),
    REOPEN(Action.DISCUSS),
    REATTACH(Action.DISCUSS),
    PURGE(Action.PURGE),
}

/**
 * A denied authorization decision. Workspace mutations record a denied audit row before throwing;
 * reader denials leave the audit store untouched. The guarded facade maps it to 401 (Anonymous) or 403 (an
 * authenticated-but-unauthorized principal), keeping [PolicyService] transport-free. Throwing (vs a nullable
 * grant) means a caller cannot accidentally ignore a deny and still get a grant — there is no grant on this path.
 *
 * [reason] distinguishes the role×action matrix deny ([DenyReason.POLICY], today's 401/403) from the
 * per-root topology denies (read-only, unsupported backend, or configured-disabled discussions, all 403).
 * It defaults to POLICY,
 * so every pre-C4 throw site is unchanged.
 */
class AccessDenied(
    val action: Action,
    val resource: String,
    val principal: Principal,
    val reason: DenyReason = DenyReason.POLICY,
) : RuntimeException("access denied: $action on '$resource' for ${principal::class.simpleName} ($reason)")

/** Why an [AccessDenied] fired: policy, non-editable root, unsupported backend, or configured-disabled discussions. */
enum class DenyReason { POLICY, ROOT_NOT_EDITABLE, DISCUSSIONS_UNSUPPORTED, DISCUSSIONS_DISABLED }

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
