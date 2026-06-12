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
    single<IndexBuilder.PublicationListener>(named("searchSync")) {
        val indexer = get<SearchIndexer>()
        IndexBuilder.PublicationListener(indexer::sync)
    }
}
