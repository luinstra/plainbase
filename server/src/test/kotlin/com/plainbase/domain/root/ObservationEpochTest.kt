package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.service.UuidV7IdProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The C2 state machine, on its own: **an OPENING scan proves nothing; only a CONFIRMATION scan can.**
 *
 * Every row here is about the ASYMMETRY, because that is the whole safety argument. An epoch must have SEEN a page
 * before it may say the page is gone - so a re-registration on a bare mountpoint opens an epoch that is powerless
 * over the corpus it never witnessed, and no break, restart or rebind can be laundered into retroactive authority.
 */
class ObservationEpochTest : FunSpec({

    val handbook = RootName.require("handbook")
    val extra = RootName.require("extra")

    val ids = UuidV7IdProvider()

    fun path(value: String) = TreePath.require(value)
    fun binding(value: String) = BindingRef(path(value), ids.next())

    /** The durable token, in memory: [revoke] mints, [applyProofs] is nobody's business here. */
    class Tokens : RetirementRepository {
        private val tokens = mutableMapOf<RootName, ObservationId>()
        override fun applyProofs(
            proofs: List<AbsenceProof>,
            witnessed: Set<RootedPageId>,
            unavailable: Set<RootName>,
            advances: List<GitCheckpointAdvance>,
        ) = emptySet<RootedPageId>()
        override fun gitHead(root: RootName): String? = null
        override fun observation(root: RootName) = tokens.getOrPut(root) { ObservationId(1) }
        override fun bindingEpoch(root: RootName) = BindingEpoch(0)
        override fun observations() = tokens.toMap()
        override fun revoke(root: RootName): ObservationId = observation(root).next().also { tokens[root] = it }
    }

    fun epochOver(convergence: RootConvergence = RootConvergence()) = ObservationEpoch(Tokens(), convergence)

    test("an OPENING scan mints NO proofs - it has nothing to compare against, and retroactive authority IS the decoy hole") {
        val epochs = epochOver().apply { observing(handbook) }
        val stranded = setOf(binding("guides/deploy.md"), binding("guides/rollback.md"))

        // 500 durable rows, a 3-page tree: the handbook decoy. The scan is complete, the coverage is whole, the tree
        // is stable - and it STILL proves nothing, because this epoch has never seen the pages it is being asked about.
        epochs.scanned(handbook, witnessed = setOf(path("decoy.md")), durable = stranded).shouldBeNull()
        epochs.isOpen(handbook) shouldBe true
    }

    test("a CONFIRMATION scan reaps a page deleted since the base - THIS is what makes an online delete converge") {
        val epochs = epochOver().apply { observing(handbook) }
        val deploy = binding("guides/deploy.md")
        val rollback = binding("guides/rollback.md")
        val durable = setOf(deploy, rollback)

        epochs.scanned(handbook, witnessed = setOf(deploy.path, rollback.path), durable = durable) // the base
        val proof = epochs.scanned(handbook, witnessed = setOf(rollback.path), durable = durable).shouldNotBeNull()

        proof.root shouldBe handbook
        proof.source shouldBe ProofSource.EPOCH
        proof.covers shouldContainExactly setOf(deploy)
    }

    test("a BREAK then a re-open grants NO authority over pages the new epoch never witnessed") {
        val epochs = epochOver().apply { observing(handbook) }
        val deploy = binding("guides/deploy.md")
        val durable = setOf(deploy)
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable) // an epoch that HAS seen it

        // The break is the whole point: past it, this observation has a hole in it, and a hole is exactly as
        // consistent with an unmount as with an `rm`. So the next PASS re-OPENS rather than confirming...
        epochs.broke(handbook, BreakCause.OVERFLOW)
        epochs.isOpen(handbook) shouldBe false

        // ...and the re-opened epoch has witnessed NOTHING of the page, so it cannot say a word about its absence -
        // however many times it looks. The decoy hole stays closed.
        epochs.scanned(handbook, witnessed = emptySet(), durable = durable).shouldBeNull()
        // An epoch really was re-opened: without this the rows above would also hold if nothing ever opened again,
        // which is the same assertion passing for the opposite reason.
        epochs.isOpen(handbook) shouldBe true
        epochs.scanned(handbook, witnessed = emptySet(), durable = durable).shouldBeNull()
    }

    test("the SCOPING rule: a durable row absent from the base scan stays in limbo no matter how healthy the epoch is") {
        val epochs = epochOver().apply { observing(handbook) }
        val seen = binding("guides/deploy.md")
        val neverSeen = binding("guides/never-witnessed.md") // a row on a submount that was down at base time
        val durable = setOf(seen, neverSeen)

        epochs.scanned(handbook, witnessed = setOf(seen.path), durable = durable) // base: witnesses ONE of the two
        val proof = epochs.scanned(handbook, witnessed = emptySet(), durable = durable).shouldNotBeNull()

        // The one it READ is proven gone. The one it never read is not evidence of anything, and no amount of
        // subsequent good health promotes it - the epoch's authority is scoped to its witness set, permanently.
        proof.covers shouldContainExactly setOf(seen)
    }

    test("a root NOBODY IS WATCHING earns no epoch at all - two scans with an `rm` between them are just two scans") {
        val epochs = epochOver() // no observing(): the watcher was never installed
        val deploy = binding("guides/deploy.md")

        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = setOf(deploy)).shouldBeNull()
        epochs.isOpen(handbook) shouldBe false
        epochs.scanned(handbook, witnessed = emptySet(), durable = setOf(deploy)).shouldBeNull()
    }

    test("PARTIAL watch coverage opens no epoch, and KILLS an open one - an unwatched subtree raises no event at all") {
        val convergence = RootConvergence()
        val epochs = epochOver(convergence).apply { observing(handbook) }
        val deploy = binding("guides/deploy.md")
        val durable = setOf(deploy)
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable)

        convergence.record(handbook, whole = false)
        epochs.scanned(handbook, witnessed = emptySet(), durable = durable).shouldBeNull()
        epochs.isOpen(handbook) shouldBe false

        // And it does not reopen while the coverage is STILL partial - which is the case the transition report cannot
        // cover, because a condition that persists reports nothing further.
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable).shouldBeNull()
        epochs.isOpen(handbook) shouldBe false
    }

    test("a BLINDING break (the watcher died) returns the root to UNOBSERVED - a dead watcher cannot anchor a new epoch") {
        val epochs = epochOver().apply { observing(handbook) }
        val deploy = binding("guides/deploy.md")
        val durable = setOf(deploy)
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable)

        epochs.broke(handbook, BreakCause.WATCHER_DIED)

        // An ORDINARY break would let the next complete scan open a fresh epoch. This one must not: there is no
        // watcher behind it any more, so nothing would ever report the next gap.
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable).shouldBeNull()
        epochs.isOpen(handbook) shouldBe false
    }

    test("a REVOKED token kills the epoch even when the map still says OPEN - the durable token is the fact, not this holder") {
        val tokens = Tokens()
        val epochs = ObservationEpoch(tokens, RootConvergence()).apply { observing(handbook) }
        val deploy = binding("guides/deploy.md")
        val durable = setOf(deploy)
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable)

        // A break lands on a watcher thread: it revokes the token, and updates the map AFTERWARDS. Revoke WITHOUT
        // the map update - the exact state a lost race leaves behind, where the map still reads OPEN - and the epoch
        // must be dead anyway. That is what makes the holder's blind stores safe: the token is the fact.
        tokens.revoke(handbook)

        // It proves NOTHING (the invariant), and the next pass RE-OPENS on the fresh token (the state machine: a break
        // closes an epoch, and the pass after it earns a new one whose authority starts empty).
        epochs.scanned(handbook, witnessed = emptySet(), durable = durable).shouldBeNull()

        // ...and the epoch it just opened witnessed nothing, so it still cannot touch the page.
        epochs.scanned(handbook, witnessed = emptySet(), durable = durable).shouldBeNull()
    }

    test("a scan does NOT re-open an epoch a break just closed - the reopen belongs to the NEXT pass, over a LATER witness") {
        val epochs = epochOver().apply { observing(handbook) }
        val deploy = binding("guides/deploy.md")
        val durable = setOf(deploy)
        epochs.scanned(handbook, witnessed = setOf(deploy.path), durable = durable) // a live epoch that HAS seen it

        // The break lands after this pass already took its evidence. The PRODUCTION signature is called directly here,
        // NOT the establish-then-scan helper: the helper would re-open the epoch first and hide the very arm under test.
        epochs.broke(handbook, BreakCause.OVERFLOW)
        val proof = epochs.scanned(
            root = handbook,
            witnessed = setOf(deploy.path),
            unread = emptySet(),
            durable = durable,
            bindingEpoch = BindingEpoch(0),
        )

        // It mints nothing - and, the part that matters, it opens NOTHING either. Re-opening here would seed the new
        // epoch with the witness set of a scan taken BEFORE the break, so the next pass would "confirm" a delete across
        // the very gap this break reported. That epoch is the next pass's to open, over a witness gathered after it.
        proof.shouldBeNull()
        epochs.isOpen(handbook) shouldBe false
    }

    test("epochs are PER-ROOT: a break in one root leaves the other's authority untouched") {
        val epochs = epochOver().apply {
            observing(handbook)
            observing(extra)
        }
        val here = binding("guides/deploy.md")
        val there = binding("notes/rollback.md")
        epochs.scanned(handbook, witnessed = setOf(here.path), durable = setOf(here))
        epochs.scanned(extra, witnessed = setOf(there.path), durable = setOf(there))

        epochs.broke(handbook, BreakCause.OVERFLOW)

        epochs.scanned(handbook, witnessed = emptySet(), durable = setOf(here)).shouldBeNull()
        epochs.scanned(extra, witnessed = emptySet(), durable = setOf(there)).shouldNotBe(null)
    }
})

/**
 * One PASS over [root], in production's own order: [ObservationEpoch.establish] settles the epoch this pass will reason
 * from - which is where an OPEN now happens, above any evidence, so its revoke can never be mistaken for a mid-pass
 * break (C5, revoke-before-stamp) - and only then does the scan speak. Every row above is a sequence of passes, so
 * folding both halves in here is what keeps them modelling the real thing rather than a state machine driven by hand.
 *
 * Every row above is also about the WITNESSED-vs-ABSENT split, over a tree with nothing UNREAD - so it says so once,
 * here, rather than twenty-two times.
 *
 * The THIRD answer (a page the walk enumerated and the read could not produce - neither witnessed nor absent) is
 * [com.plainbase.domain.service.UnreadPageIsNotAbsentTest]'s subject. Note the PRODUCTION signature takes `unread`
 * EXPLICITLY and has NO DEFAULT: a safety input that silently defaults to the optimistic value ("nothing was unread")
 * is precisely how it came to be missing in the first place, and it reaped pages that were sitting on disk.
 */
private fun ObservationEpoch.scanned(root: RootName, witnessed: Set<TreePath>, durable: Set<BindingRef>): AbsenceProof? {
    establish(root)
    return scanned(root = root, witnessed = witnessed, unread = emptySet(), durable = durable, bindingEpoch = BindingEpoch(0))
}
