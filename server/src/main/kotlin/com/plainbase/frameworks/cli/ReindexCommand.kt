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
 * **It reports on EVERY configured root, and it publishes a COMPLETE generation or NOTHING.** The rebuild is
 * one generation swap over the configured corpus, so a partial source list is not a successful partial refresh.
 * The search rebuild carries engine rows that are not in the current durable retired-unbound set; omission alone
 * is not delete authority. Two guards, and the second is the one that holds the line:
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
                    // The stdout summary remains the fresh page-pass count and per-root breakdown. Search's
                    // generation swap separately reports accepted input internally after durable retirement filtering.
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
     * **Every configured root is a source.** A main-only source list would omit other roots from the fresh page pass
     * and report an incomplete corpus, even though unretired engine rows may carry. The registry drives the source
     * list exactly as it drives `RootStores` in `contentModule`.
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
            val idMap = SqlDelightIdMapRepository(database)
            val searchIndexer = SearchIndexer(
                provider = Fts5SearchProvider(searchDb),
                splitter = SectionSplitter(),
                retiredUnboundIds = idMap::retiredUnboundIds,
                isRetiredUnbound = idMap::isRetiredUnbound,
            )
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
                idMap = idMap,
                aliasRegistry = aliasRegistry,
                checkpoint = checkpoint,
                citations = CitationFactory(),
                rootRank = registry::rank,
                registeredRoots = registry.roots.map { it.name }.toSet(),
                // The offline reindex uses the same durable retirement repository as the server. The checkpoint
                // listener consumes pass-local applied proofs, while SearchIndexer reads current retired-unbound
                // rows for the generation swap; a CLI that could reap from snapshot omission would be a second
                // door into the corpus.
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
     * The PREFLIGHT: reports and refuses the run up front unless EVERY configured root is there. The search rebuild
     * carries unretired engine rows even when a fresh CLI process has no last-good section, but an offline reindex
     * still has a complete-configured-corpus reporting contract: an operator running over a half-mounted corpus
     * should hear about it rather than receive a confident count for only the roots that happened to be mounted.
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
            "reindex: refusing to rebuild - offline reindex requires every configured root to be available. " +
                "Restore the path(s), or remove the root(s) from the roots {} block if they are gone for good.",
        )
        throw IllegalStateException("configured root(s) not available: ${missing.joinToString { it.name.value }}")
    }

    /**
     * Requires a section for every registered root before the search generation swap. A fresh CLI process cannot
     * carry a previous in-memory section if a root vanishes during the page pass. The command therefore refuses an
     * incomplete result even though unretired engine rows could survive the swap. The previous search generation
     * remains unchanged; the page pass may already update content or application metadata before this refusal.
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
            "reindex: refusing to rebuild - the page pass covers ${indexed.size} of ${registry.roots.size} configured " +
                "root(s). The previous search generation is unchanged; the page pass may already have updated " +
                "content or application metadata. Restore the path(s) and run it again.",
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
