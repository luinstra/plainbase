package com.plainbase.domain.service

import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.SearchProvider

internal fun AbsenceClassifier(idMap: IdMapRepository): AbsenceClassifier =
    AbsenceClassifier(idMap, allowAllPolicies())

internal fun PageRootResolver(idMap: IdMapRepository, registry: RootRegistry): PageRootResolver =
    PageRootResolver(idMap, registry, allowAllPolicies(registry.roots.map { it.name }))

internal fun SearchIndexer(
    provider: SearchProvider,
    splitter: SectionSplitter,
    retiredUnboundIds: () -> Set<RootedPageId>,
    isRetiredUnbound: (RootedPageId) -> Boolean,
): SearchIndexer = SearchIndexer(provider, splitter, retiredUnboundIds, isRetiredUnbound, allowAllPolicies())

internal fun SearchService(
    provider: SearchProvider,
    indexBuilder: IndexBuilder,
    availability: RootAvailability = RootAvailability(kotlin.time.Clock.System),
): SearchService = SearchService(provider, indexBuilder, availability, allowAllPolicies())
