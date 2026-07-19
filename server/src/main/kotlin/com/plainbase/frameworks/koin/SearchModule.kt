package com.plainbase.frameworks.koin

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Wires the embedded search engine (chunk S2): [SearchDb] over `DATA_DIR/search.db` (closed with
 * the Koin context), the [Fts5SearchProvider] behind the domain port, and the §B4 seam —
 * [SearchIndexer.sync] registered as an [IndexBuilder.PublicationListener] (collected by
 * `indexModule`'s `getAll()`), so every published snapshot syncs the engine inside the serialized
 * rebuild. The qualifier keeps this definition distinct from S5's checkpoint listener.
 */
val searchModule = module {
    single { SearchDb(get<PlainbaseConfig>().searchDatabasePath) } onClose { it?.close() }
    single<SearchProvider> { Fts5SearchProvider(get()) }
    single { SectionSplitter() }
    single { SearchIndexer(get(), get()) }
    // The listener seam hands each listener its delete authority as rooted ids; SearchIndexer.sync still keys by
    // bare id (C3 roots it), so bridge the rooted retired set to its ids here rather than in the engine.
    single<IndexBuilder.PublicationListener>(named("searchSync")) {
        val indexer = get<SearchIndexer>()
        IndexBuilder.PublicationListener { snap, retired -> indexer.sync(snap, retired.mapTo(mutableSetOf()) { it.id }) }
    }
}
