package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.service.ProposalSummaryView
import com.plainbase.domain.service.ProposalView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * PB-PROPOSE-1 (Phase 5, chunk P1a) — the FROZEN agent-facing proposal wire shapes for
 * `POST/GET /api/v1/changes` (+ `…/{id}` and `…/{id}/reject`). Manual-`RestJson` DTOs (snake_case
 * `@SerialName`, `Instant` -> ISO-8601 STRING never the type), NEVER content-negotiation — encoded/decoded
 * through the scoped [RestJson] like PB-WRITE-1's [WriteDtos], so no reflect-config triple is needed (the
 * native round-trip test proves it, the AuthDto idiom).
 *
 * ============================== NEVER-CHANGE POLICY ==============================
 * These shapes froze when P1a landed. They are append-only: a field is never removed or retyped; the status
 * and operation vocabularies only grow; the append-only error codes `stale_base`/`invalid_propose_request`/
 * `not_pending` are forever. The RESPONSE/error shapes are pinned by the PB-PROPOSE-1 forever-goldens; the
 * REQUEST shapes are pinned by the native DECODE round-trip + the route tests. See `ForeverApiGoldenSuite.kt`.
 * =================================================================================
 */

/**
 * `POST /api/v1/changes` request. [operation] is the LOWERCASE discriminator `edit`|`create`, mapped EXPLICITLY
 * to the domain enum (NOT `.name`). For an `edit`: [pageId] is AUTHORITATIVE (the server resolves the path from it)
 * and [baseHash] present; [targetPath] is optional + non-authoritative (a disagreeing value is a 400). For a
 * `create`: [targetPath] is required + authoritative, [pageId]/[baseHash] are null. [proposedContent] is the UTF-8
 * markdown SOURCE TEXT (the route encodes it to bytes for storage/hash/diff).
 */
@Serializable
data class ProposeChangeRequest(
    val operation: String,
    @SerialName("page_id") val pageId: String? = null,
    @SerialName("base_hash") val baseHash: String? = null,
    @SerialName("target_path") val targetPath: String? = null,
    @SerialName("proposed_content") val proposedContent: String,
    val rationale: String,
)

/** 201 response to a successful `propose_change`: the minted id, the always-`PENDING` status, and the server-computed diff. */
@Serializable
data class ProposeChangeResponse(
    val id: String,
    val status: String,
    @SerialName("unified_diff") val unifiedDiff: String,
)

/**
 * A `list_changes` element. [baseDrifted] is the LIVE-derived triage datum (MUST be present — omitting it forces
 * N+1 reads, a contract break); [pageId] carries the page id for BOTH operations — an edit's authoritative target id
 * and a create's reserved minted id (C1, baked into the proposed bytes at propose-time) — so a client links to the
 * target without an N+1 `get_change`. (Typed nullable for robustness; in practice present on every row.)
 */
@Serializable
data class ChangeSummary(
    val id: String,
    val operation: String,
    val status: String,
    @SerialName("target_path") val targetPath: String,
    @SerialName("page_id") val pageId: String?,
    @SerialName("base_drifted") val baseDrifted: Boolean,
    @SerialName("author_label") val authorLabel: String,
    @SerialName("created_at") val createdAt: String,
    val rationale: String,
)

/** `list_changes` response — a WRAPPER object (never a bare array) so additive pagination can land later. */
@Serializable
data class ListChangesResponse(val proposals: List<ChangeSummary>)

/**
 * `get_change` (and the `reject` success body, E2) — everything in [ChangeSummary] PLUS the stable stored
 * [unifiedDiff] and the nullable decision fields. [baseDrifted] is present here too (LIVE-derived, never persisted).
 */
@Serializable
data class ChangeDetail(
    val id: String,
    val operation: String,
    val status: String,
    @SerialName("target_path") val targetPath: String,
    @SerialName("page_id") val pageId: String?,
    @SerialName("base_hash") val baseHash: String?,
    @SerialName("base_drifted") val baseDrifted: Boolean,
    @SerialName("author_label") val authorLabel: String,
    @SerialName("author_issuer") val authorIssuer: String,
    @SerialName("author_external_id") val authorExternalId: String,
    @SerialName("created_at") val createdAt: String,
    val rationale: String,
    @SerialName("unified_diff") val unifiedDiff: String,
    @SerialName("approver_issuer") val approverIssuer: String?,
    @SerialName("approver_external_id") val approverExternalId: String?,
    @SerialName("decision_comment") val decisionComment: String?,
    @SerialName("decided_at") val decidedAt: String?,
    @SerialName("applied_commit") val appliedCommit: String?,
    // P1b: the human-facing reason a proposal landed in a terminal non-applied state (a FAILED reason / a
    // CONFLICTED note / the reindex_deferred|recovered apply warning), null while PENDING. DETAIL-ONLY (not on the
    // summary). Present-null on EVERY ChangeDetail serialization (the scoped RestJson has explicitNulls = true).
    @SerialName("status_reason") val statusReason: String? = null,
)

/** `POST /api/v1/changes/{id}/reject` request — an optional reviewer comment. */
@Serializable
data class RejectChangeRequest(val comment: String? = null)

/**
 * `POST /api/v1/changes/{id}/approve` 200 applied body (P1b, append-only to PB-PROPOSE-1). [warnings] carries
 * `["reindex_deferred"]` for a `WrittenButUnindexed` apply (the bytes are on disk; the dirty page heals at the next
 * boot), else absent. [commitSha] is present-null until Git produces one.
 */
@Serializable
data class ApplyResultResponse(
    @SerialName("new_hash") val newHash: String,
    @SerialName("commit_sha") val commitSha: String? = null,
    @SerialName("applied_at") val appliedAt: String,
    val warnings: List<String>? = null,
)

/**
 * `POST /api/v1/changes/{id}/approve` 409 conflicted body (P1b): an apply hit disk-drift; the proposal is
 * REBASABLE. [code] is ALWAYS present as `"conflicted"`.
 */
@Serializable
data class ConflictedResponse(
    val code: String = "conflicted",
    @SerialName("current_hash") val currentHash: String? = null,
    @SerialName("current_path") val currentPath: String? = null,
)

/** `POST /api/v1/changes/{id}/rebase` 200 body (P1b): the re-pinned base + recomputed diff; [status] is `"PENDING"`. */
@Serializable
data class RebasedResponse(
    @SerialName("new_base_hash") val newBaseHash: String,
    @SerialName("unified_diff") val unifiedDiff: String,
    val status: String = "PENDING",
)

/**
 * The frozen PB-PROPOSE-1 status string set (append-only), spelled out EXPLICITLY (the [WriteConflictReason.ALL]
 * idiom) — never inferred from the domain enum's `.name`, so a domain rename can never silently shift the wire.
 */
object ProposalStatusWire {
    const val PENDING: String = "PENDING"
    const val APPLYING: String = "APPLYING"
    const val APPLIED: String = "APPLIED"
    const val REJECTED: String = "REJECTED"
    const val CONFLICTED: String = "CONFLICTED"
    const val FAILED: String = "FAILED"

    /** The frozen status set (additive-only). */
    val ALL: Set<String> = setOf(PENDING, APPLYING, APPLIED, REJECTED, CONFLICTED, FAILED)
}

/** The frozen PB-PROPOSE-1 operation string set (append-only) — the LOWERCASE wire values. */
object ProposalOperationWire {
    const val EDIT: String = "edit"
    const val CREATE: String = "create"

    /** The closed wire-value set (additive-only). */
    val ALL: Set<String> = setOf(EDIT, CREATE)
}

// ---- domain -> DTO mapping (the only place the proposal domain views meet the wire shapes) ----------

/** The wire LOWERCASE form of a domain operation enum (explicit, never `.name`). */
fun ProposalOperation.toWire(): String = when (this) {
    ProposalOperation.EDIT -> ProposalOperationWire.EDIT
    ProposalOperation.CREATE -> ProposalOperationWire.CREATE
}

/**
 * The wire form of a domain status enum, mapped EXPLICITLY to the [ProposalStatusWire] constants (never `.name`) — so a
 * domain enum rename can't silently shift the frozen wire (symmetric with [ProposalOperation.toWire]; names == wire
 * today, golden-pinned).
 */
fun ProposalStatus.toWire(): String = when (this) {
    ProposalStatus.PENDING -> ProposalStatusWire.PENDING
    ProposalStatus.APPLYING -> ProposalStatusWire.APPLYING
    ProposalStatus.APPLIED -> ProposalStatusWire.APPLIED
    ProposalStatus.REJECTED -> ProposalStatusWire.REJECTED
    ProposalStatus.CONFLICTED -> ProposalStatusWire.CONFLICTED
    ProposalStatus.FAILED -> ProposalStatusWire.FAILED
}

fun ProposalSummaryView.toDto(): ChangeSummary = ChangeSummary(
    id = row.id.value,
    operation = row.operation.toWire(),
    status = row.status.toWire(),
    targetPath = row.targetPath.value,
    pageId = row.pageId?.value,
    baseDrifted = baseDrifted,
    authorLabel = row.authorLabel,
    createdAt = row.createdAt.toString(),
    rationale = row.rationale,
)

fun ProposalView.toDto(): ChangeDetail = ChangeDetail(
    id = row.id.value,
    operation = row.operation.toWire(),
    status = row.status.toWire(),
    targetPath = row.targetPath.value,
    pageId = row.pageId?.value,
    baseHash = row.baseHash,
    baseDrifted = baseDrifted,
    authorLabel = row.authorLabel,
    authorIssuer = row.authorIssuer,
    authorExternalId = row.authorExternalId,
    createdAt = row.createdAt.toString(),
    rationale = row.rationale,
    unifiedDiff = row.diffArtifact,
    approverIssuer = row.approverIssuer,
    approverExternalId = row.approverExternalId,
    decisionComment = row.decisionComment,
    decidedAt = row.decidedAt?.toString(),
    appliedCommit = row.appliedCommit,
    statusReason = row.statusReason,
)
