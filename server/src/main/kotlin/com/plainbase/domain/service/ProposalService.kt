package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.ApproveGrant
import com.plainbase.domain.principal.CreateGrant
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.ProposalSummaryRow
import kotlin.time.Clock

/**
 * The P1a proposal lifecycle orchestration (pure-domain, over the [ProposalRepository] port + the injected
 * [CitationFactory] + [unifiedDiff] + the LIVE [ProposalBaseReader] read seam + a [Clock] for deterministic
 * golden timestamps). APPLY-NOTHING: it writes ONLY PENDING (propose) + REJECTED (reject) rows and performs NO
 * content-tree / Git / `WritePipeline` mutation. It DOES perform live disk READS via [ProposalBaseReader]
 * (base-hash validation + drift) — reads are expected and required; the boundary is "no content-tree write."
 *
 * The decision methods DEMAND their op-matching grant as a required leading parameter (the `WritePipeline.write(
 * grant)` floor, G1a): a proposal cannot be created without an [EditGrant]/[CreateGrant] and cannot be rejected
 * without an [ApproveGrant], so a grant-free path does not compile. The grant is an unused compile-time witness
 * that the guarded facade ran the matching `PolicyService.check*`; the repository (a trusted persistence detail)
 * takes no grant.
 */
class ProposalService(
    private val repository: ProposalRepository,
    private val citations: CitationFactory,
    private val baseReader: ProposalBaseReader,
    private val proposalIdProvider: ProposalIdProvider,
    private val clock: Clock,
) {

    /**
     * Propose an EDIT to an existing page. [pageId] is AUTHORITATIVE — the stored [ProposalRow.targetPath] is the
     * path RESOLVED from it (a disagreeing client path is rejected upstream at the route, C3). [baseHash] must EQUAL
     * the target's live current hash (else `StaleBase`) and the target must still be published (else `StaleBase`,
     * target-missing). The stored `diff_artifact` is `live-base -> proposed` (so base == current). Nothing is
     * persisted on a `StaleBase`.
     */
    fun proposeEdit(
        @Suppress("UNUSED_PARAMETER") grant: EditGrant,
        pageId: PageId,
        baseHash: String,
        clientTargetPath: TreePath?,
        proposedContent: ByteArray,
        rationale: String,
        author: ProposalAuthor,
    ): ProposeOutcome {
        val targetPath = baseReader.pathOf(pageId) ?: return ProposeOutcome.StaleBase
        // C3: page_id is authoritative; a client target_path that disagrees with the resolved path is malformed.
        if (clientTargetPath != null && clientTargetPath != targetPath) return ProposeOutcome.InvalidRequest
        val currentBytes = baseReader.currentBytes(targetPath) ?: return ProposeOutcome.StaleBase
        if (citations.contentHash(currentBytes) != baseHash) return ProposeOutcome.StaleBase

        val row = newPending(
            operation = ProposalOperation.EDIT,
            pageId = pageId,
            baseHash = baseHash,
            targetPath = targetPath,
            proposedContent = proposedContent,
            rationale = rationale,
            diffArtifact = unifiedDiff(currentBytes, proposedContent),
            author = author,
        )
        repository.insert(row)
        return ProposeOutcome.Created(row.id, row.diffArtifact)
    }

    /**
     * Propose a CREATE of a new page at [targetPath] (authoritative — no page exists yet, so `page_id`/`base_hash`
     * are null). The diff is computed over an empty base. Nothing here rejects on collision — that is a LIVE
     * `base_drifted` triage flag the read path derives, and the real gate is P1b's apply.
     */
    fun proposeCreate(
        @Suppress("UNUSED_PARAMETER") grant: CreateGrant,
        targetPath: TreePath,
        proposedContent: ByteArray,
        rationale: String,
        author: ProposalAuthor,
    ): ProposeOutcome {
        val row = newPending(
            operation = ProposalOperation.CREATE,
            pageId = null,
            baseHash = null,
            targetPath = targetPath,
            proposedContent = proposedContent,
            rationale = rationale,
            diffArtifact = unifiedDiff(ByteArray(0), proposedContent),
            author = author,
        )
        repository.insert(row)
        return ProposeOutcome.Created(row.id, row.diffArtifact)
    }

    /**
     * Reject a PENDING proposal (terminal; NO content-tree write). The conditional `UPDATE … WHERE status='PENDING'`
     * is the single point of truth: a true result is `Rejected`; on false a post-CAS re-read CLASSIFIES the miss
     * (`NotFound` if the row is gone, else `NotPending`) — no TOCTOU between a pre-check and the UPDATE.
     */
    fun reject(
        @Suppress("UNUSED_PARAMETER") grant: ApproveGrant,
        id: ProposalId,
        approver: ProposalApprover,
        comment: String?,
    ): RejectOutcome {
        val updated = repository.reject(id, approver.issuer, approver.externalId, comment, clock.now())
        if (updated) {
            return RejectOutcome.Rejected(requireNotNull(get(id)) { "rejected proposal $id vanished" })
        }
        return if (repository.findById(id) == null) RejectOutcome.NotFound else RejectOutcome.NotPending
    }

    /** Every proposal as a summary view, newest-first, each carrying its LIVE-derived `base_drifted` flag. */
    fun list(): List<ProposalSummaryView> = repository.all().map {
        ProposalSummaryView(it, baseDrifted(it.operation, it.pageId, it.targetPath, it.baseHash))
    }

    /** The full proposal view for [id] (incl. the stable `unified_diff`) with its LIVE `base_drifted`, or null. */
    fun get(id: ProposalId): ProposalView? {
        val row = repository.findById(id) ?: return null
        return ProposalView(row, baseDrifted(row.operation, row.pageId, row.targetPath, row.baseHash))
    }

    private fun newPending(
        operation: ProposalOperation,
        pageId: PageId?,
        baseHash: String?,
        targetPath: TreePath,
        proposedContent: ByteArray,
        rationale: String,
        diffArtifact: String,
        author: ProposalAuthor,
    ): ProposalRow = ProposalRow(
        id = proposalIdProvider.next(),
        operation = operation,
        pageId = pageId,
        baseHash = baseHash,
        targetPath = targetPath,
        proposedContent = proposedContent,
        rationale = rationale,
        diffArtifact = diffArtifact,
        status = ProposalStatus.PENDING,
        authorIssuer = author.issuer,
        authorExternalId = author.externalId,
        authorLabel = author.label,
        approverIssuer = null,
        approverExternalId = null,
        decisionComment = null,
        createdAt = clock.now(),
        decidedAt = null,
        appliedCommit = null,
    )

    /**
     * The LIVE drift flag (§0.13(ii)), derived per-row at read time, NEVER stored (NON-AUTHORITATIVE triage — §B4):
     *  - EDIT: the live current hash differs from the stored `base_hash` — OR the target was deleted since propose
     *    (currentBytes null IS drift; do NOT diff against empty here);
     *  - CREATE: a content file now occupies `target_path` (the file-path collision, `byPath` ∪ `assets`).
     */
    private fun baseDrifted(operation: ProposalOperation, pageId: PageId?, targetPath: TreePath, baseHash: String?): Boolean =
        when (operation) {
            ProposalOperation.EDIT -> {
                val current = pageId?.let(baseReader::pathOf)?.let(baseReader::currentBytes)
                current == null || citations.contentHash(current) != baseHash
            }
            ProposalOperation.CREATE -> baseReader.occupied(targetPath)
        }
}

/** The snapshotted proposer attribution (issuer/external_id/display label), resolved at propose time (C4). */
data class ProposalAuthor(val issuer: String, val externalId: String, val label: String)

/** The deciding principal's snapshot for a reject/approve (issuer/external_id). */
data class ProposalApprover(val issuer: String, val externalId: String)

/** The outcome of a propose. Created carries the minted id + the stored stable diff; StaleBase/InvalidRequest persisted nothing. */
sealed interface ProposeOutcome {
    data class Created(val id: ProposalId, val unifiedDiff: String) : ProposeOutcome

    /** An edit's claimed `base_hash` no longer matches the live content, or the target page was deleted (400 stale_base). */
    data object StaleBase : ProposeOutcome

    /** A semantic malformed request the service detected (C3 — a client `target_path` disagreeing with the resolved path) (400 invalid_propose_request). */
    data object InvalidRequest : ProposeOutcome
}

/** The outcome of a reject (the §WI-5 E2 wire contract maps these). */
sealed interface RejectOutcome {
    data class Rejected(val view: ProposalView) : RejectOutcome

    data object NotPending : RejectOutcome

    data object NotFound : RejectOutcome
}

/** A full proposal row plus its LIVE-derived (never stored) `base_drifted` flag — the `get_change` / reject body. */
class ProposalView(val row: ProposalRow, val baseDrifted: Boolean)

/** A proposal summary plus its LIVE-derived `base_drifted` flag — a `list_changes` element. */
class ProposalSummaryView(val row: ProposalSummaryRow, val baseDrifted: Boolean)
