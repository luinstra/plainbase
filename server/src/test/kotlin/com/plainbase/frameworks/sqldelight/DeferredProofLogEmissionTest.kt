package com.plainbase.frameworks.sqldelight

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * What the deferred proof-apply log accumulates, and what it renders once the transaction has returned.
 *
 * Every case that asserts an ABSENCE or a ZERO carries its positive control IN THE SAME BLOCK, varying exactly one
 * thing: a control proving only that the emitter RAN would not prove it had anything to emit, which is how this
 * shape of test passes green against the bug it exists to catch.
 */
class DeferredProofLogEmissionTest : FunSpec({

    test("byte identity at one occurrence: every record renders exactly the text the in-transaction call wrote") {
        withRepository { retirements ->
            val stale = retirements.staleRoot("stale-one")
            val unavailable = retirements.freshRoot("unavail-one")
            val refuted = retirements.freshRoot("refuted-one")
            val advanceOk = retirements.freshRoot("adv-ok")
            val advanceWithheld = retirements.freshRoot("adv-withheld")
            val advanceStale = retirements.staleRoot("adv-stale")
            val refutedRef = bindingRef(3)
            val proofs = listOf(
                proofAt(stale, observation = 1, epoch = 0, covers = setOf(bindingRef(1))),
                proofAt(unavailable, observation = 1, epoch = 0, covers = setOf(bindingRef(2))),
                proofAt(refuted, observation = 1, epoch = 0, covers = setOf(refutedRef)),
            )
            val advances = listOf(
                GitCheckpointAdvance(advanceOk, ObservationId(1), BindingEpoch(0), "abc1234"),
                GitCheckpointAdvance(advanceWithheld, ObservationId(1), BindingEpoch(0), "def5678"),
                GitCheckpointAdvance(advanceStale, ObservationId(1), BindingEpoch(0), "9abcdef"),
            )

            withCapture(Level.INFO) { messages ->
                retirements.applyProofs(
                    proofs = proofs,
                    witnessed = setOf(RootedPageId(refuted, refutedRef.id)),
                    unavailableNow = { setOf(unavailable, advanceWithheld) },
                    advances = advances,
                )

                messages() shouldContainExactlyInAnyOrder listOf(
                    staleLine("stale-one", minted = "1/binding-epoch 0", current = "2/0"),
                    unavailableLine("unavail-one"),
                    refutedLine("refuted-one"),
                    advanceWithheldLine("adv-withheld"),
                    advanceStaleLine("adv-stale", minted = "1/binding-epoch 0", current = "2/0"),
                    summaryLine(proofCount = 3, appliedCount = 0, advanceClause = "; git checkpoint advanced: 'adv-ok' -> abc1234"),
                )
            }
        }
    }

    test("dedupe suffix: proofs sharing one key render ONE line carrying the occurrence count") {
        withRepository { retirements ->
            val stale = retirements.staleRoot("stale-dupe")
            // Same reason, root, source and token pair, so one key: only the covers differ, and covers are not keyed.
            val proofs = (1..3).map { proofAt(stale, observation = 1, epoch = 0, covers = setOf(bindingRef(it))) }

            withCapture(Level.INFO) { messages ->
                retirements.applyProofs(proofs, witnessed = emptySet(), unavailableNow = { emptySet() })

                // The EXACT count, never a containment check: "one line" is a no-duplicates claim, and a containment
                // check is satisfied by a list that also holds the duplicates.
                messages() shouldContainExactlyInAnyOrder listOf(
                    staleLine("stale-dupe", minted = "1/binding-epoch 0", current = "2/0", suffix = " (x 3 proof(s))"),
                    summaryLine(proofCount = 3, appliedCount = 0),
                )
            }
        }
    }

    test("refusal summing: several partial refutations of one root and source render one SUMMED line") {
        withRepository { retirements ->
            val root = retirements.freshRoot("refusal-sum")
            val witnessed = mutableSetOf<RootedPageId>()
            val proofs = (1..3).map { index ->
                val read = bindingRef(index * 10)
                val gone = bindingRef(index * 10 + 1)
                witnessed += RootedPageId(root, read.id)
                proofAt(root, observation = 1, epoch = 0, covers = setOf(read, gone))
            }

            withCapture(Level.INFO) { messages ->
                retirements.applyProofs(proofs, witnessed = witnessed, unavailableNow = { emptySet() })

                messages() shouldContainExactlyInAnyOrder listOf(
                    refusalLine("refusal-sum", delta = 3, suffix = " (x 3 proof(s))"),
                    summaryLine(proofCount = 3, appliedCount = 0),
                )
            }
        }
    }

    test("cap overflow: the budget is JOINT across notes and refusals, and the overflow is announced") {
        withRepository { retirements ->
            val fixture = crossedBudgetFixture(retirements)

            withCapture(Level.INFO) { messages ->
                retirements.applyProofs(fixture.proofs, fixture.witnessed, unavailableNow = { emptySet() })

                val captured = messages()
                val records = captured.filterNot { it.startsWith("suppressed ") || it.startsWith("applied ") }
                records.size shouldBe 64
                captured.size shouldBe 66
                captured.filter { it.startsWith("refusing ") } shouldContainExactly
                    listOf(refusalLine("refusal-a", delta = 1))
                // Two separate 64-entry budgets would have admitted the second refusal key instead of suppressing it.
                captured.any { it.contains("'refusal-b'") } shouldBe false
                captured.any { it == overflowLine(suppressed = 1) } shouldBe true
            }
        }
    }

    test("null-token staleness is dropped at ACCUMULATION, so it never spends a budget slot") {
        withRepository { retirements ->
            // (c) 64 row-less fillers FIRST: filtered at emission they would still consume the joint budget, and the
            // one valid record after them would become key 65 and be suppressed.
            val rowless = RootName.require("rowless")
            val fillers = (1..64).map { proofAt(rowless, observation = it.toLong(), epoch = 0, covers = setOf(bindingRef(it))) }
            // (b) the positive control: the same shape over a root whose observation row EXISTS with mismatched tokens.
            val stale = retirements.staleRoot("rowful")
            val valid = proofAt(stale, observation = 1, epoch = 0, covers = setOf(bindingRef(500)))

            withCapture(Level.INFO) { messages ->
                retirements.applyProofs(fillers + valid, witnessed = emptySet(), unavailableNow = { emptySet() })

                messages() shouldContainExactlyInAnyOrder listOf(
                    staleLine("rowful", minted = "1/binding-epoch 0", current = "2/0"),
                    summaryLine(proofCount = 65, appliedCount = 0),
                )
            }
        }
    }

    test("a rolled-back transaction emits nothing, and the same batch that commits emits its records") {
        withRepository { retirements ->
            val first = retirements.staleRoot("roll-one")
            val second = retirements.staleRoot("roll-two")
            val proofs = listOf(
                proofAt(first, observation = 1, epoch = 0, covers = setOf(bindingRef(1))),
                proofAt(second, observation = 1, epoch = 0, covers = setOf(bindingRef(2))),
            )

            withCapture(Level.INFO) { messages ->
                // Proof 1 records before the throw lands on proof 2's first `contains`, so the zero below is a claim
                // about EMISSION rather than about a batch that had nothing to say.
                shouldThrowAny {
                    retirements.applyProofs(proofs, witnessed = ThrowingWitness(throwOn = 2), unavailableNow = { emptySet() })
                }
                messages() shouldBe emptyList()

                retirements.applyProofs(proofs, witnessed = ThrowingWitness(throwOn = 0), unavailableNow = { emptySet() })
                messages() shouldContainExactlyInAnyOrder listOf(
                    staleLine("roll-one", minted = "1/binding-epoch 0", current = "2/0"),
                    staleLine("roll-two", minted = "1/binding-epoch 0", current = "2/0"),
                    summaryLine(proofCount = 2, appliedCount = 0),
                )
            }
        }
    }

    test("levels off at CONSTRUCTION accumulate nothing, even when the level is turned on inside the transaction") {
        withRepository { retirements ->
            val control = fourRecordBatch(retirements, suffix = "ctl")
            val measured = fourRecordBatch(retirements, suffix = "msr")

            withCapture(Level.DEBUG) { messages ->
                // (a) CONTROL: the identical batch shape DOES produce all four records when the levels are on.
                retirements.applyProofs(control.proofs, control.witnessed, unavailableNow = { setOf(control.unavailableRoot) })
                messages() shouldContainExactlyInAnyOrder listOf(
                    staleLine("stale-ctl", minted = "1/binding-epoch 0", current = "2/0"),
                    unavailableLine("unavail-ctl"),
                    refutedLine("refuted-ctl"),
                    refusalLine("refusal-ctl", delta = 1),
                    summaryLine(proofCount = 4, appliedCount = 0),
                )

                // (b) MEASURED: the control has already filled the appender, so isolate the runs or the zero below
                // cannot pass on any implementation.
                clearCapture()
                retirementLogbackLogger().level = Level.ERROR
                var flipObserved = false
                retirements.applyProofs(
                    proofs = measured.proofs,
                    witnessed = measured.witnessed,
                    unavailableNow = {
                        // applyProofs calls this as the transaction's FIRST statement, before any record could exist.
                        retirementLogbackLogger().level = Level.DEBUG
                        val live = LoggerFactory.getLogger(SqlDelightRetirementRepository::class.java)
                        flipObserved = live.isInfoEnabled && live.isWarnEnabled
                        setOf(measured.unavailableRoot)
                    },
                )
                flipObserved shouldBe true
                // An eagerly-capturing sink would hold four records here, and `emit` would push all of them past the
                // DEBUG filter the lambda just installed.
                messages() shouldBe emptyList()
            }
        }
    }

    test("emission order: notes, then refusal totals, then the overflow notice, then the pass summary") {
        withRepository { retirements ->
            val fixture = crossedBudgetFixture(retirements)
            val advanceOk = retirements.freshRoot("adv-ordered")
            val advances = listOf(GitCheckpointAdvance(advanceOk, ObservationId(1), BindingEpoch(0), "0ff1ce0"))

            withCapture(Level.INFO) { messages ->
                retirements.applyProofs(fixture.proofs, fixture.witnessed, unavailableNow = { emptySet() }, advances = advances)

                // Element for element, never a subsequence: a subsequence check is satisfied by a SHORTER list, so a
                // dropped line passes it and this case degrades into a weaker copy of the byte-identity one.
                messages() shouldContainExactly buildList {
                    fixture.mintedObservations.forEach {
                        add(staleLine("note-root", minted = "$it/binding-epoch 0", current = "2/0"))
                    }
                    add(refusalLine("refusal-a", delta = 1))
                    add(overflowLine(suppressed = 1))
                    add(
                        summaryLine(
                            proofCount = 65,
                            appliedCount = 0,
                            advanceClause = "; git checkpoint advanced: 'adv-ordered' -> 0ff1ce0",
                        ),
                    )
                }
            }
        }
    }
})

// ---- fixtures -------------------------------------------------------------------------------------------------

/**
 * A batch whose 63 distinct NOTE keys are followed by two distinct REFUSAL keys, so key 64 is the first refusal and
 * key 65 is the second. The refusal roots are DIFFERENT roots from the note root on purpose: `revoke` bumps a whole
 * root's observation, so a refusal proof sharing the stale root would itself be stale and land as a 64th NOTE,
 * leaving the joint budget untested.
 */
private class CrossedBudget(
    val proofs: List<AbsenceProof>,
    val witnessed: Set<RootedPageId>,
    val mintedObservations: List<Long>,
)

private fun crossedBudgetFixture(retirements: SqlDelightRetirementRepository): CrossedBudget {
    val noteRoot = retirements.staleRoot("note-root")
    // 100 upward, so no filler can accidentally MATCH the root's current 2/0 and take the fresh branch instead.
    val mintedObservations = (100L..162L).toList()
    val notes = mintedObservations.map { proofAt(noteRoot, observation = it, epoch = 0, covers = setOf(bindingRef(it.toInt()))) }
    val witnessed = mutableSetOf<RootedPageId>()
    val refusals = listOf("refusal-a", "refusal-b").mapIndexed { index, name ->
        val root = retirements.freshRoot(name)
        val read = bindingRef(900 + index * 2)
        val gone = bindingRef(901 + index * 2)
        witnessed += RootedPageId(root, read.id)
        proofAt(root, observation = 1, epoch = 0, covers = setOf(read, gone))
    }
    return CrossedBudget(notes + refusals, witnessed, mintedObservations)
}

/** One stale, one unavailable, one fully refuted and one partially refuted record, on four roots of their own. */
private class FourRecords(
    val proofs: List<AbsenceProof>,
    val witnessed: Set<RootedPageId>,
    val unavailableRoot: RootName,
)

private fun fourRecordBatch(retirements: SqlDelightRetirementRepository, suffix: String): FourRecords {
    val stale = retirements.staleRoot("stale-$suffix")
    val unavailable = retirements.freshRoot("unavail-$suffix")
    val refuted = retirements.freshRoot("refuted-$suffix")
    val refusal = retirements.freshRoot("refusal-$suffix")
    val refutedRef = bindingRef(700)
    val read = bindingRef(701)
    val gone = bindingRef(702)
    return FourRecords(
        proofs = listOf(
            proofAt(stale, observation = 1, epoch = 0, covers = setOf(bindingRef(703))),
            proofAt(unavailable, observation = 1, epoch = 0, covers = setOf(bindingRef(704))),
            proofAt(refuted, observation = 1, epoch = 0, covers = setOf(refutedRef)),
            proofAt(refusal, observation = 1, epoch = 0, covers = setOf(read, gone)),
        ),
        witnessed = setOf(RootedPageId(refuted, refutedRef.id), RootedPageId(refusal, read.id)),
        unavailableRoot = unavailable,
    )
}

/**
 * Arbitrary caller code inside the apply transaction: `AbsenceProof.survives` returns early unless the source is
 * INFERRED and the witness reports itself non-empty, so both are pinned here or the seam is never reached.
 */
private class ThrowingWitness(private val throwOn: Int) : Set<RootedPageId> {
    private var calls = 0
    override val size: Int = 1
    override fun isEmpty(): Boolean = false
    override fun iterator(): Iterator<RootedPageId> = emptyList<RootedPageId>().iterator()
    override fun containsAll(elements: Collection<RootedPageId>): Boolean = false

    override fun contains(element: RootedPageId): Boolean {
        calls++
        check(calls != throwOn) { "witnessed.contains failed on call $calls" }
        return false
    }
}

/** A root whose observation row EXISTS at 2/0 while a proof minted at 1/0 does not match it. */
private fun SqlDelightRetirementRepository.staleRoot(name: String): RootName =
    RootName.require(name).also {
        observation(it)
        revoke(it)
    }

/** A root whose observation row exists at 1/0, which is what a proof minted at 1/0 needs to be FRESH. */
private fun SqlDelightRetirementRepository.freshRoot(name: String): RootName =
    RootName.require(name).also { observation(it) }

private fun proofAt(root: RootName, observation: Long, epoch: Long, covers: Set<BindingRef>): AbsenceProof =
    AbsenceProof(root, ProofSource.EPOCH, ObservationId(observation), BindingEpoch(epoch), covers)

private fun bindingRef(index: Int): BindingRef =
    BindingRef(TreePath.require("p$index.md"), PageId.require("01900000-0000-7000-8000-%012d".format(index)))

// ---- harness --------------------------------------------------------------------------------------------------

private fun withRepository(body: (SqlDelightRetirementRepository) -> Unit) {
    val directory = Files.createTempDirectory("pb-deferred-proof-log")
    try {
        DatabaseFactory.createDriver(directory.resolve("plainbase.db")).use { driver ->
            body(SqlDelightRetirementRepository(DatabaseFactory.createDatabase(driver)))
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun retirementLogbackLogger(): Logger =
    LoggerFactory.getLogger(SqlDelightRetirementRepository::class.java) as Logger

private val captured = ListAppender<ILoggingEvent>()

private fun clearCapture() = captured.list.clear()

/**
 * Levels are set PER CASE and restored here, never spec-wide: one case needs them OFF at sink construction while
 * every other needs them on, and without an explicit set the coverage would silently follow ambient PLAINBASE_LOG_LEVEL.
 */
private fun withCapture(level: Level, body: (() -> List<String>) -> Unit) {
    val logger = retirementLogbackLogger()
    val previousLevel = logger.level
    captured.list.clear()
    captured.start()
    try {
        logger.level = level
        logger.addAppender(captured)
        body { captured.list.map { it.formattedMessage } }
    } finally {
        logger.detachAppender(captured)
        captured.stop()
        captured.list.clear()
        logger.level = previousLevel
    }
}

// ---- the expected texts, written out once ---------------------------------------------------------------------

private fun staleLine(root: String, minted: String, current: String, suffix: String = ""): String =
    "discarding a EPOCH proof for root '$root': it was minted under observation " +
        "$minted, and the root is now at " +
        "$current - the view it was minted from has been " +
        "revoked or a binding it covers was re-created, so it authorizes nothing$suffix"

private fun unavailableLine(root: String, suffix: String = ""): String =
    "discarding a EPOCH proof for root '$root': the root has been marked " +
        "unavailable since this pass gathered its evidence, so that evidence proves nothing about " +
        "the tree that is there now$suffix"

private fun refutedLine(root: String, suffix: String = ""): String =
    "root '$root''s EPOCH proof is REFUTED in full: this observation READ every id it " +
        "covers, and a page we are looking at is not a page that is absent$suffix"

private fun refusalLine(root: String, delta: Long, suffix: String = ""): String =
    "refusing $delta binding(s) of root '$root''s " +
        "EPOCH proof: this observation READ those ids somewhere, so they are not gone$suffix"

private fun advanceWithheldLine(root: String, suffix: String = ""): String =
    "withholding a GIT checkpoint advance for root '$root': it was marked unavailable " +
        "since the range was read, and a consumed range is never re-examined$suffix"

private fun advanceStaleLine(root: String, minted: String, current: String, suffix: String = ""): String =
    "discarding a GIT checkpoint advance for root '$root': it was minted under observation " +
        "$minted, and the root is now at " +
        "$current - the view it was minted from has been " +
        "revoked or a binding was re-created, so it advances nothing$suffix"

private fun summaryLine(proofCount: Int, appliedCount: Int, advanceClause: String = ""): String =
    "applied $proofCount absence proof(s): $appliedCount binding(s) retired$advanceClause"

private fun overflowLine(suppressed: Int, cap: Int = 64): String =
    "suppressed $suppressed deferred log record(s) with new keys: this pass reached the $cap-key cap"
