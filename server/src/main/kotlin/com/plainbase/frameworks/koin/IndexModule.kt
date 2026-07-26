package com.plainbase.frameworks.koin

import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.ProposalIdProvider
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.UuidV7ProposalIdProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import org.koin.dsl.module

/**
 * Wires the chunk-5 index pass. Constructor DSL only — no reflection (native-image gate).
 *
 * The renderer factory is passed inline rather than registered: a per-rebuild [FlexmarkRenderer]
 * is bound to that rebuild's URL-complete skeleton snapshot, so a `single` would be wrong and a
 * bare function type would collide on Koin's erased `Function1` key.
 */
val indexModule = module {
    single<FrontmatterParser> { FrontmatterReader() }
    // One UUIDv7 mint shared by the identity service (adopt-time ids) and the create route.
    single<IdProvider> { UuidV7IdProvider() }
    // The proposal-id mint — a SEPARATE port (IdProvider is typed to PageId, can't mint a ProposalId).
    single<ProposalIdProvider> { UuidV7ProposalIdProvider() }
    single { PageIdentityService(get()) }
    single { FrontmatterPatcher() }
    single { UrlAliasRegistry(get()) }
    single { CitationFactory() }
    single {
        val registry = get<RootRegistry>()
        val stores = get<RootStores>()
        val histories = get<HistoryProviders>()
        IndexBuilder(
            // Every registered root, in D7 order - the N-source machinery the C1-C3 seams were built for. The
            // builder sorts by rank itself, so the order is enforced by construction, not trusted from here.
            sources = registry.roots.map { root ->
                IndexBuilder.Source(
                    root = root,
                    store = stores[root.name],
                    history = histories[root.name],
                    // The C3 proof source, wired for exactly the roots that HAVE one: an object-backed root's own
                    // bucket listings. A local root answers null and earns its authority from its observation epoch.
                    manifests = stores.manifestsOrNull(root.name),
                )
            },
            availability = get(),
            frontmatterParser = get(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = get(),
            patcher = get(),
            idMap = get(),
            aliasRegistry = get(),
            checkpoint = get(),
            citations = get(),
            rootRank = registry::rank,
            // The D16 input: the FULL registry, never derived from the sources - configured extras
            // must classify as unscanned-but-registered, not detached.
            registeredRoots = registry.roots.map { it.name }.toSet(),
            // The proof-apply transaction and the durable freshness token (C0). In C0 nothing mints a proof, so
            // this reaps nothing - but it is the ONE deleter, and it is wired from the start rather than bolted on
            // by a later chunk, which is how the authority got scattered in the first place.
            retirements = get(),
            limbo = get(),
            // The C2 proof source. It shares this builder's RetirementRepository by construction (both are `get()`
            // on the same single), which is what makes the token an epoch mints and the token the proof-apply
            // transaction re-reads the SAME token - the entire freshness argument rests on there being one.
            epochs = get(),
            // The C3 latch, over the SAME durable topology `serve()` records the binding into at boot - so the row the
            // boot writes and the row a pass reads to decide whether it may believe a LIST are one row.
            bindings = get(),
            // Every PublicationListener definition across the loaded modules (searchModule's sync,
            // checkpointModule's checkpoint replace); empty when no listener module is loaded.
            listeners = getAll(),
            // The S8 reindex path: rebuildSearchIndex() rebuilds the engine under the rebuild
            // monitor. Present only when searchModule is loaded (serve()); null otherwise, so the
            // index-only module sets still resolve IndexBuilder.
            searchIndexer = getOrNull(),
        )
    }
}
