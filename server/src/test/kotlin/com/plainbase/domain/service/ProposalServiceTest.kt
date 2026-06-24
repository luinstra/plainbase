package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.ProposalSummaryRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
    val approver = ProposalApprover("builtin", "alice")
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

    /** A trivial in-memory ProposalRepository capturing inserts (no SQLite needed for the service logic tests). */
    class MemRepo : ProposalRepository {
        val rows = mutableListOf<ProposalRow>()
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
        override fun reject(
            id: com.plainbase.domain.page.ProposalId,
            approverIssuer: String,
            approverExternalId: String,
            comment: String?,
            at: Instant,
        ): Boolean {
            val idx = rows.indexOfFirst { it.id == id && it.status == ProposalStatus.PENDING }
            if (idx < 0) return false
            val r = rows[idx]
            rows[idx] = ProposalRow(
                r.id, r.operation, r.pageId, r.baseHash, r.targetPath, r.proposedContent, r.rationale, r.diffArtifact,
                ProposalStatus.REJECTED, r.authorIssuer, r.authorExternalId, r.authorLabel, approverIssuer,
                approverExternalId, comment, r.createdAt, at, r.appliedCommit,
            )
            return true
        }
        override fun claimApplying(id: com.plainbase.domain.page.ProposalId) = false
        override fun reconcileApplyingToPending() = 0
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
            com.plainbase.domain.principal.approveGrantForTests(),
            com.plainbase.domain.page.ProposalId.require("01900000-0000-7000-9000-000000000099"),
            approver,
            null,
        ) shouldBe
            RejectOutcome.NotFound
    }
})
