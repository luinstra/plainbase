package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * **The interleaving matrix: the gate this bug class has never had.**
 *
 * Six times now, a pass has gathered negative evidence, stamped a proof, and reaped a binding it should not have -
 * because an invalidating event landed between the evidence and the apply and the stamp had been taken too late to
 * catch it. Every one of those six shipped green: the failure is fail-OPEN in a narrow interleaving, and the targeted
 * REDs that pin them each pin ONE window. Three of the six were introduced by the fix for an earlier one.
 *
 * So this does not test a window. It enumerates the pass's evidence boundaries, enumerates the events that can stale
 * evidence, and asserts ONE property over the whole cross-product:
 *
 * > **If an invalidating event lands anywhere between the pass's earliest evidence read and its apply, the reap does
 * > not happen - the binding survives, and so does its `dirty_page` USER-CONTENT recovery row.**
 *
 * The MECHANISM that saves each cell differs (a binding-epoch mismatch, an observation-token mismatch, a standing
 * refusal, or no proof being minted at all), and that is deliberately not asserted: a cell rescued by a different
 * mechanism than expected is still safe, and pinning the mechanism is what made the earlier tests brittle.
 *
 * **The CONTROL row is the point of the whole file.** Without it every row here would also pass if the pass simply
 * never reaped anything, which is exactly the vacuity that let instance 3 hide for a round.
 *
 * Adding a new proof source or a new event kind means adding a row to [Event] or [Boundary] here. That is the whole
 * intent: the next instance of this class should fail a test before it reaches a reviewer.
 *
 * **VALIDATED, not asserted.** Each of these was backed out of production code and the named cells were watched RED:
 *  - drop the binding-epoch half of `applyProofs`' gate -> all 5 `REBIND` cells (the shape of instances 1, 2 and 4);
 *  - drop the observation half -> `WATCHER_BREAK at ON_APPLY_ENTRY`;
 *  - have GIT read its token at MINT instead of taking the caller's pre-evidence capture -> 4 `GIT: WATCHER_BREAK`
 *    cells. **That is instance 3**, the one a nine-seat panel found only through a single dissenting seat;
 *  - turn off the `unavailableNow` standing gate -> 3 `AVAILABILITY_MARK` cells (instance 5). The other two survive by a
 *    different mechanism: a mark landing before the scan makes the pass skip the root, so nothing is minted at all.
 *
 * And it earned its keep on its FIRST extended run, flagging `GIT: WATCHER_BREAK at AFTER_STAMPS` - not a proven loss
 * (a break before all evidence arguably leaves a git range sound) but a real inconsistency: the observation and binding
 * captures were ORDERED, so one could absorb an event the other could not. Both are now taken as early as the pass can.
 *
 * Known GAP in THIS MATRIX, stated rather than papered over: `OBJECT_LIST` is not exercised HERE. Its boundary is a
 * poll-time pagination read outside `rebuild()`, so it cannot be driven from a cell, and no row below says anything
 * about it.
 *
 * That boundary is not uncovered, though, so do not read the paragraph above as "nothing tests it". Its own fixture
 * lives in `ContentModuleWiringTest` ("object mode snapshots the real durable rows and binding epoch at the LIST
 * boundary"), which pins the poll-time read this matrix cannot reach. Bringing OBJECT_LIST INTO the matrix would still
 * need a way to inject an event at that boundary from inside a pass, which is why the gap is scoped to this file
 * rather than closed.
 */
class AbsenceInterleavingHarnessTest : FunSpec({

    val extra = RootName.require("extra")
    val rollback = TreePath.require("notes/rollback.md")

    /**
     * Drives one pass to the point of reaping `(extra, notes/rollback.md)` under [source]'s authority, firing [event] at
     * [boundary] - or, when both are null, firing nothing at all (the control). Returns what survived.
     */
    suspend fun runPass(source: Authority, boundary: Boundary?, event: Event?): Survival =
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // EPOCH needs a watched root; GIT deliberately needs NO epoch at all, and that difference is the whole
                // reason both are here: an early break stops EPOCH from minting, so only GIT can exercise the windows
                // where the observation STAMP - rather than the absent epoch - is the only thing standing in the way.
                val git = MovingHistory(deletes = source == Authority.GIT)
                if (source == Authority.EPOCH) world.observe("docs", "extra")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, extraHistory = git)
                    .rebuild().byPath.getValue(RootedPath(extra, rollback)).id

                // An interrupted save left a recovery row: USER CONTENT, and the thing a wrong reap destroys.
                world.dirtyPages.mark(id, RootedPath(extra, rollback), "sha256:recovery", Stage.WRITING)

                // The page is deleted under the running server, so the CONFIRMATION pass below mints a proof over it.
                // For GIT that deletion is also COMMITTED: the head moves, and the range is what proves it gone.
                extraDir.resolve("notes/rollback.md").toFile().delete()
                git.head = "B"

                val fire = FireOnce {
                    when (event) {
                        Event.REBIND -> world.idMap.bind(RootedPath(extra, rollback), id, materialized = true)
                        Event.WATCHER_BREAK -> world.broke("extra", BreakCause.OVERFLOW)
                        Event.AVAILABILITY_MARK -> world.availability.markUnavailable(extra, UnavailableCause.VANISHED)
                        null -> Unit
                    }
                }
                val at = { b: Boundary -> if (b == boundary) fire else FireOnce {} }
                world.builder(
                    mainDir,
                    HookedStore(LocalContentStore(extraDir), at(Boundary.AT_SCAN_END)),
                    world.indexer,
                    extraHistory = HookedHistory(git, at(Boundary.AFTER_GIT_BRACKET)),
                    retirements = HookedRetirements(
                        world.retirements,
                        onStampRead = at(Boundary.AFTER_STAMPS),
                        onApplyEntry = at(Boundary.ON_APPLY_ENTRY),
                        stampedRoot = extra,
                    ),
                    idMap = HookedIdMap(world.idMap, at(Boundary.AT_DURABLE_READ)),
                ).rebuild()

                Survival(
                    binding = world.idMap.retiredAt(extra, id) == null,
                    recoveryRow = world.dirtyPages.get(RootedPageId(extra, id)) != null,
                )
            }
        }

    // Not a matrix cell, and deliberately so: the matrix asks "can stale evidence reap a live binding", parameterised
    // over Authority x Boundary x Event, and this is neither a new Event nor a new Boundary. It asks the OTHER
    // question rebuild() answers, at its carry site: "does a section that is CARRIED rather than rescanned keep all of
    // its pages". It lives here because that carry is inside rebuild(), the method every row in this file guards, and
    // because a pending change to the carry filter needs a row that predates it.
    //
    // Anti-vacuity, in the CONTROL row's spirit, since retention alone would also hold if the pass did nothing at all.
    // Two changes land between the passes: main GAINS a page (so a pass that never rebuilt fails), and `extra` loses
    // notes/keep.md from disk (so a pass that RESCANNED `extra` cannot publish it, whatever else it does). Only a
    // genuine carry satisfies both, and a DROPPED section fails them outright.
    test("CARRY - a root skipped this pass keeps EVERY page of its last-good section, by count and by identity") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            writePage(extraDir, "notes/keep.md", "# Keep\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                // ONE builder across both passes: a carry is the builder's OWN last-published section coming forward,
                // so a second builder would carry nothing and the row would pass on an empty section.
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val published = builder.rebuild()
                val keep = RootedPath(extra, TreePath.require("notes/keep.md"))
                val keptId = published.byPath.getValue(keep).rooted
                val before = published.section(extra).pages.map { it.rooted }
                before.size shouldBe 2

                world.availability.markUnavailable(extra, UnavailableCause.VANISHED)
                extraDir.resolve("notes/keep.md").toFile().delete()
                writePage(mainDir, "guides/rollout.md", "# Rollout\n\nbody\n")

                val snapshot = builder.rebuild()
                val after = snapshot.section(extra).pages.map { it.rooted }

                withClue("this pass never rebuilt anything, so nothing below tells a carry from a no-op") {
                    snapshot.byPath.containsKey(RootedPath(RootName.PRIMARY, TreePath.require("guides/rollout.md"))) shouldBe true
                }
                withClue("'extra' was not carried: only a carry can publish notes/keep.md, whose file is gone from disk") {
                    snapshot.pageAt(keptId)?.path shouldBe keep.path
                }
                withClue("the carried section lost page(s): had ${before.size}, carried ${after.size}") {
                    after.size shouldBe before.size
                }
                withClue("the carried section holds different pages than it did - a swap or a partial drop") {
                    after shouldBe before
                }
            }
        }
    }

    for (source in Authority.entries) {
        test("CONTROL ($source) - with NO event injected the pass DOES reap; without this the matrix passes vacuously") {
            val survived = runPass(source, boundary = null, event = null)
            withClue("$source must actually reap here, or every $source row below proves nothing") {
                survived.binding shouldBe false
            }
            withClue("and the reap takes the recovery row with it - that is the loss the matrix exists to forbid") {
                survived.recoveryRow shouldBe false
            }
        }

        for (boundary in Boundary.entries) {
            for (event in Event.entries) {
                test("$source: $event at $boundary reaps NOTHING - evidence gathered before it authorizes nothing after") {
                    val survived = runPass(source, boundary, event)
                    withClue("the $source binding was reaped despite $event at $boundary") {
                        survived.binding shouldBe true
                    }
                    withClue("the dirty_page USER-CONTENT row was destroyed despite $event at $boundary") {
                        survived.recoveryRow shouldBe true
                    }
                }
            }
        }
    }
})

/**
 * Which proof source's authority is under test. They fail differently and BOTH are needed: an early break stops EPOCH
 * from minting at all (so its observation stamp is only load-bearing between mint and apply), while GIT mints with no
 * epoch whatsoever and its stamp is the only thing that can catch a break at any earlier boundary.
 */
private enum class Authority { EPOCH, GIT }

/** Where in a pass the event lands. Each entry is a real read the pass makes, in roughly the order it makes them. */
private enum class Boundary {
    /** Just after the freshness stamps are captured, before any evidence at all. */
    AFTER_STAMPS,

    /** Just after the git HEAD bracket is read - the first negative-evidence read of the pass. */
    AFTER_GIT_BRACKET,

    /** As the scan hands back: the witnessed/unread sets for this root are now fixed. */
    AT_SCAN_END,

    /** At the `durable` snapshot each mint takes - the id_map rows the proof will be ABOUT. */
    AT_DURABLE_READ,

    /** As `applyProofs` is entered, after the caller's arguments are evaluated and before its transaction opens. */
    ON_APPLY_ENTRY,
}

/** What lands. Each one stales the pass's evidence, and each is caught by a different half of the machinery. */
private enum class Event {
    /** A restore re-binds the covered key: advances `binding_epoch`, deliberately not the observation token. */
    REBIND,

    /** A watcher break: revokes the observation token, so the epoch's continuity is gone. */
    WATCHER_BREAK,

    /** The root is discovered lost: moves NO stamp at all, which is why standing is its own required input. */
    AVAILABILITY_MARK,
}

private data class Survival(val binding: Boolean, val recoveryRow: Boolean)

/** Fires at most once, so a seam the pass reads several times still yields ONE event at the earliest read. */
private class FireOnce(private val fire: () -> Unit) {
    private var fired = false

    fun go() {
        if (!fired) {
            fired = true
            fire()
        }
    }
}

private class HookedStore(private val delegate: ContentStore, private val hook: FireOnce) : ContentStore by delegate {
    override fun scan(): ScanResult = delegate.scan().also { hook.go() }
}

private class HookedIdMap(private val delegate: IdMapRepository, private val hook: FireOnce) : IdMapRepository by delegate {
    override fun bindings(): List<IdBinding> = delegate.bindings().also { hook.go() }
}

private class HookedHistory(private val delegate: HistoryProvider, private val hook: FireOnce) : HistoryProvider {
    override val enabled: Boolean get() = delegate.enabled
    override fun currentHead(): String? = delegate.currentHead().also { hook.go() }
    override fun isAncestor(ancestor: String, descendant: String): Boolean = delegate.isAncestor(ancestor, descendant)
    override fun deletedIn(from: String, to: String): Set<TreePath>? = delegate.deletedIn(from, to)
    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? =
        delegate.commit(path, bytes, author, committer)
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = delegate.lastCommits(paths)
    override fun log(path: TreePath, limit: Int?): List<Commit> = delegate.log(path, limit)
    override fun diff(from: String, to: String, path: TreePath): FileDiff = delegate.diff(from, to, path)
    override fun prepare() = delegate.prepare()
    override fun gateCheck() = delegate.gateCheck()
}

/**
 * Hooks the two retirement-side boundaries: the stamp READ (fired for [stampedRoot] only, so the covered root's stamp
 * is definitely taken before the event) and the apply ENTRY.
 */
private class HookedRetirements(
    private val delegate: RetirementRepository,
    private val onStampRead: FireOnce,
    private val onApplyEntry: FireOnce,
    private val stampedRoot: RootName,
) : RetirementRepository by delegate {

    override fun bindingEpoch(root: RootName): BindingEpoch =
        delegate.bindingEpoch(root).also { if (root == stampedRoot) onStampRead.go() }

    override fun applyProofs(
        proofs: List<AbsenceProof>,
        witnessed: Set<RootedPageId>,
        unavailableNow: () -> Set<RootName>,
        advances: List<GitCheckpointAdvance>,
    ): Set<RootedPageId> {
        onApplyEntry.go()
        return delegate.applyProofs(proofs, witnessed, unavailableNow, advances)
    }
}

/**
 * A git root whose HEAD the caller moves. With [deletes] off it proves nothing and merely makes the HEAD bracket a real
 * read (so the EPOCH rows still have that boundary); with it on, the range is what authorizes the reap and no epoch is
 * involved at all - which is the only way to exercise the windows instance 3 lived in.
 */
private class MovingHistory(private val deletes: Boolean) : HistoryProvider {
    var head: String = "A"
    override val enabled: Boolean = true
    override fun currentHead(): String = head
    override fun isAncestor(ancestor: String, descendant: String): Boolean = true
    override fun deletedIn(from: String, to: String): Set<TreePath> =
        if (deletes) setOf(TreePath.require("notes/rollback.md")) else emptySet()
    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? = null
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = emptyMap()
    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()
    override fun diff(from: String, to: String, path: TreePath): FileDiff = FileDiff(from, to, path, "")
    override fun prepare() = Unit
    override fun gateCheck() = Unit
}
