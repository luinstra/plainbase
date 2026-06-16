package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path

/**
 * The W3a (B2) write-test harness: like [RestHarness], but it COPIES the fixture tree into a fresh
 * temp dir per test before wiring the [LocalContentStore], so a `PUT` (which mutates content on
 * disk) NEVER dirties the committed `Fixtures.demoDocs`. Read-only tests keep using [RestHarness];
 * every W3a write/golden test uses this.
 *
 * [historyHook] is injectable so the WrittenButUnindexed test can wire a throwing post-write hook
 * (the same seam [IndexHarness.writePipeline] exposes).
 */
class WriteRestHarness(
    fixtureRoot: Path,
    seed: (IdMapRepository) -> Unit = {},
    historyHook: WriteHistoryHook = WriteHistoryHook { _, _ -> },
    private val storeOverride: ((LocalContentStore) -> com.plainbase.domain.content.ContentStore)? = null,
) : AutoCloseable {

    /** A private, mutable copy of the fixture tree — deleted on [close]; the committed tree is never touched. */
    val root: Path = Files.createTempDirectory("plainbase-write-root")
    private val store = LocalContentStore(root)
    private val pipelineStore = storeOverride?.invoke(store) ?: store
    private val searchDir = Files.createTempDirectory("plainbase-write-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    val searchProvider = Fts5SearchProvider(searchDb)
    private val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())
    private val harness = IndexHarness(
        root,
        contentStore = store,
        listeners = listOf(IndexBuilder.PublicationListener(searchIndexer::sync)),
        searchIndexer = searchIndexer,
    )

    val idMap: IdMapRepository get() = harness.idMap
    val builder get() = harness.builder
    val registry get() = harness.registry
    val dirtyPages get() = harness.dirtyPages

    val services: RestServices

    init {
        copyTree(fixtureRoot, root)
        seed(harness.idMap)
        harness.builder.rebuild()
        // The pipeline may run over a wrapped store (the failing-store stand-in for the Unreadable test);
        // the index/search wiring always uses the real copy so the snapshot is genuine.
        val pipeline = harness.writePipeline(historyHook, store = pipelineStore)
        services = RestServices(
            indexBuilder = harness.builder,
            pageService = PageService(harness.builder, harness.registry, CitationFactory()),
            searchService = SearchService(provider = searchProvider, indexBuilder = harness.builder),
            aliasRegistry = harness.registry,
            contentStore = store,
            writePipeline = pipeline,
            citations = CitationFactory(),
            maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
        )
    }

    /** Reads the current on-disk bytes at a fixture-relative [relativePath] (byte-fidelity assertions). */
    fun diskBytes(relativePath: String): ByteArray = Files.readAllBytes(root.resolve(relativePath))

    override fun close() {
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

/**
 * Runs [block] inside a `testApplication` serving [plainbaseModule] over a [WriteRestHarness] built
 * from a temp COPY of [fixtureRoot]. Every W3a write/golden test uses this so it is idempotent and
 * never dirties the committed fixture tree.
 */
fun writeRestTest(
    fixtureRoot: Path,
    seed: (IdMapRepository) -> Unit = {},
    historyHook: WriteHistoryHook = WriteHistoryHook { _, _ -> },
    storeOverride: ((LocalContentStore) -> com.plainbase.domain.content.ContentStore)? = null,
    block: suspend ApplicationTestBuilder.(WriteRestHarness) -> Unit,
) {
    WriteRestHarness(fixtureRoot, seed, historyHook, storeOverride).use { harness ->
        testApplication {
            application { plainbaseModule(harness.services) }
            block(harness)
        }
    }
}

/** A non-redirect-following client (parallels [restClient]) for write tests that need raw responses. */
fun ApplicationTestBuilder.writeClient(): HttpClient = createClient { followRedirects = false }
