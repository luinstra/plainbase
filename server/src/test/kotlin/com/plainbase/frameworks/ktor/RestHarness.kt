package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageService
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path

/**
 * The chunk-6 route-test harness: the chunk-5 [IndexHarness] (real store, real renderer, real
 * in-memory SQLite) plus the [RestServices] bundle `plainbaseModule` serves from — the production
 * graph minus Koin. [seed] runs against the id_map BEFORE the first rebuild, which is how the
 * golden tests inject their stable UUID literals (§A4 golden policy).
 */
class RestHarness(
    root: Path,
    seed: (IdMapRepository) -> Unit = {},
) : AutoCloseable {

    private val store = LocalContentStore(root)
    private val harness = IndexHarness(root, contentStore = store)

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
            aliasRegistry = harness.registry,
            contentStore = store,
        )
    }

    override fun close() = harness.close()
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
