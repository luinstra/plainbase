package com.plainbase.frameworks.protocol

import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.service.ProposalSummaryView
import com.plainbase.domain.service.ProposalView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * PB-PROPOSE-1 (Phase 5, chunk P1a) — the FROZEN agent-facing proposal wire shapes for
 * `POST/GET /api/v1/changes` (+ `…/{id}` and `…/{id}/reject`). Manual-`RestJson` DTOs use the shared
 * protocol serializer; `Instant` is rendered as an ISO-8601 STRING, never the type.
 * Fields are append-only: never remove or retype them. Status/operation vocabularies and error codes only grow.
 */

/**
 * `POST /api/v1/changes` request. [operation] is the LOWERCASE discriminator `edit`|`create`, mapped EXPLICITLY
 * to the domain enum (NOT `.name`). For an `edit`: [pageId] is AUTHORITATIVE (the server resolves the path from it)
 * and [baseHash] present; [targetPath] is optional + non-authoritative (a disagreeing value is a 400). For a
 * `create`: [root] + [targetPath] are required + authoritative, [pageId]/[baseHash] are null. [proposedContent] is
 * the UTF-8 markdown SOURCE TEXT (the route encodes it to bytes for storage/hash/diff).
 *
 * [root] on a create is REQUIRED, never defaulted: it decides which root's disk the applied bytes land on and
 * which root's editable/glob policy the proposal is judged against, so an omitted root is a 400, never permission
 * to write to `main`; an unknown name is a 400 `invalid_root`. On an edit, omitted [root] resolves id_map-first;
 * a supplied pin is grammar-checked by the shared parser, then durable-validated after authorization.
 */
@Serializable
data class ProposeChangeRequest(
    val operation: String,
    val root: String? = null,
    @SerialName("page_id") val pageId: String? = null,
    @SerialName("base_hash") val baseHash: String? = null,
    @SerialName("target_path") val targetPath: String? = null,
    @SerialName("proposed_content") val proposedContent: String,
    val rationale: String,
)

/** 201 response to a successful `propose_change`. */
@Serializable
data class ProposeChangeResponse(
    val id: String,
    val status: String,
    @SerialName("unified_diff") val unifiedDiff: String,
)

/**
 * A `list_changes` element. [baseDrifted] is always present and live-derived, never stored. [pageId] carries the
 * edit target or create's reserved ID, avoiding detail fetches; it remains nullable for robustness.
 */
@Serializable
data class ChangeSummary(
    val id: String,
    val operation: String,
    val status: String,
    /** The root [targetPath] lives under; an actionable proposal against an unavailable root reads drifted. */
    val root: String,
    @SerialName("target_path") val targetPath: String,
    @SerialName("page_id") val pageId: String?,
    @SerialName("base_drifted") val baseDrifted: Boolean,
    @SerialName("author_label") val authorLabel: String,
    @SerialName("created_at") val createdAt: String,
    val rationale: String,
)

/** `list_changes` response — a wrapper object so additive pagination can land later. */
@Serializable
data class ListChangesResponse(val proposals: List<ChangeSummary>)

/** `get_change` and the reject success body: the summary plus stored diff and decision fields. */
@Serializable
data class ChangeDetail(
    val id: String,
    val operation: String,
    val status: String,
    /** The root [targetPath] lives under (see [ChangeSummary.root]). */
    val root: String,
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
    /** Detail-only failure/conflict reason or apply warning; null while PENDING, serialized even when null. */
    @SerialName("status_reason") val statusReason: String? = null,
)

/** The frozen PB-PROPOSE-1 status string set (append-only). */
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

/** The frozen PB-PROPOSE-1 operation string set (append-only). */
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

/** The wire form of a domain status enum, mapped explicitly to the frozen constants. */
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
    root = row.root.value,
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
    root = row.root.value,
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
