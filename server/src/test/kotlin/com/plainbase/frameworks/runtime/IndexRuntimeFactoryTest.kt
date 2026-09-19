package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.ObjectManifest
import com.plainbase.domain.root.ObjectManifestProvider
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

class IndexRuntimeFactoryTest : FunSpec({

    test("offline local inputs reuse the caller's ignore rules") {
        val contentDir = Path.of("/content")
        val ignoreRules = IgnoreRules(ignoreGlobs = listOf("drafts/**"))
        val root = Root(
            name = RootName.PRIMARY,
            backend = RootBackend.Local(contentDir),
            editable = true,
            history = HistoryMode.OFF,
        )

        offlineLocalStoreInputs(
            config = PlainbaseConfig(contentDir, Path.of("/data"), "127.0.0.1", 8080),
            root = root,
            ignoreRules = ignoreRules,
            policy = ContentPathPolicy.ALL,
        ).ignoreRules shouldBeSameInstanceAs ignoreRules
    }

    test("observed and offline profiles forward real sources and keep authority boundaries distinct") {
        withTempTree(seed = { root -> writePage(root, "docs/primary.md", "# Primary\n\nobserved\n") }) { primaryDir ->
            withTempTree(seed = { root -> writePage(root, "docs/object.md", "# Object\n\nobserved\n") }) { objectDir ->
                withTempTree(seed = { root -> writePage(root, "docs/offline.md", "# Offline\n\nobserved\n") }) { downDir ->
                    withTempTree(seed = {}) { dataDir ->
                        DatabaseFactory.createInMemoryDriver().use { driver ->
                            val database = DatabaseFactory.createDatabase(driver)
                            val repositories = ContentRepositories(database)
                            val objectRootName = RootName.require("object")
                            val downRootName = RootName.require("down")
                            val objectRoot = Root(
                                name = objectRootName,
                                backend = RootBackend.Object(bucket = "bucket", prefix = "prefix"),
                                editable = true,
                                history = HistoryMode.OFF,
                            )
                            val downRoot = Root(
                                name = downRootName,
                                backend = RootBackend.Local(downDir),
                                editable = true,
                                history = HistoryMode.OFF,
                            )
                            val registry = RootRegistry.of(
                                listOf(
                                    downRoot,
                                    Root(
                                        name = RootName.PRIMARY,
                                        backend = RootBackend.Local(primaryDir),
                                        editable = true,
                                        history = HistoryMode.NATIVE,
                                    ),
                                    objectRoot,
                                ),
                            )
                            val primaryStore = CountingStore(localStore(primaryDir, RootName.PRIMARY, dataDir))
                            val objectManifest = ObjectManifest(
                                binding = RootBinding("test|bucket|prefix"),
                                listed = setOf(TreePath.require("docs/object.md")),
                                rowsAtStart = emptySet(),
                                bindingEpoch = BindingEpoch(0),
                            )
                            val objectStore = CountingManifestStore(
                                delegate = localStore(objectDir, objectRootName, dataDir),
                                manifest = objectManifest,
                            )
                            val downStore = CountingStore(localStore(downDir, downRootName, dataDir))
                            // Deliberately differs from registry order: source order must come from the rank function.
                            val stores = RootStores(
                                linkedMapOf(
                                    objectRootName to objectStore,
                                    RootName.PRIMARY to primaryStore,
                                    downRootName to downStore,
                                ),
                            )
                            val primaryHistory = RecordingHistoryProvider("primary")
                            val objectHistory = RecordingHistoryProvider("object")
                            val downHistory = RecordingHistoryProvider("down")
                            val histories = HistoryProviders(
                                linkedMapOf(
                                    objectRootName to objectHistory,
                                    RootName.PRIMARY to primaryHistory,
                                    downRootName to downHistory,
                                ),
                            )
                            val idProvider = TestIdProvider()
                            val checkpoint = CountingCheckpoint(repositories.checkpoints)
                            val support = IndexSupport(
                                idProvider = idProvider,
                                identity = IndexRuntimeFactory.identity(idProvider),
                                frontmatterParser = IndexRuntimeFactory.frontmatterParser(),
                                patcher = IndexRuntimeFactory.patcher(),
                                idMap = repositories.idMap,
                                aliasRegistry = IndexRuntimeFactory.aliasRegistry(repositories.aliases),
                                checkpoint = checkpoint,
                                citations = IndexRuntimeFactory.citations(),
                            )
                            val availability = spyk(RootAvailability(Clock.System))
                            val convergence = RootConvergence()
                            val retirements = spyk(repositories.retirements)
                            val epochs = spyk(ObservationEpoch(retirements, convergence))
                            val limbo = spyk(RootLimbo())
                            val bindings = spyk(BindingLatch(repositories.topology))
                            availability.markUnavailable(downRootName, UnavailableCause.VANISHED)
                            epochs.observing(RootName.PRIMARY)
                            clearMocks(availability, epochs, limbo, bindings, retirements, answers = false)
                            primaryStore.reset()
                            objectStore.reset()
                            downStore.reset()
                            primaryHistory.reset()
                            objectHistory.reset()
                            downHistory.reset()
                            checkpoint.reset()

                            var observedSources: List<IndexBuilder.Source>? = null
                            var observedRegisteredRoots: Set<RootName>? = null
                            var observedRank: ((RootName) -> Int)? = null
                            val observedBindingEpochRoots = mutableListOf<RootName>()
                            val observedProofs = slot<List<AbsenceProof>>()
                            val observed = IndexRuntimeFactory.observed(
                                registry = registry,
                                policies = com.plainbase.domain.service.allowAllPolicies(registry.roots.map { it.name }),
                                stores = stores,
                                histories = histories,
                                support = support,
                                retirements = retirements,
                                availability = availability,
                                convergence = convergence,
                                limbo = limbo,
                                epochs = epochs,
                                bindings = bindings,
                                listeners = emptyList(),
                                searchIndexer = null,
                                sourceObserver = { sources, registeredRoots, rank ->
                                    observedSources = sources
                                    observedRegisteredRoots = registeredRoots
                                    observedRank = rank
                                },
                            )
                            val observedSourceList = checkNotNull(observedSources)
                            val observedRootSet = checkNotNull(observedRegisteredRoots)
                            val observedRankFunction = checkNotNull(observedRank)
                            observedSourceList.map { it.root.name } shouldBe registry.roots.map { it.name }
                            observedSourceList.map { it.store } shouldBe
                                listOf(downStore, primaryStore, objectStore)
                            observedSourceList.map { it.history } shouldBe
                                listOf(downHistory, primaryHistory, objectHistory)
                            observedSourceList.first { it.root.name == downRootName }.manifests.shouldBeNull()
                            observedSourceList.first { it.root.name == RootName.PRIMARY }.manifests.shouldBeNull()
                            observedSourceList.first { it.root.name == objectRootName }.manifests shouldBeSameInstanceAs objectStore
                            observedRootSet shouldBe setOf(downRootName, RootName.PRIMARY, objectRootName)
                            observedRankFunction(downRootName) shouldBe 0
                            observedRankFunction(RootName.PRIMARY) shouldBe 1
                            observedRankFunction(objectRootName) shouldBe 2
                            observed.identity shouldBeSameInstanceAs support.identity
                            observed.idProvider shouldBeSameInstanceAs idProvider
                            observed.aliasRegistry shouldBeSameInstanceAs support.aliasRegistry
                            primaryStore.totalCalls() shouldBe 0
                            objectStore.totalCalls() shouldBe 0
                            downStore.totalCalls() shouldBe 0
                            primaryHistory.totalCalls() shouldBe 0
                            objectHistory.totalCalls() shouldBe 0
                            downHistory.totalCalls() shouldBe 0
                            checkpoint.loadCalls shouldBe 0
                            checkpoint.replaceCalls shouldBe 0
                            verify(exactly = 0) { availability.current() }
                            verify(exactly = 0) { epochs.establish(any()) }
                            verify(exactly = 0) { bindings.protects(any()) }
                            verify(exactly = 0) { bindings.proven(any(), any(), any()) }
                            verify(exactly = 0) { limbo.publish(any()) }
                            verify(exactly = 0) { retirements.applyProofs(any(), any(), any(), any()) }

                            val observedSnapshot = observed.builder.rebuild()
                            observedSnapshot.sections.map { it.root } shouldBe listOf(RootName.PRIMARY, objectRootName)
                            observedSnapshot.pages.map { it.root } shouldBe listOf(RootName.PRIMARY, objectRootName)
                            observedSnapshot.pages.map { it.commit } shouldBe listOf(
                                primaryHistory.sha,
                                objectHistory.sha,
                            )
                            observedSnapshot.pages.map { it.rooted }.toSet() shouldBe setOf(
                                RootedPageId(RootName.PRIMARY, observedSnapshot.pages[0].id),
                                RootedPageId(objectRootName, observedSnapshot.pages[1].id),
                            )
                            repositories.idMap.bindings().map { it.path.root }.toSet() shouldBe
                                setOf(RootName.PRIMARY, objectRootName)
                            primaryStore.scanCalls shouldBe 1
                            objectStore.scanCalls shouldBe 1
                            downStore.scanCalls shouldBe 0
                            primaryHistory.lastCommitsCalls shouldBe 1
                            objectHistory.lastCommitsCalls shouldBe 1
                            downHistory.lastCommitsCalls shouldBe 0
                            objectStore.manifestCalls shouldBe 1
                            verify { epochs.establish(RootName.PRIMARY) }
                            verify { availability.current() }
                            verify { bindings.protects(objectRootName) }
                            verify { bindings.proven(objectRootName, objectManifest, any()) }
                            verify { limbo.publish(any()) }
                            verify(exactly = 1) { retirements.observation(downRootName) }
                            verify(exactly = 1) { retirements.observation(objectRootName) }
                            verify(exactly = 2) { retirements.bindingEpoch(capture(observedBindingEpochRoots)) }
                            observedBindingEpochRoots shouldBe listOf(downRootName, RootName.PRIMARY)
                            verify(exactly = 1) { retirements.applyProofs(capture(observedProofs), any(), any(), any()) }
                            observedProofs.captured.shouldBeEmpty()
                            verify { retirements.revoke(RootName.PRIMARY) }

                            val provider = RecordingSearchProvider()
                            val indexer = SearchIndexer(
                                provider = provider,
                                splitter = SectionSplitter(),
                                retiredUnboundIds = repositories.idMap::retiredUnboundIds,
                                isRetiredUnbound = repositories.idMap::isRetiredUnbound,
                                policies = com.plainbase.domain.service.allowAllPolicies(registry.roots.map { it.name }),
                            )
                            val configuredRoots = listOf(downRootName, RootName.PRIMARY, objectRootName)
                            val persistedObservations = repositories.retirements.observations()
                            val observationBaseline = configuredRoots.associateWith { root -> persistedObservations.getValue(root) }
                            val bindingEpochBaseline = configuredRoots.associateWith { root ->
                                repositories.retirements.bindingEpoch(root)
                            }
                            val topologyBaseline = configuredRoots.associateWith { root -> repositories.topology.topology(root) }
                            clearMocks(availability, epochs, limbo, bindings, retirements, answers = false)
                            primaryStore.reset()
                            objectStore.reset()
                            downStore.reset()
                            primaryHistory.reset()
                            objectHistory.reset()
                            downHistory.reset()
                            checkpoint.reset()
                            var offlineSources: List<IndexBuilder.Source>? = null
                            var offlineRegisteredRoots: Set<RootName>? = null
                            var offlineRank: ((RootName) -> Int)? = null
                            val offlineObservationRoots = mutableListOf<RootName>()
                            val offlineBindingEpochRoots = mutableListOf<RootName>()
                            val offlineProofs = slot<List<AbsenceProof>>()
                            val offline = IndexRuntimeFactory.offlineReindex(
                                registry = registry,
                                policies = com.plainbase.domain.service.allowAllPolicies(registry.roots.map { it.name }),
                                stores = stores,
                                support = support,
                                retirements = retirements,
                                searchIndexer = indexer,
                                sourceObserver = { sources, registeredRoots, rank ->
                                    offlineSources = sources
                                    offlineRegisteredRoots = registeredRoots
                                    offlineRank = rank
                                },
                            )
                            val offlineSourceList = checkNotNull(offlineSources)
                            val offlineRootSet = checkNotNull(offlineRegisteredRoots)
                            val offlineRankFunction = checkNotNull(offlineRank)
                            offlineSourceList.map { it.store } shouldBe listOf(downStore, primaryStore, objectStore)
                            offlineSourceList.map { it.history } shouldBe
                                listOf(NoOpHistoryProvider, NoOpHistoryProvider, NoOpHistoryProvider)
                            offlineSourceList.map { it.manifests }.forEach { it.shouldBeNull() }
                            offlineRootSet shouldBe observedRootSet
                            offlineRankFunction(downRootName) shouldBe 0
                            offlineRankFunction(RootName.PRIMARY) shouldBe 1
                            offlineRankFunction(objectRootName) shouldBe 2
                            primaryStore.totalCalls() shouldBe 0
                            objectStore.totalCalls() shouldBe 0
                            downStore.totalCalls() shouldBe 0
                            primaryHistory.totalCalls() shouldBe 0
                            objectHistory.totalCalls() shouldBe 0
                            downHistory.totalCalls() shouldBe 0
                            checkpoint.loadCalls shouldBe 0
                            checkpoint.replaceCalls shouldBe 0
                            provider.indexCalls shouldBe 0
                            provider.rebuildCalls shouldBe 0
                            verify(exactly = 0) { availability.current() }
                            verify(exactly = 0) { epochs.establish(any()) }
                            verify(exactly = 0) { retirements.observation(any()) }
                            verify(exactly = 0) { retirements.bindingEpoch(any()) }
                            verify(exactly = 0) { retirements.gitHead(any()) }
                            verify(exactly = 0) { retirements.revoke(any()) }
                            verify(exactly = 0) { retirements.applyProofs(any(), any(), any(), any()) }

                            val offlineSnapshot = offline.rebuild()
                            offlineSnapshot.pages.map { it.root } shouldBe
                                listOf(downRootName, RootName.PRIMARY, objectRootName)
                            offlineSnapshot.pages.all { it.commit == null } shouldBe true
                            checkpoint.loadCalls shouldBe 2
                            checkpoint.replaceCalls shouldBe 1
                            downStore.scanCalls shouldBe 1
                            primaryStore.scanCalls shouldBe 1
                            objectStore.scanCalls shouldBe 1
                            objectStore.manifestCalls shouldBe 0
                            primaryHistory.totalCalls() shouldBe 0
                            objectHistory.totalCalls() shouldBe 0
                            downHistory.totalCalls() shouldBe 0
                            repositories.checkpoints.load() shouldBe offlineSnapshot.pages.associate { it.rooted to it.urlPath }
                            verify(exactly = 3) { retirements.observation(capture(offlineObservationRoots)) }
                            offlineObservationRoots shouldBe configuredRoots
                            verify(exactly = 2) { retirements.bindingEpoch(capture(offlineBindingEpochRoots)) }
                            offlineBindingEpochRoots shouldBe listOf(downRootName, RootName.PRIMARY)
                            verify(exactly = 1) { retirements.applyProofs(capture(offlineProofs), any(), any(), any()) }
                            offlineProofs.captured.shouldBeEmpty()
                            verify(exactly = 0) { retirements.revoke(any()) }
                            verify(exactly = 0) { retirements.gitHead(any()) }
                            val persistedObservationsAfter = repositories.retirements.observations()
                            configuredRoots.associateWith { root -> persistedObservationsAfter.getValue(root) } shouldBe
                                observationBaseline
                            configuredRoots.associateWith { root -> repositories.topology.topology(root) } shouldBe topologyBaseline
                            configuredRoots.associateWith { root -> repositories.retirements.bindingEpoch(root) } shouldBe
                                bindingEpochBaseline.mapValues { (_, epoch) -> BindingEpoch(epoch.value + 1) }
                            offline.rebuildSearchIndex() shouldBe 3
                            provider.indexCalls shouldBe 0
                            provider.rebuildCalls shouldBe 1
                        }
                    }
                }
            }
        }
    }
})

private fun localStore(root: Path, name: RootName, dataDir: Path): LocalContentStore = LocalStoreInputs(
    root = root,
    ignoreRules = IgnoreRules(),
    exclusions = listOf(dataDir),
    rootName = name,
    onRootUnavailable = {},
    onIdentityRebind = {},
    policy = ContentPathPolicy.ALL,
).let(RootStoreFactory::local)

private open class CountingStore(private val delegate: ContentStore) : ContentStore by delegate {
    var availableCalls = 0
    var scanCalls = 0
    var readCalls = 0
    var readClassifiedCalls = 0

    override fun available(): Boolean {
        availableCalls += 1
        return delegate.available()
    }

    override fun scan() = delegate.scan().also { scanCalls += 1 }

    override fun read(path: TreePath) = delegate.read(path).also { readCalls += 1 }

    override fun readClassified(path: TreePath) = delegate.readClassified(path).also { readClassifiedCalls += 1 }

    open fun reset() {
        availableCalls = 0
        scanCalls = 0
        readCalls = 0
        readClassifiedCalls = 0
    }

    open fun totalCalls(): Int = availableCalls + scanCalls + readCalls + readClassifiedCalls
}

private class CountingManifestStore(
    delegate: ContentStore,
    private val manifest: ObjectManifest,
) : CountingStore(delegate), ObjectManifestProvider {
    var manifestCalls = 0

    override fun latestManifest(): ObjectManifest? {
        manifestCalls += 1
        return manifest
    }

    override fun reset() {
        super.reset()
        manifestCalls = 0
    }

    override fun totalCalls(): Int = super.totalCalls() + manifestCalls
}

private class RecordingHistoryProvider(label: String) : HistoryProvider {
    val sha = label.padEnd(40, label.first())
    var currentHeadCalls = 0
    var lastCommitsCalls = 0

    override val enabled: Boolean = true

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit =
        error("rebuild must never commit")

    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> {
        lastCommitsCalls += 1
        return paths.associateWith { commit }
    }

    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()

    override fun diff(from: String, to: String, path: TreePath): FileDiff =
        FileDiff(from = from, to = to, path = path, unifiedDiff = "")

    override fun prepare() = Unit

    override fun gateCheck() = Unit

    override fun currentHead(): String? {
        currentHeadCalls += 1
        return null
    }

    override fun isAncestor(ancestor: String, descendant: String): Boolean = false

    override fun deletedIn(from: String, to: String): Set<TreePath>? = null

    fun reset() {
        currentHeadCalls = 0
        lastCommitsCalls = 0
    }

    fun totalCalls(): Int = currentHeadCalls + lastCommitsCalls

    private val commit = Commit(
        sha = sha,
        author = CommitIdentity("runtime-test", "runtime@test"),
        committer = CommitIdentity("runtime-test", "runtime@test"),
        authorTime = Instant.fromEpochSeconds(0),
        committerTime = Instant.fromEpochSeconds(0),
        message = "runtime-test",
    )
}

private class CountingCheckpoint(private val delegate: PageCheckpointRepository) : PageCheckpointRepository {
    var loadCalls = 0
    var replaceCalls = 0

    override fun load() = delegate.load().also { loadCalls += 1 }

    override fun replace(urlPaths: Map<RootedPageId, TreePath?>) {
        replaceCalls += 1
        delegate.replace(urlPaths)
    }

    fun reset() {
        loadCalls = 0
        replaceCalls = 0
    }
}

private class RecordingSearchProvider : SearchProvider {
    var indexCalls = 0
    var rebuildCalls = 0

    override fun index(pages: List<PageDocuments>) {
        indexCalls += 1
    }

    override fun delete(ids: Collection<RootedPageId>) = Unit

    override fun search(query: SearchQuery): SearchResults = SearchResults(total = 0, hits = emptyList())

    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) {
        rebuildCalls += 1
        pages.count()
    }

    override fun indexedState(): Map<RootedPageId, PageSearchState> = emptyMap()
}
