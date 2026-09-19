package com.plainbase.frameworks.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.localRoot
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Native-local real callback and reclaim ordering coverage; no JVM fixture or mock dependency. */
@Tag("native")
class SearchRetirementWiringNativeTest {

    @Test
    fun `sequential retirement exclusion refusal reclaim and targeted upsert use real callbacks in-image`() {
        NativeRetirementGraph().use { graph ->
            graph.observeRoot()
            val before = graph.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, graph.victimId)
            val oldPage = requireNotNull(before.pageAt(victim))
            val oldBytes = Files.readAllBytes(graph.victimPath)
            val applied = graph.retireVictim()
            assertEquals(setOf(victim), applied)
            assertNotNull(graph.idMap.retiredAt(RootName.PRIMARY, graph.victimId))

            graph.seedSurvivingMetadata()
            val metadataAfterProof = graph.metadataSnapshot()
            val holderBeforeExplicit = graph.builder.current
            val accepted = graph.builder.rebuildSearchIndex()
            assertEquals(1, accepted)
            assertEquals(holderBeforeExplicit, graph.builder.current)
            assertEquals(metadataAfterProof, graph.metadataSnapshot())
            assertFalse(graph.provider.indexedState().containsKey(victim))
            assertTrue(graph.provider.indexedState().containsKey(graph.anchorRooted))
            val anchorState = requireNotNull(graph.provider.indexedState()[graph.anchorRooted])
            assertEquals(graph.anchorHash, anchorState.contentHash)
            assertEquals(TreePath.require("anchor.md"), anchorState.path)
            assertEquals(0L, graph.provider.search(SearchQuery("nativevictimold", limit = 20, offset = 0)).total)
            assertEquals(1L, graph.provider.search(SearchQuery("nativeanchor", limit = 20, offset = 0)).total)
            assertTrue(Files.readAllBytes(graph.victimPath).contentEquals(oldBytes))

            val refusal = runCatching {
                graph.builder.reindex(RootedPath(RootName.PRIMARY, TreePath.require("victim.md")))
            }.exceptionOrNull()
            assertNotNull(refusal)
            assertTrue(refusal is IllegalStateException)
            assertTrue(refusal.message.orEmpty().contains("retired/unbound"), refusal.message)
            assertFalse(graph.provider.indexedState().containsKey(victim))

            assertEquals(
                BindOutcome.Bound,
                graph.idMap.bind(RootedPath(RootName.PRIMARY, TreePath.require("victim.md")), graph.victimId, materialized = true),
            )
            val reclaimed = graph.builder.reindex(RootedPath(RootName.PRIMARY, TreePath.require("victim.md")))
            assertEquals(graph.victimId, requireNotNull(reclaimed.pageAt(victim)).id)
            assertEquals(null, graph.idMap.retiredAt(RootName.PRIMARY, graph.victimId))
            val reclaimedState = requireNotNull(graph.provider.indexedState()[victim])
            assertEquals(oldPage.contentHash, reclaimedState.contentHash)
            assertEquals(TreePath.require("victim.md"), reclaimedState.path)
            assertEquals(1L, graph.provider.search(SearchQuery("nativevictimold", limit = 20, offset = 0)).total)
        }
    }

    @Test
    fun `after-capture reclaim waits for old swap then publishes through real create in-image`() {
        NativeRetirementGraph().use { graph ->
            graph.observeRoot()
            graph.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, graph.victimId)
            Files.delete(graph.victimPath)
            graph.retireVictim()
            graph.provider.failNextDelete = true
            graph.builder.rebuild()
            assertEquals(null, graph.builder.current.pageAt(victim))
            assertTrue(graph.provider.indexedState().containsKey(victim))

            graph.provider.resetEvents()
            graph.provider.recordEvents = true
            graph.provider.markNextRebuildComplete = true
            val queryGate = graph.armRetirementQueryGate()
            val bindReturned = CountDownLatch(1)
            val releaseHistory = CountDownLatch(1)
            val oldError = AtomicReference<Throwable?>(null)
            val creatorError = AtomicReference<Throwable?>(null)
            val creatorOutcome = AtomicReference<WriteOutcome?>(null)
            val newBytes = graph.pageBytes("nativevictimnew")
            val pipeline = graph.pipeline(
                historyHook = WriteHistoryHook { _, _, _, _, _ ->
                    bindReturned.countDown()
                    check(releaseHistory.await(10, TimeUnit.SECONDS)) { "timed out waiting for native create release" }
                    null
                },
            )
            val oldRebuild = Thread {
                runCatching { graph.builder.rebuildSearchIndex() }.onFailure(oldError::set)
            }
            val creator = Thread {
                runCatching {
                    pipeline.create(
                        createGrantForTests(),
                        CreateIntent(graph.victimId, RootName.PRIMARY, TreePath.require("victim.md"), newBytes),
                    )
                }.onSuccess(creatorOutcome::set).onFailure(creatorError::set)
            }
            val workers = listOf(oldRebuild, creator)

            try {
                oldRebuild.start()
                assertTrue(queryGate.entered.await(10, TimeUnit.SECONDS))
                assertTrue(victim in queryGate.captured)
                creator.start()
                assertTrue(bindReturned.await(10, TimeUnit.SECONDS))
                assertNotNull(graph.idMap.bindingInRoot(RootName.PRIMARY, graph.victimId))
                assertEquals(null, graph.idMap.retiredAt(RootName.PRIMARY, graph.victimId))
                releaseHistory.countDown()
                assertTrue(awaitBlocked(creator), "creator did not reach the builder monitor after its bind hook")

                queryGate.release.countDown()
                assertTrue(queryGate.completed.await(10, TimeUnit.SECONDS))
                oldRebuild.join(10_000)
                assertFalse(oldRebuild.isAlive)
            } finally {
                releaseHistory.countDown()
                queryGate.release.countDown()
                workers.forEach { it.join(10_000) }
                workers.forEach { assertFalse(it.isAlive) }
            }

            assertEquals(null, oldError.get())
            assertEquals(null, creatorError.get())
            assertEquals(WriteOutcome.Written(graph.contentHash(newBytes), null), creatorOutcome.get())
            val events = graph.provider.eventsSnapshot()
            assertTrue(events.indexOf("old-swap-complete") >= 0)
            assertTrue(events.indexOf("creator-index") > events.indexOf("old-swap-complete"), events.toString())
            val current = requireNotNull(graph.builder.current.pageAt(victim))
            assertEquals(graph.contentHash(newBytes), current.contentHash)
            assertEquals(TreePath.require("victim.md"), current.path)
            val newState = requireNotNull(graph.provider.indexedState()[victim])
            assertEquals(graph.contentHash(newBytes), newState.contentHash)
            assertEquals(TreePath.require("victim.md"), newState.path)
            assertTrue(Files.readAllBytes(graph.victimPath).contentEquals(newBytes))
            assertEquals(1L, graph.provider.search(SearchQuery("nativevictimnew", limit = 20, offset = 0)).total)
            assertEquals(0L, graph.provider.search(SearchQuery("nativevictimold", limit = 20, offset = 0)).total)
            assertFalse(graph.dirtyPages.all().any { it.pageId == graph.victimId })
        }
    }
}

private class NativeRetirementGraph : AutoCloseable {
    private val dataDir = Files.createTempDirectory("pb-native-retirement-wiring")
    private val contentDir = Files.createDirectories(dataDir.resolve("docs"))
    private val appPath = dataDir.resolve("plainbase.db")
    private val searchPath = dataDir.resolve("search.db")
    private val driver = DatabaseFactory.createDriver(appPath)
    private val database = DatabaseFactory.createDatabase(driver)
    private val searchDb = SearchDb(searchPath)
    private val registry = RootRegistry.of(listOf(localRoot("docs", contentDir)))
    private val store = LocalContentStore(contentDir)
    private val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val idMap = SqlDelightIdMapRepository(database)
    private val retirements = SqlDelightRetirementRepository(database)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val dirtyPages = SqlDelightDirtyPageRepository(database)
    private val epochs = ObservationEpoch(retirements, RootConvergence())
    private val availability = RootAvailability(kotlin.time.Clock.System)
    val victimId = PageId.require("05050505-0505-0505-0505-050505050505")
    private val anchorId = PageId.require("06060606-0606-0606-0606-060606060606")
    val victimPath = contentDir.resolve("victim.md")
    private val anchorPath = contentDir.resolve("anchor.md")
    val anchorHash: String by lazy { contentHash(Files.readAllBytes(anchorPath)) }
    val anchorRooted = RootedPageId(RootName.PRIMARY, anchorId)
    val provider = NativeFaultingProvider(Fts5SearchProvider(searchDb))
    private val searchIndexer: SearchIndexer
    val builder: IndexBuilder
    private val stores = mapOf(RootName.PRIMARY to store)

    init {
        Files.writeString(victimPath, page(victimId, "Native victim", "nativevictimold"))
        Files.writeString(anchorPath, page(anchorId, "Native anchor", "nativeanchor"))
        searchIndexer = SearchIndexer(
            provider,
            SectionSplitter(),
            {
                val retired = idMap.retiredUnboundIds()
                queryGate?.let { gate ->
                    queryGate = null
                    gate.captured = retired
                    gate.entered.countDown()
                    gate.awaitRelease()
                    gate.completed.countDown()
                }
                retired
            },
            idMap::isRetiredUnbound,
            com.plainbase.domain.service.allowAllNativePolicies(),
        )
        builder = IndexBuilder(
            sources = listOf(IndexBuilder.Source(registry.primary, store, NoOpHistoryProvider)),
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7IdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = idMap,
            aliasRegistry = aliases,
            checkpoint = checkpoints,
            citations = CitationFactory(),
            rootRank = registry::rank,
            registeredRoots = registry.roots.map { it.name }.toSet(),
            listeners = listOf(
                IndexBuilder.PublicationListener(checkpoints::replaceFrom),
                IndexBuilder.PublicationListener { snapshot, _ -> searchIndexer.sync(snapshot) },
            ),
            searchIndexer = searchIndexer,
            availability = availability,
            retirements = retirements,
            limbo = RootLimbo(),
            epochs = epochs,
            policies = com.plainbase.domain.service.allowAllNativePolicies(registry.roots.map { it.name }),
        )
    }

    private var queryGate: NativeWholeGate? = null

    fun observeRoot() {
        epochs.observing(RootName.PRIMARY)
    }

    fun retireVictim(): Set<RootedPageId> {
        val observation = retirements.observation(RootName.PRIMARY)
        val epoch = retirements.bindingEpoch(RootName.PRIMARY)
        val binding = requireNotNull(idMap.bindingInRoot(RootName.PRIMARY, victimId))
        val proof = AbsenceProof.accepted(
            root = RootName.PRIMARY,
            source = ProofSource.OPERATOR,
            observationId = observation,
            bindingEpoch = epoch,
            covers = setOf(BindingRef(binding.path.path, victimId)),
        )
        return retirements.applyProofs(listOf(proof), witnessed = emptySet(), unavailableNow = { emptySet() })
    }

    fun armRetirementQueryGate(): NativeWholeGate = NativeWholeGate().also { queryGate = it }

    fun pipeline(historyHook: WriteHistoryHook): WritePipeline = WritePipeline(
        stores = { root -> stores.getValue(root) },
        indexBuilder = builder,
        citations = CitationFactory(),
        frontmatterParser = FrontmatterReader(),
        dirtyPages = dirtyPages,
        idMap = idMap,
        aliasRegistry = aliases,
        availability = availability,
        historyHook = historyHook,
        policies = com.plainbase.domain.service.allowAllNativePolicies(),
    )

    fun pageBytes(body: String): ByteArray = page(victimId, "Native victim", body).toByteArray()

    fun contentHash(bytes: ByteArray): String = CitationFactory().contentHash(bytes)

    fun seedSurvivingMetadata() {
        checkpoints.replace(mapOf(anchorRooted to TreePath.require("anchor.md")))
        dirtyPages.mark(
            anchorId,
            RootedPath(RootName.PRIMARY, TreePath.require("anchor.md")),
            expectedHash = anchorHash,
            stage = Stage.WRITING,
        )
    }

    fun metadataSnapshot(): List<Any> = listOf(
        idMap.bindings(),
        idMap.retiredBindings(),
        checkpoints.load(),
        dirtyPages.all(),
        retirements.observations(),
        retirements.bindingEpoch(RootName.PRIMARY),
    )

    override fun close() {
        searchDb.close()
        driver.close()
        Files.walk(dataDir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun page(id: PageId, title: String, body: String): String =
        "---\nid: ${id.value}\ntitle: $title\n---\n\n# $title\n\n$body\n"
}

private class NativeWholeGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = CountDownLatch(1)
    var captured: Set<RootedPageId> = emptySet()

    fun awaitRelease() {
        check(release.await(10, TimeUnit.SECONDS)) { "timed out waiting for native query release" }
    }
}

private class NativeFaultingProvider(private val delegate: SearchProvider) : SearchProvider by delegate {
    var failNextDelete = false
    var recordEvents = false
    var markNextRebuildComplete = false
    private val events = mutableListOf<String>()

    fun resetEvents() {
        events.clear()
    }

    fun eventsSnapshot(): List<String> = events.toList()

    override fun index(pages: List<PageDocuments>) {
        delegate.index(pages)
        if (recordEvents) events += "creator-index"
    }

    override fun delete(ids: Collection<RootedPageId>) {
        if (failNextDelete) {
            failNextDelete = false
            error("native setup search delete failure")
        }
        delegate.delete(ids)
    }

    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) {
        delegate.rebuild(pages, retired)
        if (markNextRebuildComplete) {
            markNextRebuildComplete = false
            if (recordEvents) events += "old-swap-complete"
        }
    }
}

private fun awaitBlocked(thread: Thread): Boolean {
    // This control relies on IndexBuilder's JVM-compatible intrinsic monitor: BLOCKED means the creator reached it.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        when (thread.state) {
            Thread.State.BLOCKED -> return true
            Thread.State.TERMINATED -> return false
            else -> Thread.yield()
        }
    }
    return false
}
