package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.approveGrantForTests
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.ProposalSummaryRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The P1a lifecycle/diff-compute logic over a fake [ProposalBaseReader] + an in-memory repo + a fixed [Clock].
 * Exercises base-hash validation (§WI-9 hole #2 both branches), the C3 path-resolve/mismatch, the create base, the
 * §0.13(i) freeze (the stored diff survives convergence), and the LIVE `base_drifted` (§WI-9 hole #1).
 */
class ProposalServiceTest : FunSpec({

    val citations = CitationFactory()
    val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
    val author = ProposalAuthor("agent", "pb_a", "ci-bot")
    val approver = ProposalApprover("builtin", "alice", "Alice Admin")
    val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val path = TreePath.require("guides/deploy.md")

    class FakeReader(
        var pathById: Map<PageId, TreePath> = emptyMap(),
        var bytesByPath: Map<TreePath, ByteArray> = emptyMap(),
        var occupiedPaths: Set<TreePath> = emptySet(),
    ) : ProposalBaseReader {
        override fun pathOf(pageId: PageId): TreePath? = pathById[pageId]
        override fun currentBytes(path: TreePath): ByteArray? = bytesByPath[path]
        override fun occupied(path: TreePath): Boolean = path in occupiedPaths
    }

    /** A trivial in-memory ProposalRepository capturing inserts + the P1b status CASes (no SQLite needed). */
    class MemRepo : ProposalRepository {
        val rows = mutableListOf<ProposalRow>()
        var claimApplyingCalls = 0

        override fun insert(row: ProposalRow) {
            rows.add(row)
        }
        override fun findById(id: com.plainbase.domain.page.ProposalId) = rows.firstOrNull { it.id == id }
        override fun all(): List<ProposalSummaryRow> = rows.sortedByDescending { it.createdAt }.map {
            ProposalSummaryRow(
                it.id, it.operation, it.pageId, it.targetPath, it.baseHash, it.status, it.rationale,
                it.authorIssuer, it.authorExternalId, it.authorLabel, it.approverIssuer, it.approverExternalId,
                it.decisionComment, it.createdAt, it.decidedAt,
            )
        }

        /** Rebuilds a row mutating only the fields a CAS touches (the plain-class copy the prod query performs). */
        private fun replace(
            r: ProposalRow,
            status: ProposalStatus = r.status,
            baseHash: String? = r.baseHash,
            targetPath: TreePath = r.targetPath,
            diffArtifact: String = r.diffArtifact,
            approverIssuer: String? = r.approverIssuer,
            approverExternalId: String? = r.approverExternalId,
            decisionComment: String? = r.decisionComment,
            decidedAt: Instant? = r.decidedAt,
            appliedCommit: String? = r.appliedCommit,
            statusReason: String? = r.statusReason,
        ) = ProposalRow(
            r.id, r.operation, r.pageId, baseHash, targetPath, r.proposedContent, r.rationale, diffArtifact,
            status, r.authorIssuer, r.authorExternalId, r.authorLabel, approverIssuer, approverExternalId,
            decisionComment, r.createdAt, decidedAt, appliedCommit, statusReason,
        )

        private fun cas(id: com.plainbase.domain.page.ProposalId, from: ProposalStatus, mutate: (ProposalRow) -> ProposalRow): Boolean {
            val idx = rows.indexOfFirst { it.id == id && it.status == from }
            if (idx < 0) return false
            rows[idx] = mutate(rows[idx])
            return true
        }

        override fun reject(
            id: com.plainbase.domain.page.ProposalId,
            approverIssuer: String,
            approverExternalId: String,
            comment: String?,
            at: Instant,
        ): Boolean = cas(id, ProposalStatus.PENDING) {
            replace(
                it,
                ProposalStatus.REJECTED,
                approverIssuer = approverIssuer,
                approverExternalId = approverExternalId,
                decisionComment = comment,
                decidedAt = at,
            )
        }

        override fun claimApplying(id: com.plainbase.domain.page.ProposalId): Boolean {
            claimApplyingCalls++
            return cas(id, ProposalStatus.PENDING) { replace(it, ProposalStatus.APPLYING) }
        }

        override fun reconcileApplyingToPending() = 0

        override fun markApplied(
            id: com.plainbase.domain.page.ProposalId,
            appliedCommit: String?,
            statusReason: String?,
            approverIssuer: String?,
            approverExternalId: String?,
            at: Instant,
        ): Boolean = cas(id, ProposalStatus.APPLYING) {
            replace(
                it,
                ProposalStatus.APPLIED,
                approverIssuer = approverIssuer,
                approverExternalId = approverExternalId,
                decidedAt = at,
                appliedCommit = appliedCommit,
                statusReason = statusReason,
            )
        }

        override fun markConflicted(
            id: com.plainbase.domain.page.ProposalId,
            statusReason: String?,
            approverIssuer: String?,
            approverExternalId: String?,
            at: Instant,
        ): Boolean = cas(id, ProposalStatus.APPLYING) {
            replace(
                it,
                ProposalStatus.CONFLICTED,
                approverIssuer = approverIssuer,
                approverExternalId = approverExternalId,
                decidedAt = at,
                statusReason = statusReason,
            )
        }

        override fun markFailed(
            id: com.plainbase.domain.page.ProposalId,
            statusReason: String,
            approverIssuer: String?,
            approverExternalId: String?,
            at: Instant,
        ): Boolean = cas(id, ProposalStatus.APPLYING) {
            replace(
                it,
                ProposalStatus.FAILED,
                approverIssuer = approverIssuer,
                approverExternalId = approverExternalId,
                decidedAt = at,
                statusReason = statusReason,
            )
        }

        override fun failPending(
            id: com.plainbase.domain.page.ProposalId,
            statusReason: String,
            approverIssuer: String?,
            approverExternalId: String?,
            at: Instant,
        ): Boolean = cas(id, ProposalStatus.PENDING) {
            replace(
                it,
                ProposalStatus.FAILED,
                approverIssuer = approverIssuer,
                approverExternalId = approverExternalId,
                decidedAt = at,
                statusReason = statusReason,
            )
        }

        override fun failConflicted(id: com.plainbase.domain.page.ProposalId, statusReason: String, at: Instant): Boolean =
            cas(id, ProposalStatus.CONFLICTED) { replace(it, ProposalStatus.FAILED, decidedAt = at, statusReason = statusReason) }

        override fun rebaseToPending(
            id: com.plainbase.domain.page.ProposalId,
            baseHash: String,
            diffArtifact: String,
            targetPath: TreePath,
        ): Boolean =
            cas(id, ProposalStatus.CONFLICTED) {
                // Mirrors the prod query: a rebased-back-to-PENDING row re-pins target_path (the page may have MOVED)
                // + clears the decision metadata markConflicted stamped (approver_*, decided_at), so it is
                // contract-identical to a fresh PENDING row.
                replace(
                    it,
                    ProposalStatus.PENDING,
                    baseHash = baseHash,
                    diffArtifact = diffArtifact,
                    targetPath = targetPath,
                    statusReason = null,
                    approverIssuer = null,
                    approverExternalId = null,
                    decidedAt = null,
                )
            }

        override fun markPendingFromApplying(id: com.plainbase.domain.page.ProposalId): Boolean =
            cas(id, ProposalStatus.APPLYING) { replace(it, ProposalStatus.PENDING) }

        override fun allApplying(): List<ProposalRow> = rows.filter { it.status == ProposalStatus.APPLYING }
    }

    fun service(reader: ProposalBaseReader, repo: ProposalRepository = MemRepo()) =
        ProposalService(repo, citations, reader, TestProposalIdProvider(), clock) to repo

    test("proposeEdit resolves target_path from page_id and stores a PENDING row with a computed diff") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val (svc, repo) = service(reader)
        val outcome = svc.proposeEdit(grantForTests(), pageId, citations.contentHash(current), null, "# New\n".toByteArray(), "r", author)
        outcome.shouldBeInstanceOf<ProposeOutcome.Created>()
        val row = (repo as MemRepo).rows.single()
        row.status shouldBe ProposalStatus.PENDING
        row.targetPath shouldBe path // resolved from page_id, not a client value
        row.operation shouldBe ProposalOperation.EDIT
        (outcome.unifiedDiff.isNotEmpty()).shouldBeTrue()
    }

    test("an edit base_hash mismatch is stale_base and persists nothing (hole #2 mismatch branch)") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val (svc, repo) = service(reader)
        val outcome = svc.proposeEdit(grantForTests(), pageId, "sha256:" + "0".repeat(64), null, "# New\n".toByteArray(), "r", author)
        outcome shouldBe ProposeOutcome.StaleBase
        (repo as MemRepo).rows.shouldBeEmpty()
    }

    test("an edit whose page_id resolves to no published page is stale_base, nothing inserted (target-missing branch)") {
        val reader = FakeReader(pathById = emptyMap())
        val (svc, repo) = service(reader)
        svc.proposeEdit(grantForTests(), pageId, "sha256:" + "0".repeat(64), null, "x".toByteArray(), "r", author) shouldBe
            ProposeOutcome.StaleBase
        (repo as MemRepo).rows.shouldBeEmpty()
    }

    test("an edit whose currentBytes is null (target deleted) is stale_base, nothing inserted") {
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = emptyMap())
        val (svc, repo) = service(reader)
        svc.proposeEdit(grantForTests(), pageId, "sha256:" + "0".repeat(64), null, "x".toByteArray(), "r", author) shouldBe
            ProposeOutcome.StaleBase
        (repo as MemRepo).rows.shouldBeEmpty()
    }

    test("a client target_path disagreeing with the resolved path is InvalidRequest (C3)") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val (svc, repo) = service(reader)
        val outcome = svc.proposeEdit(
            grantForTests(),
            pageId,
            citations.contentHash(current),
            TreePath.require("other.md"),
            "# New\n".toByteArray(),
            "r",
            author,
        )
        outcome shouldBe ProposeOutcome.InvalidRequest
        (repo as MemRepo).rows.shouldBeEmpty()
    }

    test("proposeCreate builds a PENDING row over an empty base") {
        val (svc, repo) = service(FakeReader())
        val target = TreePath.require("guides/new.md")
        val outcome = svc.proposeCreate(createGrantForTests(), target, "# Brand New\n".toByteArray(), "r", author)
        outcome.shouldBeInstanceOf<ProposeOutcome.Created>()
        val row = (repo as MemRepo).rows.single()
        row.operation shouldBe ProposalOperation.CREATE
        row.pageId shouldBe null
        row.baseHash shouldBe null
        row.targetPath shouldBe target
    }

    test("the stored diff survives the live page converging on the proposed content (§0.13(i) freeze)") {
        val current = "# Old\n".toByteArray()
        val proposed = "# New\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val (svc, repo) = service(reader)
        val created = svc.proposeEdit(
            grantForTests(),
            pageId,
            citations.contentHash(current),
            null,
            proposed,
            "r",
            author,
        ) as ProposeOutcome.Created
        // The live content now EQUALS the proposed content.
        reader.bytesByPath = mapOf(path to proposed)
        val view = svc.get(created.id)!!
        // The STORED diff is still the non-empty base->proposed diff, NOT recomputed-empty.
        (view.row.diffArtifact.isNotEmpty()).shouldBeTrue()
        view.row.diffArtifact shouldBe created.unifiedDiff
    }

    test("base_drifted: a create flips to true when a content file occupies the target (hole #1)") {
        val target = TreePath.require("guides/new.md")
        val reader = FakeReader()
        val (svc, _) = service(reader)
        val created = svc.proposeCreate(createGrantForTests(), target, "# X\n".toByteArray(), "r", author) as ProposeOutcome.Created
        svc.get(created.id)!!.baseDrifted.shouldBeFalse()
        reader.occupiedPaths = setOf(target)
        svc.get(created.id)!!.baseDrifted.shouldBeTrue()
    }

    test("base_drifted: an edit is true when the live hash no longer matches the stored base_hash") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val (svc, _) = service(reader)
        val created = svc.proposeEdit(
            grantForTests(),
            pageId,
            citations.contentHash(current),
            null,
            "# New\n".toByteArray(),
            "r",
            author,
        ) as ProposeOutcome.Created
        svc.get(created.id)!!.baseDrifted.shouldBeFalse()
        reader.bytesByPath = mapOf(path to "# Changed underneath\n".toByteArray())
        svc.get(created.id)!!.baseDrifted.shouldBeTrue()
    }

    test("reject of a PENDING returns Rejected; a second reject is NotPending; an unknown id is NotFound") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val (svc, _) = service(reader)
        val created = svc.proposeEdit(
            grantForTests(),
            pageId,
            citations.contentHash(current),
            null,
            "# New\n".toByteArray(),
            "r",
            author,
        ) as ProposeOutcome.Created
        val rejected = svc.reject(com.plainbase.domain.principal.approveGrantForTests(), created.id, approver, "nope")
        rejected.shouldBeInstanceOf<RejectOutcome.Rejected>()
        rejected.view.row.status shouldBe ProposalStatus.REJECTED
        svc.reject(com.plainbase.domain.principal.approveGrantForTests(), created.id, approver, null) shouldBe RejectOutcome.NotPending
        svc.reject(
            approveGrantForTests(),
            com.plainbase.domain.page.ProposalId.require("01900000-0000-7000-9000-000000000099"),
            approver,
            null,
        ) shouldBe
            RejectOutcome.NotFound
    }

    // ---- P1b apply (WI-4) ----------------------------------------------------------------------------

    /** A counting fake writer that returns a fixed WriteOutcome and records every invocation. */
    class FakeWriter(private val outcome: WriteOutcome) : ProposalContentWriter {
        var calls = 0
        override fun write(row: ProposalRow, author: CommitIdentity, committer: CommitIdentity): WriteOutcome {
            calls++
            return outcome
        }
    }

    val proposed = "# New\n".toByteArray()

    fun pendingEdit(svc: ProposalService): com.plainbase.domain.page.ProposalId {
        val current = "# Old\n".toByteArray()
        return (
            svc.proposeEdit(
                grantForTests(),
                pageId,
                citations.contentHash(current),
                null,
                proposed,
                "r",
                author,
            ) as ProposeOutcome.Created
            ).id
    }

    fun editService(): Pair<ProposalService, MemRepo> {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val repo = MemRepo()
        return ProposalService(repo, citations, reader, TestProposalIdProvider(), clock) to repo
    }

    test("apply: Written -> Applied; the row is APPLIED with the commit") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        val writer = FakeWriter(WriteOutcome.Written(newHash = "sha256:" + "a".repeat(64), commit = "abc"))
        val outcome = svc.apply(approveGrantForTests(), id, approver, writer)
        outcome.shouldBeInstanceOf<ApplyOutcome.Applied>()
        outcome.commit shouldBe "abc"
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.APPLIED
            it.appliedCommit shouldBe "abc"
            it.statusReason shouldBe null
        }
    }

    test("apply: WrittenButUnindexed -> Applied + reindexDeferred; applied_commit null + status_reason reindex_deferred") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        val writer = FakeWriter(WriteOutcome.WrittenButUnindexed(newHash = "sha256:" + "a".repeat(64), cause = "fts boom"))
        val outcome = svc.apply(approveGrantForTests(), id, approver, writer)
        outcome.shouldBeInstanceOf<ApplyOutcome.Applied>()
        outcome.reindexDeferred.shouldBeTrue()
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.APPLIED
            it.appliedCommit shouldBe null
            it.statusReason shouldBe
                "reindex_deferred"
        }
    }

    test("apply: Conflict (drift) -> Conflicted (rebasable)") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        val writer = FakeWriter(WriteOutcome.Conflict("content_changed", "x", "sha256:" + "b".repeat(64), path))
        svc.apply(approveGrantForTests(), id, approver, writer).shouldBeInstanceOf<ApplyOutcome.Conflicted>()
        repo.findById(id)!!.status shouldBe ProposalStatus.CONFLICTED
    }

    test("apply: idempotent-replay Conflict (currentHash == hash(proposed)) -> Applied, not Conflicted") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        val writer = FakeWriter(WriteOutcome.Conflict("content_changed", "x", citations.contentHash(proposed), path))
        svc.apply(approveGrantForTests(), id, approver, writer).shouldBeInstanceOf<ApplyOutcome.Applied>()
        repo.findById(id)!!.status shouldBe ProposalStatus.APPLIED
    }

    test("apply: Unreadable -> Failed with the STABLE status_reason 'unreadable' (no raw cause)") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        val writer = FakeWriter(WriteOutcome.Unreadable(cause = "/secret/path: permission denied"))
        val outcome = svc.apply(approveGrantForTests(), id, approver, writer)
        outcome.shouldBeInstanceOf<ApplyOutcome.Failed>()
        outcome.reason shouldBe "unreadable"
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.FAILED
            it.statusReason shouldBe "unreadable"
        }
    }

    test("apply: UnsupportedEdit -> Failed") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        val writer = FakeWriter(WriteOutcome.UnsupportedEdit(field = "id"))
        val outcome = svc.apply(approveGrantForTests(), id, approver, writer)
        outcome.shouldBeInstanceOf<ApplyOutcome.Failed>()
        outcome.reason shouldContain "unsupported_edit"
        repo.findById(id)!!.status shouldBe ProposalStatus.FAILED
    }

    test("apply of a CREATE -> CreateUnsupported via failPending; the writer is NEVER invoked, claimApplying NEVER called") {
        val (svc, repo) = service(FakeReader())
        repo as MemRepo
        val createId = (
            svc.proposeCreate(
                createGrantForTests(),
                TreePath.require("guides/new.md"),
                "# X\n".toByteArray(),
                "r",
                author,
            ) as ProposeOutcome.Created
            ).id
        val writer = FakeWriter(WriteOutcome.Written("h", "c"))
        svc.apply(approveGrantForTests(), createId, approver, writer) shouldBe ApplyOutcome.CreateUnsupported
        writer.calls shouldBeExactly 0
        repo.claimApplyingCalls shouldBeExactly 0
        repo.findById(createId)!!.let {
            it.status shouldBe ProposalStatus.FAILED
            it.statusReason shouldBe "create_apply_unsupported"
        }
    }

    test("apply: a double-claim loser returns NotPending and the writer is NEVER invoked") {
        val (svc, repo) = editService()
        val id = pendingEdit(svc)
        svc.apply(approveGrantForTests(), id, approver, FakeWriter(WriteOutcome.Written("h", "c"))) // first wins -> APPLIED
        val loserWriter = FakeWriter(WriteOutcome.Written("h2", "c2"))
        svc.apply(approveGrantForTests(), id, approver, loserWriter) shouldBe ApplyOutcome.NotPending
        loserWriter.calls shouldBeExactly 0
        repo.findById(id)!!.appliedCommit shouldBe "c" // not overwritten
    }

    test("apply of an unknown id -> NotFound; of a non-PENDING row -> NotPending") {
        val (svc, _) = editService()
        svc.apply(
            approveGrantForTests(),
            com.plainbase.domain.page.ProposalId.require("01900000-0000-7000-9000-000000000099"),
            approver,
            FakeWriter(WriteOutcome.Written("h", "c")),
        ) shouldBe
            ApplyOutcome.NotFound
        val id = pendingEdit(svc)
        svc.apply(approveGrantForTests(), id, approver, FakeWriter(WriteOutcome.Written("h", "c"))) // -> APPLIED
        svc.apply(approveGrantForTests(), id, approver, FakeWriter(WriteOutcome.Written("h", "c"))) shouldBe ApplyOutcome.NotPending
    }

    /** A writer that THROWS (the abnormal path — an audit/store/index RuntimeException, NOT a normal WriteOutcome). */
    class ThrowingWriter : ProposalContentWriter {
        var calls = 0
        override fun write(row: ProposalRow, author: CommitIdentity, committer: CommitIdentity): WriteOutcome {
            calls++
            error("boom: the write path threw post-claim")
        }
    }

    test("apply: a thrown write (bytes did NOT land) rethrows AND un-wedges the row to PENDING (decidable again)") {
        // The live disk still holds the ORIGINAL bytes (the throw happened before any disk change).
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val id = pendingEdit(svc)
        val writer = ThrowingWriter()
        val thrown = shouldThrow<IllegalStateException> { svc.apply(approveGrantForTests(), id, approver, writer) }
        thrown.message shouldContain "boom"
        writer.calls shouldBeExactly 1
        // NOT left APPLYING: recovery saw disk != proposed -> back to PENDING, so a later approve/rebase can decide it.
        repo.findById(id)!!.status shouldBe ProposalStatus.PENDING
    }

    test("apply: a thrown write AFTER the bytes landed rethrows AND stamps APPLIED (recovered), not stuck APPLYING") {
        // The write reached disk (live bytes == proposed) but a later step (audit/index) threw before the terminal stamp.
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to "# Old\n".toByteArray()))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val id = pendingEdit(svc)
        val writer = object : ProposalContentWriter {
            override fun write(row: ProposalRow, author: CommitIdentity, committer: CommitIdentity): WriteOutcome {
                reader.bytesByPath = mapOf(path to proposed) // the bytes landed...
                error("boom: a post-write step threw") // ...then a later step blew up before the stamp.
            }
        }
        shouldThrow<IllegalStateException> { svc.apply(approveGrantForTests(), id, approver, writer) }
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.APPLIED
            it.statusReason shouldBe "recovered"
            it.appliedCommit shouldBe null
        }
    }

    // ---- P1b rebase (WI-8) ---------------------------------------------------------------------------

    test("rebase of a CONFLICTED edit re-pins base+diff over the intervening bytes; proposed_content unchanged; status PENDING") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val id = (
            svc.proposeEdit(
                grantForTests(),
                pageId,
                citations.contentHash(current),
                null,
                proposed,
                "r",
                author,
            ) as ProposeOutcome.Created
            ).id
        svc.apply(
            approveGrantForTests(),
            id,
            approver,
            FakeWriter(WriteOutcome.Conflict("content_changed", "x", "sha256:" + "b".repeat(64), path)),
        )
        // The CONFLICTED row carries the decision metadata markConflicted stamped at apply time.
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.CONFLICTED
            it.approverIssuer shouldBe approver.issuer
            it.approverExternalId shouldBe approver.externalId
            it.decidedAt.shouldNotBeNull()
        }
        // The page also MOVED since propose; the live intervening edit lives at the NEW path. rebase recomputes base
        // = those bytes (showing the clobber) AND re-pins target_path to the current path (not the stale propose-time
        // path) — fresh base_hash/diff must not pair with a stale target_path.
        val movedPath = TreePath.require("guides/relocated.md")
        val intervening = "# Intervening edit\n".toByteArray()
        reader.pathById = mapOf(pageId to movedPath)
        reader.bytesByPath = mapOf(movedPath to intervening)
        svc.rebase(approveGrantForTests(), id).shouldBeInstanceOf<RebaseOutcome.Rebased>()
        val row = repo.findById(id)!!
        row.status shouldBe ProposalStatus.PENDING
        row.baseHash shouldBe citations.contentHash(intervening)
        row.diffArtifact shouldBe unifiedDiff(intervening, proposed)
        row.proposedContent.contentEquals(proposed).shouldBeTrue()
        row.targetPath shouldBe movedPath // re-pinned to the CURRENT path, NOT the stale propose-time `path`
        // A rebased-back-to-PENDING row is contract-identical to a fresh PENDING row: the stale decision metadata
        // (approver_*, decided_at, status_reason) is CLEARED, so get_change shows it cleanly pending, not decided.
        row.approverIssuer.shouldBeNull()
        row.approverExternalId.shouldBeNull()
        row.decidedAt.shouldBeNull()
        row.statusReason.shouldBeNull()
    }

    test("rebase of a non-CONFLICTED row -> NotConflicted") {
        val (svc, _) = editService()
        val id = pendingEdit(svc)
        svc.rebase(approveGrantForTests(), id) shouldBe RebaseOutcome.NotConflicted
    }

    test("rebase whose pathOf returns null -> Gone AND the row is FAILED with status_reason rebase_target_gone") {
        val current = "# Old\n".toByteArray()
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to current))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val id = (
            svc.proposeEdit(
                grantForTests(),
                pageId,
                citations.contentHash(current),
                null,
                proposed,
                "r",
                author,
            ) as ProposeOutcome.Created
            ).id
        svc.apply(
            approveGrantForTests(),
            id,
            approver,
            FakeWriter(WriteOutcome.Conflict("content_changed", "x", "sha256:" + "b".repeat(64), path)),
        )
        // The page vanishes: pathOf now returns null.
        reader.pathById = emptyMap()
        svc.rebase(approveGrantForTests(), id) shouldBe RebaseOutcome.Gone
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.FAILED
            it.statusReason shouldBe "rebase_target_gone"
        }
    }

    // ---- P1b reconcileApplying (WI-6) ----------------------------------------------------------------

    /** Inserts a fresh PENDING edit row, then claims it APPLYING (simulating a crash-mid-apply, no terminal stamp). */
    fun insertApplying(repo: MemRepo, content: ByteArray): com.plainbase.domain.page.ProposalId {
        val id = TestProposalIdProvider().next()
        repo.insert(
            ProposalRow(
                id = id, operation = ProposalOperation.EDIT, pageId = pageId, baseHash = "sha256:" + "0".repeat(64),
                targetPath = path, proposedContent = content, rationale = "r", diffArtifact = "",
                status = ProposalStatus.PENDING, authorIssuer = "agent", authorExternalId = "pb_a", authorLabel = "ci",
                approverIssuer = null, approverExternalId = null, decisionComment = null,
                createdAt = clock.now(), decidedAt = null, appliedCommit = null, statusReason = null,
            ),
        )
        repo.claimApplying(id).shouldBeTrue()
        return id
    }

    test("reconcileApplying: disk == proposed at the current pageId path -> APPLIED with NULL approver + recovered + null commit") {
        // The disk bytes at the page's CURRENT path already equal the proposed bytes (the apply's write landed).
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to proposed))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val id = insertApplying(repo, proposed)
        svc.reconcileApplying()
        repo.findById(id)!!.let {
            it.status shouldBe ProposalStatus.APPLIED
            it.approverIssuer shouldBe null
            it.approverExternalId shouldBe null
            it.statusReason shouldBe "recovered"
            it.appliedCommit shouldBe null
        }
    }

    test("reconcileApplying: disk does NOT match (write did not land) -> PENDING; a non-APPLYING row is untouched") {
        val reader = FakeReader(pathById = mapOf(pageId to path), bytesByPath = mapOf(path to "# Different\n".toByteArray()))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val applyingId = insertApplying(repo, proposed)
        // A separate PENDING row that must be left untouched.
        val pendingId = (
            svc.proposeCreate(
                createGrantForTests(),
                TreePath.require("guides/new.md"),
                "# X\n".toByteArray(),
                "r",
                author,
            ) as ProposeOutcome.Created
            ).id
        svc.reconcileApplying()
        repo.findById(applyingId)!!.status shouldBe ProposalStatus.PENDING
        repo.findById(pendingId)!!.status shouldBe ProposalStatus.PENDING
    }

    test("reconcileApplying moved-page: disk landed at the NEW pageId path -> APPLIED (not mis-reconciled to PENDING)") {
        // The page MOVED: its pageId now resolves to a NEW path where the proposed bytes landed.
        val newPath = TreePath.require("guides/moved.md")
        val reader = FakeReader(pathById = mapOf(pageId to newPath), bytesByPath = mapOf(newPath to proposed))
        val repo = MemRepo()
        val svc = ProposalService(repo, citations, reader, TestProposalIdProvider(), clock)
        val id = insertApplying(repo, proposed) // its stored target_path is the STALE `path`, never read here
        svc.reconcileApplying()
        repo.findById(id)!!.status shouldBe ProposalStatus.APPLIED
    }
})
