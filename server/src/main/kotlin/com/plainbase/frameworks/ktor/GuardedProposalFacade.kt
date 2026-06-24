package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalApprover
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalCommandResource
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.ProposalSummaryView
import com.plainbase.domain.service.ProposalView
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.RejectOutcome

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
        val approver = labeler.resolve(principal).let { ProposalApprover(it.issuer, it.externalId) }
        return proposals.reject(grant, id, approver, comment)
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
