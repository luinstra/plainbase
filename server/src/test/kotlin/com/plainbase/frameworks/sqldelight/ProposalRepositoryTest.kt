package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * The P1a store round-trip + reject terminality + all() ordering + the reconcile SEAM, driving the REAL
 * [SqlDelightProposalRepository] over an in-memory SQLite DB (the `7.sqm` migration applies on create).
 */
class ProposalRepositoryTest : FunSpec({

    fun pending(idCounter: Int, createdAt: Instant, op: ProposalOperation = ProposalOperation.EDIT): ProposalRow = ProposalRow(
        id = ProposalId.require("01900000-0000-7000-9000-%012d".format(idCounter)),
        operation = op,
        pageId = if (op == ProposalOperation.EDIT) PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a") else null,
        baseHash = if (op == ProposalOperation.EDIT) "sha256:" + "0".repeat(64) else null,
        targetPath = TreePath.require("guides/deploy.md"),
        proposedContent = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello\r\n".toByteArray(),
        rationale = "fix a typo",
        diffArtifact = "@@ -1,1 +1,1 @@\n-a\n+b\n",
        status = ProposalStatus.PENDING,
        authorIssuer = "agent",
        authorExternalId = "pb_abc",
        authorLabel = "ci-bot",
        approverIssuer = null,
        approverExternalId = null,
        decisionComment = null,
        createdAt = createdAt,
        decidedAt = null,
        appliedCommit = null,
        statusReason = null,
    )

    fun withRepo(block: (SqlDelightProposalRepository) -> Unit) {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightProposalRepository(DatabaseFactory.createDatabase(driver)))
        }
    }

    test("a stored proposal round-trips every column (proposed_content byte-identical)") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            val read = repo.findById(row.id).shouldNotBeNull()
            read.operation shouldBe ProposalOperation.EDIT
            read.pageId shouldBe row.pageId
            read.baseHash shouldBe row.baseHash
            read.targetPath shouldBe row.targetPath
            read.proposedContent.contentEquals(row.proposedContent).shouldBeTrue()
            read.rationale shouldBe "fix a typo"
            read.diffArtifact shouldBe row.diffArtifact
            read.status shouldBe ProposalStatus.PENDING
            read.authorIssuer shouldBe "agent"
            read.authorExternalId shouldBe "pb_abc"
            read.authorLabel shouldBe "ci-bot"
            read.approverIssuer.shouldBeNull()
            read.decidedAt.shouldBeNull()
            read.appliedCommit.shouldBeNull()
        }
    }

    test("all() returns proposals newest-first, stable for same-millis inserts via the id DESC tie-break") {
        withRepo { repo ->
            val t = Instant.fromEpochMilliseconds(1_700_000_000_000)
            repo.insert(pending(1, t))
            repo.insert(pending(2, t))
            repo.insert(pending(3, t.plus(kotlin.time.Duration.parse("1s"))))
            // Newest createdAt first; for the same millis, id DESC (id 2 before id 1).
            repo.all().map { it.id.value } shouldContainExactly listOf(
                "01900000-0000-7000-9000-000000000003",
                "01900000-0000-7000-9000-000000000002",
                "01900000-0000-7000-9000-000000000001",
            )
        }
    }

    test("reject flips PENDING -> REJECTED once; a second reject of the now-terminal row returns false") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            val at = Instant.fromEpochMilliseconds(1_700_000_100_000)
            repo.reject(row.id, "builtin", "alice", "no thanks", at).shouldBeTrue()
            val rejected = repo.findById(row.id).shouldNotBeNull()
            rejected.status shouldBe ProposalStatus.REJECTED
            rejected.approverIssuer shouldBe "builtin"
            rejected.approverExternalId shouldBe "alice"
            rejected.decisionComment shouldBe "no thanks"
            rejected.decidedAt shouldBe at
            repo.reject(row.id, "builtin", "alice", null, at).shouldBeFalse()
        }
    }

    test("reconcileApplyingToPending returns an APPLYING row to PENDING and leaves terminal rows untouched") {
        withRepo { repo ->
            val applying = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            val rejected = pending(2, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(applying)
            repo.insert(rejected)
            repo.claimApplying(applying.id).shouldBeTrue()
            repo.reject(rejected.id, "builtin", "alice", null, Instant.fromEpochMilliseconds(1_700_000_100_000)).shouldBeTrue()

            repo.reconcileApplyingToPending() shouldBe 1
            repo.findById(applying.id).shouldNotBeNull().status shouldBe ProposalStatus.PENDING
            repo.findById(rejected.id).shouldNotBeNull().status shouldBe ProposalStatus.REJECTED
        }
    }

    test("claimApplying is a CAS: a PENDING row claims once, then a re-claim of the now-APPLYING row returns false") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            repo.claimApplying(row.id).shouldBeTrue()
            repo.claimApplying(row.id).shouldBeFalse()
            repo.findById(row.id).shouldNotBeNull().status shouldBe ProposalStatus.APPLYING
        }
    }

    // ---- P1b terminal/rebase CAS round-trips ---------------------------------------------------------
    val at = Instant.fromEpochMilliseconds(1_700_000_100_000)

    test("markApplied flips APPLYING -> APPLIED once, stamping commit/reason/approver; a second markApplied is false") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            repo.claimApplying(row.id).shouldBeTrue()
            repo.markApplied(row.id, "deadbeef", "reindex_deferred", "builtin", "alice", at).shouldBeTrue()
            val applied = repo.findById(row.id).shouldNotBeNull()
            applied.status shouldBe ProposalStatus.APPLIED
            applied.appliedCommit shouldBe "deadbeef"
            applied.statusReason shouldBe "reindex_deferred"
            applied.approverIssuer shouldBe "builtin"
            applied.approverExternalId shouldBe "alice"
            applied.decidedAt shouldBe at
            repo.markApplied(row.id, "other", null, "builtin", "alice", at).shouldBeFalse()
        }
    }

    test("markApplied with a NULL approver pair stamps APPLIED with approver columns NULL (the recovery shape)") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            repo.claimApplying(row.id).shouldBeTrue()
            repo.markApplied(
                row.id,
                appliedCommit = null,
                statusReason = "recovered",
                approverIssuer = null,
                approverExternalId = null,
                at = at,
            ).shouldBeTrue()
            val applied = repo.findById(row.id).shouldNotBeNull()
            applied.status shouldBe ProposalStatus.APPLIED
            applied.appliedCommit.shouldBeNull()
            applied.statusReason shouldBe "recovered"
            applied.approverIssuer.shouldBeNull()
            applied.approverExternalId.shouldBeNull()
        }
    }

    test("markConflicted and markFailed flip APPLYING -> terminal once; a second call is false") {
        withRepo { repo ->
            val c = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            val f = pending(2, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(c)
            repo.insert(f)
            repo.claimApplying(c.id).shouldBeTrue()
            repo.claimApplying(f.id).shouldBeTrue()
            repo.markConflicted(c.id, "content_changed", "builtin", "alice", at).shouldBeTrue()
            repo.markFailed(f.id, "unreadable", "builtin", "alice", at).shouldBeTrue()
            repo.findById(c.id).shouldNotBeNull().let {
                it.status shouldBe ProposalStatus.CONFLICTED
                it.statusReason shouldBe
                    "content_changed"
            }
            repo.findById(f.id).shouldNotBeNull().let {
                it.status shouldBe ProposalStatus.FAILED
                it.statusReason shouldBe "unreadable"
            }
            repo.markConflicted(c.id, "x", "builtin", "alice", at).shouldBeFalse()
            repo.markFailed(f.id, "x", "builtin", "alice", at).shouldBeFalse()
        }
    }

    test("failPending flips PENDING -> FAILED once; false on the now-FAILED row AND on an APPLYING row (create never enters APPLYING)") {
        withRepo { repo ->
            val create = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000), op = ProposalOperation.CREATE)
            repo.insert(create)
            repo.failPending(create.id, "create_apply_unsupported", "builtin", "alice", at).shouldBeTrue()
            repo.findById(create.id).shouldNotBeNull().let {
                it.status shouldBe ProposalStatus.FAILED
                it.statusReason shouldBe
                    "create_apply_unsupported"
            }
            repo.failPending(create.id, "x", "builtin", "alice", at).shouldBeFalse()
            // An APPLYING row never matches failPending's WHERE status='PENDING'.
            val edit = pending(2, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(edit)
            repo.claimApplying(edit.id).shouldBeTrue()
            repo.failPending(edit.id, "x", "builtin", "alice", at).shouldBeFalse()
        }
    }

    test("failConflicted flips CONFLICTED -> FAILED once; false on a non-CONFLICTED row") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            repo.failConflicted(row.id, "rebase_target_gone", at).shouldBeFalse() // still PENDING
            repo.claimApplying(row.id).shouldBeTrue()
            repo.markConflicted(row.id, "content_changed", "builtin", "alice", at).shouldBeTrue()
            repo.failConflicted(row.id, "rebase_target_gone", at).shouldBeTrue()
            repo.findById(row.id).shouldNotBeNull().let {
                it.status shouldBe ProposalStatus.FAILED
                it.statusReason shouldBe
                    "rebase_target_gone"
            }
            repo.failConflicted(row.id, "x", at).shouldBeFalse()
        }
    }

    test("rebaseToPending flips CONFLICTED -> PENDING once, re-pinning base/diff + leaving proposed_content byte-identical") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            repo.claimApplying(row.id).shouldBeTrue()
            repo.markConflicted(row.id, "content_changed", "builtin", "alice", at).shouldBeTrue()
            // The pre-rebase CONFLICTED row carries the decision metadata markConflicted stamped.
            repo.findById(row.id).shouldNotBeNull().let {
                it.approverIssuer shouldBe "builtin"
                it.approverExternalId shouldBe "alice"
                it.decidedAt shouldBe at
            }
            // The page MOVED since propose: rebase re-pins target_path to the current (relocated) path.
            val movedPath = TreePath.require("guides/relocated.md")
            repo.rebaseToPending(row.id, "sha256:" + "a".repeat(64), "@@ -1,1 +1,1 @@\n-x\n+y\n", movedPath).shouldBeTrue()
            val rebased = repo.findById(row.id).shouldNotBeNull()
            rebased.status shouldBe ProposalStatus.PENDING
            rebased.baseHash shouldBe "sha256:" + "a".repeat(64)
            rebased.diffArtifact shouldBe "@@ -1,1 +1,1 @@\n-x\n+y\n"
            rebased.targetPath shouldBe movedPath // re-pinned to the CURRENT path, NOT the stale propose-time path
            rebased.statusReason.shouldBeNull()
            // A rebased-back-to-PENDING row is contract-identical to a fresh PENDING row: the decision metadata
            // markConflicted stamped (approver_*, decided_at) is CLEARED, not stale.
            rebased.approverIssuer.shouldBeNull()
            rebased.approverExternalId.shouldBeNull()
            rebased.decidedAt.shouldBeNull()
            rebased.proposedContent.contentEquals(row.proposedContent).shouldBeTrue()
            repo.rebaseToPending(row.id, "sha256:" + "b".repeat(64), "z", movedPath).shouldBeFalse()
        }
    }

    test("markPendingFromApplying flips APPLYING -> PENDING once (per-row); false on a non-APPLYING row") {
        withRepo { repo ->
            val row = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(row)
            repo.markPendingFromApplying(row.id).shouldBeFalse() // PENDING, not APPLYING
            repo.claimApplying(row.id).shouldBeTrue()
            repo.markPendingFromApplying(row.id).shouldBeTrue()
            repo.findById(row.id).shouldNotBeNull().status shouldBe ProposalStatus.PENDING
        }
    }

    test("allApplying returns only APPLYING rows with full proposed_content + pageId") {
        withRepo { repo ->
            val applying = pending(1, Instant.fromEpochMilliseconds(1_700_000_000_000))
            val pendingRow = pending(2, Instant.fromEpochMilliseconds(1_700_000_000_000))
            repo.insert(applying)
            repo.insert(pendingRow)
            repo.claimApplying(applying.id).shouldBeTrue()
            val rows = repo.allApplying()
            rows.map { it.id.value } shouldContainExactly listOf(applying.id.value)
            rows.single().pageId shouldBe applying.pageId
            rows.single().proposedContent.contentEquals(applying.proposedContent).shouldBeTrue()
        }
    }
})
