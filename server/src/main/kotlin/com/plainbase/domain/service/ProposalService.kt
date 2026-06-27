package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.model.WriteOutcome
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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

/**
 * The proposal lifecycle orchestration (pure-domain, over the [ProposalRepository] port + the injected
 * [CitationFactory] + [unifiedDiff] + the LIVE [ProposalBaseReader] read seam + a [Clock] for deterministic
 * golden timestamps). P1a writes PENDING (propose) + REJECTED (reject) rows; P1b ADDS the mutating apply surface —
 * the EDIT-apply ([apply]) drives the content-tree/Git write through the injected [ProposalContentWriter] under the
 * claim->write->stamp order, plus the one-step [rebase] of a CONFLICTED row and the boot [reconcileApplying] crash
 * recovery. CREATE-apply is still deferred (5.5). Live disk READS via [ProposalBaseReader] (base-hash validation +
 * drift + recovery) are expected throughout.
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

    /**
     * Apply a PENDING proposal (P1b, EDIT-APPLY ONLY). The load-bearing order is **claim(DB) -> write(disk+git) ->
     * stamp-terminal(DB)**, the terminal stamp a conditional `WHERE status='APPLYING'` CAS so a crash-recovery
     * reconcile racing a live apply cannot double-stamp.
     *
     * CREATE-apply is DEFERRED to 5.5: a CREATE proposal short-circuits to terminal FAILED via a DIRECT
     * PENDING->FAILED CAS ([ProposalRepository.failPending]) that NEVER calls [ProposalRepository.claimApplying], so
     * a CREATE row can never enter APPLYING — which is what makes the recovery invariant "every APPLYING row has a
     * non-null page_id" hold BY CONSTRUCTION. The [writer] (the guarded EDIT content write the facade binds) is
     * NEVER invoked for a CREATE.
     *
     * The [grant] is the demanded witness that `checkApprove` ran; [approver] carries the deciding ADMIN's
     * (issuer, externalId, label); [writer] routes the content write through `GuardedMutatingFacade.save` (so
     * `checkEdit` mints the real EditGrant + audits the EDIT row + resolves the page's CURRENT pageId path).
     */
    fun apply(
        @Suppress("UNUSED_PARAMETER") grant: ApproveGrant,
        id: ProposalId,
        approver: ProposalApprover,
        writer: ProposalContentWriter,
    ): ApplyOutcome {
        // (0) CREATE short-circuit — DIRECT PENDING->FAILED, NEVER APPLYING (create-apply deferred to 5.5).
        val initial = repository.findById(id) ?: return ApplyOutcome.NotFound
        if (initial.operation == ProposalOperation.CREATE) {
            return if (repository.failPending(id, "create_apply_unsupported", approver.issuer, approver.externalId, clock.now())) {
                ApplyOutcome.CreateUnsupported
            } else if (repository.findById(id) == null) {
                ApplyOutcome.NotFound
            } else {
                ApplyOutcome.NotPending
            }
        }

        // (1) claim: PENDING -> APPLYING CAS (the EDIT path).
        if (!repository.claimApplying(id)) {
            return if (repository.findById(id) == null) ApplyOutcome.NotFound else ApplyOutcome.NotPending
        }

        // (2) now APPLYING; an EDIT row, so pageId is non-null by the edit invariant (proposeEdit requires pathOf).
        val row = requireNotNull(repository.findById(id)) { "claimed proposal $id vanished" }
        val proposer = CommitIdentity(row.authorLabel, syntheticEmail(row.authorIssuer, row.authorExternalId))
        val committer = CommitIdentity(approver.label, syntheticEmail(approver.issuer, approver.externalId))

        // (3-5) write + map + stamp. The post-claim work is wrapped so a THROWN exception (an abnormal path — distinct
        // from a normal WriteOutcome) cannot wedge the row in APPLYING: every later approve/reject/rebase CASes on
        // PENDING/CONFLICTED, so a stuck APPLYING row is undecidable until the next boot reconcile. On a throw we run
        // the SAME single-row inspect-then-decide recovery the boot reconciler uses ([recoverApplyingRow]) — the bytes
        // either landed (-> APPLIED "recovered") or did not (-> back to PENDING, decidable again) — log, then RETHROW
        // so the route still surfaces the 500 (we are not swallowing the failure, only un-wedging the row).
        val disposition = try {
            // (3) write — routes through the guarded mutating path (checkEdit -> WritePipeline) at the CURRENT pageId path.
            val outcome = writer.write(row, proposer, committer)
            if (outcome is WriteOutcome.Unreadable) {
                // The raw cause is diagnostic and MUST NOT reach the wire/status_reason — log it server-side only.
                logger.error { "apply $id: the content write was Unreadable (cause logged, never surfaced): ${outcome.cause}" }
            }

            // (4) map the outcome via the FROZEN pure table.
            val disposition = dispositionOf(outcome, proposedHash = citations.contentHash(row.proposedContent))

            // (5) stamp-terminal — a conditional WHERE status='APPLYING' CAS. We CLAIMED this row APPLYING above and the
            // engine is single-writer, so the stamp MUST affect exactly one row: a false here (affected-rows != 1) is a
            // real invariant breach (the row left APPLYING out from under us), not a normal race — fail LOUD, never
            // silently report a terminal the DB did not record. (The reconcile/rebase-loser paths where a false IS a
            // legitimate lost race keep returning their not-pending/not-conflicted outcome — they do NOT call this.)
            val stamped = when (disposition) {
                is ApplyDisposition.Applied ->
                    repository.markApplied(
                        id = id,
                        appliedCommit = disposition.commit,
                        statusReason = if (disposition.reindexDeferred) "reindex_deferred" else null,
                        approverIssuer = approver.issuer,
                        approverExternalId = approver.externalId,
                        at = clock.now(),
                    )
                is ApplyDisposition.Conflicted ->
                    repository.markConflicted(id, disposition.reason, approver.issuer, approver.externalId, clock.now())
                is ApplyDisposition.Failed ->
                    repository.markFailed(id, disposition.reason, approver.issuer, approver.externalId, clock.now())
            }
            if (!stamped) {
                logger.error {
                    "apply $id: the terminal CAS affected 0 rows — the row left APPLYING under the single-writer (invariant breach)"
                }
                error("apply $id: terminal stamp CAS affected != 1 row (the APPLYING claim was lost — broken single-writer invariant)")
            }
            disposition
        } catch (e: Throwable) {
            logger.error(e) {
                "apply $id: the post-claim write/stamp threw — running single-row recovery so the row is not wedged in APPLYING"
            }
            recoverApplyingRow(row)
            throw e
        }

        // (6) re-read for the wire body (reflects the winning stamp) + return the typed outcome.
        val view = requireNotNull(get(id)) { "applied proposal $id vanished" }
        return when (disposition) {
            is ApplyDisposition.Applied -> ApplyOutcome.Applied(view, disposition.newHash, disposition.commit, disposition.reindexDeferred)
            is ApplyDisposition.Conflicted -> ApplyOutcome.Conflicted(view, disposition.currentHash, disposition.currentPath)
            is ApplyDisposition.Failed -> ApplyOutcome.Failed(view, disposition.reason)
        }
    }

    /**
     * One-step rebase (P1b, edits only): re-pin `base_hash` to the CURRENT disk hash + recompute `diff_artifact`
     * (base = current disk bytes — showing the clobbered intervening edit, proposed = the UNCHANGED stored bytes)
     * + flip CONFLICTED->PENDING via the idempotent CAS for a fresh human re-approve. A CONFLICTED row is ALWAYS an
     * EDIT (creates never apply -> never CONFLICTED), so `pageId` is non-null and there is no create branch. A rebase
     * whose target page was DELETED ([RebaseOutcome.Gone]) is TERMINAL: the row is stamped FAILED +
     * `status_reason="rebase_target_gone"` so it is not a dangling CONFLICTED.
     */
    fun rebase(@Suppress("UNUSED_PARAMETER") grant: ApproveGrant, id: ProposalId): RebaseOutcome {
        val row = repository.findById(id) ?: return RebaseOutcome.NotFound
        if (row.status != ProposalStatus.CONFLICTED) return RebaseOutcome.NotConflicted
        // A CONFLICTED row is ALWAYS an EDIT (creates never enter APPLYING -> never CONFLICTED), so pageId is non-null
        // by construction; assert the invariant rather than silently treat an impossible null as Gone.
        val pageId = requireNotNull(row.pageId) { "CONFLICTED proposal $id must be an edit with a non-null page_id" }
        val path = baseReader.pathOf(pageId)
        val currentBytes = path?.let(baseReader::currentBytes)
        if (path == null || currentBytes == null) {
            // The target page is gone → stamp terminal FAILED via the CONFLICTED->FAILED CAS. HONOR the CAS result the
            // SAME way the success path honors a lost `rebaseToPending` CAS: a false means a concurrent rebase/terminal
            // transition won this row after our initial CONFLICTED read, so the row already left CONFLICTED — report
            // NotConflicted (the idempotent already-transitioned miss), NEVER an unconditional Gone we did not record.
            return if (repository.failConflicted(id, "rebase_target_gone", clock.now())) {
                RebaseOutcome.Gone
            } else {
                RebaseOutcome.NotConflicted
            }
        }
        val newBaseHash = citations.contentHash(currentBytes)
        val newDiff = unifiedDiff(currentBytes, row.proposedContent)
        // Re-pin target_path to the CURRENT path too (the page may have MOVED since propose): otherwise a rebased
        // PENDING row would show a stale propose-time path against a fresh base_hash/diff — mixed staleness (the same
        // class as the stale decision metadata cleared below).
        return if (repository.rebaseToPending(id, newBaseHash, newDiff, path)) {
            RebaseOutcome.Rebased(requireNotNull(get(id)) { "rebased proposal $id vanished" })
        } else {
            RebaseOutcome.NotConflicted
        }
    }

    /**
     * The inspect-then-decide crash-recovery reconciler (P1b), run at startup AFTER the disk + index are ready
     * (replaces P1a's BLIND [ProposalRepository.reconcileApplyingToPending] use for the APPLYING-row case). Every
     * APPLYING row is an EDIT with a non-null `page_id` (creates never enter APPLYING — the [failPending] invariant),
     * so the current path resolves through `pageId` (NOT the stale `target_path`): if the disk bytes at that current
     * path equal `hash(proposed_content)`, the apply's disk write SUCCEEDED before the terminal stamp ran -> stamp
     * APPLIED (with a NULL approver + `status_reason="recovered"`, since the approver is unknown post-crash);
     * otherwise the write did NOT land -> return to PENDING for a fresh approve. Cannot race a live apply (the engine
     * is not serving yet), the [reconcileDirtyPages] guarantee.
     */
    fun reconcileApplying() {
        val applying = repository.allApplying()
        if (applying.isEmpty()) return
        logger.info { "reconciling ${applying.size} APPLYING proposal(s) from a prior interrupted apply" }
        for (row in applying) recoverApplyingRow(row)
    }

    /**
     * The single-row inspect-then-decide recovery shared by the boot [reconcileApplying] AND the live post-claim catch
     * in [apply] (ONE codepath, two callers). Every APPLYING row is an EDIT with a non-null `page_id` (creates never
     * enter APPLYING — the [failPending] invariant), so the current path resolves through `pageId` (NOT the stale
     * `target_path`): if the disk bytes at that current path equal `hash(proposed_content)`, the apply's disk write
     * SUCCEEDED before the terminal stamp ran -> stamp APPLIED (NULL approver + `status_reason="recovered"`, since the
     * approver is unknown post-crash and uniform with the boot path); otherwise the write did NOT land -> return to
     * PENDING for a fresh approve. The terminal CAS conditions on `status='APPLYING'`, so a row that already left
     * APPLYING (a racing winner) is a no-op.
     */
    private fun recoverApplyingRow(row: ProposalRow) {
        // Every APPLYING row is an EDIT with a non-null page_id (creates never enter APPLYING — the failPending
        // invariant); assert it rather than silently absorb an impossible null into a PENDING reset.
        val pageId = requireNotNull(row.pageId) { "APPLYING proposal ${row.id} must be an edit with a non-null page_id" }
        val path = baseReader.pathOf(pageId)
        val diskBytes = path?.let(baseReader::currentBytes)
        if (diskBytes != null && citations.contentHash(diskBytes) == citations.contentHash(row.proposedContent)) {
            repository.markApplied(
                id = row.id,
                appliedCommit = null,
                statusReason = "recovered",
                approverIssuer = null,
                approverExternalId = null,
                at = clock.now(),
            )
        } else {
            repository.markPendingFromApplying(row.id)
        }
    }

    /** Every proposal as a summary view, newest-first, each carrying its LIVE-derived `base_drifted` flag. */
    fun list(): List<ProposalSummaryView> = repository.all().map {
        ProposalSummaryView(it, baseDrifted(it.status, it.operation, it.pageId, it.targetPath, it.baseHash))
    }

    /** The full proposal view for [id] (incl. the stable `unified_diff`) with its LIVE `base_drifted`, or null. */
    fun get(id: ProposalId): ProposalView? {
        val row = repository.findById(id) ?: return null
        return ProposalView(row, baseDrifted(row.status, row.operation, row.pageId, row.targetPath, row.baseHash))
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
        statusReason = null,
    )

    /**
     * The LIVE drift flag (§0.13(ii)), derived per-row at read time, NEVER stored (NON-AUTHORITATIVE triage — §B4).
     * Only an ACTIONABLE row carries it: a PENDING or CONFLICTED proposal can still be applied/rebased against the live
     * base, so drift is meaningful. A TERMINAL row (APPLIED/REJECTED/FAILED — and the transient APPLYING) is decided;
     * deriving drift against a live base would be misleading (an APPLIED row's base ALWAYS "differs" post-apply), so it
     * is fixed to `false`. For an actionable row:
     *  - EDIT: the live current hash differs from the stored `base_hash` — OR the target was deleted since propose
     *    (currentBytes null IS drift; do NOT diff against empty here);
     *  - CREATE: a content file now occupies `target_path` (the file-path collision, `byPath` ∪ `assets`).
     */
    private fun baseDrifted(
        status: ProposalStatus,
        operation: ProposalOperation,
        pageId: PageId?,
        targetPath: TreePath,
        baseHash: String?,
    ): Boolean {
        if (status != ProposalStatus.PENDING && status != ProposalStatus.CONFLICTED) return false
        return when (operation) {
            ProposalOperation.EDIT -> {
                val current = pageId?.let(baseReader::pathOf)?.let(baseReader::currentBytes)
                current == null || citations.contentHash(current) != baseHash
            }
            ProposalOperation.CREATE -> baseReader.occupied(targetPath)
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * A deterministic, non-routable synthetic git email for an issuer/externalId pair (P1b): git requires an email and
 * there is no real one for an agent token or a proxy subject. PINNED form `"<externalId>@<issuer>.plainbase.local"`
 * so the git-attribution test can assert it. The builtin human externalId is the GENERATED user id (not username).
 */
internal fun syntheticEmail(issuer: String, externalId: String): String = "$externalId@$issuer.plainbase.local"

/**
 * The EDIT content-write seam the apply drives (P1b): the FACADE binds it to `GuardedMutatingFacade.save` (which
 * mints the real EditGrant + audits + resolves the page's CURRENT path by pageId + drives `WritePipeline`).
 * Domain-side so [ProposalService] stays framework-free. P1b is EDIT-ONLY; CREATE-apply (a create() binding) is
 * deferred to 5.5 — a CREATE row is short-circuited in [ProposalService.apply] BEFORE this is ever consulted.
 */
fun interface ProposalContentWriter {
    fun write(row: ProposalRow, author: CommitIdentity, committer: CommitIdentity): WriteOutcome
}

/** The snapshotted proposer attribution (issuer/external_id/display label), resolved at propose time (C4). */
data class ProposalAuthor(val issuer: String, val externalId: String, val label: String)

/** The deciding principal's snapshot for a reject/approve (issuer/external_id + the display label for git committer). */
data class ProposalApprover(val issuer: String, val externalId: String, val label: String)

/** The outcome of a propose. Created carries the minted id + the stored stable diff; StaleBase/InvalidRequest persisted nothing. */
sealed interface ProposeOutcome {
    data class Created(val id: ProposalId, val unifiedDiff: String) : ProposeOutcome

    /** An edit's claimed `base_hash` no longer matches the live content, or the target page was deleted (400 stale_base). */
    data object StaleBase : ProposeOutcome

    /** A semantic malformed request the service detected (C3 — a client `target_path` disagreeing with the resolved path) (400 invalid_propose_request). */
    data object InvalidRequest : ProposeOutcome
}

/** The outcome of an apply (P1b — the §WI-5 wire contract maps these). */
sealed interface ApplyOutcome {
    data class Applied(val view: ProposalView, val newHash: String, val commit: String?, val reindexDeferred: Boolean) : ApplyOutcome

    data class Conflicted(val view: ProposalView, val currentHash: String?, val currentPath: TreePath?) : ApplyOutcome

    data class Failed(val view: ProposalView, val reason: String) : ApplyOutcome

    /** A CREATE proposal: DIRECT PENDING->FAILED + status_reason="create_apply_unsupported" (never APPLYING; deferred to 5.5). */
    data object CreateUnsupported : ApplyOutcome

    /** The row was not PENDING (already terminal/in-flight) — the double-approve loser. */
    data object NotPending : ApplyOutcome

    data object NotFound : ApplyOutcome
}

/** The outcome of a rebase (P1b, edits only). */
sealed interface RebaseOutcome {
    data class Rebased(val view: ProposalView) : RebaseOutcome

    /** Not in CONFLICTED state (already pending/terminal) — an idempotent miss. */
    data object NotConflicted : RebaseOutcome

    /** The target page was deleted — the row is stamped terminal FAILED (rebase_target_gone). */
    data object Gone : RebaseOutcome

    data object NotFound : RebaseOutcome
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
