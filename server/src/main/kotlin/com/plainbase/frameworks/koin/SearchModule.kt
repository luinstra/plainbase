package com.plainbase.frameworks.koin

import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Path

/**
 * Wires the embedded search engine (chunk S2): [SearchDb] over `DATA_DIR/search.db`, with its close callback
 * supplied by the caller, the [Fts5SearchProvider] behind the domain port, and the §B4 seam —
 * [SearchIndexer.sync] registered as an [IndexBuilder.PublicationListener] (collected by
 * `indexModule`'s `getAll()`), so every published snapshot syncs the engine inside the serialized
 * rebuild. The indexer and builder share the same [IdMapRepository] callbacks for current retirement
 * eligibility. The standalone [searchModule] supplies a direct `close` callback; serving supplies its run-owned
 * closer. The qualifier keeps this definition distinct from S5's checkpoint listener.
 */
internal fun createSearchModule(
    openSearch: (Path) -> SearchDb,
    closeSearch: (SearchDb) -> Unit,
    resourceOwner: ServerResourceOwner,
) = module {
    single {
        val open = { openSearch(get<PlainbaseConfig>().searchDatabasePath) }
        resourceOwner.construct("search database") {
            open().also { search ->
                resourceOwner.own(ServerResourcePhase.SEARCH_DATABASE, search, closeSearch)
            }
        }
    } onClose {
        resourceOwner.drainServices()
    }
    single<SearchProvider> { Fts5SearchProvider(get()) }
    single { SectionSplitter() }
    single {
        val idMap = get<IdMapRepository>()
        SearchIndexer(
            provider = get(),
            splitter = get(),
            retiredUnboundIds = idMap::retiredUnboundIds,
            isRetiredUnbound = idMap::isRetiredUnbound,
        )
    }
    single<IndexBuilder.PublicationListener>(named("searchSync")) {
        val indexer = get<SearchIndexer>()
        IndexBuilder.PublicationListener { snapshot, _ -> indexer.sync(snapshot) }
    }
}

internal fun searchModule(resourceOwner: ServerResourceOwner) =
    createSearchModule({ path -> SearchDb(path) }, { it.close() }, resourceOwner)
