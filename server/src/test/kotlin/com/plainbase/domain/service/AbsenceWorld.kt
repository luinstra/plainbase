package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.search.SearchProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

internal fun <T> withAbsenceTrees(block: (Path, Path) -> T): T {
    val mainDir = Files.createTempDirectory("plainbase-absence-main")
    val extraDir = Files.createTempDirectory("plainbase-absence-extra")
    return try {
        block(mainDir, extraDir)
    } finally {
        mainDir.toFile().deleteRecursively()
        extraDir.toFile().deleteRecursively()
    }
}

/**
 * One app-DB world (main, then extra) seating SEVERAL builders over the same durable state and the same REAL
 * search engine - because half the assertions here are about what the engine's generation swap did NOT delete,
 * and a fake provider would simply agree with whatever it was told.
 *
 * **A root starts UNOBSERVED, so a builder from here reaps NOTHING until a test says otherwise.** That is not a
 * fixture convenience; it is production's own rule (C2), and it is why every C0 row still passes untouched: an
 * epoch is earned by a WATCHER, and there is no watcher here until [observe] declares one. A row that wants the
 * C2 authority asks for it - and a row that wants the BREAK half calls [broke], which is the very call a real
 * watcher makes.
 */
internal class AbsenceWorld(mainDir: Path, extraDir: Path) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)

    private val registry: RootRegistry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("extra", extraDir)))

    val availability = RootAvailability(Clock.System)
    val idMap = SqlDelightIdMapRepository(database)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val dirtyPages = SqlDelightDirtyPageRepository(database)
    val retirements = SqlDelightRetirementRepository(database)
    val limbo = RootLimbo()
    val convergence = RootConvergence()

    /**
     * The C2 proof source, over the SAME token the proof-apply transaction re-reads.
     *
     * A `var` because a RESTART is one of the states under test, and a restart is exactly this: the in-memory epochs
     * are gone, the DURABLE token in `root_observation` is not. Anything that survives a [restart] here survives one
     * in production, and nothing else does.
     */
    var epochs = ObservationEpoch(retirements, convergence)
        private set

    private val aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))

    private val searchDir: Path = Files.createTempDirectory("plainbase-absence-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))

    val engine: SearchProvider = Fts5SearchProvider(searchDb)
    val indexer = SearchIndexer(engine, SectionSplitter())

    /**
     * Every break this world's WIRING reported - so a row can prove the mechanism it claims to be testing actually
     * fired, rather than passing because something else happened to break the epoch too. (It does NOT see the breaks
     * `IndexBuilder` raises for itself; those are the pass's own business.)
     */
    val reportedBreaks = mutableListOf<BreakCause>()

    /** `serve()`'s declaration at the watcher-install site: this root is being WATCHED, so it may earn an epoch. */
    fun observe(vararg roots: String) = roots.forEach { epochs.observing(RootName.require(it)) }

    /** What a real watcher calls when it knows it has missed something (`ContentStore.watch`'s `onBreak`). */
    fun broke(root: String, cause: BreakCause) = report(RootName.require(root), cause)

    private fun report(root: RootName, cause: BreakCause) {
        reportedBreaks += cause
        epochs.broke(root, cause)
    }

    /**
     * The process died and came back: every epoch is gone, and it has to be EARNED again from a scan. The durable
     * rows and the durable observation token stay exactly where they were - which is the whole reason a restart is a
     * revocation rather than an amnesia.
     */
    fun restart() {
        epochs = ObservationEpoch(retirements, convergence)
    }

    /**
     * The `extra` root's tree, wired as `ContentModule` wires it - INCLUDING `onIdentityRebind`, which is the only
     * thing standing between an epoch and a tree that was swapped out from under it. Held across rebuilds by a test
     * that wants the probe to actually SEE the swap: a store constructed fresh over the new tree binds to the new
     * inode and has nothing to compare against, so it could never notice.
     */
    fun extraStore(dir: Path): LocalContentStore {
        val extra = RootName.require("extra")
        return LocalContentStore(
            root = dir,
            rootName = extra,
            onRootUnavailable = { availability.markUnavailable(extra, UnavailableCause.VANISHED) },
            onIdentityRebind = { report(extra, BreakCause.IDENTITY_REBIND) },
        )
    }

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
        retirements = retirements,
        limbo = limbo,
        epochs = epochs,
    )

    override fun close() {
        searchDb.close()
        searchDir.toFile().deleteRecursively()
        driver.close()
    }
}
