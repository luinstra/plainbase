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
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
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
                world.checkpoints.load().keys shouldContain rollback

                vanishing.arm()
                val second = builder.rebuild()

                withClue("an empty scan from a root that is GONE is not an empty corpus - it is no scan at all") {
                    second.section(extra).pages.map { it.id } shouldContainExactly listOf(rollback)
                }
                world.availability.current().isAvailable(extra) shouldBe false
                withClue("the checkpoint replace deletes exactly what the pass had authority over, and it had none here") {
                    world.checkpoints.load().keys shouldContain rollback
                }
                withClue("the id_map binding is durable state under the same rule") {
                    world.idMap.pathOf(rollback) shouldBe rollbackPath
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
                world.checkpoints.load().keys shouldContain rollback

                // The outage, and the shape that matters: the volume is unmounted while the server is DOWN, so what
                // the next boot finds at the path is an EMPTY DIRECTORY (a mount point, a bind mount the container
                // runtime created for it, a restore that has not run). Every path predicate passes; the tree
                // identity has nothing to be compared against, because this process captured it after the loss. The
                // ONLY thing left that knows the corpus existed is the durable rows.
                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                val cold = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                cold.rebuild()

                withClue("the checkpoint replace would have purged the whole root - it must have had NO authority here") {
                    world.checkpoints.load().keys shouldContain rollback
                }
                withClue("the id_map binding is the permalink: losing it re-mints /p/{id} for a page that still exists") {
                    world.idMap.pathOf(rollback) shouldBe rollbackPath
                }
                withClue("and the search rows, which the sync listener deletes off the same authority set") {
                    world.engine.indexedState().keys shouldContain rollback
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

    test("the CONTROL: a corpus this process SCANNED and then watched drain is a real delete, and its rows DO delete") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nrollback beacon\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nrollback beacon\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                // The case the whole tripwire is balanced against: an `rm -rf` under a RUNNING server. The pass saw
                // the corpus with its own eyes and then saw it go, which is precisely the evidence a cold boot into
                // an outage does not have. It is a full-corpus delete, and it must still land - a tripwire that
                // refuses this one is not conservative, it is broken.
                val builder = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                val rollback = builder.rebuild().byPath.getValue(rollbackPath).id
                world.engine.indexedState().keys shouldContain rollback

                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                builder.rebuild()

                world.availability.current().isAvailable(extra) shouldBe true
                withClue("the two pipelines delete authority actually governs: the checkpoint replace and the search sync") {
                    world.checkpoints.load().keys.contains(rollback) shouldBe false
                    world.engine.indexedState().keys.contains(rollback) shouldBe false
                }
            }
        }
    }

    test("the OPERATOR OVERRIDE: PLAINBASE_ACCEPT_EMPTY_ROOTS restores delete authority for a wipe performed while DOWN") {
        withAuthorityTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nmain body\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nextra body\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                val rollback = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                    .rebuild().byPath.getValue(rollbackPath).id

                // The wipe was REAL, and it happened while the server was down - the one case no probe and no row
                // can distinguish from an outage. So the operator says so, by name, and the pass believes them.
                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                val cold = world.builder(mainDir, LocalContentStore(extraDir), world.indexer, acceptEmptyRoots = setOf(extra))
                cold.rebuild()

                withClue("the operator declared the emptiness real: the pass must perform the deletion they ran it for") {
                    world.checkpoints.load().keys.contains(rollback) shouldBe false
                    world.engine.indexedState().keys.contains(rollback) shouldBe false
                }
                world.availability.current().isAvailable(extra) shouldBe true
            }
        }
    }

    test("a page that MOVED to another root while we were DOWN exonerates the row it left behind - a move is not a loss") {
        withAuthorityTrees { mainDir, extraDir ->
            val moved = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nmain body\n")
            writePage(extraDir, "notes/rollback.md", "---\nid: ${moved.value}\ntitle: Rollback\n---\n\n# Rollback\n\nbody\n")
            AuthorityWorld(mainDir, extraDir).use { world ->
                world.builder(mainDir, LocalContentStore(extraDir)).rebuild()

                // The one-page root is emptied not by an unmount but by a MOVE: the page is in main now, carrying
                // its id in its own file. The row extra leaves behind is exactly the row a lost corpus leaves - and
                // the tripwire must not read it as one, because the pass is HOLDING the page it names. Firing here
                // would refuse extra's scan, which makes its stale binding unsupersedable (D16), which costs the
                // moved page the permalink it carried with it - on a pass that can see perfectly well where it went.
                Files.walk(extraDir.resolve("notes")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                writePage(mainDir, "notes/arrived.md", "---\nid: ${moved.value}\ntitle: Rollback\n---\n\n# Rollback\n\nbody\n")
                val cold = world.builder(mainDir, LocalContentStore(extraDir))
                val snapshot = cold.rebuild()

                world.availability.current().isAvailable(extra) shouldBe true
                withClue("the page KEEPS its id - that is the permalink, and it travelled in the file") {
                    snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("notes/arrived.md"))).id shouldBe moved
                }
                world.idMap.pathOf(moved) shouldBe RootedPath(RootName.MAIN, TreePath.require("notes/arrived.md"))
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
                world.engine.indexedState().keys shouldContain rollback

                // Run 2: a RESTART with extra's disk unplugged. A fresh builder has no previous snapshot to carry,
                // so the root is not merely skipped - it is absent from the corpus entirely, which is precisely
                // what makes a snapshot-derived generation swap read it as a full-corpus delete.
                world.availability.markUnavailable(extra, UnavailableCause.MISSING_AT_BOOT)
                val cold = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                cold.rebuild().section(extra).pages shouldBe emptyList()
                withClue("the SYNC listener already respected the authority set") {
                    world.engine.indexedState().keys shouldContain rollback
                }

                cold.rebuildSearchIndex() // the admin `reindex` route / the `plainbase reindex` CLI

                withClue("search.db is derived state, but an operator's reindex must not purge a root's index behind an outage") {
                    world.engine.indexedState().keys shouldContain rollback
                }
                withClue("and the swap still re-derived everything it DID scan") {
                    world.engine.indexedState().values.map { it.root }.toSet() shouldBe setOf(RootName.MAIN, extra)
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

    fun builder(
        mainDir: Path,
        extraStore: ContentStore,
        searchIndexer: SearchIndexer? = null,
        acceptEmptyRoots: Set<RootName> = emptySet(),
    ): IndexBuilder = IndexBuilder(
        sources = listOf(
            IndexBuilder.Source(registry.main, LocalContentStore(mainDir), NoOpHistoryProvider),
            IndexBuilder.Source(requireNotNull(registry.byName(RootName.require("extra"))), extraStore, NoOpHistoryProvider),
        ),
        frontmatterParser = FrontmatterReader(),
        rendererFactory = { view -> FlexmarkRenderer(view) },
        identity = PageIdentityService(UuidV7IdProvider(), registry::rank),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = aliasRegistry,
        checkpoint = checkpoints,
        citations = CitationFactory(),
        rootRank = registry::rank,
        registeredRoots = registry.roots.map { it.name }.toSet(),
        acceptEmptyRoots = acceptEmptyRoots,
        listeners = listOfNotNull(
            IndexBuilder.PublicationListener(checkpoints::replaceFrom),
            searchIndexer?.let { indexer -> IndexBuilder.PublicationListener(indexer::sync) },
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
