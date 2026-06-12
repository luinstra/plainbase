package com.plainbase.frameworks.koin

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.search.Fts5SearchProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
})
