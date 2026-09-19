package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.ProposalIdProvider
import com.plainbase.domain.service.UuidV7ProposalIdProvider
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.IndexRuntimeFactory
import com.plainbase.frameworks.runtime.IndexSupport
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import org.koin.dsl.module

/**
 * Lazily wires shared index recipes and aliases the observed builder. Constructor DSL only — no reflection
 * (native-image gate).
 *
 * The runtime factory owns typed source and holder assembly; Koin aliases the resulting builder without a second
 * graph.
 */
val indexModule = module {
    single<FrontmatterParser> { IndexRuntimeFactory.frontmatterParser() }
    // One UUIDv7 mint shared by the identity service (adopt-time ids) and the create route.
    single<IdProvider> { IndexRuntimeFactory.idProvider() }
    // The proposal-id mint — a SEPARATE port (IdProvider is typed to PageId, can't mint a ProposalId).
    single<ProposalIdProvider> { UuidV7ProposalIdProvider() }
    single { IndexRuntimeFactory.identity(get<IdProvider>()) }
    single { IndexRuntimeFactory.patcher() }
    single { IndexRuntimeFactory.aliasRegistry(get()) }
    single { IndexRuntimeFactory.citations() }
    single<ObservedIndexRuntime> {
        val registry = get<RootRegistry>()
        val stores = get<RootStores>()
        val histories = get<HistoryProviders>()
        val support = IndexSupport(
            idProvider = get<IdProvider>(),
            identity = get<PageIdentityService>(),
            frontmatterParser = get(),
            patcher = get(),
            idMap = get(),
            aliasRegistry = get(),
            checkpoint = get(),
            citations = get<CitationFactory>(),
        )
        IndexRuntimeFactory.observed(
            registry = registry,
            policies = get<Map<RootName, ContentPathPolicy>>(),
            stores = stores,
            histories = histories,
            support = support,
            retirements = get(),
            availability = get(),
            convergence = get(),
            limbo = get(),
            epochs = get(),
            bindings = get(),
            listeners = getAll(),
            searchIndexer = getOrNull(),
        )
    }
    single<IndexBuilder> { get<ObservedIndexRuntime>().builder }
}
