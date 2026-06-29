package com.plainbase.frameworks.ktor

import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.service.ApplyOutcome
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.CreateOutcome
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.MutatingFacade
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
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
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
) : ProposalFacade {

    override fun propose(principal: Principal, command: ProposeCommand): ProposeOutcome =
        // Check FIRST (mint the grant), THEN resolve the author — so a DENIED propose never does the labeler's
        // token/user lookups before the deny is audited+thrown (the choke-point ordering, mirroring `reject`).
        when (command) {
            is ProposeCommand.Edit -> {
                val grant = policy.checkEdit(principal, ProposalCommandResource.PROPOSE)
                proposals.proposeEdit(
                    grant = grant,
                    pageId = command.pageId,
                    baseHash = command.baseHash,
                    clientTargetPath = command.clientTargetPath,
                    proposedContent = command.proposedContent,
                    rationale = command.rationale,
                    author = labeler.resolve(principal),
                )
            }
            is ProposeCommand.Create -> proposeCreate(principal, command)
        }

    /**
     * SD-1 — patch-the-blob: the server owns identity. `checkCreate` FIRST (READ_ONLY/revoked deny HERE, before any
     * mint/patch). The EXPLICIT-propose path supplies NO id, so the server mints one and splices ONLY the `id:` line
     * into the agent's whole-doc blob via the surgical [FrontmatterPatcher] (the agent's title/slug stay untouched —
     * the patcher inserts only the id; a no-frontmatter blob gets a fresh block prepended). The DEGRADE path already
     * minted + baked the id at the create route (`command.pageId` set) — store both verbatim, no re-mint, no re-patch.
     */
    private fun proposeCreate(principal: Principal, command: ProposeCommand.Create): ProposeOutcome {
        val grant = policy.checkCreate(principal, ProposalCommandResource.PROPOSE)
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
            targetPath = command.targetPath,
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
                    ),
                ).toWriteOutcome()
                ProposalOperation.CREATE -> mutate.create(
                    principal,
                    CreateIntent(
                        pageId = requireNotNull(row.pageId) { "a CREATE proposal must carry a page_id (minted at propose time)" },
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
        return proposals.rebase(grant, id)
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
