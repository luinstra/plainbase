package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
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
 *    allowed to delete.
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

    fun builder(mainDir: Path, extraStore: ContentStore, searchIndexer: SearchIndexer? = null): IndexBuilder = IndexBuilder(
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
