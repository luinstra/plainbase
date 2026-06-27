package com.plainbase.domain.repository

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import kotlin.time.Instant

/**
 * The at-rest store for agent proposals (Phase 5, chunk P1a — PB-PROPOSE-1). Lives in the APP database
 * (`plainbase.db`) — the review queue is security truth, like the A2 `api_tokens` table — NEVER disposable
 * `search.db` (ADR-0004). Author attribution is DENORMALIZED + SNAPSHOTTED ([ProposalRow.authorIssuer] etc.),
 * never a FK to revocable tokens/users, so a terminal proposal's authorship survives revocation.
 *
 * Framework-free (hexagonal, memoria-style): the SQLDelight impl is `SqlDelightProposalRepository`. This is a
 * TRUSTED persistence detail — its mutators take NO grant (like `SqlDelightApiTokenRepository`); the demanded-grant
 * floor lives at the `ProposalService` boundary (G1a) + `GrantUnforgeabilityTest`.
 */
interface ProposalRepository {

    /** Persist a freshly built proposal row (a fresh PENDING row has NULL decision/approver/applied columns). */
    fun insert(row: ProposalRow)

    /** The full row for [id] (incl. `proposed_content` + `diff_artifact`), or null if unknown — the `get_change` path. */
    fun findById(id: ProposalId): ProposalRow?

    /**
     * Every proposal as a SUMMARY row (the `list_changes` source) — newest-first, stable for same-millis inserts.
     * Deliberately NOT [ProposalRow]: a list must never overfetch every `proposed_content` BLOB + `diff_artifact`.
     */
    fun all(): List<ProposalSummaryRow>

    /**
     * The reject decision (conditional on PENDING): flips PENDING -> REJECTED + stamps approver/comment/decided_at.
     * Returns true iff exactly one row was updated (the row was PENDING); false means it was already terminal/gone.
     */
    fun reject(id: ProposalId, approverIssuer: String, approverExternalId: String, comment: String?, at: Instant): Boolean

    /**
     * The FROZEN P1b status-CAS: claims a PENDING proposal for apply (PENDING -> APPLYING). Returns true iff exactly
     * one row was updated (it was PENDING) — the idempotency floor a concurrent double-claim loses on. SHIPPED +
     * tested in P1a; driven by NO P1a endpoint (apply-nothing); consumed by P1b.
     */
    fun claimApplying(id: ProposalId): Boolean

    /**
     * The startup-reconcile SEAM: flips any APPLYING row back to PENDING and returns the count. P1a SHIPS + unit-tests
     * the QUERY only; the crash-recovery SEMANTICS (inspect-then-decide) are DEFERRED to P1b, which owns apply.
     */
    fun reconcileApplyingToPending(): Int

    /**
     * Terminal CAS: APPLYING -> APPLIED, stamping the commit + the optional reindex_deferred/recovered reason +
     * approver. [approverIssuer]/[approverExternalId] are NULL on the crash-recovery path (the original approver is
     * not recorded at claim time), the real approver on the live apply path. Returns true iff exactly one row moved.
     */
    fun markApplied(
        id: ProposalId,
        appliedCommit: String?,
        statusReason: String?,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean

    /** Terminal CAS: APPLYING -> CONFLICTED (rebasable), stamping the conflict reason + approver. */
    fun markConflicted(
        id: ProposalId,
        statusReason: String?,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean

    /** Terminal CAS: APPLYING -> FAILED, stamping the failure reason + approver. */
    fun markFailed(
        id: ProposalId,
        statusReason: String,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean

    /**
     * DIRECT terminal CAS: PENDING -> FAILED (the create-unsupported path — NEVER claims APPLYING, so a CREATE
     * proposal can never enter APPLYING; mirrors the `reject` PENDING->REJECTED idiom). Returns true iff one row moved.
     */
    fun failPending(
        id: ProposalId,
        statusReason: String,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean

    /**
     * Terminal CAS: CONFLICTED -> FAILED (the rebase `Gone` path — the target page was deleted; stamps a durable
     * reason so the row is not a dangling CONFLICTED. No approver — the rebase actor lives in the audit row).
     */
    fun failConflicted(id: ProposalId, statusReason: String, at: Instant): Boolean

    /**
     * One-step rebase CAS: CONFLICTED -> PENDING, re-pinning base_hash + diff_artifact + target_path (the page may
     * have MOVED since propose; the rebase resolved the CURRENT path by page_id), clearing status_reason.
     */
    fun rebaseToPending(id: ProposalId, baseHash: String, diffArtifact: String, targetPath: TreePath): Boolean

    /** Per-row reconcile CAS: APPLYING -> PENDING (the inspect-then-decide recovery "write did not land" arm). */
    fun markPendingFromApplying(id: ProposalId): Boolean

    /** Every APPLYING row (the inspect-then-decide reconciler reads each one's disk hash to choose the terminal). */
    fun allApplying(): List<ProposalRow>
}

/** The proposal operation discriminator. Stored as the enum NAME (uppercase); the wire serializes lowercase (§B5). */
enum class ProposalOperation { EDIT, CREATE }

/**
 * The proposal lifecycle states (§0.12, append-only freeze; stored as the enum NAME in TEXT, the [AgentMode] idiom).
 * P1a writes only PENDING (propose) + REJECTED (reject); it freezes the full set so P1b/P3 inherit it. There is NO
 * durable APPROVED resting state. APPLYING is transient/non-terminal (the reconcile seam).
 */
enum class ProposalStatus { PENDING, APPLYING, APPLIED, REJECTED, CONFLICTED, FAILED }

/**
 * One `proposals` row, fully typed. A plain class (NOT `data`) — it carries [proposedContent]: [ByteArray], which a
 * generated `equals`/`hashCode` would get wrong (the [ApiTokenRow] note). For an `edit` [pageId] is authoritative
 * and [baseHash] present; for a `create` both are null. [targetPath] is the on-disk content-file path
 * ([com.plainbase.domain.page.PageIndex.byPath] space), resolved from [pageId] for an edit. The three `author*`
 * columns are the snapshotted proposer attribution; `approver*`/`decisionComment`/`decidedAt` are null until decided;
 * `appliedCommit` + `statusReason` are filled by P1b (apply-on-approve; append-only freeze). [statusReason] carries
 * the human-facing reason a proposal landed in a terminal non-applied state (a FAILED reason / a CONFLICTED note /
 * the `reindex_deferred`/`recovered` apply warning), null while PENDING.
 */
class ProposalRow(
    val id: ProposalId,
    val operation: ProposalOperation,
    val pageId: PageId?,
    val baseHash: String?,
    val targetPath: TreePath,
    val proposedContent: ByteArray,
    val rationale: String,
    val diffArtifact: String,
    val status: ProposalStatus,
    val authorIssuer: String,
    val authorExternalId: String,
    val authorLabel: String,
    val approverIssuer: String?,
    val approverExternalId: String?,
    val decisionComment: String?,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val appliedCommit: String?,
    val statusReason: String?,
)

/**
 * A proposal SUMMARY (the `list_changes` element backing) — every column EXCEPT the heavyweight `proposed_content`
 * BLOB and the full `diff_artifact`, so the list path never overfetches. INCLUDES [targetPath] (the DTO + the live
 * `base_drifted` drift check need it). A plain class for symmetry with [ProposalRow]; no [ByteArray] field, so a
 * `data` class would be fine, but the list type carries no equality contract worth generating.
 */
class ProposalSummaryRow(
    val id: ProposalId,
    val operation: ProposalOperation,
    val pageId: PageId?,
    val targetPath: TreePath,
    val baseHash: String?,
    val status: ProposalStatus,
    val rationale: String,
    val authorIssuer: String,
    val authorExternalId: String,
    val authorLabel: String,
    val approverIssuer: String?,
    val approverExternalId: String?,
    val decisionComment: String?,
    val createdAt: Instant,
    val decidedAt: Instant?,
)
