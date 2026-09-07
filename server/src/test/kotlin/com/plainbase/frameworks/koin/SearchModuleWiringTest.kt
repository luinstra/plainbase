package com.plainbase.frameworks.koin

import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.search.Fts5SearchProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * DI wiring for the S2 search stack, end to end through the REAL module graph: [searchModule]
 * resolves the provider behind the port, and — the §B4 seam this chunk exists to close —
 * `indexModule`'s `getAll()` collects the registered `SearchIndexer` publication listener, so an
 * `IndexBuilder.rebuild()` leaves the engine synced WITHOUT anyone calling the indexer by hand.
 * A "declared but not collected" listener would make every rebuild silently skip search sync.
 */
class SearchModuleWiringTest : FunSpec({

    test("rebuild through the production wiring syncs the search engine via the publication listener") {
        withTempTree(seed = { root ->
            writePage(root, "docs/widget.md", "# Widget Catalog\n\nflux capacitors and sprockets\n")
        }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val env = mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString())
                val app = koinApplication {
                    modules(
                        module { single { PlainbaseConfig.fromEnv(env) } }, // configModule, env pinned to temp dirs
                        contentModule,
                        repositoryModule,
                        historyModule,
                        indexModule,
                        searchModule,
                    )
                }
                try {
                    val provider = app.koin.get<SearchProvider>()
                    provider.shouldBeInstanceOf<Fts5SearchProvider>()
                    app.koin.getAll<IndexBuilder.PublicationListener>() shouldHaveSize 1

                    app.koin.get<IndexBuilder>().rebuild()
                    val results = provider.search(SearchQuery("sprockets", 10, 0))
                    results.total shouldBe 1L
                } finally {
                    app.close() // also closes SearchDb (onClose)
                }
            }
        }
    }

    test("production search-only rebuild uses shared durable retirement authority and leaves checkpoints unchanged") {
        withTempTree(seed = { root ->
            writePage(root, "docs/widget.md", "# Widget Catalog\n\nretirement wiring term\n")
            writePage(root, "docs/control.md", "# Control Page\n\nretirement control term\n")
        }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val env = mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString())
                val app = koinApplication {
                    modules(
                        module { single { PlainbaseConfig.fromEnv(env) } },
                        contentModule,
                        repositoryModule,
                        historyModule,
                        checkpointModule,
                        indexModule,
                        searchModule,
                    )
                }
                try {
                    app.koin.getAll<IndexBuilder.PublicationListener>() shouldHaveSize 2
                    val builder = app.koin.get<IndexBuilder>()
                    val provider = app.koin.get<SearchProvider>()
                    val idMap = app.koin.get<IdMapRepository>()
                    val checkpoints = app.koin.get<PageCheckpointRepository>()
                    val indexer = app.koin.get<SearchIndexer>()
                    val before = builder.rebuild()
                    val victim = before.pages.single { it.path.value == "docs/widget.md" }
                    val control = before.pages.single { it.path.value == "docs/control.md" }
                    val oldRootedPath = RootedPath(RootName.PRIMARY, victim.path)
                    val checkpointsBefore = checkpoints.load()
                    val replacementId = PageId.require("01900000-0000-7000-8000-000000000201")

                    idMap.bind(oldRootedPath, replacementId, materialized = true)
                    idMap.bindingInRoot(RootName.PRIMARY, victim.id) shouldBe null
                    val retired = idMap.retiredAt(RootName.PRIMARY, victim.id)
                    retired shouldNotBe null
                    retired!!.path shouldBe oldRootedPath

                    builder.rebuildSearchIndex() shouldBe 1
                    (builder.current === before) shouldBe true
                    provider.indexedState().keys shouldBe setOf(control.rooted)
                    val controlState = provider.indexedState()[control.rooted]
                    controlState shouldNotBe null
                    controlState!!.contentHash shouldBe control.contentHash
                    controlState.path shouldBe control.path
                    provider.search(SearchQuery("retirement control term", 10, 0)).total shouldBe 1L
                    provider.search(SearchQuery("retirement wiring term", 10, 0)).total shouldBe 0L
                    checkpoints.load() shouldBe checkpointsBefore
                    shouldThrow<IllegalStateException> { indexer.syncPage(victim) }
                } finally {
                    app.close()
                }
            }
        }
    }
})
