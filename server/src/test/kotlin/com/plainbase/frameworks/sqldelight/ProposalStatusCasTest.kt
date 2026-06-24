package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Instant

/**
 * The FROZEN idempotency floor (consumed by P1b): two concurrent `claimApplying(id)` calls on ONE PENDING row —
 * EXACTLY ONE wins the conditional `UPDATE … WHERE status='PENDING'`. The two threads MUST share ONE repo over ONE
 * driver (the [ConcurrentRevokeSessionTest] construction): `createInMemoryDriver()` is PER-CONNECTION, so two
 * drivers would be two invisible empty DBs and this race would pass VACUOUSLY.
 */
class ProposalStatusCasTest : FunSpec({

    test("two concurrent claimApplying calls: exactly one wins, the row ends APPLYING") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightProposalRepository(DatabaseFactory.createDatabase(driver))
            val id = ProposalId.require("01900000-0000-7000-9000-000000000001")
            repo.insert(
                ProposalRow(
                    id = id,
                    operation = ProposalOperation.EDIT,
                    pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"),
                    baseHash = "sha256:" + "0".repeat(64),
                    targetPath = TreePath.require("a.md"),
                    proposedContent = "x".toByteArray(),
                    rationale = "r",
                    diffArtifact = "",
                    status = ProposalStatus.PENDING,
                    authorIssuer = "agent",
                    authorExternalId = "pb_a",
                    authorLabel = "ci",
                    approverIssuer = null,
                    approverExternalId = null,
                    decisionComment = null,
                    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                    decidedAt = null,
                    appliedCommit = null,
                    statusReason = null,
                ),
            )

            val wins = AtomicInteger(0)
            val a = thread { if (repo.claimApplying(id)) wins.incrementAndGet() }
            val b = thread { if (repo.claimApplying(id)) wins.incrementAndGet() }
            a.join()
            b.join()

            wins.get() shouldBe 1
            repo.findById(id)!!.status shouldBe ProposalStatus.APPLYING
        }
    }
})
