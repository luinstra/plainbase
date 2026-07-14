package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
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
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

/**
 * The proposal lifecycle orchestration (pure-domain, over the [ProposalRepository] port + the injected
 * [CitationFactory] + [unifiedDiff] + the LIVE [ProposalBaseReader] read seam + a [Clock] for deterministic
 * golden timestamps). P1a writes PENDING (propose) + REJECTED (reject) rows; P1b ADDS the mutating apply surface —
 * the apply ([apply]) drives the content-tree/Git write through the injected [ProposalContentWriter] under the
 * claim->write->stamp order, plus the one-step [rebase] of a CONFLICTED row and the boot [reconcileApplying] crash
 * recovery. C1 extends [apply] to CREATE proposals too — the guarded create write under `WriteOrigin.PROPOSAL_APPLY`,
 * which bypasses the agent glob gate so an approved out-of-glob create still lands. Live disk READS via
 * [ProposalBaseReader] (base-hash validation + drift + recovery) are expected throughout.
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
    /**
     * The serving status of the root a durable ROW names (ADR-0011 D15) — a NARROW function over domain types,
     * not a service graph: the composition root closes over the resolver + the availability holder, exactly as
     * `PolicyService` takes an `editableOf` and `LocalContentStore` an `onRootUnavailable`.
     *
     * Defaulted AVAILABLE = today's exact semantics (every root serves), so a single-root construction stays
     * terse and inert and every existing construction site compiles untouched.
     *
     * It is evaluated PER CALL, not once per pass, and that is the more current answer rather than the sloppier
     * one: the watchers are already live when the boot reconcile runs, so a WATCHER_FAILED flip can land DURING
     * the pass, and a pass-level snapshot would miss it and then rewrite a row for a root that just went down.
     * Availability is monotonic, so a per-call read can only ever get MORE unavailable — which is the safe
     * direction for a guard whose whole job is to NOT rewrite the row.
     */
    private val rootStatus: (RootName) -> RootStatus = { RootStatus.AVAILABLE },
) {

    /**
     * Propose an EDIT to an existing page. [pageId] is AUTHORITATIVE — the stored [ProposalRow.targetPath] is the
     * path RESOLVED from it (a disagreeing client path is rejected upstream at the route, C3). [baseHash] must EQUAL
     * the target's live current hash (else `StaleBase`) and the target must still be published (else `StaleBase`,
     * target-missing). The stored `diff_artifact` is `live-base -> proposed` (so base == current). Nothing is
     * persisted on a `StaleBase`.
     *
     * [target] is THREADED from the facade, never re-resolved here (ADR-0011 D17). The facade had to resolve the
     * page anyway to derive the gate's rooted resource, and `baseReader.pathOf` is a FRESH read of the published
     * snapshot — so re-resolving would let a rebuild landing between the gate and this call persist a proposal
     * against a root the gate never authorized (possibly a non-editable or unavailable one). Gate-root and
     * row-root are one object's answer, end to end.
     */
    fun proposeEdit(
        @Suppress("UNUSED_PARAMETER") grant: EditGrant,
        pageId: PageId,
        target: RootedPath,
        baseHash: String,
        clientTargetPath: TreePath?,
        proposedContent: ByteArray,
        rationale: String,
        author: ProposalAuthor,
    ): ProposeOutcome {
        // C3: page_id is authoritative; a client target_path that disagrees with the resolved path is malformed.
        if (clientTargetPath != null && clientTargetPath != target.path) return ProposeOutcome.InvalidRequest
        val currentBytes = when (val read = baseReader.currentBytes(target)) {
            is ContentRead.Bytes -> read.bytes
            // NEVER StaleBase: "your base moved" is a lie when the truth is that the disk is unmounted, and it is
            // the kind of lie that makes an agent re-read and re-propose against nothing.
            ContentRead.RootDown -> throw RootUnavailable(target.root, UnavailableCause.VANISHED)
            // ...and the same lie, one step subtler (C1): a page whose binding is still live and whose bytes we
            // cannot produce has NOT moved either. `StaleBase` tells the agent to re-read a base that is not there
            // to be re-read; 503 tells it to come back, which is the truth.
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(target)
            ContentRead.ConfirmedAbsent -> return ProposeOutcome.StaleBase
        }
        if (citations.contentHash(currentBytes) != baseHash) return ProposeOutcome.StaleBase

        val row = newPending(
            operation = ProposalOperation.EDIT,
            pageId = pageId,
            root = target.root,
            baseHash = baseHash,
            targetPath = target.path,
            proposedContent = proposedContent,
            rationale = rationale,
            diffArtifact = unifiedDiff(currentBytes, proposedContent),
            author = author,
        )
        repository.insert(row)
        return ProposeOutcome.Created(row.id, row.diffArtifact)
    }

    /**
     * Propose a CREATE of a new page at [targetPath] (authoritative — no page exists yet, so `base_hash` is null). A
     * pure store: [pageId] is the SERVER-minted id (C1), already materialized into the [proposedContent] frontmatter
     * by the facade (explicit propose) or the create route (degrade) — this method NEVER mints or patches. Storing it
     * on the row preserves the invariant that an APPLYING create row carries a non-null `page_id` (a RESERVATION — no
     * live page exists yet; `baseDrifted` for a CREATE keys on `occupied(targetPath)`, never on `page_id`). The diff is
     * computed over an empty base. Nothing here rejects on collision — that is a LIVE `base_drifted` triage flag the
     * read path derives, and the real gate is the apply.
     */
    fun proposeCreate(
        @Suppress("UNUSED_PARAMETER") grant: CreateGrant,
        pageId: PageId,
        target: RootedPath,
        proposedContent: ByteArray,
        rationale: String,
        author: ProposalAuthor,
    ): ProposeOutcome {
        val row = newPending(
            operation = ProposalOperation.CREATE,
            pageId = pageId,
            // A CREATE proposal's stored root is AUTHORITATIVE - there is no page yet to resolve one from, so this
            // is what apply will write into. (An EDIT's is authoritative too, for its own reason: see [writeRootOf].)
            root = target.root,
            baseHash = null,
            targetPath = target.path,
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
     * Apply a PENDING proposal (P1b edit-apply + C1 create-apply). The load-bearing order is **claim(DB) ->
     * write(disk+git) -> stamp-terminal(DB)**, the terminal stamp a conditional `WHERE status='APPLYING'` CAS so a
     * crash-recovery reconcile racing a live apply cannot double-stamp.
     *
     * BOTH operations flow through the SAME claim->write->stamp path (C1 removed the P1b CREATE short-circuit). The
     * [writer] branches on `row.operation` (the facade binding): an EDIT routes through `GuardedMutatingFacade.save`
     * (resolving the page's CURRENT pageId path); a CREATE routes through `GuardedMutatingFacade.create` under
     * `WriteOrigin.PROPOSAL_APPLY` (bypassing the agent glob gate). An APPLYING row carries a non-null `page_id`
     * regardless (minted at propose/degrade time for a create; the edit invariant for an edit) — but a fresh create's
     * page is NOT yet resolvable by `pathOf(pageId)`, so create recovery keys on the immutable `target_path`.
     *
     * The [grant] is the demanded witness that `checkApprove` ran; [approver] carries the deciding ADMIN's
     * (issuer, externalId, label).
     */
    fun apply(
        @Suppress("UNUSED_PARAMETER") grant: ApproveGrant,
        id: ProposalId,
        approver: ProposalApprover,
        writer: ProposalContentWriter,
    ): ApplyOutcome {
        // (1) claim: PENDING -> APPLYING CAS (both EDIT and CREATE).
        if (!repository.claimApplying(id)) {
            return if (repository.findById(id) == null) ApplyOutcome.NotFound else ApplyOutcome.NotPending
        }

        // (2) now APPLYING; pageId is non-null (an edit resolves its path; a create carries the propose-time id).
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
            if (outcome is WriteOutcome.InvalidLocation) {
                // The raw reason can carry FS detail; dispositionOf emits only a stable string (the same no-leak rule).
                logger.error { "apply $id: create InvalidLocation (reason logged, never surfaced): ${outcome.reason}" }
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
        // Resolved IN THE ROW'S OWN ROOT: a rebase re-pins `base_hash` and recomputes the diff a human then approves,
        // so resolving across roots would silently re-point the whole review at another repository's file.
        val target = baseReader.pathOf(row.root, pageId)
        // A null pathOf needs NO classification, and the proof is worth stating: pathOf is SNAPSHOT-derived, so a
        // null means the id is in no published section OF THIS ROOT. Under a root whose status the facade's pre-guard
        // already passed, that can only be a never-scanned root (which is MISSING_AT_BOOT and therefore already marked,
        // so the pre-guard fired), a genuinely absent page under a live root, or a page whose id was re-awarded to
        // another root (D17) - all of them the honest Gone. It CANNOT be an unmarked-vanished root, because such a
        // root's section is carried forward, so its pages are still in byId.
        val currentBytes = when (val read = target?.let(baseReader::currentBytes)) {
            is ContentRead.Bytes -> read.bytes
            // The facade's pre-guard is a STATUS check, so the root can vanish after it passes and the read then
            // comes back empty for a reason the status has not heard about. Stamping the row terminally FAILED off
            // THAT would rewrite durable state on evidence a missing root could not supply - and it would FORECLOSE
            // the recovery that restoring the root would otherwise give. Leave it CONFLICTED; answer 503.
            ContentRead.RootDown -> throw RootUnavailable(target.root, UnavailableCause.VANISHED)
            // The SAME foreclosure, on the SAME durable row, from an absence nobody proved (C1):
            // `rebase_target_gone` is TERMINAL, and a page still bound in the index is not gone. Leave it
            // CONFLICTED and answer 503 - the rebase is decidable again the moment the page is witnessed.
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(target)
            null, ContentRead.ConfirmedAbsent -> null
        }
        if (target == null || currentBytes == null) {
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
        return if (repository.rebaseToPending(id, newBaseHash, newDiff, target.path)) {
            RebaseOutcome.Rebased(requireNotNull(get(id)) { "rebased proposal $id vanished" })
        } else {
            RebaseOutcome.NotConflicted
        }
    }

    /**
     * The two facts the facade's PRE-CLAIM guards (`approve`/`rebase`) need before they act: which root the write
     * would land in, and whether the row is still ACTIONABLE at all. Both must be answered BEFORE the
     * PENDING->APPLYING claim, since that claim is itself a durable rewrite that would convert a decidable row into
     * an undecidable one. Deliberately NOT [get], which would compute `baseDrifted` and touch the base reader.
     *
     * [ProposalGuard.status] does NOT become a second source of truth: the CAS in [apply] (and the CONFLICTED check
     * in [rebase]) still decides, so a row that turns terminal after this read is still caught. It exists so an
     * ALREADY-DECIDED row can be reported as decided — a 409 — without first demanding that its root be up. Reading
     * the state of a proposal nobody can act on any more needs no disk.
     *
     * The root is the STORED one, for BOTH operations (ADR-0011 D15/D17). A create has no page to resolve one from.
     * An edit HAS one - and still does not follow it: the D17 cross-root duplicate-id contest re-awards an ID, it
     * does not MOVE a file, so following the id would walk an approved edit off the root it was proposed, reviewed
     * and gated against. The apply pins the same stored root ([SaveRequest.expectedRoot]), so the guard and the
     * write can never disagree about which root a 503 is about; an id re-awarded across roots answers `page_deleted`
     * -> CONFLICTED instead. That is also why the guard needs nothing but this name.
     */
    fun guardOf(id: ProposalId): ProposalGuard? = repository.findById(id)?.let { ProposalGuard(it.root, it.status) }

    /**
     * The inspect-then-decide crash-recovery reconciler (P1b/C1), run at startup AFTER the disk + index are ready
     * (replaces P1a's BLIND [ProposalRepository.reconcileApplyingToPending] use for the APPLYING-row case). Every
     * APPLYING row carries a non-null `page_id` (an edit by the edit invariant; a create from the propose/degrade-time
     * mint). An EDIT resolves the page's CURRENT path through `pageId`; a CREATE resolves by the IMMUTABLE
     * `target_path` (its fresh page is not in the index/idMap after a crash). If the disk bytes at the resolved path
     * equal `hash(proposed_content)`, the apply's disk write SUCCEEDED before the terminal stamp ran -> stamp APPLIED
     * (with a NULL approver + `status_reason="recovered"`, since the approver is unknown post-crash); otherwise the
     * write did NOT land -> return to PENDING for a fresh approve. Cannot race a live apply (the engine is not serving
     * yet), the [reconcileDirtyPages] guarantee.
     */
    fun reconcileApplying() {
        val applying = repository.allApplying()
        if (applying.isEmpty()) return
        logger.info { "reconciling ${applying.size} APPLYING proposal(s) from a prior interrupted apply" }
        for (row in applying) recoverApplyingRow(row)
    }

    /**
     * The single-row inspect-then-decide recovery shared by the boot [reconcileApplying] AND the live post-claim catch
     * in [apply] (ONE codepath, two callers). Every APPLYING row carries a non-null `page_id` (an edit by the edit
     * invariant; a create from the propose/degrade-time mint). An EDIT resolves the page's CURRENT path through
     * `pageId` (it may have moved); a CREATE resolves by the IMMUTABLE `target_path` — a fresh page is not in the
     * index/idMap after a crash, so `pathOf(pageId)` would wrongly miss a landed create. If the disk bytes at the
     * resolved path equal `hash(proposed_content)`, the apply's disk write SUCCEEDED before the terminal stamp ran ->
     * stamp APPLIED (NULL approver + `status_reason="recovered"`, since the approver is unknown post-crash and uniform
     * with the boot path); otherwise the write did NOT land -> return to PENDING for a fresh approve. The terminal CAS
     * conditions on `status='APPLYING'`, so a row that already left APPLYING (a racing winner) is a no-op.
     */
    private fun recoverApplyingRow(row: ProposalRow) {
        // A root name read back off a DURABLE ROW is UNTRUSTED: plainbase.db outlives roots{}. A DETACHED stored root
        // refuses BEFORE anything is resolved through it - it has no store to read AT ALL (its per-root lookup would
        // throw, and the boot reconcile is unwrapped, so the server would die at startup instead of serving).
        if (rootStatus(row.root) == RootStatus.DETACHED) {
            logger.warn {
                "APPLYING proposal ${row.id} targets root '${row.root}', which is not configured; leaving it APPLYING - " +
                    "it is never stamped off evidence a root that is not serving cannot supply. Re-add the root and restart to decide it."
            }
            return
        }
        // EDIT resolves the page's CURRENT path via pageId, WITHIN the row's root (it may have moved inside that root;
        // it may never cross out of it — see [ProposalBaseReader.pathOf]); CREATE resolves by the IMMUTABLE target_path
        // — a fresh page is not in the index/idMap after a crash, so pathOf would wrongly miss a landed create. Both
        // operations carry a non-null page_id (the edit invariant / the propose-time mint).
        val target = when (row.operation) {
            ProposalOperation.EDIT ->
                baseReader.pathOf(row.root, requireNotNull(row.pageId) { "APPLYING edit ${row.id} must carry a page_id" })
            ProposalOperation.CREATE -> RootedPath(row.root, row.targetPath)
        }
        // Every target now resolves inside the row's own root, so THAT root is the one whose evidence decides - and an
        // unavailable root cannot supply the evidence this recovery decides on: leave the row APPLYING and WARN.
        val status = rootStatus(row.root)
        if (status != RootStatus.AVAILABLE) {
            logger.warn {
                "APPLYING proposal ${row.id} targets root '${row.root}' ($status); leaving it APPLYING - it is never " +
                    "stamped off evidence a root that is not serving cannot supply. Restore the root and restart to decide it."
            }
            return
        }
        val diskBytes = when (val read = target?.let(baseReader::currentBytes)) {
            is ContentRead.Bytes -> read.bytes
            // The window the status guard CANNOT close: the root vanished after it passed, and nothing has marked it,
            // so a second look at the status would return the same stale AVAILABLE. The window closes at the READ.
            // Stamping PENDING here would rewrite durable state off a null a missing root produced.
            ContentRead.RootDown -> {
                logger.warn {
                    "APPLYING proposal ${row.id}: root '${row.root}' went away under the recovery read; leaving it APPLYING"
                }
                return
            }
            // A boot path cannot answer 503 to anybody - so it does the other thing this design always does with an
            // unverified absence: NOTHING (C1). The row stays APPLYING and the next boot decides it. Returning it to
            // PENDING off a page whose binding is still live would be a durable rewrite on an absence nobody proved,
            // and it would discard an apply that may well have LANDED (the bytes are only unreadable, not known gone).
            ContentRead.AbsenceUnknown -> {
                logger.warn {
                    "APPLYING proposal ${row.id}: '${row.targetPath.value}' is still bound in the index but its bytes are not " +
                        "there; leaving it APPLYING - an unverified absence never decides a durable row"
                }
                return
            }
            null, ContentRead.ConfirmedAbsent -> null
        }
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
        ProposalSummaryView(it, baseDrifted(it.status, it.operation, it.pageId, RootedPath(it.root, it.targetPath), it.baseHash))
    }

    /** The full proposal view for [id] (incl. the stable `unified_diff`) with its LIVE `base_drifted`, or null. */
    fun get(id: ProposalId): ProposalView? {
        val row = repository.findById(id) ?: return null
        return ProposalView(row, baseDrifted(row.status, row.operation, row.pageId, RootedPath(row.root, row.targetPath), row.baseHash))
    }

    private fun newPending(
        operation: ProposalOperation,
        pageId: PageId?,
        root: RootName,
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
        root = root,
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
     *
     * A `RootDown` read answers `true`, EXPLICITLY, and it must NOT throw: `list`/`get` render the review queue, and
     * an unavailable root's rows are exactly the ones an operator most needs to see - throwing would take the whole
     * queue down for them. The consequence is named rather than hidden: such a row reads `base_drifted = true`, which
     * under the existing rule (an unreadable base IS drift) is the honest "not applyable right now". It is an
     * explicitly NON-AUTHORITATIVE triage datum, and the reviewer sees the real reason directly, because the row
     * carries its `root` and the tree/health say whether that root is serving.
     */
    private fun baseDrifted(
        status: ProposalStatus,
        operation: ProposalOperation,
        pageId: PageId?,
        target: RootedPath,
        baseHash: String?,
    ): Boolean {
        if (status != ProposalStatus.PENDING && status != ProposalStatus.CONFLICTED) return false
        return when (operation) {
            // Resolved in the row's own root ([target].root): drift is the reviewer's "is this still applyable"
            // signal, so it must report on the file the apply will actually touch, never on a stranger that
            // happens to hold the id today.
            ProposalOperation.EDIT -> when (val read = pageId?.let { baseReader.pathOf(target.root, it) }?.let(baseReader::currentBytes)) {
                is ContentRead.Bytes -> citations.contentHash(read.bytes) != baseHash
                // An unreadable base IS drift, whichever way it is unreadable - and NEITHER may throw here (C1). The
                // whole review queue renders through this, and the rows an operator most needs to see during an
                // outage are precisely the ones a throw would take the page down for. It is an explicitly
                // NON-AUTHORITATIVE triage flag: "not applyable right now", which is exactly true of both.
                ContentRead.RootDown, ContentRead.AbsenceUnknown -> true
                null, ContentRead.ConfirmedAbsent -> true
            }
            ProposalOperation.CREATE -> baseReader.occupied(target)
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
 * The content-write seam the apply drives (P1b/C1): the FACADE binds it, branching on `row.operation` — an EDIT to
 * `GuardedMutatingFacade.save` (which mints the real EditGrant + audits + resolves the page's CURRENT path by pageId +
 * drives `WritePipeline.write`); a CREATE to `GuardedMutatingFacade.create` under `WriteOrigin.PROPOSAL_APPLY` (which
 * mints the CreateGrant + drives `WritePipeline.create`, bypassing the agent glob gate). Domain-side so
 * [ProposalService] stays framework-free.
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

    /** A create blob the server could not materialize an id into (FrontmatterPatcher refusal / an agent-supplied id) (400 invalid_create_content). */
    data class InvalidCreateContent(val message: String) : ProposeOutcome
}

/** A row's write [root] and current [status]: what the facade's pre-claim guards read before they act. */
data class ProposalGuard(val root: RootName, val status: ProposalStatus)

/** The outcome of an apply (the wire contract maps these). */
sealed interface ApplyOutcome {
    data class Applied(val view: ProposalView, val newHash: String, val commit: String?, val reindexDeferred: Boolean) : ApplyOutcome

    data class Conflicted(val view: ProposalView, val currentHash: String?, val currentPath: TreePath?) : ApplyOutcome

    data class Failed(val view: ProposalView, val reason: String) : ApplyOutcome

    /** The row was not PENDING (already terminal/in-flight) — the double-approve loser. */
    data object NotPending : ApplyOutcome

    data object NotFound : ApplyOutcome
}

/** The outcome of a rebase (edits only). */
sealed interface RebaseOutcome {
    data class Rebased(val view: ProposalView) : RebaseOutcome

    /** Not in CONFLICTED state (already pending/terminal) — an idempotent miss. */
    data object NotConflicted : RebaseOutcome

    /** The target page was deleted — the row is stamped terminal FAILED (rebase_target_gone). */
    data object Gone : RebaseOutcome

    data object NotFound : RebaseOutcome
}

/** The outcome of a reject (the wire contract maps these). */
sealed interface RejectOutcome {
    data class Rejected(val view: ProposalView) : RejectOutcome

    data object NotPending : RejectOutcome

    data object NotFound : RejectOutcome
}

/** A full proposal row plus its LIVE-derived (never stored) `base_drifted` flag — the `get_change` / reject body. */
class ProposalView(val row: ProposalRow, val baseDrifted: Boolean)

/** A proposal summary plus its LIVE-derived `base_drifted` flag — a `list_changes` element. */
class ProposalSummaryView(val row: ProposalSummaryRow, val baseDrifted: Boolean)
