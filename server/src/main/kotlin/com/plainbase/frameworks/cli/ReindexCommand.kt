package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentStore
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
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * `plainbase reindex` - the OFFLINE/ops full-search-rebuild path. It runs the
 * page-index pass and then a clean generation-swap rebuild of `DATA_DIR/search.db` from the
 * resulting snapshot, the SAME atomic `IndexBuilder.rebuildSearchIndex()` the endpoint uses.
 *
 * **Prefer the endpoint on a RUNNING instance.** `POST /api/v1/admin/reindex` reindexes in-process
 * against the live snapshot with the single-flight 409 guard. This CLI is for when the server is
 * down, or for a scripted operational reindex.
 *
 * **It refuses to run while a server is up.** The two would be separate JVM processes with separate
 * write monitors, and while SQLite WAL + `busy_timeout` prevent corruption they do NOT prevent the
 * CLI silently publishing an OLDER generation over the server's newer one (a freshness regression,
 * the cross-process twin of the in-process stale-snapshot defect). So it acquires the DATA_DIR
 * advisory lock ([DataDirLock]) FIRST and exits 1 if a server holds it.
 *
 * stdout is a CLI output contract (`println` by design, like `adopt`/`spike`): the one summary
 * line below. Diagnostics (engine generation logs, failures) stay on the logging facade.
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
    fun runAsMain(args: List<String>): Int {
        val config = PlainbaseConfig.loadForCommand("reindex") ?: return 1
        return run(args, config)
    }

    /** Exit codes: 0 success / 1 runtime failure (incl. a server holding the lock) / 2 usage error. */
    fun run(args: List<String>, config: PlainbaseConfig): Int {
        if (args.isNotEmpty()) {
            System.err.println(USAGE) // reindex takes no flags
            return 2
        }
        return try {
            config.requireContentDir() // inside try → a bad config exits 1, honoring the contract (not a stack trace)
            reindex(config)
            0
        } catch (e: Exception) {
            logger.error(e) { "reindex failed" } // diagnostics via the facade, not println
            1
        }
    }

    private fun reindex(config: PlainbaseConfig) {
        // Resolution 1b: acquire the DATA_DIR lock FIRST. A live server holds it for its lifetime;
        // writing search.db underneath it would risk the cross-process stale-generation regression.
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            System.err.println(
                "reindex: a Plainbase server is holding ${config.dataDir} - stop it, or use " +
                    "POST /api/v1/admin/reindex on the running server",
            )
            throw IllegalStateException("DATA_DIR ${config.dataDir} is locked by a running server")
        }
        lock.use {
            val driver = DatabaseFactory.createDriver(config.appDatabasePath)
            try {
                SearchDb(config.searchDatabasePath).use { searchDb ->
                    val pages = rebuildSearchIndex(config, driver, searchDb)
                    // The ONLY sanctioned println here (CLI output contract, like adopt/spike).
                    println("reindex: rebuilt the search index for $pages page(s) under ${indexedRoot(config)}")
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
     * Returns the page count rebuilt into the engine.
     */
    private fun rebuildSearchIndex(config: PlainbaseConfig, driver: SqlDriver, searchDb: SearchDb): Int {
        val database = DatabaseFactory.createDatabase(driver)
        val store: ContentStore = when (config.storage.backend) {
            StorageBackend.LOCAL -> LocalContentStore(root = config.mainContentRoot(), ignoreRules = IgnoreRules())
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
                    isDirty = { dirtyPages.isDirty(RootedPath(RootName.MAIN, it)) },
                )
                try {
                    hybrid.hydrate()
                } catch (e: Exception) {
                    hybrid.close()
                    throw e
                }
                hybrid
            }
        }
        val aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
        val checkpoint = SqlDelightPageCheckpointRepository(database)
        val searchIndexer = SearchIndexer(Fts5SearchProvider(searchDb), SectionSplitter())
        // The CLI indexes main's tree only, but the registry still seats every configured root:
        // the D16/D17 machinery must see extras as registered, never detached.
        val rootRegistry = RootRegistry.of(config.roots.list)
        val builder = IndexBuilder(
            // The CLI reindex rebuilds the search engine only; search never reads `commit`, so no git
            // process is spawned here (the snapshot's commit fields stay null - harmless for this path).
            sources = listOf(IndexBuilder.Source(root = rootRegistry.main, store = store, history = NoOpHistoryProvider)),
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7IdProvider(), rootRegistry::rank),
            patcher = FrontmatterPatcher(),
            idMap = SqlDelightIdMapRepository(database),
            aliasRegistry = aliasRegistry,
            checkpoint = checkpoint,
            citations = CitationFactory(),
            rootRank = rootRegistry::rank,
            registeredRoots = rootRegistry.roots.map { it.name }.toSet(),
            // No search sync listener - only the §B3 checkpoint replace. The search engine is
            // rebuilt explicitly below, not diff-synced as a side effect of the page pass.
            listeners = listOf(IndexBuilder.PublicationListener(checkpoint::replaceFrom)),
            searchIndexer = searchIndexer,
        )
        try {
            builder.rebuild() // page-index pass; publishes the snapshot (the sync listener does not fire)
            return builder.rebuildSearchIndex() // atomic snapshot-read + clean engine rebuild - identical to the endpoint
        } finally {
            (store as? AutoCloseable)?.close() // release the object-store transport (LocalContentStore is not closeable)
        }
    }

    /** The tree the rebuild actually indexed: main's content root locally, the DATA_DIR mirror in object mode. */
    private fun indexedRoot(config: PlainbaseConfig) = when (config.storage.backend) {
        StorageBackend.LOCAL -> config.mainContentRoot()
        StorageBackend.OBJECT -> config.dataDir.resolve("mirror")
    }

    private const val USAGE = "usage: plainbase reindex"
}
