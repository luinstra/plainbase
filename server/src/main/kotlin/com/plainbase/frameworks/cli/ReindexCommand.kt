package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * `plainbase reindex` - the OFFLINE/ops full-search-rebuild path. It runs the
 * page-index pass and then a clean generation-swap rebuild of `DATA_DIR/search.db` from the
 * resulting snapshot, the SAME atomic `IndexBuilder.rebuildSearchIndex()` the endpoint uses.
 *
 * **It covers EVERY configured root, and it publishes a COMPLETE generation or NOTHING.** The rebuild is
 * one generation swap over the whole corpus, so a partial source list is not a partial refresh - it is a
 * DELETE of the roots it left out. Two guards, and the second is the one that holds the line:
 * [requireEveryRootAvailable] is a PREFLIGHT (fail early, and actionably, on a corpus that is already
 * half-mounted), while [requireCompleteGeneration] checks the OUTCOME - the snapshot that was actually
 * built - and so also catches the root that goes away DURING the pass, which no preflight can see.
 * See [rebuildSearchIndex].
 *
 * **Prefer the endpoint on a RUNNING instance.** `POST /api/v1/admin/reindex` reindexes in-process
 * against the live snapshot with the single-flight 409 guard. This CLI is for when the server is
 * down, or for a scripted operational reindex.
 *
 * **It refuses to run while a server is up.** The two would be separate JVM processes with separate
 * write monitors, and while SQLite's own locking prevents corruption it does NOT prevent the
 * CLI silently publishing an OLDER generation over the server's newer one (a freshness regression,
 * the cross-process twin of the in-process stale-snapshot defect). So it acquires the DATA_DIR
 * advisory lock ([DataDirLock]) FIRST and exits 1 if a server holds it.
 *
 * The one summary line is a deterministic stdout result. Expected refusals use stderr; engine diagnostics and
 * unexpected failures stay on the logging facade.
 */
object ReindexCommand {

    private val logger = KotlinLogging.logger {}

    /**
     * Entry point for the `main` dispatch: env + `DATA_DIR/plainbase.conf`, exit-code result. Resolves via
     * [PlainbaseConfig.loadForCommand] (NOT the env-only fast path) so the storage-backend decision matches
     * `serve` for the same DATA_DIR: an operator who sets `storage.backend=object` only in `plainbase.conf`
     * must not get the LOCAL branch here and rebuild search from an ignored CONTENT_DIR instead of the bucket
     * mirror. A bad config (IAE or HOCON) surfaces as the actionable `reindex:` stderr + exit 1.
     */
    fun runAsMain(args: List<String>, output: CommandOutput = systemCommandOutput()): Int {
        val config = PlainbaseConfig.loadForCommand("reindex", output::error) ?: return 1
        return run(args, config, output)
    }

    /** Exit codes: 0 success / 1 runtime failure (incl. a server holding the lock) / 2 usage error. */
    fun run(args: List<String>, config: PlainbaseConfig, output: CommandOutput = systemCommandOutput()): Int =
        run(args, config, NO_DECORATION, output)

    /**
     * The [StoreDecorator] seam: production runs [NO_DECORATION], and the mid-rebuild-disappearance test wraps ONE
     * root's store so it answers the preflight probe and reports gone from the rebuild's probe on. The window
     * [requireCompleteGeneration] closes is otherwise unreachable from a test - it is a real NAS unmounting between
     * two probes, and a guard nobody can exercise is a guard nobody can trust.
     */
    internal fun run(
        args: List<String>,
        config: PlainbaseConfig,
        decorate: StoreDecorator,
        output: CommandOutput = systemCommandOutput(),
    ): Int {
        if (args.isNotEmpty()) {
            output.error(USAGE) // reindex takes no flags
            return 2
        }
        return runCatching {
            config.requireContentDir() // inside try → a bad config exits 1, honoring the contract (not a stack trace)
            reindex(config, decorate, output)
            0
        }.getOrElse { failure ->
            if (failure is Error) throw failure
            logger.error(failure) { "reindex failed" } // diagnostics via the facade, not println
            1
        }
    }

    private fun reindex(config: PlainbaseConfig, decorate: StoreDecorator, output: CommandOutput) {
        // Resolution 1b: acquire the DATA_DIR lock FIRST. A live server holds it for its lifetime;
        // writing search.db underneath it would risk the cross-process stale-generation regression.
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            output.error(
                "reindex: a Plainbase server is holding ${config.dataDir} - stop it, or use " +
                    "POST /api/v1/admin/reindex on the running server",
            )
            throw IllegalStateException("DATA_DIR ${config.dataDir} is locked by a running server")
        }
        lock.use {
            val driver = DatabaseFactory.createDriver(config.appDatabasePath)
            try {
                SearchDb(config.searchDatabasePath).use { searchDb ->
                    // The engine count and the published snapshot's page count are the SAME figure - the swap
                    // re-derives the engine from exactly this snapshot, under one monitor - and the snapshot
                    // also carries the per-root split the multi-root summary reports.
                    val snapshot = rebuildSearchIndex(config, driver, searchDb, decorate, output)
                    // The command's deterministic stdout result contract.
                    output.result(summary(snapshot, config))
                }
            } finally {
                driver.close()
            }
        }
    }

    /**
     * Builds the offline graph (the production stack minus HTTP + Koin) with NO `SearchIndexer`
     * publication listener - the page pass must not auto-diff-sync; the explicit
     * `rebuildSearchIndex()` below is the single clean generation swap, the SAME atomic path the
     * endpoint uses. The checkpoint listener still runs so down-time-move aliasing stays correct.
     * Returns the published snapshot.
     *
     * **EVERY configured root is a source (ADR-0011 D7 order), not just main.** The swap is a
     * GENERATION SWAP: it re-derives the whole engine from the snapshot it is handed, so a main-only
     * source list would delete every extra root's search rows and report a page count for a fraction of
     * the corpus. The registry drives the source list here exactly as it drives `RootStores` in
     * `contentModule` - one wiring rule, two entry points.
     */
    private fun rebuildSearchIndex(
        config: PlainbaseConfig,
        driver: SqlDriver,
        searchDb: SearchDb,
        decorate: StoreDecorator,
        output: CommandOutput,
    ): PageIndex {
        val database = DatabaseFactory.createDatabase(driver)
        val registry = RootRegistry.of(config.roots.list)
        val stores = openStores(config, registry, database, decorate)
        try {
            requireEveryRootAvailable(registry, stores, output)
            val aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
            val checkpoint = SqlDelightPageCheckpointRepository(database)
            val searchIndexer = SearchIndexer(Fts5SearchProvider(searchDb), SectionSplitter())
            val builder = IndexBuilder(
                // The CLI reindex rebuilds the search engine only; search never reads `commit`, so no git
                // process is spawned here (the snapshot's commit fields stay null - harmless for this path).
                sources = registry.roots.map { root ->
                    IndexBuilder.Source(root = root, store = stores.getValue(root.name), history = NoOpHistoryProvider)
                },
                frontmatterParser = FrontmatterReader(),
                rendererFactory = { view -> FlexmarkRenderer(view) },
                identity = PageIdentityService(UuidV7IdProvider()),
                patcher = FrontmatterPatcher(),
                idMap = SqlDelightIdMapRepository(database),
                aliasRegistry = aliasRegistry,
                checkpoint = checkpoint,
                citations = CitationFactory(),
                rootRank = registry::rank,
                registeredRoots = registry.roots.map { it.name }.toSet(),
                // The offline reindex holds the same DELETE AUTHORITY the server does (the checkpoint listener
                // below, and the generation swap) - which since C0 means it holds NONE unless an AbsenceProof says
                // otherwise. A CLI that could reap on its own inference would be a second door into the corpus.
                retirements = SqlDelightRetirementRepository(database),
                // No search sync listener - only the §B3 checkpoint replace. The search engine is
                // rebuilt explicitly below, not diff-synced as a side effect of the page pass.
                listeners = listOf(IndexBuilder.PublicationListener(checkpoint::replaceFrom)),
                searchIndexer = searchIndexer,
            )
            val snapshot = builder.rebuild() // page-index pass; publishes the snapshot (the sync listener does not fire)
            requireCompleteGeneration(registry, snapshot, output) // ...and NOW check what the pass actually produced
            builder.rebuildSearchIndex() // atomic snapshot-read + clean engine rebuild - identical to the endpoint
            return snapshot
        } finally {
            // Release the object-store transport (LocalContentStore is not closeable).
            stores.values.forEach { (it as? AutoCloseable)?.close() }
        }
    }

    /**
     * One store per configured root - the offline twin of `contentModule`'s `RootStores`: main rides the
     * backend-selected store, and extras are LOCAL-only (D10 keeps object mode single-root). Name-keyed; its
     * insertion order is nobody's contract (the source list is built from `registry.roots`, and `IndexBuilder`
     * re-sorts by rank anyway). A failure part-way through closes whatever was already opened, so an unreachable
     * bucket cannot leak the ktor transport.
     */
    private fun openStores(
        config: PlainbaseConfig,
        registry: RootRegistry,
        database: PlainbaseDb,
        decorate: StoreDecorator,
    ): Map<RootName, ContentStore> {
        val stores = LinkedHashMap<RootName, ContentStore>()
        runCatching {
            // Main is explicit (it rides the backend-selected store); the fold sees ONLY extras, never re-selecting
            // primary by name. `decorate` wraps EVERY entry, main's included - it is the seam the mid-rebuild-
            // disappearance test drives, so dropping it here would disarm that test for main's own tree, silently.
            stores[registry.primary.name] = decorate(registry.primary.name, mainStore(config, database))
            registry.extras.forEach { root ->
                val store = LocalContentStore(
                    root = requireNotNull(root.localPath) { "extra root '${root.name}' must be local-backed" },
                    ignoreRules = IgnoreRules(),
                    // Extras inherit main's DATA_DIR exclusion: a legally-nested data dir is never walked as content.
                    exclusions = listOf(config.dataDir),
                    rootName = root.name,
                )
                stores[root.name] = decorate(root.name, store)
            }
        }.onFailure { failure ->
            if (failure is Error) throw failure
            stores.values.forEach { (it as? AutoCloseable)?.close() }
            throw failure
        }
        return stores
    }

    /** Main's tree: the CONTENT_DIR store locally, the hydrated DATA_DIR mirror in object mode. */
    private fun mainStore(config: PlainbaseConfig, database: PlainbaseDb): ContentStore = when (config.storage.backend) {
        StorageBackend.LOCAL -> LocalContentStore(
            root = config.mainContentRoot(),
            ignoreRules = IgnoreRules(),
            // The SAME DATA_DIR exclusion the server's store carries (ADR-0011): a legally-nested data
            // dir must never be walked as CONTENT, or the CLI indexes plainbase.db/search.db as pages and
            // assets. The server has always excluded it; these two never did, which is the scan-parity gap.
            exclusions = listOf(config.dataDir),
        )
        StorageBackend.OBJECT -> {
            // Object mode reindexes the DATA_DIR mirror (the bucket is the authority), hydrating it
            // first - under the DataDirLock already held above, race-free (the server is down).
            val dirtyPages = SqlDelightDirtyPageRepository(database)
            // Build (transport open) BEFORE hydrate, and close it on a hydrate failure so the ktor
            // client never leaks when the bucket is unreachable.
            val hybrid = ObjectContentStoreFactory.build(
                config,
                IgnoreRules(),
                dirtyPaths = { dirtyPages.all().map { it.path.path }.toSet() },
                isDirty = { dirtyPages.isDirty(RootedPath(RootName.PRIMARY, it)) },
            )
            runCatching {
                hybrid.hydrate()
            }.onFailure { failure ->
                if (failure is Error) throw failure
                hybrid.close()
                throw failure
            }
            hybrid
        }
    }

    /**
     * The PREFLIGHT: refuses the run up front unless EVERY configured root is there. The server can afford to skip an
     * unavailable root (`IndexBuilder` carries its last-good section forward, so the swap regenerates its
     * rows); a fresh CLI process has no last-good section to carry, so a skipped root would contribute NO
     * section and the generation swap would silently PURGE its search rows - while the summary line reported
     * a confident count for the roots that happened to be mounted. Derived state or not, an operator running
     * an offline reindex over a half-mounted corpus wants to hear about it, not to find out from search.
     *
     * This is a courtesy, NOT the guarantee: it answers about the corpus as it was BEFORE the pass, and a check
     * that runs before the thing it protects cannot speak for what happens during it. [requireCompleteGeneration]
     * is what actually holds the invariant.
     */
    private fun requireEveryRootAvailable(
        registry: RootRegistry,
        stores: Map<RootName, ContentStore>,
        output: CommandOutput,
    ) {
        val missing = registry.roots.filterNot { stores.getValue(it.name).available() }
        if (missing.isEmpty()) return
        missing.forEach { root ->
            output.error("reindex: root '${root.name}' is not available (${root.localPath ?: "object backend"})")
        }
        output.error(
            "reindex: refusing to rebuild - the search index is rebuilt as ONE generation over every root, so " +
                "running now would drop the missing root(s) from it. Restore the path(s), or remove the root(s) " +
                "from the roots {} block if they are gone for good.",
        )
        throw IllegalStateException("configured root(s) not available: ${missing.joinToString { it.name.value }}")
    }

    /**
     * The POSTCONDITION, and the guard that actually holds the line: the snapshot about to be swapped in carries a
     * section for EVERY registered root, or nothing is published at all.
     *
     * The preflight closes the door and then stops watching it. A root that vanishes DURING [IndexBuilder.rebuild] is
     * SKIPPED by the carry-forward rule - correct for the server, which still holds that root's last-good section, but
     * a fresh CLI process has nothing to carry, so the root contributes NO section and the generation swap below would
     * purge its search rows while this command exited 0 and printed a confident summary. The window is real (a NAS
     * unmounting mid-scan is precisely when someone is running an offline reindex), and no check that runs BEFORE the
     * pass can see into it.
     *
     * So it asks the only question that cannot be raced: not "is every root there?" but "is every root IN what we just
     * built?". A missing root aborts before the swap - nothing is written, the previous generation stands untouched,
     * and the operator is told which root went away.
     */
    private fun requireCompleteGeneration(registry: RootRegistry, snapshot: PageIndex, output: CommandOutput) {
        val indexed = snapshot.sections.map { it.root }.toSet()
        val missing = registry.roots.filterNot { it.name in indexed }
        if (missing.isEmpty()) return
        missing.forEach { root ->
            output.error(
                "reindex: root '${root.name}' went away while it was being indexed (${root.localPath ?: "object backend"})",
            )
        }
        output.error(
            "reindex: nothing was written - the rebuilt index covers ${indexed.size} of ${registry.roots.size} configured " +
                "root(s), and swapping THAT in would drop the missing root(s) from search. The previous search index is " +
                "untouched; restore the path(s) and run it again.",
        )
        throw IllegalStateException("root(s) missing from the rebuilt index: ${missing.joinToString { it.name.value }}")
    }

    /**
     * The one summary line (the CLI output contract). A single-root install keeps the pinned legacy line
     * verbatim; a multi-root install reports the WHOLE corpus and its per-root split - which is what the
     * generation swap actually re-derived.
     */
    private fun summary(snapshot: PageIndex, config: PlainbaseConfig): String {
        val sections = snapshot.sections
        if (sections.size <= 1) return "reindex: rebuilt the search index for ${snapshot.pages.size} page(s) under ${indexedRoot(config)}"
        val breakdown = sections.joinToString { "${it.root} (${it.pages.size})" }
        return "reindex: rebuilt the search index for ${snapshot.pages.size} page(s) across ${sections.size} roots: $breakdown"
    }

    /** The tree the rebuild indexed for a single-root install: main's content root locally, the DATA_DIR mirror in object mode. */
    private fun indexedRoot(config: PlainbaseConfig) = when (config.storage.backend) {
        StorageBackend.LOCAL -> config.mainContentRoot()
        StorageBackend.OBJECT -> config.dataDir.resolve("mirror")
    }

    private const val USAGE = "usage: plainbase reindex"

    /** Production opens the stores and uses them as they come. */
    private val NO_DECORATION: StoreDecorator = { _, store -> store }
}

/** Wraps one root's freshly-opened store on its way into the source list (see [ReindexCommand.run]'s internal overload). */
internal typealias StoreDecorator = (RootName, ContentStore) -> ContentStore
