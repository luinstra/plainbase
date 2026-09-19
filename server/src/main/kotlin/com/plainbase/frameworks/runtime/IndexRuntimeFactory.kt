package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.NoRetirements
import com.plainbase.domain.repository.NoTopology
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.ObjectManifestProvider
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import kotlin.time.Clock

/** The typed construction boundary for the observed server and offline reindex index profiles. */
internal object IndexRuntimeFactory {

    private val noSourceObserver: (List<IndexBuilder.Source>, Set<RootName>, (RootName) -> Int) -> Unit =
        { _, _, _ -> }

    fun frontmatterParser(): FrontmatterParser = FrontmatterReader()

    fun idProvider(): IdProvider = UuidV7IdProvider()

    fun identity(idProvider: IdProvider): PageIdentityService = PageIdentityService(idProvider)

    fun patcher(): FrontmatterPatcher = FrontmatterPatcher()

    fun aliasRegistry(repository: UrlAliasRepository): UrlAliasRegistry = UrlAliasRegistry(repository)

    fun citations(): CitationFactory = CitationFactory()

    fun observed(
        registry: RootRegistry,
        policies: Map<RootName, ContentPathPolicy>,
        stores: RootStores,
        histories: HistoryProviders,
        support: IndexSupport,
        retirements: RetirementRepository,
        availability: RootAvailability,
        convergence: RootConvergence,
        limbo: RootLimbo,
        epochs: ObservationEpoch,
        bindings: BindingLatch,
        listeners: List<IndexBuilder.PublicationListener>,
        searchIndexer: SearchIndexer?,
        sourceObserver: (List<IndexBuilder.Source>, Set<RootName>, (RootName) -> Int) -> Unit = noSourceObserver,
    ): ObservedIndexRuntime {
        val builder = build(
            registry = registry,
            policies = policies,
            stores = stores,
            history = histories::get,
            manifests = stores::manifestsOrNull,
            support = support,
            retirements = retirements,
            availability = availability,
            limbo = limbo,
            epochs = epochs,
            bindings = bindings,
            listeners = listeners,
            searchIndexer = searchIndexer,
            sourceObserver = sourceObserver,
        )
        return ObservedIndexRuntime(
            builder = builder,
            registry = registry,
            stores = stores,
            histories = histories,
            availability = availability,
            convergence = convergence,
            limbo = limbo,
            epochs = epochs,
            bindings = bindings,
            identity = support.identity,
            idProvider = support.idProvider,
            aliasRegistry = support.aliasRegistry,
            policies = policies,
        )
    }

    /** Offline publishes checkpoints using real durable repositories, without live absence authority or incremental search sync. */
    fun offlineReindex(
        registry: RootRegistry,
        policies: Map<RootName, ContentPathPolicy>,
        stores: RootStores,
        support: IndexSupport,
        retirements: RetirementRepository,
        searchIndexer: SearchIndexer?,
        sourceObserver: (List<IndexBuilder.Source>, Set<RootName>, (RootName) -> Int) -> Unit = noSourceObserver,
    ): IndexBuilder = build(
        registry = registry,
        policies = policies,
        stores = stores,
        history = { NoOpHistoryProvider },
        manifests = { null },
        support = support,
        retirements = retirements,
        availability = RootAvailability(Clock.System),
        limbo = RootLimbo(),
        epochs = ObservationEpoch(NoRetirements, RootConvergence()),
        bindings = BindingLatch(NoTopology),
        listeners = listOf(IndexBuilder.PublicationListener(support.checkpoint::replaceFrom)),
        searchIndexer = searchIndexer,
        sourceObserver = sourceObserver,
    )

    private fun build(
        registry: RootRegistry,
        policies: Map<RootName, ContentPathPolicy>,
        stores: RootStores,
        history: (RootName) -> HistoryProvider,
        manifests: (RootName) -> ObjectManifestProvider?,
        support: IndexSupport,
        retirements: RetirementRepository,
        availability: RootAvailability,
        limbo: RootLimbo,
        epochs: ObservationEpoch,
        bindings: BindingLatch,
        listeners: List<IndexBuilder.PublicationListener>,
        searchIndexer: SearchIndexer?,
        sourceObserver: (List<IndexBuilder.Source>, Set<RootName>, (RootName) -> Int) -> Unit,
    ): IndexBuilder {
        val sources = registry.roots.map { root ->
            IndexBuilder.Source(
                root = root,
                store = stores[root.name],
                history = history(root.name),
                manifests = manifests(root.name),
            )
        }
        // Keep unreadable configured roots registered.
        val registeredRoots = registry.roots.map { it.name }.toSet()
        val rootRank: (RootName) -> Int = registry::rank
        sourceObserver(sources, registeredRoots, rootRank)
        return IndexBuilder(
            sources = sources,
            frontmatterParser = support.frontmatterParser,
            // Bind each renderer to the current root's provisional view.
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = support.identity,
            patcher = support.patcher,
            idMap = support.idMap,
            aliasRegistry = support.aliasRegistry,
            checkpoint = support.checkpoint,
            citations = support.citations,
            rootRank = rootRank,
            registeredRoots = registeredRoots,
            listeners = listeners,
            searchIndexer = searchIndexer,
            availability = availability,
            retirements = retirements,
            limbo = limbo,
            epochs = epochs,
            bindings = bindings,
            policies = policies,
        )
    }
}

internal class IndexSupport(
    val idProvider: IdProvider,
    val identity: PageIdentityService,
    val frontmatterParser: FrontmatterParser,
    val patcher: FrontmatterPatcher,
    val idMap: IdMapRepository,
    val aliasRegistry: UrlAliasRegistry,
    val checkpoint: PageCheckpointRepository,
    val citations: CitationFactory,
)

internal class ObservedIndexRuntime(
    val builder: IndexBuilder,
    val registry: RootRegistry,
    val stores: RootStores,
    val histories: HistoryProviders,
    val availability: RootAvailability,
    val convergence: RootConvergence,
    val limbo: RootLimbo,
    val epochs: ObservationEpoch,
    val bindings: BindingLatch,
    val identity: PageIdentityService,
    val idProvider: IdProvider,
    val aliasRegistry: UrlAliasRegistry,
    val policies: Map<RootName, ContentPathPolicy>,
)

internal class ServingRuntime(
    val index: ObservedIndexRuntime,
    val pageService: PageService,
    val searchService: SearchService,
    val writePipeline: WritePipeline,
    val resolver: PageRootResolver,
    val absence: AbsenceClassifier,
    val proposalService: ProposalService,
    val proposalLabeler: ProposalAuthorLabeler,
    val agentDirectCommitGlobs: List<CommitGlob>,
)
