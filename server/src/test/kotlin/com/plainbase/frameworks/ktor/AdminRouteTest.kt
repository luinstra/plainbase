@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * `POST /api/v1/admin/reindex` (S8 Resolution 1, acceptance criteria 1/2/4-endpoint-half). The
 * route mirrors rescan: same `Route.adminRoute` home, R7-unauthenticated, the single-flight 409
 * `reindex_in_flight` guard, and the `finally` flag release. The flag-release-after-failure leg
 * drives the route over a deliberately-throwing search engine.
 */
class AdminRouteTest : FunSpec({

    test("reindex succeeds: 200 {status:ok, pages:N}, and a known term is still searchable afterward") {
        restTest(Fixtures.demoDocs) { harness ->
            val response = client.post("/api/v1/admin/reindex")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.getValue("status").jsonPrimitive.content shouldBe "ok"
            body.getValue("pages").jsonPrimitive.content.toInt() shouldBe harness.builder.current.pages.size

            // The engine is whole after a full generation swap: a known term still returns its hit.
            val total = harness.searchProvider.search(SearchQuery(text = "blameless", limit = 20, offset = 0)).total
            total shouldBeGreaterThan 0L
        }
    }

    test("concurrent reindex returns 409 reindex_in_flight; the flag releases for the next request") {
        restTest(Fixtures.demoDocs) { harness ->
            // The flag IS the contract under test — set it directly to simulate an in-flight reindex,
            // avoiding a flaky race between two real rebuilds (the addendum's deterministic approach).
            harness.services.reindexInFlight.store(true)
            val conflict = client.post("/api/v1/admin/reindex")
            conflict.status shouldBe HttpStatusCode.Conflict
            val error = Json.parseToJsonElement(conflict.bodyAsText()).jsonObject.getValue("error").jsonObject
            error.getValue("code").jsonPrimitive.content shouldBe "reindex_in_flight"

            harness.services.reindexInFlight.store(false)
            client.post("/api/v1/admin/reindex").status shouldBe HttpStatusCode.OK
        }
    }

    test("a thrown rebuild yields a 500 envelope but releases the flag; a follow-up POST succeeds") {
        // First failing rebuild (the engine throws), then a healthy one, drives both legs.
        val engine = FailThenPassProvider()
        val harness = ReindexFailureHarness(Fixtures.demoDocs, engine)
        harness.use {
            testApplication {
                application { plainbaseModule(harness.services) }

                engine.fail.store(true)
                val failed = client.post("/api/v1/admin/reindex")
                failed.status shouldBe HttpStatusCode.InternalServerError
                Json.parseToJsonElement(failed.bodyAsText()).jsonObject
                    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "internal_error"

                // The finally{} released the flag despite the throw — a follow-up is not wedged at 409.
                harness.services.reindexInFlight.load() shouldBe false
                engine.fail.store(false)
                client.post("/api/v1/admin/reindex").status shouldBe HttpStatusCode.OK
            }
        }
    }
})

/** A search engine whose [rebuild] throws while [fail] is set — drives the reindex failure path. */
private class FailThenPassProvider : SearchProvider {
    val fail = AtomicBoolean(false)

    override fun index(pages: List<PageDocuments>) = Unit
    override fun delete(ids: Collection<com.plainbase.domain.page.PageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(0, emptyList())
    override fun rebuild(pages: Sequence<PageDocuments>) {
        if (fail.load()) throw IllegalStateException("rebuild blew up (deliberately)")
    }
    override fun indexedState(): Map<com.plainbase.domain.page.PageId, PageSearchState> = emptyMap()
}

/**
 * A [RestServices] over the real index pass but a controllable search engine, so the reindex
 * failure path (flag release) can be exercised without a real FTS5 throw.
 */
private class ReindexFailureHarness(
    root: java.nio.file.Path,
    engine: SearchProvider,
) : AutoCloseable {

    private val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
    private val indexer = SearchIndexer(engine, SectionSplitter())
    private val harness = com.plainbase.domain.service.IndexHarness(root, contentStore = store, searchIndexer = indexer)

    val services: RestServices

    init {
        harness.builder.rebuild()
        services = RestServices(
            indexBuilder = harness.builder,
            pageService = com.plainbase.domain.service.PageService(
                harness.builder,
                harness.registry,
                com.plainbase.domain.service.CitationFactory(),
            ),
            searchService = com.plainbase.domain.service.SearchService(provider = engine, indexBuilder = harness.builder),
            aliasRegistry = harness.registry,
            contentStore = store,
            writePipeline = harness.writePipeline(),
        )
    }

    override fun close() = harness.close()
}
