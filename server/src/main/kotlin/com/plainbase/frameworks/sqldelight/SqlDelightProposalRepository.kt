package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.ProposalSummaryRow
import kotlin.time.Instant

/**
 * SQLDelight adapter for [ProposalRepository] over the `proposals` table (landed by `7.sqm`). The
 * [SqlDelightApiTokenRepository] shape: the three non-primitive columns (id/page_id/target_path) carry an adapter
 * registered in [DatabaseFactory.createDatabase]; every other column is mapped at THIS boundary — the enum NAME
 * ([ProposalStatus]/[ProposalOperation] `.name` / `valueOf`) and `Instant` <-> epoch-millis `Long`. The conditional
 * UPDATEs return the affected-row count in `QueryResult.value` (synchronous JDBC), so `== 1L` confirms a CAS win.
 */
class SqlDelightProposalRepository(private val db: PlainbaseDb) : ProposalRepository {

    private val queries get() = db.proposalsQueries

    override fun insert(row: ProposalRow) {
        queries.insert(
            id = row.id,
            operation = row.operation.name,
            pageId = row.pageId,
            baseHash = row.baseHash,
            targetPath = row.targetPath,
            proposedContent = row.proposedContent,
            rationale = row.rationale,
            diffArtifact = row.diffArtifact,
            status = row.status.name,
            authorIssuer = row.authorIssuer,
            authorExternalId = row.authorExternalId,
            authorLabel = row.authorLabel,
            approverIssuer = row.approverIssuer,
            approverExternalId = row.approverExternalId,
            decisionComment = row.decisionComment,
            createdAt = row.createdAt.toEpochMilliseconds(),
            decidedAt = row.decidedAt?.toEpochMilliseconds(),
            appliedCommit = row.appliedCommit,
            statusReason = row.statusReason,
        )
    }

    override fun findById(id: ProposalId): ProposalRow? =
        queries.selectById(id, ::toRow).executeAsOneOrNull()

    override fun all(): List<ProposalSummaryRow> =
        queries.selectAllSummary(::toSummary).executeAsList()

    override fun reject(id: ProposalId, approverIssuer: String, approverExternalId: String, comment: String?, at: Instant): Boolean =
        queries.reject(
            issuer = approverIssuer,
            externalId = approverExternalId,
            comment = comment,
            at = at.toEpochMilliseconds(),
            id = id,
        ).value == 1L

    override fun claimApplying(id: ProposalId): Boolean =
        queries.claimApplying(id).value == 1L

    override fun reconcileApplyingToPending(): Int =
        queries.reconcileApplyingToPending().value.toInt()

    override fun markApplied(
        id: ProposalId,
        appliedCommit: String?,
        statusReason: String?,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean =
        queries.markApplied(
            appliedCommit = appliedCommit,
            statusReason = statusReason,
            at = at.toEpochMilliseconds(),
            approverIssuer = approverIssuer,
            approverExternalId = approverExternalId,
            id = id,
        ).value == 1L

    override fun markConflicted(
        id: ProposalId,
        statusReason: String?,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean =
        queries.markConflicted(
            statusReason = statusReason,
            at = at.toEpochMilliseconds(),
            approverIssuer = approverIssuer,
            approverExternalId = approverExternalId,
            id = id,
        ).value == 1L

    override fun markFailed(
        id: ProposalId,
        statusReason: String,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean =
        queries.markFailed(
            statusReason = statusReason,
            at = at.toEpochMilliseconds(),
            approverIssuer = approverIssuer,
            approverExternalId = approverExternalId,
            id = id,
        ).value == 1L

    override fun failPending(
        id: ProposalId,
        statusReason: String,
        approverIssuer: String?,
        approverExternalId: String?,
        at: Instant,
    ): Boolean =
        queries.failPending(
            statusReason = statusReason,
            at = at.toEpochMilliseconds(),
            approverIssuer = approverIssuer,
            approverExternalId = approverExternalId,
            id = id,
        ).value == 1L

    override fun failConflicted(id: ProposalId, statusReason: String, at: Instant): Boolean =
        queries.failConflicted(statusReason = statusReason, at = at.toEpochMilliseconds(), id = id).value == 1L

    override fun rebaseToPending(id: ProposalId, baseHash: String, diffArtifact: String, targetPath: TreePath): Boolean =
        queries.rebaseToPending(baseHash = baseHash, diffArtifact = diffArtifact, targetPath = targetPath, id = id).value == 1L

    override fun markPendingFromApplying(id: ProposalId): Boolean =
        queries.markPendingFromApplying(id).value == 1L

    override fun allApplying(): List<ProposalRow> =
        queries.selectApplying(::toRow).executeAsList()

    @Suppress("LongParameterList")
    private fun toRow(
        id: ProposalId,
        operation: String,
        pageId: PageId?,
        baseHash: String?,
        targetPath: TreePath,
        proposedContent: ByteArray,
        rationale: String,
        diffArtifact: String,
        status: String,
        authorIssuer: String,
        authorExternalId: String,
        authorLabel: String,
        approverIssuer: String?,
        approverExternalId: String?,
        decisionComment: String?,
        createdAt: Long,
        decidedAt: Long?,
        appliedCommit: String?,
        statusReason: String?,
    ) = ProposalRow(
        id = id,
        operation = ProposalOperation.valueOf(operation),
        pageId = pageId,
        baseHash = baseHash,
        targetPath = targetPath,
        proposedContent = proposedContent,
        rationale = rationale,
        diffArtifact = diffArtifact,
        status = ProposalStatus.valueOf(status),
        authorIssuer = authorIssuer,
        authorExternalId = authorExternalId,
        authorLabel = authorLabel,
        approverIssuer = approverIssuer,
        approverExternalId = approverExternalId,
        decisionComment = decisionComment,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        decidedAt = decidedAt?.let(Instant::fromEpochMilliseconds),
        appliedCommit = appliedCommit,
        statusReason = statusReason,
    )

    @Suppress("LongParameterList")
    private fun toSummary(
        id: ProposalId,
        operation: String,
        pageId: PageId?,
        targetPath: TreePath,
        baseHash: String?,
        status: String,
        rationale: String,
        authorIssuer: String,
        authorExternalId: String,
        authorLabel: String,
        approverIssuer: String?,
        approverExternalId: String?,
        decisionComment: String?,
        createdAt: Long,
        decidedAt: Long?,
    ) = ProposalSummaryRow(
        id = id,
        operation = ProposalOperation.valueOf(operation),
        pageId = pageId,
        targetPath = targetPath,
        baseHash = baseHash,
        status = ProposalStatus.valueOf(status),
        rationale = rationale,
        authorIssuer = authorIssuer,
        authorExternalId = authorExternalId,
        authorLabel = authorLabel,
        approverIssuer = approverIssuer,
        approverExternalId = approverExternalId,
        decisionComment = decisionComment,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        decidedAt = decidedAt?.let(Instant::fromEpochMilliseconds),
    )
}
