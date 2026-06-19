package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * `plainbase reindex` — the OFFLINE/ops full-search-rebuild path (S8 Resolution 2). It runs the
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

    /** Entry point for the `main` dispatch: real env config, exit-code result. */
    fun runAsMain(args: List<String>): Int = run(args, PlainbaseConfig.fromEnv())

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
                "reindex: a Plainbase server is holding ${config.dataDir} — stop it, or use " +
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
                    println("reindex: rebuilt the search index for $pages page(s) under ${config.contentDir}")
                }
            } finally {
                driver.close()
            }
        }
    }

    /**
     * Builds the offline graph (the production stack minus HTTP + Koin) with NO `SearchIndexer`
     * publication listener — the page pass must not auto-diff-sync; the explicit
     * `rebuildSearchIndex()` below is the single clean generation swap, the SAME atomic path the
     * endpoint uses. The checkpoint listener still runs so down-time-move aliasing stays correct.
     * Returns the page count rebuilt into the engine.
     */
    private fun rebuildSearchIndex(config: PlainbaseConfig, driver: SqlDriver, searchDb: SearchDb): Int {
        val database = DatabaseFactory.createDatabase(driver)
        val store = LocalContentStore(root = config.contentDir, ignoreRules = IgnoreRules())
        val registry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
        val checkpoint = SqlDelightPageCheckpointRepository(database)
        val searchIndexer = SearchIndexer(Fts5SearchProvider(searchDb), SectionSplitter())
        val builder = IndexBuilder(
            contentStore = store,
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7IdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = SqlDelightIdMapRepository(database),
            aliasRegistry = registry,
            checkpoint = checkpoint,
            citations = CitationFactory(),
            // The CLI reindex rebuilds the search engine only; search never reads `commit`, so no git
            // process is spawned here (the snapshot's commit fields stay null — harmless for this path).
            history = NoOpHistoryProvider,
            // No search sync listener — only the §B3 checkpoint replace. The search engine is
            // rebuilt explicitly below, not diff-synced as a side effect of the page pass.
            listeners = listOf(IndexBuilder.PublicationListener(checkpoint::replaceFrom)),
            searchIndexer = searchIndexer,
        )
        builder.rebuild() // page-index pass; publishes the snapshot (the sync listener does not fire)
        return builder.rebuildSearchIndex() // atomic snapshot-read + clean engine rebuild — identical to the endpoint
    }

    private const val USAGE = "usage: plainbase reindex"
}
