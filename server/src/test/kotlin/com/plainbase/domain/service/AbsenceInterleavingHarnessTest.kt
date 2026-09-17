package com.plainbase.domain.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.InferredProofMint
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchQuery
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

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

    test("C5.1 confirmation advances freshness before stale proof apply, with a positive reap control") {
        fun runScenario(confirm: Boolean): ConfirmationScenario = withAbsenceTrees { mainDir, extraDir ->
            val firstId = PageId.require("01010101-0101-0101-0101-010101010101")
            val secondId = PageId.require("02020202-0202-0202-0202-020202020202")
            writePage(mainDir, "guides/deploy.md", "---\nid: $firstId\ntitle: Deploy\n---\n\n# Deploy\n\nbody\n")
            writePage(
                extraDir,
                "notes/rollback.md",
                "---\nid: $secondId\ntitle: Rollback\n---\n\n# Rollback\n\nrollback-unique-term\n",
            )
            writePage(
                extraDir,
                "notes/keep.md",
                "---\nid: 03030303-0303-0303-0303-030303030303\ntitle: Keep\n---\n\n# Keep\n\nkeep\n",
            )
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("extra")
                val snapshot = world.builder(mainDir, world.extraStore(extraDir), world.indexer).rebuild()
                val expected = snapshot.section(extra).pages.map { page ->
                    IdBinding(RootedPath(extra, page.path), page.id, materialized = true)
                }
                expected shouldContainExactlyInAnyOrder world.idMap.bindings().filter { it.path.root == extra }
                expected.forEach { binding ->
                    world.dirtyPages.mark(binding.id, binding.path, "sha256:recovery", Stage.WRITING)
                }
                val beforeEpoch = world.retirements.bindingEpoch(extra)
                val observation = world.retirements.observation(extra)
                val proof = inferredProof(extra, observation, beforeEpoch, expected)
                val beforeCheckpoint = world.checkpoints.load()
                val beforeSearch = world.engine.search(SearchQuery("rollback-unique-term", limit = 20, offset = 0))

                val confirmed = if (confirm) world.idMap.confirmUnchangedBindings(expected) else false
                val retired = world.retirements.applyProofs(
                    proofs = listOf(proof),
                    witnessed = emptySet(),
                    unavailableNow = { emptySet() },
                )
                if (!confirm) world.indexer.sync(snapshot)

                ConfirmationScenario(
                    confirmed = confirmed,
                    beforeEpoch = beforeEpoch,
                    afterEpoch = world.retirements.bindingEpoch(extra),
                    observation = observation,
                    actualObservation = world.retirements.observation(extra),
                    retired = retired,
                    bindings = world.idMap.bindings().filter { it.path.root == extra },
                    tombstones = world.idMap.retiredBindings().filter { it.path.root == extra },
                    dirty = world.dirtyPages.all().filter { it.path.root == extra },
                    checkpoints = world.checkpoints.load().filterKeys { it.root == extra },
                    search = world.engine.search(SearchQuery("rollback-unique-term", limit = 20, offset = 0)),
                    beforeCheckpoint = beforeCheckpoint.filterKeys { it.root == extra },
                    beforeSearchTotal = beforeSearch.total,
                )
            }
        }

        val confirmed = runScenario(confirm = true)
        assertSoftly {
            confirmed.confirmed shouldBe true
            confirmed.afterEpoch.value shouldBe confirmed.beforeEpoch.value + 2L
            confirmed.actualObservation shouldBe confirmed.observation
            confirmed.retired shouldBe emptySet()
            withClue("live bindings survive confirmation") { confirmed.bindings shouldHaveSize 2 }
            confirmed.tombstones shouldBe emptyList()
            withClue("dirty recovery survives confirmation") { confirmed.dirty shouldHaveSize 2 }
            confirmed.checkpoints shouldBe confirmed.beforeCheckpoint
            confirmed.search.total shouldBe confirmed.beforeSearchTotal
        }

        val control = runScenario(confirm = false)
        assertSoftly {
            control.confirmed shouldBe false
            control.afterEpoch shouldBe control.beforeEpoch
            control.retired shouldHaveSize 2
            control.bindings shouldBe emptyList()
            control.tombstones shouldHaveSize 2
            control.dirty shouldBe emptyList()
            control.checkpoints shouldBe emptyMap()
            control.search.total shouldBe 0L
        }
    }

    /**
     * Drives one pass to the point of reaping `(extra, notes/rollback.md)` under [source]'s authority, firing [event] at
     * [boundary] - or, when both are null, firing nothing at all (the control). Returns what survived.
     */
    suspend fun runPass(source: Authority, boundary: Boundary?, event: Event?): Survival =
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nrollback-unique-term\n")
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

                val rooted = RootedPageId(extra, id)
                val expectedRaw = requireNotNull(world.engine.indexedState()[rooted])
                val expectedCheckpoint = requireNotNull(world.checkpoints.load()[rooted])
                val expectedDirty = requireNotNull(world.dirtyPages.get(rooted))
                val expectedBinding = requireNotNull(world.idMap.bindingInRoot(extra, id))

                val beforeDelete = world.engine.search(SearchQuery("rollback-unique-term", limit = 20, offset = 0))
                beforeDelete.total shouldBe 1L
                beforeDelete.hits.map { RootedPageId(it.root, it.pageId) } shouldBe listOf(rooted)

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

                val search = world.engine.search(SearchQuery("rollback-unique-term", limit = 20, offset = 0))
                Survival(
                    binding = world.idMap.retiredAt(extra, id) == null,
                    recoveryRow = world.dirtyPages.get(rooted) != null,
                    currentRetired = rooted in world.idMap.retiredUnboundIds(),
                    liveBinding = world.idMap.bindingInRoot(extra, id)?.path == RootedPath(extra, rollback),
                    rawPresent = rooted in world.engine.indexedState(),
                    termTotal = search.total,
                    termHitsPresent = search.hits.isNotEmpty(),
                    checkpointPresent = rooted in world.checkpoints.load(),
                    dirtyPresent = world.dirtyPages.get(rooted) != null,
                    expectedRaw = expectedRaw,
                    actualRaw = world.engine.indexedState()[rooted],
                    expectedCheckpoint = expectedCheckpoint,
                    actualCheckpoint = world.checkpoints.load()[rooted],
                    expectedDirty = expectedDirty,
                    actualDirty = world.dirtyPages.get(rooted),
                    expectedBinding = if (event == Event.REBIND) expectedBinding.copy(materialized = true) else expectedBinding,
                    actualBinding = world.idMap.bindingInRoot(extra, id),
                )
            }
        }

    // Deliberate out-of-matrix mechanism pin: this repeats one cell solely to preserve the EPOCH source label.
    test("EPOCH provenance - the stale-discard line names EPOCH") {
        val logger = LoggerFactory.getLogger(SqlDelightRetirementRepository::class.java) as Logger
        val previousLevel = logger.level
        val captured = ListAppender<ILoggingEvent>()
        captured.start()
        try {
            logger.level = Level.WARN
            logger.addAppender(captured)
            val survived = runPass(Authority.EPOCH, Boundary.ON_APPLY_ENTRY, Event.WATCHER_BREAK)
            survived.binding shouldBe true
            survived.recoveryRow shouldBe true
            val messages = captured.list.map { it.formattedMessage }
            withClue("captured stale-discard lines: ${messages.joinToString()}") {
                messages.count { "a EPOCH proof" in it } shouldBe 1
            }
        } finally {
            logger.detachAppender(captured)
            captured.stop()
            captured.list.clear()
            logger.level = previousLevel
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
            survived.currentRetired shouldBe true
            survived.liveBinding shouldBe false
            survived.rawPresent shouldBe false
            survived.termTotal shouldBe 0L
            survived.termHitsPresent shouldBe false
            survived.checkpointPresent shouldBe false
            survived.dirtyPresent shouldBe false
            survived.actualRaw shouldBe null
            survived.actualCheckpoint shouldBe null
            survived.actualDirty shouldBe null
            survived.actualBinding shouldBe null
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
                    withClue("current durable retirement changed despite invalidated evidence at $event/$boundary") {
                        survived.currentRetired shouldBe false
                    }
                    survived.liveBinding shouldBe true
                    survived.rawPresent shouldBe true
                    survived.termTotal shouldBe 1L
                    survived.termHitsPresent shouldBe true
                    survived.checkpointPresent shouldBe true
                    survived.dirtyPresent shouldBe true
                    survived.actualRaw shouldBe survived.expectedRaw
                    survived.actualCheckpoint shouldBe survived.expectedCheckpoint
                    survived.actualDirty shouldBe survived.expectedDirty
                    survived.actualBinding shouldBe survived.expectedBinding
                }
            }
        }
    }
})

private data class ConfirmationScenario(
    val confirmed: Boolean,
    val beforeEpoch: BindingEpoch,
    val afterEpoch: BindingEpoch,
    val observation: com.plainbase.domain.root.ObservationId,
    val actualObservation: com.plainbase.domain.root.ObservationId,
    val retired: Set<RootedPageId>,
    val bindings: List<IdBinding>,
    val tombstones: List<com.plainbase.domain.root.RetiredBinding>,
    val dirty: List<DirtyPage>,
    val checkpoints: Map<RootedPageId, TreePath?>,
    val search: com.plainbase.domain.search.SearchResults,
    val beforeCheckpoint: Map<RootedPageId, TreePath?>,
    val beforeSearchTotal: Long,
)

@OptIn(InferredProofMint::class)
private fun inferredProof(
    root: RootName,
    observation: com.plainbase.domain.root.ObservationId,
    epoch: BindingEpoch,
    bindings: List<IdBinding>,
): AbsenceProof = AbsenceProof.inferred(
    root = root,
    source = com.plainbase.domain.root.ProofSource.EPOCH,
    observationId = observation,
    bindingEpoch = epoch,
    covers = bindings.mapTo(mutableSetOf()) { BindingRef(it.path.path, it.id) },
)

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

private data class Survival(
    val binding: Boolean,
    val recoveryRow: Boolean,
    val currentRetired: Boolean,
    val liveBinding: Boolean,
    val rawPresent: Boolean,
    val termTotal: Long,
    val termHitsPresent: Boolean,
    val checkpointPresent: Boolean,
    val dirtyPresent: Boolean,
    val expectedRaw: PageSearchState,
    val actualRaw: PageSearchState?,
    val expectedCheckpoint: TreePath,
    val actualCheckpoint: TreePath?,
    val expectedDirty: DirtyPage,
    val actualDirty: DirtyPage?,
    val expectedBinding: IdBinding,
    val actualBinding: IdBinding?,
)

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
