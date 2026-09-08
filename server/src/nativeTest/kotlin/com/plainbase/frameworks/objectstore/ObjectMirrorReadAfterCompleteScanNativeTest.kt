package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRootTopologyRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NATIVE_OBJECT_BINDING = RootBinding("https://native.example|handbook|")

/** Native coverage for actual NIO post-scan mirror loss and the file-backed xerial seam through close, reopen, and hydrate. */
@Tag("native")
class ObjectMirrorReadAfterCompleteScanNativeTest {

    @Test
    fun `renamed page disappearing after a complete scan retains the materialized binding`() {
        NativeObjectGraph().use { graph ->
            val initial = graph.prepare()
            graph.renameVictim()
            val store = graph.reopen()
            val manifest = requireNotNull(store.latestManifest())
            assertEquals(setOf(graph.bPath, graph.anchorPath), manifest.listed)
            assertEquals(
                setOf(BindingRef(graph.aPath, graph.victimId), BindingRef(graph.anchorPath, graph.anchorId)),
                manifest.rowsAtStart,
            )
            assertEquals(NATIVE_OBJECT_BINDING, manifest.binding)
            assertEquals(graph.bindingEpoch(), manifest.bindingEpoch)
            assertEquals(
                mapOf(
                    graph.aRooted to (graph.victimId to true),
                    graph.anchorRootedPath to (graph.anchorId to true),
                ),
                liveProjection(graph),
            )
            assertNull(graph.idMap.find(graph.bRooted))
            assertTrue(store.available())

            var scanCalls = 0
            var deletionCalls = 0
            val reads = mutableListOf<NativeReadObservation>()
            val wrapped = object : ContentStore by store {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    assertEquals(1, scanCalls)
                    val result = store.scan()
                    assertTrue(result.complete)
                    assertEquals(setOf(graph.bPath, graph.anchorPath), result.files.map { it.path }.toSet())
                    assertContentEquals(graph.victimBytes, Files.readAllBytes(graph.mirrorRoot.resolve(graph.bPath.value)))
                    deletionCalls += 1
                    assertEquals(1, deletionCalls)
                    try {
                        Files.delete(graph.mirrorRoot.resolve(graph.bPath.value))
                    } catch (failure: IOException) {
                        throw AssertionError("the native probe could not delete the B mirror file", failure)
                    }
                    assertTrue(Files.exists(graph.mirrorRoot.resolve(graph.anchorPath.value)))
                    assertTrue(store.available())
                    return result
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = store.readClassified(path)
                    reads += NativeReadObservation(path, result)
                    if (path == graph.bPath) {
                        assertEquals(StoreRead.NoBytes, result)
                        assertNull(graph.idMap.find(graph.bRooted))
                    }
                    return result
                }
            }

            val snapshot = graph.builder(wrapped).rebuild()
            assertEquals(1, scanCalls)
            assertEquals(1, deletionCalls)
            assertEquals(listOf(NativeReadObservation(graph.bPath, StoreRead.NoBytes)), reads.filter { it.path == graph.bPath })
            assertTrue(store.available())
            assertContentEquals(graph.victimBytes, requireNotNull(graph.client.currentBytes(graph.bPath.value)))
            assertFalse(Files.exists(graph.mirrorRoot.resolve(graph.bPath.value)))
            assertTrue(Files.exists(graph.mirrorRoot.resolve(graph.anchorPath.value)))
            assertEquals(manifest, requireNotNull(store.latestManifest()))
            assertEquals(setOf(graph.anchorRootedPath), rootedPaths(snapshot))
            assertNull(snapshot.byRootedId[graph.victimRootedId])
            assertNull(snapshot.byPath[graph.aRooted])
            assertNull(snapshot.byPath[graph.bRooted])
            assertEquals(
                mapOf(graph.victimRootedId to initial.victimUrl, graph.anchorRootedId to initial.anchorUrl),
                graph.checkpoints.load(),
            )
            assertEquals(
                mapOf(
                    graph.aRooted to (graph.victimId to true),
                    graph.anchorRootedPath to (graph.anchorId to true),
                ),
                liveProjection(graph),
            )
            assertTrue(retiredProjection(graph).isEmpty())
            assertEquals(
                mapOf(RootName.PRIMARY to setOf(BindingRef(graph.aPath, graph.victimId))),
                graph.limbo.current(),
            )
        }
    }

    @Test
    fun `readable rename preserves the materialized page without retirement`() {
        NativeObjectGraph().use { graph ->
            val initial = graph.prepare()
            graph.renameVictim()
            val store = graph.reopen()
            var scanCalls = 0
            val reads = mutableListOf<NativeReadObservation>()
            val wrapped = object : ContentStore by store {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    val result = store.scan()
                    assertTrue(result.complete)
                    assertEquals(setOf(graph.bPath, graph.anchorPath), result.files.map { it.path }.toSet())
                    return result
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = store.readClassified(path)
                    reads += NativeReadObservation(path, result)
                    return result
                }
            }
            val snapshot = graph.builder(wrapped).rebuild()

            assertEquals(1, scanCalls)
            assertEquals(
                listOf(NativeReadObservation(graph.bPath, StoreRead.Bytes(graph.victimBytes))),
                reads.filter { it.path == graph.bPath },
            )
            assertEquals(setOf(graph.bRooted, graph.anchorRootedPath), rootedPaths(snapshot))
            assertEquals(graph.bPath, snapshot.byRootedId.getValue(graph.victimRootedId).path)
            assertNull(graph.idMap.find(graph.aRooted))
            assertEquals(
                mapOf(
                    graph.bRooted to (graph.victimId to true),
                    graph.anchorRootedPath to (graph.anchorId to true),
                ),
                liveProjection(graph),
            )
            assertTrue(retiredProjection(graph).isEmpty())
            assertEquals(initial.anchorUrl, graph.checkpoints.load()[graph.anchorRootedId])
            assertEquals(
                snapshot.byRootedId.getValue(graph.victimRootedId).urlPath,
                graph.checkpoints.load()[graph.victimRootedId],
            )
            assertTrue(graph.limbo.current().isEmpty())
            assertContentEquals(graph.victimBytes, requireNotNull(graph.client.currentBytes(graph.bPath.value)))
        }
    }

    @Test
    fun `genuine deletion retires the old binding while the anchor remains`() {
        NativeObjectGraph().use { graph ->
            val initial = graph.prepare()
            graph.deleteVictim()
            val store = graph.reopen()
            val manifest = requireNotNull(store.latestManifest())
            assertEquals(setOf(graph.anchorPath), manifest.listed)
            assertTrue(store.available())
            val scan = store.scan()
            assertTrue(scan.complete)
            assertEquals(setOf(graph.anchorPath), scan.files.map { it.path }.toSet())
            assertEquals(StoreRead.Bytes(graph.anchorBytes), store.readClassified(graph.anchorPath))

            val snapshot = graph.builder().rebuild()
            assertEquals(setOf(graph.anchorRootedPath), rootedPaths(snapshot))
            assertNull(snapshot.byPath[graph.aRooted])
            assertNull(snapshot.byPath[graph.bRooted])
            assertEquals(
                mapOf(graph.anchorRootedPath to (graph.anchorId to true)),
                liveProjection(graph),
            )
            assertEquals(setOf(graph.aRooted to graph.victimId), retiredProjection(graph))
            assertEquals(mapOf(graph.anchorRootedId to initial.anchorUrl), graph.checkpoints.load())
            assertTrue(graph.limbo.current().isEmpty())
        }
    }
}

private data class InitialNativeState(val victimUrl: TreePath?, val anchorUrl: TreePath?)

private data class NativeReadObservation(val path: TreePath, val result: StoreRead)

private class NativeObjectGraph : AutoCloseable {
    val dataDir: Path = Files.createTempDirectory("pb-native-object-read")
    val mirrorRoot: Path = Files.createDirectories(dataDir.resolve("mirror"))
    private val statePath = dataDir.resolve("mirror-state")
    private val databasePath = dataDir.resolve("plainbase.db")
    private val driver = DatabaseFactory.createDriver(databasePath)
    private val database = DatabaseFactory.createDatabase(driver)
    val idMap = SqlDelightIdMapRepository(database)
    private val retirements = SqlDelightRetirementRepository(database)
    private val topology = SqlDelightRootTopologyRepository(database)
    private val bindings = BindingLatch(topology)
    private val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val limbo = RootLimbo()
    val client = NativeObjectClient()
    private val root = Root(
        name = RootName.PRIMARY,
        backend = RootBackend.Object(bucket = "docs", prefix = ""),
        editable = true,
        history = HistoryMode.OFF,
    )
    private val registry = RootRegistry.of(listOf(root))
    val victimId = PageId.require("05050505-0505-7050-8050-000000000050")
    val anchorId = PageId.require("06060606-0606-7060-8060-000000000060")
    val aPath = TreePath.require("a.md")
    val bPath = TreePath.require("b.md")
    val anchorPath = TreePath.require("anchor.md")
    val victimBytes = materializedPage(victimId, "Native victim")
    val anchorBytes = materializedPage(anchorId, "Native anchor")
    val aRooted = RootedPath(RootName.PRIMARY, aPath)
    val bRooted = RootedPath(RootName.PRIMARY, bPath)
    val anchorRootedPath = RootedPath(RootName.PRIMARY, anchorPath)
    val victimRootedId = RootedPageId(RootName.PRIMARY, victimId)
    val anchorRootedId = RootedPageId(RootName.PRIMARY, anchorId)
    private var store: ObjectContentStore? = null

    fun prepare(): InitialNativeState {
        client.seed(aPath.value, victimBytes)
        client.seed(anchorPath.value, anchorBytes)
        val first = freshStore()
        val snapshot = builder(first).rebuild()
        assertEquals(BindingStatus.TRUSTED, requireNotNull(topology.topology(RootName.PRIMARY)).status)
        assertEquals(setOf(aRooted, anchorRootedPath), rootedPaths(snapshot))
        assertEquals(mapOf(aRooted to (victimId to true), anchorRootedPath to (anchorId to true)), liveProjection(this))
        assertTrue(retiredProjection(this).isEmpty())
        assertTrue(limbo.current().isEmpty())
        val victimUrl = snapshot.byRootedId.getValue(victimRootedId).urlPath
        val anchorUrl = snapshot.byRootedId.getValue(anchorRootedId).urlPath
        assertEquals(mapOf(victimRootedId to victimUrl, anchorRootedId to anchorUrl), checkpoints.load())
        return InitialNativeState(victimUrl, anchorUrl)
    }

    fun renameVictim() {
        val bytes = requireNotNull(client.currentBytes(aPath.value))
        assertContentEquals(victimBytes, bytes)
        client.remove(aPath.value)
        client.seed(bPath.value, bytes)
    }

    fun deleteVictim() {
        client.remove(aPath.value)
    }

    fun reopen(): ObjectContentStore {
        requireNotNull(store).close()
        store = null
        return freshStore()
    }

    fun builder(contentStore: ContentStore = requireNotNull(store)): IndexBuilder {
        val manifests = requireNotNull(store)
        return IndexBuilder(
            sources = listOf(IndexBuilder.Source(root, contentStore, NoOpHistoryProvider, manifests = manifests)),
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7IdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = idMap,
            aliasRegistry = aliases,
            checkpoint = checkpoints,
            citations = CitationFactory(),
            rootRank = registry::rank,
            registeredRoots = setOf(RootName.PRIMARY),
            listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)),
            retirements = retirements,
            limbo = limbo,
            bindings = bindings,
        )
    }

    fun bindingEpoch() = retirements.bindingEpoch(RootName.PRIMARY)

    private fun freshStore(): ObjectContentStore {
        val ignoreRules = IgnoreRules()
        val mirror = LocalContentStore(root = mirrorRoot, rootName = RootName.PRIMARY, ignoreRules = ignoreRules)
        val fresh = ObjectContentStore(
            client = client,
            mirror = mirror,
            state = MirrorState(statePath),
            binding = NATIVE_OBJECT_BINDING,
            rowsAtStart = ::rowsAtStart,
            keyPrefix = "",
            pollSeconds = 3600,
            dirtyPaths = { emptySet() },
            mirrorRoot = mirrorRoot,
            ignoreRules = ignoreRules,
        )
        if (bindings.observe(RootName.PRIMARY, NATIVE_OBJECT_BINDING) != BindingStatus.TRUSTED) fresh.rebind()
        fresh.hydrate()
        store = fresh
        return fresh
    }

    private fun rowsAtStart(): RowsAtStart {
        val bindingEpoch = retirements.bindingEpoch(RootName.PRIMARY)
        val rows = idMap.bindings().filter { it.path.root == RootName.PRIMARY }
            .mapTo(mutableSetOf()) { BindingRef(it.path.path, it.id) }
        return RowsAtStart(rows, bindingEpoch)
    }

    override fun close() {
        store?.close()
        driver.close()
        dataDir.toFile().deleteRecursively()
    }
}

private fun materializedPage(id: PageId, title: String): ByteArray =
    "---\nid: ${id.value}\n---\n\n# $title\n\nbody\n".toByteArray()

private fun rootedPaths(snapshot: PageIndex): Set<RootedPath> =
    snapshot.pages.mapTo(mutableSetOf()) { RootedPath(it.root, it.path) }

private fun liveProjection(graph: NativeObjectGraph): Map<RootedPath, Pair<PageId, Boolean>> =
    graph.idMap.bindings().associate { it.path to (it.id to it.materialized) }

private fun retiredProjection(graph: NativeObjectGraph): Set<Pair<RootedPath, PageId>> =
    graph.idMap.retiredBindings().mapTo(mutableSetOf()) { it.path to it.id }

private class NativeObjectClient : ObjectStoreClient {
    private val objects = linkedMapOf<String, Pair<ByteArray, String>>()
    private var etagSequence = 0

    fun seed(key: String, bytes: ByteArray): String {
        val etag = "\"native-object-${etagSequence++}\""
        objects[key] = bytes.copyOf() to etag
        return etag
    }

    fun remove(key: String) {
        objects.remove(key)
    }

    fun currentBytes(key: String): ByteArray? = objects[key]?.first?.copyOf()

    override suspend fun head(key: String): ObjectStat? =
        objects[key]?.let { ObjectStat(etag = it.second, size = it.first.size.toLong()) }

    override suspend fun get(key: String, maxBytes: Long?): FetchedObject? =
        objects[key]?.let { FetchedObject(it.first.copyOf(), it.second) }

    override suspend fun getToFile(key: String, target: Path, requestTimeoutMillis: Long?): Boolean {
        val bytes = objects[key]?.first ?: return false
        Files.write(target, bytes)
        return true
    }

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        condition: PutCondition,
        contentType: String?,
        requestTimeoutMillis: Long?,
    ): PutOutcome = PutOutcome.Stored(seed(key, bytes))

    override suspend fun putFromFile(key: String, source: Path, contentType: String?, requestTimeoutMillis: Long?): PutOutcome =
        put(key, Files.readAllBytes(source), PutCondition.None, contentType, requestTimeoutMillis)

    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int?): ListResponseParser.Listing {
        val entries = objects.keys.filter { it.startsWith(prefix) }.sorted().map { key ->
            val (bytes, etag) = objects.getValue(key)
            ListResponseParser.Entry(
                key = PercentCoding.encodeSegment(key),
                etag = etag,
                size = bytes.size.toLong(),
            )
        }
        return ListResponseParser.Listing(entries, isTruncated = false, nextContinuationToken = null)
    }

    override fun close() = Unit
}
