package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [RestHarness] variant with a LIVE watcher (S8 criterion 7). Unlike [RestHarness] — which points
 * [LocalContentStore] at the root and rebuilds once at init, no watcher — this copies a fixture tree
 * into a fresh temp dir (so edits never touch the frozen `fixtures/demo-docs`) and wires the
 * production watcher exactly as `Application.serve()` does: a [RebuildScheduler] over
 * `store.watch { scheduler.schedule() }`. An on-disk edit therefore debounces → rebuilds → syncs the
 * engine end to end, which is what the 5-second searchable-after-edit criterion exercises.
 */
class WatchingRestHarness(fixtureRoot: Path) : AutoCloseable {

    /** The temp copy edits land on (never the frozen fixtures). */
    val root: Path = Files.createTempDirectory("plainbase-watch-content")

    private val store: LocalContentStore
    private val searchDir = Files.createTempDirectory("plainbase-watch-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    private val searchProvider = Fts5SearchProvider(searchDb)
    private val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())
    private val harness: IndexHarness
    private val scheduler: RebuildScheduler
    private val watch: AutoCloseable

    val services: RestServices

    init {
        copyTree(fixtureRoot, root)
        store = LocalContentStore(root)
        harness = IndexHarness(
            root,
            contentStore = store,
            listeners = listOf(IndexBuilder.PublicationListener(searchIndexer::sync)),
            searchIndexer = searchIndexer,
        )
        // Mirror Application.serve(): watcher registers, then the first rebuild runs.
        scheduler = RebuildScheduler(rebuild = { harness.builder.rebuild() })
        watch = store.watch { scheduler.schedule() }
        harness.builder.rebuild()
        services = RestServices(
            indexBuilder = harness.builder,
            pageService = PageService(harness.builder, harness.registry, CitationFactory()),
            searchService = SearchService(provider = searchProvider, indexBuilder = harness.builder),
            aliasRegistry = harness.registry,
            contentStore = store,
            writePipeline = harness.writePipeline(),
            citations = CitationFactory(),
            idProvider = UuidV7IdProvider(),
            maxWriteBodyBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
            history = NoOpHistoryProvider,
        )
    }

    val idMap: IdMapRepository get() = harness.idMap

    override fun close() {
        watch.close()
        scheduler.close()
        harness.close()
        searchDb.close()
        searchDir.toFile().deleteRecursively()
        root.toFile().deleteRecursively()
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target)
                }
            }
        }
    }
}

/** Runs [block] inside a `testApplication` serving [plainbaseModule] over a watcher-attached harness. */
fun watchingRestTest(
    fixtureRoot: Path,
    block: suspend ApplicationTestBuilder.(WatchingRestHarness) -> Unit,
) {
    WatchingRestHarness(fixtureRoot).use { harness ->
        testApplication {
            application { plainbaseModule(harness.services) }
            block(harness)
        }
    }
}
