package com.plainbase.frameworks.ktor

import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.ApplyOutcome
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
            is ProposeCommand.Create -> {
                val grant = policy.checkCreate(principal, ProposalCommandResource.PROPOSE)
                proposals.proposeCreate(
                    grant = grant,
                    targetPath = command.targetPath,
                    proposedContent = command.proposedContent,
                    rationale = command.rationale,
                    author = labeler.resolve(principal),
                )
            }
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
            // EDIT-only: a CREATE row is short-circuited in ProposalService.apply BEFORE this lambda runs, so
            // row.pageId/baseHash are non-null here (the edit invariant).
            mutate.save(
                principal,
                SaveRequest(
                    pageId = requireNotNull(row.pageId) { "an EDIT proposal must carry a page_id" },
                    baseHash = requireNotNull(row.baseHash) { "an EDIT proposal must carry a base_hash" },
                    bytes = row.proposedContent,
                    author = author,
                    committer = committer,
                ),
            ).toWriteOutcome()
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
}
