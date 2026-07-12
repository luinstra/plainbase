package com.plainbase.frameworks.ktor

import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.Action
import com.plainbase.domain.service.ApplyOutcome
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.CreateOutcome
import com.plainbase.domain.service.DenyReason
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalApprover
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalCommandResource
import com.plainbase.domain.service.ProposalContentWriter
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.ProposalSummaryView
import com.plainbase.domain.service.ProposalView
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.RebaseOutcome
import com.plainbase.domain.service.RejectOutcome
import com.plainbase.domain.service.RootStatus
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.RootedResource
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.domain.service.WriteClass
import com.plainbase.domain.service.WriteOrigin

/**
 * The frameworks-side [ProposalFacade] impl (P1a, the A3 choke point — the [GuardedReadFacade] shape): it holds the
 * [PolicyService] + [ProposalService] + the C4 [ProposalAuthorLabeler] as PRIVATE deps, calls the matching
 * `check*` FIRST, and passes the minted grant INTO the grant-demanded [ProposalService] method (the demanded-value
 * floor). It lets [com.plainbase.domain.service.AccessDenied] propagate (the route maps it to 401/403).
 *
 *  - `propose` routes the operation: `Edit` -> `checkEdit` -> `proposeEdit(editGrant, …)`; `Create` -> `checkCreate`
 *    -> `proposeCreate(createGrant, …)`. The author snapshot is resolved from the `Principal` only AFTER the matching
 *    `check*` mints its grant — a denied propose does no labeler lookup before the deny is audited+thrown.
 *  - `reject` -> `checkApprove` -> `proposalService.reject(approveGrant, …)` (the status transition only).
 *  - `list`/`get` -> `checkRead`.
 */
class GuardedProposalFacade(
    private val policy: PolicyService,
    private val proposals: ProposalService,
    private val labeler: ProposalAuthorLabeler,
    private val mutate: MutatingFacade,
    // C1 (SD-1): the explicit-propose create path mints the page id server-side, then PATCHES it into the agent's
    // whole-doc blob via the surgical FrontmatterPatcher (the server owns identity; the agent owns the body/title/slug).
    private val idProvider: IdProvider,
    // The propose gates are ROOTED (ADR-0011 D6), so this facade needs a snapshot source of its own - it cannot obey
    // the one-snapshot rule without one - plus the ONE resolver and the availability holder.
    private val indexBuilder: IndexBuilder,
    private val resolver: PageRootResolver,
    private val availability: RootAvailability,
) : ProposalFacade {

    override fun propose(principal: Principal, command: ProposeCommand): ProposeOutcome =
        // Check FIRST (mint the grant), THEN resolve the author — so a DENIED propose never does the labeler's
        // token/user lookups before the deny is audited+thrown (the choke-point ordering, mirroring `reject`).
        when (command) {
            is ProposeCommand.Edit -> proposeEdit(principal, command)
            is ProposeCommand.Create -> proposeCreate(principal, command)
        }

    /**
     * The rooted EDIT-propose (the [GuardedMutatingFacade] write ordering, applied to a propose): ONE snapshot, the
     * root resolved from it, the gate on that root, then the availability throw - and the GATED target THREADED into
     * the service rather than re-resolved there.
     *
     * Threading is the point. `ProposalService` used to call `baseReader.pathOf` itself, which is a FRESH read of the
     * published snapshot: a rebuild landing between the gate and the service call could re-award the id to another
     * root, and the row would be persisted against a root the gate never authorized - possibly a non-editable or an
     * unavailable one. Both classes are `gatedByEditable`, so a proposal against a read-only root now denies AT
     * PROPOSE TIME with `root_not_editable` rather than surviving to surprise a reviewer at apply.
     */
    private fun proposeEdit(principal: Principal, command: ProposeCommand.Edit): ProposeOutcome {
        val snapshot = indexBuilder.current
        val root = resolver.rootOf(snapshot, command.pageId)
        val grant = policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(root, ProposalCommandResource.PROPOSE))
        // No registered root owns the id (unknown, or bound only under a detached root) -> StaleBase FROM HERE: the
        // service is never called, so it can never re-resolve a target the gate did not see.
        if (root == null) return ProposeOutcome.StaleBase
        requireAvailable(root)
        val target = snapshot.byId[command.pageId]?.let { RootedPath(it.root, it.path) } ?: return ProposeOutcome.StaleBase
        return proposals.proposeEdit(
            grant = grant,
            pageId = command.pageId,
            target = target,
            baseHash = command.baseHash,
            clientTargetPath = command.clientTargetPath,
            proposedContent = command.proposedContent,
            rationale = command.rationale,
            author = labeler.resolve(principal),
        )
    }

    /**
     * SD-1 — patch-the-blob: the server owns identity. `checkCreate` FIRST (READ_ONLY/revoked deny HERE, before any
     * mint/patch). The EXPLICIT-propose path supplies NO id, so the server mints one and splices ONLY the `id:` line
     * into the agent's whole-doc blob via the surgical [FrontmatterPatcher] (the agent's title/slug stay untouched —
     * the patcher inserts only the id; a no-frontmatter blob gets a fresh block prepended). The DEGRADE path already
     * minted + baked the id at the create route (`command.pageId` set) — store both verbatim, no re-mint, no re-patch.
     */
    private fun proposeCreate(principal: Principal, command: ProposeCommand.Create): ProposeOutcome {
        // A create's root is DECLARED, and the shared parser already validated it against the registry (400
        // `invalid_root`), so the gate always sees a real root - no unrooted arm.
        val grant = policy.checkCreate(principal, WriteClass.PageCreate, RootedResource(command.root, ProposalCommandResource.PROPOSE))
        requireAvailable(command.root)
        val (pageId, bakedBytes) = when (val pre = command.pageId) {
            null -> {
                val minted = idProvider.next()
                when (val patched = PATCHER.patch(command.proposedContent, minted)) {
                    is FrontmatterPatcher.PatchResult.Patched -> minted to patched.bytes
                    // The agent supplied its OWN column-0 `id:` — reject; the server is the sole identity authority.
                    FrontmatterPatcher.PatchResult.AlreadyPresent ->
                        return ProposeOutcome.InvalidCreateContent(
                            "a create proposal must not supply its own frontmatter id; the server mints it",
                        )
                    // Malformed / non-mapping / oversized / invalid-encoding frontmatter — the patcher's stable rule string.
                    is FrontmatterPatcher.PatchResult.Refused -> return ProposeOutcome.InvalidCreateContent(patched.message)
                }
            }
            else -> pre to command.proposedContent
        }
        return proposals.proposeCreate(
            grant = grant,
            pageId = pageId,
            target = RootedPath(command.root, command.targetPath),
            proposedContent = bakedBytes,
            rationale = command.rationale,
            author = labeler.resolve(principal),
        )
    }

    override fun reject(principal: Principal, id: ProposalId, comment: String?): RejectOutcome {
        val grant = policy.checkApprove(principal, ProposalCommandResource.approve(id))
        val approver = labeler.resolve(principal).let { ProposalApprover(it.issuer, it.externalId, it.label) }
        return proposals.reject(grant, id, approver, comment)
    }

    override fun approve(principal: Principal, id: ProposalId): ApplyOutcome {
        // checkApprove (ADMIN-only, audited as `proposal:{id}:apply`) FIRST; THEN drive the content write through the
        // guarded MUTATING path so `checkEdit` mints the real EditGrant + audits the EDIT row. ApproveGrant never
        // reaches WritePipeline — grant-composition option (a).
        val grant = policy.checkApprove(principal, ProposalCommandResource.apply(id))
        // The PRE-CLAIM guard (ADR-0011 D15). It cannot wait for the in-service recovery: by the time that runs, the
        // PENDING -> APPLYING claim has ALREADY rewritten durable state, so a single approve of a proposal whose root
        // is not serving would permanently convert a decidable row into an undecidable one (every later
        // approve/reject/rebase CASes on PENDING/CONFLICTED).
        //
        // STATUS first, availability second - and only for the rows that will actually TOUCH the root. Answering an
        // already-decided proposal with a 503 says "try again once the disk is back", which is a lie: no retry will
        // ever apply it, the honest answer is the 409 the contract documents, and reporting a row as decided reads
        // nothing off its root. This is NOT the gate order for anything that ACTS (that stays authn -> editable ->
        // availability, below), and it introduces no status TOCTOU: the PENDING CAS in the service remains the single
        // point of truth, so a row that turns terminal after this read is still caught there.
        val row = proposals.guardOf(id) ?: return ApplyOutcome.NotFound
        if (row.status != ProposalStatus.PENDING) return ApplyOutcome.NotPending
        requireEditable(principal, row.root, ProposalCommandResource.apply(id))
        requireAvailable(row.root)
        val approverAuthor = labeler.resolve(principal)
        val writer = ProposalContentWriter { row, author, committer ->
            // C1: the writer branches on the row's operation. BOTH paths carry already-approved, already-reviewed
            // content and pass WriteOrigin.PROPOSAL_APPLY so the guarded mutating facade bypasses the agent direct-
            // commit/degrade decision ENTIRELY, even when `principal` is a Principal.Agent (an off-mode agent can drive
            // approve — finding #11). For a CREATE the bypass is load-bearing: an approved OUT-OF-GLOB create MUST land.
            when (row.operation) {
                ProposalOperation.EDIT -> mutate.save(
                    principal,
                    SaveRequest(
                        pageId = requireNotNull(row.pageId) { "an EDIT proposal must carry a page_id" },
                        baseHash = requireNotNull(row.baseHash) { "an EDIT proposal must carry a base_hash" },
                        bytes = row.proposedContent,
                        author = author,
                        committer = committer,
                        origin = WriteOrigin.PROPOSAL_APPLY,
                        // An EDIT-proposal's stored root is AUTHORITATIVE too - the same rule the CREATE arm below has
                        // always followed. The id still picks the PATH (an in-root move applies); the root is pinned, so
                        // an id re-awarded across roots (D17) answers `page_deleted` -> CONFLICTED instead of landing an
                        // approved edit in a repository the admin never saw.
                        expectedRoot = row.root,
                    ),
                ).toWriteOutcome()
                ProposalOperation.CREATE -> mutate.create(
                    principal,
                    CreateIntent(
                        pageId = requireNotNull(row.pageId) { "a CREATE proposal must carry a page_id (minted at propose time)" },
                        // A create-proposal's stored root is AUTHORITATIVE: there is no page to re-resolve one from.
                        root = row.root,
                        path = row.targetPath,
                        bytes = row.proposedContent,
                        author = author,
                        committer = committer,
                    ),
                    origin = WriteOrigin.PROPOSAL_APPLY,
                ).toWriteOutcome()
            }
        }
        return proposals.apply(
            grant,
            id,
            ProposalApprover(approverAuthor.issuer, approverAuthor.externalId, approverAuthor.label),
            writer,
        )
    }

    override fun rebase(principal: Principal, id: ProposalId): RebaseOutcome {
        val grant = policy.checkApprove(principal, ProposalCommandResource.rebase(id))
        // The same pre-guard, in the same order and for the same two reasons. A rebase whose target it cannot read
        // stamps the row TERMINALLY FAILED (`rebase_target_gone`), which is a durable rewrite off a null a missing
        // root produced - and one that FORECLOSES the recovery that restoring the root would give. Leave it
        // CONFLICTED; answer 503. But a row that is NOT conflicted has no rebase to perform at all, so it answers
        // the documented 409 without the root having to be up.
        val row = proposals.guardOf(id) ?: return RebaseOutcome.NotFound
        if (row.status != ProposalStatus.CONFLICTED) return RebaseOutcome.NotConflicted
        requireAvailable(row.root)
        return proposals.rebase(grant, id)
    }

    /**
     * The EDITABLE gate on the row's STORED root, and it runs BEFORE [requireAvailable] because a TERMINAL condition
     * must never masquerade as a RETRYABLE one. A pending proposal whose root has since been made read-only can never
     * be applied by any retry, ever; if that root is ALSO down, checking availability first answers 503
     * `root_unavailable` - "try again when the disk is back" - to an admin whose proposal will be refused just as hard
     * when it IS back. `editable = false` is topology and is knowable with the root offline, so ask it first and give
     * the honest, final 403 `root_not_editable`.
     *
     * It also has to run BEFORE the claim. `mutate.save`/`mutate.create` mint the real [EditGrant] and would deny
     * there anyway (the write classes are `gatedByEditable`) - but by then `proposals.apply` has already CASed
     * PENDING -> APPLYING, so the deny would strand a decidable row in a state nothing can decide. This is the same
     * pre-claim reasoning [requireAvailable] documents, applied to the gate that ranks above it. Nothing is minted
     * here: it is the predicate, so the ONE audited EDIT decision still belongs to the write that follows.
     *
     * (Propose-time already denies this - [proposeEdit] - so reaching it means the root turned read-only AFTER the
     * proposal was raised. That is a config change plus a restart, i.e. exactly the case a reviewer walks into.)
     */
    private fun requireEditable(principal: Principal, root: RootName, resource: String) {
        if (!policy.editable(root)) policy.deny(principal, Action.EDIT, resource, DenyReason.ROOT_NOT_EDITABLE)
    }

    /**
     * The availability gate. For `approve`/`rebase` it runs on the root the write will ACTUALLY land in - which, for
     * BOTH operations, is the row's STORED root ([com.plainbase.domain.service.ProposalService.writeRootOf]), so the
     * guard and the write ([com.plainbase.domain.service.SaveRequest.expectedRoot]) can never disagree about which
     * root a 503 is about.
     *
     * It runs AFTER `checkApprove` (which stays unrooted - a proposal id is not a rooted resource), after the
     * already-decided rows have been answered 409 (they touch no root, so they need none), and BEFORE any durable
     * rewrite. A DETACHED root and an UNAVAILABLE one behave identically here, on purpose: the row stays
     * exactly as it is, is never applied, never rewritten, never deleted, and re-adding the root's name to `roots {}`
     * revives it.
     */
    private fun requireAvailable(root: RootName) {
        val snapshot = availability.current()
        when (resolver.statusOf(root, snapshot)) {
            RootStatus.AVAILABLE -> Unit
            RootStatus.DETACHED -> throw RootUnavailable(root, UnavailableCause.DETACHED)
            RootStatus.UNAVAILABLE -> throw RootUnavailable(root, snapshot.unavailable.getValue(root).cause)
        }
    }

    override fun list(principal: Principal): List<ProposalSummaryView> {
        policy.checkRead(principal, ProposalCommandResource.LIST)
        return proposals.list()
    }

    override fun get(principal: Principal, id: ProposalId): ProposalView? {
        policy.checkRead(principal, ProposalCommandResource.detail(id))
        return proposals.get(id)
    }

    private companion object {
        /** The single surgical frontmatter patcher (the `GuardedMutatingFacade` idiom) — splices ONLY the `id:` line. */
        val PATCHER = FrontmatterPatcher()
    }
}

/**
 * The ONE owner of the [SaveResult] -> [WriteOutcome] bridge for the apply path (P1b). The two facade-resolved
 * pre-pipeline outcomes map to apply-meaningful `WriteOutcome`s the FROZEN `dispositionOf` table understands:
 *  - [SaveResult.PageNotFound] -> a `Conflict(reason="page_deleted")` (the target vanished — CONFLICTED, rebasable);
 *  - [SaveResult.IdMismatch] -> `UnsupportedEdit(field="id")` (a rename — terminal FAILED).
 */
private fun SaveResult.toWriteOutcome(): WriteOutcome = when (this) {
    is SaveResult.Written -> outcome
    SaveResult.PageNotFound -> WriteOutcome.Conflict(reason = "page_deleted", currentContent = null, currentHash = null, currentPath = null)
    SaveResult.IdMismatch -> WriteOutcome.UnsupportedEdit(field = "id")
    // P5: a degrade is DIRECT_PUT-only. The apply path passes WriteOrigin.PROPOSAL_APPLY, so GuardedMutatingFacade.save
    // NEVER enters the agent direct-commit/degrade decision here — these arms are unreachable BY THE ORIGIN
    // DISCRIMINATOR (NOT by the approver's principal type: an off-mode agent CAN drive approve — finding #11). Like the
    // create-only `toWire` arms, but earned by an explicit discriminator rather than by construction over principals.
    is SaveResult.DegradedToProposal ->
        error("a degrade is DIRECT_PUT-only; the apply path passes WriteOrigin.PROPOSAL_APPLY and never enters the agent decision")
    SaveResult.DegradeStaleBase ->
        error("a degrade is DIRECT_PUT-only; the apply path passes WriteOrigin.PROPOSAL_APPLY and never enters the agent decision")
}

/**
 * The [CreateOutcome] -> [WriteOutcome] bridge for the create-apply path (C1, the [SaveResult.toWriteOutcome] sibling).
 * The apply path passes [WriteOrigin.PROPOSAL_APPLY], so `create()` NEVER enters the agent direct-commit/degrade
 * decision — the degrade arms are unreachable BY THE ORIGIN DISCRIMINATOR (not by the approver's principal type: an
 * off-mode agent CAN drive approve — finding #11).
 */
private fun CreateOutcome.toWriteOutcome(): WriteOutcome = when (this) {
    is CreateOutcome.DirectCreated -> outcome
    is CreateOutcome.DegradedToProposal ->
        error("a create degrade is DIRECT_PUT-only; the apply path passes WriteOrigin.PROPOSAL_APPLY")
    is CreateOutcome.InvalidContent ->
        error("a create degrade is DIRECT_PUT-only; the apply path passes WriteOrigin.PROPOSAL_APPLY")
}
