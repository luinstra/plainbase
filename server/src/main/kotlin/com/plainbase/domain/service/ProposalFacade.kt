package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal

/**
 * The guarded PROPOSE/decision surface (P1a, the choke point — the [ReadFacade] shape). Every method takes a
 * [Principal] and calls the matching `PolicyService.check*` FIRST, mints the typed grant, and passes it into the
 * grant-demanded [ProposalService] method (the demanded-value floor). The impl
 * ([com.plainbase.frameworks.ktor.GuardedProposalFacade]) lives frameworks-side ([AccessDenied] -> HTTP is its
 * concern). NO content-tree write — `propose`/`reject` store proposal rows; the live disk READS for base-hash/drift
 * go through [ProposalBaseReader].
 *
 *  - [propose] is gated by `checkEdit` (for an edit) / `checkCreate` (for a create) — an agent in PROPOSE/COMMIT
 *    (-> EDITOR) is permitted, READ_ONLY denied; a HUMAN EDITOR/ADMIN permitted; in `auth.mode=off`, Anonymous too.
 *  - [reject] is gated by `checkApprove` (ADMIN-only).
 *  - [list]/[get] are gated by `checkRead` (VIEWER+).
 */
interface ProposalFacade {

    /** Authorize + route the [command] to `proposeEdit`/`proposeCreate`. The author snapshot is resolved from [principal]. */
    fun propose(principal: Principal, command: ProposeCommand): ProposeOutcome

    /** Authorize (checkApprove, ADMIN-only) + reject the PENDING proposal [id]. */
    fun reject(principal: Principal, id: ProposalId, comment: String?): RejectOutcome

    /**
     * Authorize (checkApprove, ADMIN-only) + APPLY the PENDING proposal [id] (P1b): claim PENDING->APPLYING, drive
     * the guarded EDIT content write, and stamp the terminal status. A CREATE proposal is short-circuited to terminal
     * FAILED ([ApplyOutcome.CreateUnsupported]) — create-apply is deferred to 5.5.
     */
    fun approve(principal: Principal, id: ProposalId): ApplyOutcome

    /** Authorize (checkApprove, ADMIN-only) + REBASE the CONFLICTED proposal [id] (P1b): re-pin base + recompute diff. */
    fun rebase(principal: Principal, id: ProposalId): RebaseOutcome

    /** Authorize (checkRead) + list every proposal (summary + live `base_drifted`), newest-first. */
    fun list(principal: Principal): List<ProposalSummaryView>

    /** Authorize (checkRead) + the full proposal view for [id], or null. */
    fun get(principal: Principal, id: ProposalId): ProposalView?
}

/**
 * The PINNED audit `resource` strings for the proposal gates (codex directive — deterministic audit rows + deny
 * messages, not executor-improvised). `propose` audits `"proposal"`; the per-id gates name the id so a denied
 * decision is traceable to the targeted proposal.
 */
object ProposalCommandResource {
    const val PROPOSE: String = "proposal"
    const val LIST: String = "proposal:list"

    fun detail(id: com.plainbase.domain.page.ProposalId): String = "proposal:${id.value}"

    fun approve(id: com.plainbase.domain.page.ProposalId): String = "proposal:${id.value}:approve"

    // P1b: the approve-that-APPLIES path uses a DISTINCT resource from `approve` (which reject's checkApprove uses)
    // so the audit row disambiguates an apply's APPROVE from a reject's APPROVE — both ride checkApprove.
    fun apply(id: com.plainbase.domain.page.ProposalId): String = "proposal:${id.value}:apply"

    fun rebase(id: com.plainbase.domain.page.ProposalId): String = "proposal:${id.value}:rebase"
}

/**
 * A VALIDATED propose command (the route did the shape/parse validation — the F4 malformed-shape matrix rows — and
 * built one of these). The semantic checks the FACADE/service still own (an edit's `target_path` mismatch, base-hash
 * staleness, target-missing) ride here.
 */
sealed interface ProposeCommand {

    /** An edit proposal: [pageId] is authoritative; [clientTargetPath] is the optional client path the service checks against `pathOf`. */
    data class Edit(
        val pageId: PageId,
        val baseHash: String,
        val clientTargetPath: TreePath?,
        val proposedContent: ByteArray,
        val rationale: String,
    ) : ProposeCommand

    /** A create proposal: [targetPath] is authoritative (no page exists yet). */
    data class Create(
        val targetPath: TreePath,
        val proposedContent: ByteArray,
        val rationale: String,
    ) : ProposeCommand
}
