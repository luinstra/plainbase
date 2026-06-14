package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path

/**
 * The chunk-6 route-test harness: the chunk-5 [IndexHarness] (real store, real renderer, real
 * in-memory SQLite) plus the [RestServices] bundle `plainbaseModule` serves from — the production
 * graph minus Koin. [seed] runs against the id_map BEFORE the first rebuild, which is how the
 * golden tests inject their stable UUID literals (§A4 golden policy).
 *
 * Since chunk S4 the bundle also carries the real search stack ([SearchDb] in a temp dir behind
 * [Fts5SearchProvider], synced by the same [SearchIndexer] publication listener `searchModule`
 * registers), so route tests exercise PB-SEARCH-1 against the embedded engine for real.
 */
class RestHarness(
    root: Path,
    seed: (IdMapRepository) -> Unit = {},
) : AutoCloseable {

    private val store = LocalContentStore(root)
    private val searchDir = Files.createTempDirectory("plainbase-rest-search")
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

    val services: RestServices

    init {
        seed(harness.idMap)
        harness.builder.rebuild()
        services = RestServices(
            indexBuilder = harness.builder,
            pageService = PageService(harness.builder, harness.registry, CitationFactory()),
            searchService = SearchService(provider = searchProvider, indexBuilder = harness.builder),
            aliasRegistry = harness.registry,
            contentStore = store,
        )
    }

    override fun close() {
        harness.close()
        searchDb.close()
        searchDir.toFile().deleteRecursively()
    }
}

/**
 * Runs [block] inside a `testApplication` serving [plainbaseModule] over a [RestHarness] for
 * [root]. Redirect tests need raw 30x responses, so [restClient] builds the non-following client.
 */
fun restTest(
    root: Path,
    seed: (IdMapRepository) -> Unit = {},
    block: suspend ApplicationTestBuilder.(RestHarness) -> Unit,
) {
    RestHarness(root, seed).use { harness ->
        testApplication {
            application { plainbaseModule(harness.services) }
            block(harness)
        }
    }
}

/** A test client that surfaces 30x responses instead of following them (the redirect assertions). */
fun ApplicationTestBuilder.restClient(): HttpClient = createClient { followRedirects = false }
