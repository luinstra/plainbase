@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.domain.service.ReindexResult
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread

/**
 * `POST /api/v1/admin/reindex` (S8 Resolution 1, A3-gated). The route mirrors rescan: same `Route.adminRoute`
 * home, the manage `check()` gate, the single-flight 409 `reindex_in_flight` guard (now INSIDE the facade), and
 * the flag-release-after-failure leg. Re-pinned auth-on under loopback-dev (the harness's open mode), so a manage
 * call reaches the rebuild byte-identically to pre-auth.
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

    test("the single-flight returns InFlight to a concurrent reindex; the flag releases for the next call") {
        // The §A5 single-flight lives inside GuardedMutatingFacade; drive it deterministically by parking the
        // first reindex on a blocking search engine and firing a second from another thread.
        val engine = BlockingProvider()
        ReindexFacadeHarness(Fixtures.demoDocs, engine).use { h ->
            val firstStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            engine.onRebuild = {
                firstStarted.countDown()
                release.await()
            }
            val first = thread { h.mutate.reindex(Principal.Anonymous) }
            firstStarted.await(5, TimeUnit.SECONDS) shouldBe true

            // A concurrent reindex sees the flip fail → InFlight (the route's 409).
            h.mutate.reindex(Principal.Anonymous).shouldBeInstanceOf<ReindexResult.InFlight>()

            release.countDown()
            first.join()
            // The flag released, so the next reindex proceeds to Done.
            engine.onRebuild = {}
            h.mutate.reindex(Principal.Anonymous).shouldBeInstanceOf<ReindexResult.Done>()
        }
    }

    test("a thrown rebuild yields a 500 envelope but releases the flag; a follow-up POST succeeds") {
        val engine = FailThenPassProvider()
        ReindexFacadeHarness(Fixtures.demoDocs, engine).use { harness ->
            testApplication {
                application { plainbaseModule(harness.routeContext) }

                engine.fail.store(true)
                val failed = client.post("/api/v1/admin/reindex")
                failed.status shouldBe HttpStatusCode.InternalServerError
                Json.parseToJsonElement(failed.bodyAsText()).jsonObject
                    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "internal_error"

                // The finally{} released the flag despite the throw — a follow-up is not wedged at 409.
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

/** A search engine whose [rebuild] runs [onRebuild] — used to park the first reindex so the second sees InFlight. */
private class BlockingProvider : SearchProvider {
    var onRebuild: () -> Unit = {}

    override fun index(pages: List<PageDocuments>) = Unit
    override fun delete(ids: Collection<com.plainbase.domain.page.PageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(0, emptyList())
    override fun rebuild(pages: Sequence<PageDocuments>) = onRebuild()
    override fun indexedState(): Map<com.plainbase.domain.page.PageId, PageSearchState> = emptyMap()
}

/**
 * A [RouteContext] over the real index pass but a controllable search engine, so the reindex single-flight + the
 * failure path (flag release) can be exercised without a real FTS5 throw. Exposes the [MutatingFacade] directly
 * for the unit-level single-flight assertion.
 */
private class ReindexFacadeHarness(
    root: java.nio.file.Path,
    engine: SearchProvider,
) : AutoCloseable {

    private val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
    private val indexer = SearchIndexer(engine, SectionSplitter())
    private val harness = com.plainbase.domain.service.IndexHarness(root, contentStore = store, searchIndexer = indexer)

    val routeContext: RouteContext
    val mutate get() = routeContext.mutate

    init {
        harness.builder.rebuild()
        routeContext = harness.testRouteContext(contentStore = store, searchProvider = engine)
    }

    override fun close() = harness.close()
}
