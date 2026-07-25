package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.search.SearchProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.livePathOf
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * DELETE AUTHORITY (ADR-0011 D5), pinned at the places a pass can hand it out by accident.
 *
 * Every deletion pipeline downstream of a rebuild - the checkpoint replace, the search sync, the search
 * generation swap, the id_map supersession - is keyed off ONE set: the roots the pass actually walked. So the
 * only way a root's durable state dies behind an outage is for something to put it in that set, or to fail to
 * carry it out of one. That is exactly what these rows drive:
 *
 *  - a scan that VANISHES MID-WALK and returns SHORT rather than throwing (a directory iteration simply runs out
 *    of entries) - the pass must classify the SCAN IT WAS HANDED, not the probe it ran before starting;
 *  - a LIVE root whose scan fails (one `chmod 000` subdirectory) - that root's pass fails, never the whole
 *    rebuild, which at boot would take the server down over one unreadable folder in one extra root;
 *  - the search REINDEX, which re-derives the engine from the snapshot alone and therefore says nothing at all
 *    about a root unavailable since boot - unless it is told what the pass that published that snapshot was
 *    allowed to delete;
 *  - and the CORPUS-LOSS TRIPWIRE, the last check and the only one anchored in state that survives a restart: a
 *    root that scans to ZERO pages while its durable rows say otherwise, on a corpus this process has never seen,
 *    is a broken view rather than a delete. Its three siblings are here too, because a tripwire is only as good as
 *    the cases it lets THROUGH: the control (a corpus we watched drain still deletes), the operator override, and
 *    the fresh empty root that must be allowed to be empty.
 */
class RootDeleteAuthorityTest : FunSpec({

    val extra = RootName.require("extra")
    val rollbackPath = RootedPath(extra, TreePath.require("notes/rollback.md"))

    test("a scan that vanishes MID-WALK and returns SHORT gets NO delete authority: the section carries, the checkpoints stay") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nmain body\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nextra body\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                // The store hands back an EMPTY scan and only THEN reads as gone - a tree pulled out from under a
                // walk that had already opened it. Nothing throws: the walk just finds nothing left to iterate.
                val vanishing = VanishingScan(LocalContentStore(extraDir))
                val builder = world.builder(mainDir, vanishing)
                val rollback = builder.rebuild().byPath.getValue(rollbackPath).id
                world.checkpoints.load().keys.map { it.id } shouldContain rollback

                vanishing.arm()
                val second = builder.rebuild()

                withClue("an empty scan from a root that is GONE is not an empty corpus - it is no scan at all") {
                    second.section(extra).pages.map { it.id } shouldContainExactly listOf(rollback)
                }
                world.availability.current().isAvailable(extra) shouldBe false
                withClue("the checkpoint replace deletes exactly what the pass had authority over, and it had none here") {
                    world.checkpoints.load().keys.map { it.id } shouldContain rollback
                }
                withClue("the id_map binding is durable state under the same rule") {
                    world.idMap.livePathOf(rollback) shouldBe rollbackPath
                }
            }
        }
    }

    test("a LIVE root whose scan FAILS fails that root's pass, not the rebuild - the other roots still index") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nmain body\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nextra body\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                // The shape that used to kill `serve()`: one unreadable subdirectory in ONE extra root raised an
                // AccessDeniedException that escaped the per-root loop, so EVERY root's pass failed - and at boot,
                // where the first rebuild is uncaught, the server died with a stack trace instead of serving the
                // roots that were perfectly fine.
                val failing = FailingScan(LocalContentStore(extraDir))
                val builder = world.builder(mainDir, failing)
                builder.rebuild()

                failing.armed = true
                val second = builder.rebuild() // must NOT throw

                second.section(RootName.MAIN).pages.map { it.path.value } shouldContainExactly listOf("guides/deploy.md")
                withClue("the failed root keeps its last-good section: nothing is deleted for it") {
                    second.section(extra).pages.map { it.path.value } shouldContainExactly listOf("notes/rollback.md")
                }
                withClue("it is THERE - a permission is fixed in place, so sticky-until-restart would prescribe a restart nobody needs") {
                    world.availability.current().isAvailable(extra) shouldBe true
                }

                // ...and the next pass retries it, so a fixed directory heals with no restart.
                failing.armed = false
                builder.rebuild().section(extra).pages.map { it.path.value } shouldContainExactly listOf("notes/rollback.md")
            }
        }
    }

    // ---- the CORPUS-LOSS TRIPWIRE: a zero-page scan is not, on its own, a delete instruction ---------
    //
    // Every probe upstream of the pass is a PROXY for "is the corpus there", and each has a hole at BOOT - where
    // the tree an identity check would compare against is the broken one, and the remedy it prescribes (restart)
    // is the trigger. What they all come out as is a root that scans to ZERO pages, which is also exactly what a
    // genuine full-corpus delete looks like. Durable rows are the one oracle that outlives the process, so they
    // are what decides - and the operator gets the override, because no row can tell a wipe-while-down from an
    // outage either.

    test("a root that scans to ZERO pages with durable rows, whose corpus we never saw, is a BROKEN VIEW: nothing is deleted") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nrollback beacon\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nrollback beacon\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                // Run 1: the corpus is there, and its rows go durable.
                val warm = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val rollback = warm.rebuild().byPath.getValue(rollbackPath).id
                world.checkpoints.load().keys.map { it.id } shouldContain rollback

                // The outage, and the shape that matters: the volume is unmounted while the server is DOWN, so what
                // the next boot finds at the path is an EMPTY DIRECTORY (a mount point, a bind mount the container
                // runtime created for it, a restore that has not run). Every path predicate passes; the tree
                // identity has nothing to be compared against, because this process captured it after the loss. The
                // ONLY thing left that knows the corpus existed is the durable rows.
                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                val cold = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                cold.rebuild()

                withClue("the checkpoint replace would have purged the whole root - it must have had NO authority here") {
                    world.checkpoints.load().keys.map { it.id } shouldContain rollback
                }
                withClue("the id_map binding is the permalink: losing it re-mints /p/{root}/{id} for a page that still exists") {
                    world.idMap.livePathOf(rollback) shouldBe rollbackPath
                }
                withClue("and the search rows, which the sync listener deletes off the same authority set") {
                    world.engine.indexedState().keys.map { it.id } shouldContain rollback
                }
                val down = world.availability.current().unavailable[extra]
                withClue("an empty VIEW must not be SERVED as a live corpus: 503 (the pages exist), never 404") {
                    down?.cause shouldBe UnavailableCause.CORPUS_MISSING
                }
                withClue("the other roots are untouched - one broken mount is not a corpus-wide outage") {
                    cold.current.section(RootName.MAIN).pages.map { it.path.value } shouldContainExactly listOf("guides/deploy.md")
                }
            }
        }
    }

    // ⚠ THIS ROW MOVES TO C2 (the observation epoch), and it is TIGHTENED there, never weakened. Under C0 an
    // `rm -rf` beneath a RUNNING server does NOT reap - not because the case is wrong, but because C0 has NO PROOF
    // SOURCE with which to believe it. "This process watched the corpus drain" was `corpusSeen`, and `corpusSeen`
    // was a snapshot from T cashed at T+n: with ext4 inode reuse it hands a REAPED corpus full authority, which is
    // ledger A2. C2 replaces it with an unbroken observation EPOCH - a live claim, revoked by every break - and at
    // that point this case reaps again, on evidence rather than on a memory. Both sides get pinned there: a small
    // delete inside an unbroken epoch REAPS; a delete storm that overflows the queue does NOT, and lands in limbo.
    test("the C0 COST, stated: a corpus we watched drain does NOT reap - it lands in LIMBO until C2 can prove it") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nrollback beacon\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nrollback beacon\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val rollback = builder.rebuild().byPath.getValue(rollbackPath).id
                world.engine.indexedState().keys.map { it.id } shouldContain rollback

                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                builder.rebuild()

                withClue("the page leaves the SNAPSHOT at once - what waits for a proof is the DURABLE reap") {
                    builder.current.section(extra).pages.shouldBeEmpty()
                }
                withClue("the durable rows are carried, not destroyed: an unmount and an rm -rf look identical from here") {
                    world.checkpoints.load().keys.map { it.id } shouldContain rollback
                    world.engine.indexedState().keys.map { it.id } shouldContain rollback
                    world.idMap.livePathOf(rollback) shouldBe rollbackPath
                }
            }
        }
    }

    // The OPERATOR OVERRIDE (PLAINBASE_ACCEPT_EMPTY_ROOTS) is DELETED with the tripwire it overrode. It was a
    // deletion authority with no proof, no coverage set, no freshness and no revalidation - a sticky env var
    // authorizing every future zero-scan of that root, forever - i.e. precisely the shape of thing this redesign
    // exists to abolish. Its replacement is `plainbase root reconcile <root> --accept <digest>` (C5), which is
    // still one non-interactive command an entrypoint can run, and which FAILS when the digest no longer matches.
    // That is the property the env var could never have.

    test("a cross-root 'move' is UNDECIDABLE: the drained root fails closed and its binding survives in limbo, unstolen") {
        withAuthorityTrees { mainDir, extraDir ->
            val moved = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nmain body\n")
            writePage(extraDir, "notes/rollback.md", "---\nid: ${moved.value}\ntitle: Rollback\n---\n\n# Rollback\n\nbody\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                // extra's one page is emptied and a file carrying the SAME id turns up in main. Pre-flip a bare-id move
                // detector exonerated extra's drain here. Post-flip a cross-root move is INDISTINGUISHABLE from a
                // copy-plus-delete (the absence theorem, per-root identity), so extra's drain is NOT exonerated: the
                // tripwire fires fail-closed, and - crucially - NOTHING is stolen. extra's binding survives (unobserved,
                // so unreaped) and main gets its OWN per-root copy of the id.
                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                writePage(mainDir, "notes/arrived.md", "---\nid: ${moved.value}\ntitle: Rollback\n---\n\n# Rollback\n\nbody\n")
                val cold = world.builder(mainDir, LocalContentStore(extraDir))
                val snapshot = cold.rebuild()

                withClue("a drained corpus with no proof of a move is fail-closed to CORPUS_MISSING, not silently exonerated") {
                    world.availability.current().unavailable.getValue(extra).cause shouldBe UnavailableCause.CORPUS_MISSING
                }
                withClue("main's arrival KEEPS the id it carries - a legal per-root duplicate, not a move of extra's page") {
                    snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("notes/arrived.md"))).id shouldBe moved
                }
                withClue("extra's binding is neither exonerated nor reaped - BOTH roots hold the id, extra's in limbo") {
                    world.idMap.rootsHoldingId(moved).toSet() shouldBe setOf(RootName.MAIN, extra)
                    world.idMap.bindingInRoot(extra, moved)?.path shouldBe RootedPath(extra, TreePath.require("notes/rollback.md"))
                    world.idMap.bindingInRoot(RootName.MAIN, moved)?.path shouldBe
                        RootedPath(RootName.MAIN, TreePath.require("notes/arrived.md"))
                }
            }
        }
    }

    test("a FRESH empty root trips nothing: with no durable rows there is no corpus to lose, and empty must be allowed") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nmain body\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                // A newly added root, or a fresh install. The tripwire keys on DURABLE ROWS, not on emptiness -
                // an empty root that has never held a page is simply empty, and must index (and delete) normally.
                val builder = world.builder(mainDir, LocalContentStore(extraDir))
                builder.rebuild()

                world.availability.current().isAvailable(extra) shouldBe true
                builder.current.section(extra).pages.shouldBeEmpty()

                // ...and it is a full member of the pass, not a quarantined one: a page dropped into it indexes.
                writePage(extraDir, "notes/rollback.md", "# Rollback\n\nextra body\n")
                builder.rebuild().section(extra).pages.map { it.path.value } shouldContainExactly listOf("notes/rollback.md")
            }
        }
    }

    test("a search REINDEX during an outage keeps the unscanned root's engine rows - a reindex is not a mass delete") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nrollback beacon\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nrollback beacon\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                // Run 1: both roots there, both indexed into the real engine.
                val warm = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val rollback = warm.rebuild().byPath.getValue(rollbackPath).id
                world.engine.indexedState().keys.map { it.id } shouldContain rollback

                // Run 2: a RESTART with extra's disk unplugged. A fresh builder has no previous snapshot to carry,
                // so the root is not merely skipped - it is absent from the corpus entirely, which is precisely
                // what makes a snapshot-derived generation swap read it as a full-corpus delete.
                world.availability.markUnavailable(extra, UnavailableCause.MISSING_AT_BOOT)
                val cold = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                cold.rebuild().section(extra).pages shouldBe emptyList()
                withClue("the SYNC listener already respected the authority set") {
                    world.engine.indexedState().keys.map { it.id } shouldContain rollback
                }

                cold.rebuildSearchIndex() // the admin `reindex` route / the `plainbase reindex` CLI

                withClue("search.db is derived state, but an operator's reindex must not purge a root's index behind an outage") {
                    world.engine.indexedState().keys.map { it.id } shouldContain rollback
                }
                withClue("and the swap still re-derived everything it DID scan") {
                    world.engine.indexedState().keys.map { it.root }.toSet() shouldBe setOf(RootName.MAIN, extra)
                }
            }
        }
    }
})

/** Scans normally until [arm]ed; then hands back an EMPTY tree and reads as gone - a walk whose root vanished. */
private class VanishingScan(private val delegate: ContentStore) : ContentStore by delegate {

    private var armed = false
    private var gone = false

    fun arm() {
        armed = true
    }

    override fun available(): Boolean = !gone

    override fun scan(): ScanResult {
        if (!armed) return delegate.scan()
        gone = true
        return ScanResult(files = emptyList(), folders = emptyList(), issues = emptyList())
    }
}

/** Throws while [armed], on a root that is still perfectly THERE (the `chmod 000` subdirectory shape). */
private class FailingScan(private val delegate: ContentStore) : ContentStore by delegate {

    var armed = false

    override fun scan(): ScanResult =
        if (armed) throw IOException("notes/private: Permission denied") else delegate.scan()
}

private fun <T> withAuthorityTrees(block: (Path, Path) -> T): T {
    val mainDir = Files.createTempDirectory("plainbase-authority-main")
    val extraDir = Files.createTempDirectory("plainbase-authority-extra")
    return try {
        block(mainDir, extraDir)
    } finally {
        mainDir.toFile().deleteRecursively()
        extraDir.toFile().deleteRecursively()
    }
}

/**
 * One app-DB world (main, then extra) that can seat SEVERAL builders over the same durable state and the same
 * REAL search engine - which the reindex row needs, because the thing under test is the engine's own generation
 * swap and a fake provider would simply agree with whatever it was told.
 */
private class AuthorityWorld(mainDir: Path, extraDir: Path) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)

    private val registry: RootRegistry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("extra", extraDir)))

    val availability = RootAvailability(Clock.System)
    val idMap = SqlDelightIdMapRepository(database)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    private val aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))

    private val searchDir: Path = Files.createTempDirectory("plainbase-authority-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))

    val engine: SearchProvider = Fts5SearchProvider(searchDb)
    val indexer = SearchIndexer(engine, SectionSplitter())
    val retirements = SqlDelightRetirementRepository(database)

    fun builder(
        mainDir: Path,
        extraStore: ContentStore,
        searchIndexer: SearchIndexer? = null,
    ): IndexBuilder = IndexBuilder(
        sources = listOf(
            IndexBuilder.Source(registry.main, LocalContentStore(mainDir), NoOpHistoryProvider),
            IndexBuilder.Source(requireNotNull(registry.byName(RootName.require("extra"))), extraStore, NoOpHistoryProvider),
        ),
        frontmatterParser = FrontmatterReader(),
        rendererFactory = { view -> FlexmarkRenderer(view) },
        identity = PageIdentityService(UuidV7IdProvider()),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = aliasRegistry,
        checkpoint = checkpoints,
        citations = CitationFactory(),
        rootRank = registry::rank,
        registeredRoots = registry.roots.map { it.name }.toSet(),
        retirements = retirements,
        listeners = listOfNotNull(
            IndexBuilder.PublicationListener(checkpoints::replaceFrom),
            searchIndexer?.let { indexer ->
                IndexBuilder.PublicationListener { snap, retired -> indexer.sync(snap, retired) }
            },
        ),
        searchIndexer = searchIndexer,
        availability = availability,
    )

    override fun close() {
        searchDb.close()
        searchDir.toFile().deleteRecursively()
        driver.close()
    }
}
