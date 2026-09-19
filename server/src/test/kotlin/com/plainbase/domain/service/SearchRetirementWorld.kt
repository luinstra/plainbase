package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RetiredBinding
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * File-backed two-root graph for retirement recovery, restart, carry, and builder-ordering scenarios.
 *
 * The app DB and search DB remain at stable paths for [reopen], so a later restart/race oracle can rebuild the
 * domain graph without turning a file-backed recovery claim into an in-memory shortcut. The search listener is kept
 * after the checkpoint listener, exactly as the production wiring orders them; the recorder is a separate listener so
 * a failed search delivery is still observable.
 */
internal class SearchRetirementWorld : AutoCloseable {

    val dataDir: Path = Files.createTempDirectory("plainbase-search-retirement-data")
    val mainDir: Path = Files.createTempDirectory("plainbase-search-retirement-main")
    val handbookDir: Path = Files.createTempDirectory("plainbase-search-retirement-handbook")
    val appDatabasePath: Path = dataDir.resolve("plainbase.db")
    val searchDatabasePath: Path = dataDir.resolve("search.db")

    val victimId: PageId = PageId.require("01900000-0000-7000-8000-000000000101")
    val mainAnchorId: PageId = PageId.require("01900000-0000-7000-8000-000000000102")
    val handbookAnchorId: PageId = PageId.require("01900000-0000-7000-8000-000000000103")
    val handbookVictimId: PageId = PageId.require("01900000-0000-7000-8000-000000000104")
    val handbookControlId: PageId = PageId.require("01900000-0000-7000-8000-000000000105")

    val victimPath: Path = mainDir.resolve("victim.md")
    val mainAnchorPath: Path = mainDir.resolve("docs-anchor.md")
    val docsRenderTriggerPath: Path = mainAnchorPath
    val handbookVictimPath: Path = handbookDir.resolve("victim.md")
    val handbookControlPath: Path = handbookDir.resolve("control.md")

    private var searchPublicationFailures = 0
    private var searchPublicationCalls = 0
    private var searchPublicationFailuresThrown = 0
    private var deleteFailureCalls = 0
    private val delegatedDeleteFailures = mutableListOf<SQLException>()
    private var rendererFailurePath: String? = null
    private var rendererFailureCalls = 0
    private var wholeRetirementReads = 0
    private var pointRetirementReads = 0
    private var pointQueryFailure = false
    private var nextRetirementQueryGate: RetirementQueryGate? = null
    private val providerEvents = mutableListOf<String>()
    private var closed = false
    private var storesOpen = true
    private var selectedRootNames: Set<RootName> = setOf(RootName.PRIMARY, HANDBOOK)
    private val scannedRoots = mutableListOf<RootName>()
    private val publishedRetirements = mutableListOf<Set<RootedPageId>>()
    private lateinit var state: State

    val builder: IndexBuilder get() = state.builder
    val idMap: SqlDelightIdMapRepository get() = state.idMap
    val provider: FaultingSearchProvider get() = state.provider
    val publicationRetirements: List<Set<RootedPageId>> get() = publishedRetirements.toList()
    val searchPublicationInvocationCount: Int get() = searchPublicationCalls
    val searchPublicationFailureCount: Int get() = searchPublicationFailuresThrown
    val searchPublicationFailuresRemaining: Int get() = searchPublicationFailures
    val deleteFailureCount: Int get() = deleteFailureCalls
    val delegatedDeleteExceptions: List<SQLException> get() = delegatedDeleteFailures.toList()
    val rendererFailureCount: Int get() = rendererFailureCalls
    val availability get() = state.availability
    val checkpoints get() = state.checkpoints
    val dirtyPages get() = state.dirtyPages
    val providerEventsSnapshot: List<String> get() = providerEvents.toList()
    val wholeRetirementReadCount: Int get() = wholeRetirementReads
    val pointRetirementReadCount: Int get() = pointRetirementReads
    val scannedRootsSnapshot: List<RootName> get() = scannedRoots.toList()
    val sourceRoots: Set<RootName> get() = state.sourceRoots

    init {
        seedCorpus()
        state = openState()
    }

    /** Declares both local roots observed, which is the precondition for a real absence retirement. */
    fun observeRoots() {
        selectedRootNames.forEach { state.epochs.observing(it) }
    }

    fun failNextDelete() {
        state.provider.failNextDelete = true
    }

    fun failNextSearchPublications(count: Int) {
        require(count >= 0)
        searchPublicationFailures = count
    }

    fun failNextRender(path: Path = docsRenderTriggerPath) {
        rendererFailurePath = mainDir.relativize(path).toString()
    }

    fun failNextPointQuery() {
        pointQueryFailure = true
    }

    /** Applies one real accepted proof using the same stamp-before-binding-read order as admin force-retire. */
    fun retire(root: RootName, id: PageId): Set<RootedPageId> {
        val observationId = state.retirements.observation(root)
        val bindingEpoch = state.retirements.bindingEpoch(root)
        val binding = requireNotNull(idMap.bindingInRoot(root, id))
        val proof = AbsenceProof.accepted(
            root = root,
            source = ProofSource.OPERATOR,
            observationId = observationId,
            bindingEpoch = bindingEpoch,
            covers = setOf(BindingRef(binding.path.path, id)),
        )
        return state.retirements.applyProofs(
            proofs = listOf(proof),
            witnessed = emptySet(),
            unavailableNow = { emptySet() },
        )
    }

    /** Sets up a retired/unbound victim, an excluded holder, and the old raw FTS row for the JVM race schedules. */
    fun establishRetiredVictimWithStaleSearch(): Set<RootedPageId> {
        Files.delete(victimPath)
        val applied = retire(RootName.PRIMARY, victimId)
        check(applied == setOf(RootedPageId(RootName.PRIMARY, victimId))) { "unexpected retirement set: $applied" }
        failNextDelete()
        builder.rebuild()
        check(builder.current.pageAt(RootedPageId(RootName.PRIMARY, victimId)) == null)
        check(provider.indexedState().containsKey(RootedPageId(RootName.PRIMARY, victimId)))
        return applied
    }

    fun armRetirementQueryGate(): RetirementQueryGate = RetirementQueryGate().also {
        nextRetirementQueryGate = it
    }

    fun resetRaceObservability() {
        providerEvents.clear()
        wholeRetirementReads = 0
        pointRetirementReads = 0
        provider.resetObservability()
        delegatedDeleteFailures.clear()
    }

    fun resetScanObservability() {
        scannedRoots.clear()
    }

    fun <T> withSearchWriter(block: (Connection) -> T): T = state.searchDb.write(block)

    fun applicationMetadata(): SearchRetirementApplicationMetadata = SearchRetirementApplicationMetadata(
        retired = idMap.retiredUnboundIds(),
        retiredBindings = idMap.retiredBindings(),
        bindings = idMap.bindings(),
        checkpoints = checkpoints.load(),
        dirtyPages = dirtyPages.all(),
        observations = state.retirements.observations(),
        bindingEpochs = setOf(RootName.PRIMARY, HANDBOOK).associateWith { state.retirements.bindingEpoch(it) },
    )

    fun armNextRebuildGate(): RebuildGate = RebuildGate().also {
        state.provider.nextRebuildGate = it
        state.provider.recordMonitorEvents = true
    }

    fun pipeline(historyHook: WriteHistoryHook = WriteHistoryHook { _, _, _, _, _ -> null }): WritePipeline =
        WritePipeline(
            stores = { root -> state.stores.getValue(root) },
            indexBuilder = builder,
            citations = state.citations,
            frontmatterParser = state.frontmatterParser,
            dirtyPages = dirtyPages,
            idMap = idMap,
            aliasRegistry = state.aliases,
            availability = availability,
            historyHook = historyHook,
            policies = allowAllPolicies(state.sourceRoots),
        )

    fun pageBytes(id: PageId, title: String, body: String): ByteArray = page(id, title, body).toByteArray()

    /** Explicit command configuration matching the running two-root graph, in deterministic registry order. */
    fun commandConfig(roots: Set<RootName> = selectedRootNames): PlainbaseConfig {
        require(RootName.PRIMARY in roots) { "the explicit fixture config must retain the docs root" }
        val base = PlainbaseConfig(contentDir = mainDir, dataDir = dataDir, host = "127.0.0.1", port = 0)
        return base.copy(
            roots = RootsConfig.of(
                list = listOf(
                    Root(RootName.PRIMARY, RootBackend.Local(mainDir), editable = true, history = HistoryMode.OFF),
                    Root(HANDBOOK, RootBackend.Local(handbookDir), editable = true, history = HistoryMode.OFF),
                ).filter { it.name in roots },
                origin = RootsOrigin.EXPLICIT,
            ),
        )
    }

    /** Stops the fixture stores for an offline command, then reconstructs the graph only after a successful callback. */
    fun <T> withStoresClosed(block: () -> T): T {
        check(!closed) { "world is closed" }
        check(storesOpen) { "world stores are already closed" }
        storesOpen = false
        state.close()
        val result = block()
        state = openState()
        storesOpen = true
        return result
    }

    fun contentHash(bytes: ByteArray): String = state.citations.contentHash(bytes)

    fun updateMainAnchor() {
        Files.writeString(
            mainAnchorPath,
            page(mainAnchorId, "Docs anchor", "anchor term changed after the lost retirement delivery."),
        )
    }

    /** Reopens both durable stores and reconstructs the repository, epoch, indexer, and builder graph. */
    fun reopen(roots: Set<RootName> = selectedRootNames) {
        check(!closed) { "world is closed" }
        require(RootName.PRIMARY in roots) { "the selected fixture graph must retain the docs root" }
        selectedRootNames = roots
        storesOpen = false
        state.close()
        state = openState()
        storesOpen = true
    }

    override fun close() {
        if (closed) return
        closed = true
        if (storesOpen) state.close()
        listOf(mainDir, handbookDir, dataDir).forEach { it.toFile().deleteRecursively() }
    }

    private fun seedCorpus() {
        Files.writeString(victimPath, page(victimId, "Victim", "lost-delivery-victim-term"))
        Files.writeString(mainAnchorPath, page(mainAnchorId, "Docs anchor", "main-anchor-term"))
        Files.writeString(
            handbookDir.resolve("anchor.md"),
            page(handbookAnchorId, "Handbook anchor", "handbook-anchor-term"),
        )
        Files.writeString(handbookVictimPath, page(handbookVictimId, "Handbook victim", "handbook-victim-term"))
        Files.writeString(handbookControlPath, page(handbookControlId, "Handbook control", "handbook-control-term"))
    }

    private fun openState(): State {
        val driver = DatabaseFactory.createDriver(appDatabasePath)
        var searchDb: SearchDb? = null
        try {
            val database = DatabaseFactory.createDatabase(driver)
            searchDb = SearchDb(searchDatabasePath)
            val config = commandConfig(selectedRootNames)
            val registry = RootRegistry.of(config.roots.list)
            val idMap = SqlDelightIdMapRepository(database)
            val checkpoints = SqlDelightPageCheckpointRepository(database)
            val dirtyPages = SqlDelightDirtyPageRepository(database)
            val retirements = SqlDelightRetirementRepository(database)
            val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
            val epochs = ObservationEpoch(retirements, RootConvergence())
            val availability = RootAvailability(Clock.System)
            val stores = registry.roots.associate { root ->
                val path = requireNotNull(root.localPath)
                root.name to ScanRecordingStore(
                    LocalContentStore(path, rootName = root.name),
                    root.name,
                ) { scannedRoots += it }
            }
            val sources = registry.roots.map { root ->
                IndexBuilder.Source(root, stores.getValue(root.name), NoOpHistoryProvider)
            }
            val sourceRoots = sources.map { it.root.name }.toSet()
            val openedSearchDb = checkNotNull(searchDb)
            val provider = FaultingSearchProvider(
                delegate = Fts5SearchProvider(openedSearchDb),
                onDeleteFailure = { deleteFailureCalls++ },
                onProviderEvent = { event -> providerEvents += event },
                onDelegatedDeleteFailure = { failure -> delegatedDeleteFailures += failure },
            )
            val indexer = SearchIndexer(
                provider,
                SectionSplitter(),
                {
                    wholeRetirementReads++
                    idMap.retiredUnboundIds().also { retired ->
                        nextRetirementQueryGate?.let { gate ->
                            nextRetirementQueryGate = null
                            gate.captured = retired
                            gate.entered.countDown()
                            gate.awaitRelease()
                            gate.completed.countDown()
                        }
                    }
                },
                { rooted ->
                    pointRetirementReads++
                    if (pointQueryFailure) {
                        pointQueryFailure = false
                        error("point retirement query unavailable for targeted RED oracle")
                    }
                    idMap.isRetiredUnbound(rooted)
                },
                allowAllPolicies(registry.roots.map { it.name }),
            )
            val frontmatterParser = FrontmatterReader()
            val citations = CitationFactory()
            val builder = IndexBuilder(
                sources = sources,
                frontmatterParser = frontmatterParser,
                rendererFactory = { view ->
                    val delegate = FlexmarkRenderer(view)
                    object : MarkdownRenderer {
                        override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
                            if (rendererFailurePath == sourcePath.value) {
                                rendererFailurePath = null
                                rendererFailureCalls++
                                error("renderer unavailable for RED oracle at ${sourcePath.value}")
                            }
                            return delegate.render(sourcePath, source)
                        }
                    }
                },
                identity = PageIdentityService(TestIdProvider()),
                patcher = FrontmatterPatcher(),
                idMap = idMap,
                aliasRegistry = aliases,
                checkpoint = checkpoints,
                citations = citations,
                rootRank = registry::rank,
                registeredRoots = registry.roots.map { it.name }.toSet(),
                listeners = listOf(
                    IndexBuilder.PublicationListener(checkpoints::replaceFrom),
                    IndexBuilder.PublicationListener { snapshot, _ ->
                        searchPublicationCalls++
                        if (searchPublicationFailures > 0) {
                            searchPublicationFailures--
                            searchPublicationFailuresThrown++
                            error("search publication unavailable for RED oracle")
                        }
                        indexer.sync(snapshot)
                    },
                    IndexBuilder.PublicationListener { _, retired ->
                        publishedRetirements += retired.toSet()
                    },
                ),
                searchIndexer = indexer,
                availability = availability,
                retirements = retirements,
                limbo = RootLimbo(),
                epochs = epochs,
                policies = allowAllPolicies(registry.roots.map { it.name }),
            )
            return State(
                driver = driver,
                searchDb = openedSearchDb,
                idMap = idMap,
                checkpoints = checkpoints,
                dirtyPages = dirtyPages,
                retirements = retirements,
                aliases = aliases,
                citations = citations,
                frontmatterParser = frontmatterParser,
                epochs = epochs,
                availability = availability,
                stores = stores,
                sourceRoots = sourceRoots,
                provider = provider,
                builder = builder,
            )
        } catch (failure: Throwable) {
            throw requireNotNull(closeResources(failure, { searchDb?.close() }, { driver.close() }))
        }
    }

    private data class State(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val searchDb: SearchDb,
        val idMap: SqlDelightIdMapRepository,
        val checkpoints: SqlDelightPageCheckpointRepository,
        val dirtyPages: SqlDelightDirtyPageRepository,
        val retirements: SqlDelightRetirementRepository,
        val aliases: UrlAliasRegistry,
        val citations: CitationFactory,
        val frontmatterParser: FrontmatterReader,
        val epochs: ObservationEpoch,
        val availability: RootAvailability,
        val stores: Map<RootName, ContentStore>,
        val sourceRoots: Set<RootName>,
        val provider: FaultingSearchProvider,
        val builder: IndexBuilder,
    ) : AutoCloseable {
        private var open = true

        override fun close() {
            if (!open) return
            open = false
            closeResources(null, { searchDb.close() }, { driver.close() })?.let { throw it }
        }
    }

    private fun page(id: PageId, title: String, body: String): String =
        "---\nid: ${id.value}\ntitle: $title\n---\n\n# $title\n\n$body\n"

    companion object {
        val HANDBOOK: RootName = RootName.require("handbook")
    }
}

internal data class SearchRetirementApplicationMetadata(
    val retired: Set<RootedPageId>,
    val retiredBindings: List<RetiredBinding>,
    val bindings: List<IdBinding>,
    val checkpoints: Map<RootedPageId, TreePath?>,
    val dirtyPages: List<DirtyPage>,
    val observations: Map<RootName, ObservationId>,
    val bindingEpochs: Map<RootName, BindingEpoch>,
)

private class ScanRecordingStore(
    private val delegate: ContentStore,
    private val root: RootName,
    private val onScan: (RootName) -> Unit,
) : ContentStore by delegate {
    override fun scan(): ScanResult {
        onScan(root)
        return delegate.scan()
    }
}

private fun closeResources(original: Throwable?, vararg closers: () -> Unit): Throwable? {
    var failure = original
    closers.forEach { close ->
        try {
            close()
        } catch (cleanup: Throwable) {
            if (failure == null) {
                failure = cleanup
            } else {
                failure?.addSuppressed(cleanup)
            }
        }
    }
    return failure
}

/** A one-shot provider-entry gate for the serialized builder-monitor ordering control. */
internal class RebuildGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = CountDownLatch(1)

    fun awaitRelease() {
        check(release.await(10, TimeUnit.SECONDS)) { "timed out waiting for rebuild gate release" }
    }
}

/** One-shot gate after the real whole retirement SELECT has fully materialized and released its DB read. */
internal class RetirementQueryGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = CountDownLatch(1)
    var captured: Set<RootedPageId> = emptySet()

    fun awaitRelease() {
        check(release.await(10, TimeUnit.SECONDS)) { "timed out waiting for retirement query gate release" }
    }
}

/** A real-provider decorator used to make delivery failures and builder ordering observable in recovery tests. */
internal class FaultingSearchProvider(
    private val delegate: SearchProvider,
    private val onDeleteFailure: () -> Unit,
    private val onProviderEvent: (String) -> Unit = {},
    private val onDelegatedDeleteFailure: (SQLException) -> Unit = {},
) : SearchProvider {

    var failNextDelete: Boolean = false
    var indexFailuresRemaining: Int = 0
    var nextRebuildGate: RebuildGate? = null
    var recordMonitorEvents: Boolean = false

    var indexCalls: Int = 0
        private set

    fun failNextIndexes(count: Int) {
        require(count >= 0)
        indexFailuresRemaining = count
    }

    fun resetObservability() {
        indexCalls = 0
        indexFailuresRemaining = 0
    }

    override fun index(pages: List<PageDocuments>) {
        indexCalls++
        if (recordMonitorEvents) onProviderEvent("index-attempt")
        if (indexFailuresRemaining > 0) {
            indexFailuresRemaining--
            if (recordMonitorEvents) onProviderEvent("index-failure")
            error("search index unavailable for race recovery oracle")
        }
        delegate.index(pages)
        if (recordMonitorEvents) onProviderEvent("creator-index")
    }

    override fun delete(ids: Collection<RootedPageId>) {
        if (failNextDelete) {
            failNextDelete = false
            onDeleteFailure()
            error("search delete unavailable for RED oracle")
        }
        try {
            delegate.delete(ids)
        } catch (failure: SQLException) {
            onDelegatedDeleteFailure(failure)
            throw failure
        }
    }

    override fun search(query: SearchQuery): SearchResults = delegate.search(query)

    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) {
        val gate = nextRebuildGate
        nextRebuildGate = null
        if (gate == null) {
            delegate.rebuild(pages, retired)
            if (recordMonitorEvents) onProviderEvent("old-swap-complete")
            return
        }
        gate.entered.countDown()
        gate.awaitRelease()
        delegate.rebuild(pages, retired)
        if (recordMonitorEvents) onProviderEvent("old-swap-complete")
        gate.completed.countDown()
    }

    override fun indexedState(): Map<RootedPageId, PageSearchState> = delegate.indexedState()
}
