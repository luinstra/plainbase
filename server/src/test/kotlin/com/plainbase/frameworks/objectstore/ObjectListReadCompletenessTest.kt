package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.ContentStore
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
import com.plainbase.domain.service.TestIdProvider
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals

private val OBJECT_READ_BINDING = RootBinding("https://r2.example|handbook|")

/** Matrix for the distinction between walk completeness and successful Markdown candidate reads. */
class ObjectListReadCompletenessTest : FunSpec({

    test("should retain every bound page when one enumerated candidate loses bytes after a complete scan") {
        val fixture = readFixture(withBoundCandidate = true)

        ObjectAbsenceWorld().use { world ->
            val initial = prepareInitial(world, fixture)
            fixture.bucket.remove(fixture.a.value)
            world.store.pollOnce()
            val manifest = requireNotNull(world.store.latestManifest())
            manifest.listed shouldBe setOf(fixture.c, fixture.anchor)
            manifest.rowsAtStart shouldBe setOf(
                BindingRef(fixture.a, fixture.aId),
                BindingRef(fixture.c, fixture.cId),
                BindingRef(fixture.anchor, fixture.anchorId),
            )

            val real = world.store
            var scanCalls = 0
            var deletionCalls = 0
            val reads = mutableListOf<CompletenessReadObservation>()
            val wrapped = object : ContentStore by real {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    scanCalls shouldBe 1
                    val result = real.scan()
                    result.complete shouldBe true
                    result.files.map { it.path }.toSet() shouldBe setOf(fixture.c, fixture.anchor)
                    assertContentEquals(fixture.cBytes, Files.readAllBytes(world.mirrorRoot.resolve(fixture.c.value)))
                    deletionCalls += 1
                    deletionCalls shouldBe 1
                    try {
                        Files.delete(world.mirrorRoot.resolve(fixture.c.value))
                    } catch (failure: IOException) {
                        throw AssertionError("the test could not delete the C mirror file", failure)
                    }
                    return result
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += CompletenessReadObservation(path, result)
                    if (path == fixture.c) {
                        result shouldBe StoreRead.NoBytes
                        val cBinding = world.idMap.find(fixture.cRooted).shouldNotBeNull()
                        cBinding.id shouldBe fixture.cId
                        cBinding.materialized shouldBe true
                    }
                    return result
                }
            }

            val snapshot = world.builder(contentStore = wrapped).rebuild()
            scanCalls shouldBe 1
            deletionCalls shouldBe 1
            reads.filter { it.path == fixture.c } shouldBe listOf(CompletenessReadObservation(fixture.c, StoreRead.NoBytes))
            completenessSnapshotRootedPaths(snapshot) shouldBe setOf(fixture.anchorRooted)
            world.idMap.bindings().associate { it.path to (it.id to it.materialized) } shouldBe mapOf(
                fixture.aRooted to (fixture.aId to true),
                fixture.cRooted to (fixture.cId to true),
                fixture.anchorRooted to (fixture.anchorId to true),
            )
            completenessRetiredProjection(world) shouldBe emptySet()
            world.checkpoints.load() shouldBe initial.urls
            world.limbo.current() shouldBe mapOf(
                RootName.PRIMARY to setOf(
                    BindingRef(fixture.a, fixture.aId),
                    BindingRef(fixture.c, fixture.cId),
                ),
            )
        }
    }

    test("should publish bytes without identity as an unmaterialized page") {
        val fixture = readFixture()
        val draft = page("draft.md")
        val draftBytes = "---\ntitle: Draft\n---\n\n# Draft\n\nbody\n".toByteArray()

        ObjectAbsenceWorld().use { world ->
            val initial = prepareInitial(world, fixture)
            fixture.bucket.remove(fixture.a.value)
            fixture.bucket.seed(draft.value, draftBytes)
            world.store.pollOnce()

            val scan = world.store.scan()
            scan.complete shouldBe true
            scan.files.map { it.path }.toSet() shouldBe setOf(draft, fixture.anchor)
            assertContentEquals(draftBytes, (world.store.readClassified(draft) as StoreRead.Bytes).bytes)
            FrontmatterReader().parse(draftBytes).scalar("id") shouldBe null

            val reads = mutableListOf<CompletenessReadObservation>()
            val real = world.store
            val wrapped = object : ContentStore by real {
                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += CompletenessReadObservation(path, result)
                    return result
                }
            }
            val snapshot = world.builder(contentStore = wrapped).rebuild()
            val draftRooted = RootedPath(RootName.PRIMARY, draft)
            val draftPage = snapshot.byPath.getValue(draftRooted)
            val draftBinding = world.idMap.find(draftRooted).shouldNotBeNull()

            reads.filter { it.path == draft } shouldBe listOf(CompletenessReadObservation(draft, StoreRead.Bytes(draftBytes)))
            draftPage.materialized shouldBe false
            draftBinding.id shouldBe draftPage.id
            draftBinding.materialized shouldBe false
            completenessSnapshotRootedPaths(snapshot) shouldBe setOf(draftRooted, fixture.anchorRooted)
            world.idMap.find(fixture.aRooted).shouldBeNull()
            completenessRetiredProjection(world) shouldBe setOf(fixture.aRooted to fixture.aId)
            world.checkpoints.load() shouldBe mapOf(
                RootedPageId(RootName.PRIMARY, draftPage.id) to draftPage.urlPath,
                fixture.anchorRootedId to initial.urls.getValue(fixture.anchorRootedId),
            )
            world.limbo.current() shouldBe emptyMap()
        }
    }

    test("should retire prior bindings when a complete generation has zero Markdown candidates") {
        val fixture = readFixture()

        ObjectAbsenceWorld().use { world ->
            prepareInitial(world, fixture)
            fixture.bucket.remove(fixture.a.value)
            fixture.bucket.remove(fixture.anchor.value)
            world.store.pollOnce()

            val manifest = requireNotNull(world.store.latestManifest())
            manifest.listed shouldBe emptySet()
            val scan = world.store.scan()
            scan.complete shouldBe true
            scan.files shouldBe emptyList()
            world.store.available() shouldBe true

            val snapshot = world.builder().rebuild()
            snapshot.pages shouldBe emptyList()
            snapshot.section(RootName.PRIMARY).pages shouldBe emptyList()
            snapshot.section(RootName.PRIMARY).folders shouldBe emptyList()
            snapshot.section(RootName.PRIMARY).assets shouldBe emptySet()
            world.idMap.bindings() shouldBe emptyList()
            world.checkpoints.load() shouldBe emptyMap()
            completenessRetiredProjection(world) shouldBe setOf(
                fixture.aRooted to fixture.aId,
                fixture.anchorRooted to fixture.anchorId,
            )
            world.limbo.current() shouldBe emptyMap()
        }
    }

    test("should retire prior pages while excluding assets and ignored or metadata descendants") {
        val fixture = readFixture()
        val asset = page("asset.bin")
        val uppercase = page("UPPER.MD")
        val hidden = page(".hidden.md")
        val metadataDescendant = page("_folder.yaml/ignored.md")

        ObjectAbsenceWorld().use { world ->
            prepareInitial(world, fixture)
            fixture.bucket.remove(fixture.a.value)
            fixture.bucket.remove(fixture.anchor.value)
            fixture.bucket.seed(asset.value, byteArrayOf(1, 2, 3))
            fixture.bucket.seed(uppercase.value, "not a page".toByteArray())
            fixture.bucket.seed(hidden.value, "hidden".toByteArray())
            fixture.bucket.seed(metadataDescendant.value, "ignored".toByteArray())
            world.store.pollOnce()

            val manifest = requireNotNull(world.store.latestManifest())
            manifest.listed shouldBe setOf(asset, uppercase, metadataDescendant)
            val real = world.store
            val actualScan = real.scan()
            actualScan.complete shouldBe true
            actualScan.files.map { it.path }.toSet() shouldBe setOf(asset, uppercase)
            actualScan.files.map { it.path }.toSet().intersect(setOf(hidden, metadataDescendant)) shouldBe emptySet()

            val reads = mutableListOf<CompletenessReadObservation>()
            val wrapped = object : ContentStore by real {
                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += CompletenessReadObservation(path, result)
                    return result
                }
            }
            val snapshot = world.builder(contentStore = wrapped).rebuild()

            reads shouldBe emptyList()
            snapshot.pages shouldBe emptyList()
            snapshot.section(RootName.PRIMARY).assets shouldBe setOf(asset, uppercase)
            world.idMap.bindings() shouldBe emptyList()
            world.checkpoints.load() shouldBe emptyMap()
            completenessRetiredProjection(world) shouldBe setOf(
                fixture.aRooted to fixture.aId,
                fixture.anchorRooted to fixture.anchorId,
            )
            world.limbo.current() shouldBe emptyMap()
        }
    }

    test("should retain bindings when the walk is incomplete even though every read succeeds") {
        val fixture = readFixture()

        ObjectAbsenceWorld().use { world ->
            val initial = prepareInitial(world, fixture)
            fixture.bucket.remove(fixture.a.value)
            world.store.pollOnce()
            val real = world.store
            var scanCalls = 0
            val reads = mutableListOf<CompletenessReadObservation>()
            val wrapped = object : ContentStore by real {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    scanCalls shouldBe 1
                    val result = real.scan()
                    result.complete shouldBe true
                    result.files.map { it.path }.toSet() shouldBe setOf(fixture.anchor)
                    return result.copy(complete = false)
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += CompletenessReadObservation(path, result)
                    return result
                }
            }

            val snapshot = world.builder(contentStore = wrapped).rebuild()
            scanCalls shouldBe 1
            reads shouldBe listOf(CompletenessReadObservation(fixture.anchor, StoreRead.Bytes(fixture.anchorBytes)))
            completenessSnapshotRootedPaths(snapshot) shouldBe setOf(fixture.anchorRooted)
            completenessLiveProjection(world) shouldBe mapOf(
                fixture.aRooted to (fixture.aId to true),
                fixture.anchorRooted to (fixture.anchorId to true),
            )
            completenessRetiredProjection(world) shouldBe emptySet()
            world.checkpoints.load() shouldBe initial.urls
            world.limbo.current() shouldBe mapOf(
                RootName.PRIMARY to setOf(BindingRef(fixture.a, fixture.aId)),
            )
        }
    }

    test("should isolate object-list retirement between programmatic object roots") {
        DualObjectWorld().use { world ->
            val primaryVictim = page("a.md")
            val primaryReplacement = page("b.md")
            val primaryAnchor = page("primary-anchor.md")
            val peerVictim = page("a.md")
            val peerAnchor = page("peer-anchor.md")
            val ids = TestIdProvider()
            val victimId = ids.next()
            val primaryAnchorId = ids.next()
            val peerAnchorId = ids.next()
            val victimBytes = materializedPage(victimId, "victim")
            val primaryAnchorBytes = materializedPage(primaryAnchorId, "primary")
            val peerAnchorBytes = materializedPage(peerAnchorId, "peer")

            world.primaryBucket.seed(primaryVictim.value, victimBytes)
            world.primaryBucket.seed(primaryAnchor.value, primaryAnchorBytes)
            world.peerBucket.seed(peerVictim.value, victimBytes)
            world.peerBucket.seed(peerAnchor.value, peerAnchorBytes)
            val primaryVictimRooted = RootedPath(RootName.PRIMARY, primaryVictim)
            val primaryReplacementRooted = RootedPath(RootName.PRIMARY, primaryReplacement)
            val primaryAnchorRooted = RootedPath(RootName.PRIMARY, primaryAnchor)
            val peerVictimRooted = RootedPath(PEER, peerVictim)
            val peerAnchorRooted = RootedPath(PEER, peerAnchor)
            val primaryVictimId = RootedPageId(RootName.PRIMARY, victimId)
            val peerVictimId = RootedPageId(PEER, victimId)
            val primaryAnchorIdRooted = RootedPageId(RootName.PRIMARY, primaryAnchorId)
            val peerAnchorIdRooted = RootedPageId(PEER, peerAnchorId)
            world.boot()
            val initial = world.builder().rebuild()
            val initialCheckpoints = world.checkpoints.load()
            val initialRootedPaths = setOf(primaryVictimRooted, primaryAnchorRooted, peerVictimRooted, peerAnchorRooted)
            completenessSnapshotRootedPaths(initial) shouldBe initialRootedPaths
            world.idMap.bindings().associate { it.path to (it.id to it.materialized) } shouldBe mapOf(
                primaryVictimRooted to (victimId to true),
                primaryAnchorRooted to (primaryAnchorId to true),
                peerVictimRooted to (victimId to true),
                peerAnchorRooted to (peerAnchorId to true),
            )
            initialCheckpoints shouldBe mapOf(
                primaryVictimId to initial.byRootedId.getValue(primaryVictimId).urlPath,
                primaryAnchorIdRooted to initial.byRootedId.getValue(primaryAnchorIdRooted).urlPath,
                peerVictimId to initial.byRootedId.getValue(peerVictimId).urlPath,
                peerAnchorIdRooted to initial.byRootedId.getValue(peerAnchorIdRooted).urlPath,
            )
            world.idMap.retiredBindings() shouldBe emptyList()
            world.limbo.current() shouldBe emptyMap()

            world.primaryBucket.remove(primaryVictim.value)
            world.primaryBucket.seed(primaryReplacement.value, victimBytes)
            world.peerBucket.remove(peerVictim.value)
            world.primaryStore.pollOnce()
            world.peerStore.pollOnce()

            val realPrimary = world.primaryStore
            val wrappedPrimary = object : ContentStore by realPrimary {
                override fun scan(): ScanResult {
                    val result = realPrimary.scan()
                    result.complete shouldBe true
                    result.files.map { it.path }.toSet() shouldBe setOf(primaryReplacement, primaryAnchor)
                    try {
                        Files.delete(world.primaryMirror.resolve(primaryReplacement.value))
                    } catch (failure: IOException) {
                        throw AssertionError("the test could not delete the primary replacement mirror file", failure)
                    }
                    return result
                }
            }

            val snapshot = world.builder(primaryContentStore = wrappedPrimary).rebuild()
            completenessSnapshotRootedPaths(snapshot) shouldBe setOf(primaryAnchorRooted, peerAnchorRooted)
            snapshot.byPath[primaryVictimRooted] shouldBe null
            snapshot.byPath[primaryReplacementRooted] shouldBe null
            snapshot.byPath[peerVictimRooted] shouldBe null
            world.idMap.bindingInRoot(RootName.PRIMARY, victimId)?.path shouldBe primaryVictimRooted
            world.idMap.bindingInRoot(PEER, victimId) shouldBe null
            completenessRetiredProjection(world) shouldBe setOf(peerVictimRooted to victimId)
            world.checkpoints.load() shouldBe mapOf(
                primaryVictimId to initialCheckpoints.getValue(primaryVictimId),
                primaryAnchorIdRooted to initialCheckpoints.getValue(primaryAnchorIdRooted),
                peerAnchorIdRooted to initialCheckpoints.getValue(peerAnchorIdRooted),
            )
            world.limbo.current() shouldBe mapOf(
                RootName.PRIMARY to setOf(BindingRef(primaryVictim, victimId)),
            )
            assertContentEquals(victimBytes, requireNotNull(world.primaryBucket.currentBytes(primaryReplacement.value)))
        }
    }
})

private val PEER = RootName.require("peer")

private class ReadFixture(
    val a: TreePath,
    val c: TreePath,
    val anchor: TreePath,
    val aId: PageId,
    val cId: PageId,
    val anchorId: PageId,
    val cBytes: ByteArray,
    val anchorBytes: ByteArray,
    val bucket: FakeObjectStore,
    val hasBoundCandidate: Boolean,
) {
    val aRooted = RootedPath(RootName.PRIMARY, a)
    val cRooted = RootedPath(RootName.PRIMARY, c)
    val anchorRooted = RootedPath(RootName.PRIMARY, anchor)
    val aRootedId = RootedPageId(RootName.PRIMARY, aId)
    val cRootedId = RootedPageId(RootName.PRIMARY, cId)
    val anchorRootedId = RootedPageId(RootName.PRIMARY, anchorId)
}

private data class CompletenessInitialState(val urls: Map<RootedPageId, TreePath?>)

private data class CompletenessReadObservation(val path: TreePath, val result: StoreRead)

private fun readFixture(withBoundCandidate: Boolean = false): ReadFixture {
    val a = page("a.md")
    val c = page("c.md")
    val anchor = page("anchor.md")
    val ids = TestIdProvider()
    val aId = ids.next()
    val cId = ids.next()
    val anchorId = ids.next()
    val aBytes = materializedPage(aId, "A")
    val cBytes = materializedPage(cId, "C")
    val anchorBytes = materializedPage(anchorId, "Anchor")
    val bucket = FakeObjectStore().apply {
        seed(a.value, aBytes)
        if (withBoundCandidate) seed(c.value, cBytes)
        seed(anchor.value, anchorBytes)
    }
    return ReadFixture(a, c, anchor, aId, cId, anchorId, cBytes, anchorBytes, bucket, withBoundCandidate)
}

private fun prepareInitial(world: ObjectAbsenceWorld, fixture: ReadFixture): CompletenessInitialState {
    val snapshot = world.boot(fixture.bucket, OBJECT_READ_BINDING).rebuild()
    world.topology.topology(RootName.PRIMARY).shouldNotBeNull().status shouldBe BindingStatus.TRUSTED
    val expected = buildSet {
        add(fixture.aRooted)
        add(fixture.anchorRooted)
        if (fixture.hasBoundCandidate) add(fixture.cRooted)
    }
    completenessSnapshotRootedPaths(snapshot) shouldBe expected
    fixture.aRootedId.let { snapshot.byRootedId.getValue(it).materialized shouldBe true }
    fixture.anchorRootedId.let { snapshot.byRootedId.getValue(it).materialized shouldBe true }
    if (fixture.hasBoundCandidate) fixture.cRootedId.let { snapshot.byRootedId.getValue(it).materialized shouldBe true }
    completenessLiveProjection(world).keys shouldBe expected
    world.idMap.retiredBindings() shouldBe emptyList()
    world.limbo.current() shouldBe emptyMap()
    val expectedIds = buildSet {
        add(fixture.aRootedId)
        add(fixture.anchorRootedId)
        if (fixture.hasBoundCandidate) add(fixture.cRootedId)
    }
    val urls = expectedIds.associateWith { snapshot.byRootedId.getValue(it).urlPath }
    world.checkpoints.load() shouldBe urls
    val manifest = requireNotNull(world.store.latestManifest())
    manifest.binding shouldBe OBJECT_READ_BINDING
    manifest.listed shouldBe buildSet {
        add(fixture.a)
        add(fixture.anchor)
        if (fixture.hasBoundCandidate) add(fixture.c)
    }
    manifest.rowsAtStart shouldBe emptySet()
    return CompletenessInitialState(urls)
}

private fun completenessSnapshotRootedPaths(snapshot: PageIndex): Set<RootedPath> =
    snapshot.pages.mapTo(mutableSetOf()) { RootedPath(it.root, it.path) }

private fun completenessLiveProjection(world: ObjectAbsenceWorld): Map<RootedPath, Pair<PageId, Boolean>> =
    world.idMap.bindings().associate { it.path to (it.id to it.materialized) }

private fun completenessRetiredProjection(world: ObjectAbsenceWorld): Set<Pair<RootedPath, PageId>> =
    world.idMap.retiredBindings().mapTo(mutableSetOf()) { it.path to it.id }

private fun completenessRetiredProjection(world: DualObjectWorld): Set<Pair<RootedPath, PageId>> =
    world.idMap.retiredBindings().mapTo(mutableSetOf()) { it.path to it.id }

private class DualObjectWorld : AutoCloseable {
    private val dataDir = Files.createTempDirectory("pb-dual-object-read")
    val primaryMirror: Path = Files.createDirectories(dataDir.resolve("primary-mirror"))
    private val peerMirror: Path = Files.createDirectories(dataDir.resolve("peer-mirror"))
    private val primaryState = dataDir.resolve("primary-state")
    private val peerState = dataDir.resolve("peer-state")
    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    val idMap = SqlDelightIdMapRepository(database)
    private val retirements = SqlDelightRetirementRepository(database)
    private val topology = SqlDelightRootTopologyRepository(database)
    private val latch = BindingLatch(topology)
    private val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val limbo = RootLimbo()
    private val primaryRoot = Root(
        name = RootName.PRIMARY,
        backend = RootBackend.Object(bucket = "primary", prefix = ""),
        editable = true,
        history = HistoryMode.OFF,
    )
    private val peerRoot = Root(
        name = PEER,
        backend = RootBackend.Object(bucket = "peer", prefix = ""),
        editable = true,
        history = HistoryMode.OFF,
    )
    private val registry = RootRegistry.of(listOf(primaryRoot, peerRoot))
    val primaryBucket = FakeObjectStore()
    val peerBucket = FakeObjectStore()
    val primaryStore = makeStore(primaryRoot, primaryBucket, primaryMirror, primaryState, RootBinding("https://primary|docs|"))
    val peerStore = makeStore(peerRoot, peerBucket, peerMirror, peerState, RootBinding("https://peer|docs|"))
    private val primaryBinding = primaryStore.binding
    private val peerBinding = peerStore.binding

    fun boot() {
        boot(primaryRoot.name, primaryBinding, primaryStore)
        boot(peerRoot.name, peerBinding, peerStore)
    }

    fun builder(primaryContentStore: ContentStore = primaryStore): IndexBuilder = IndexBuilder(
        sources = listOf(
            IndexBuilder.Source(primaryRoot, primaryContentStore, NoOpHistoryProvider, manifests = primaryStore),
            IndexBuilder.Source(peerRoot, peerStore, NoOpHistoryProvider, manifests = peerStore),
        ),
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
        listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)),
        retirements = retirements,
        limbo = limbo,
        bindings = latch,
    )

    private fun makeStore(
        root: Root,
        bucket: FakeObjectStore,
        mirrorRoot: Path,
        stateFile: Path,
        binding: RootBinding,
    ): ObjectContentStore {
        val ignoreRules = IgnoreRules()
        val mirror = LocalContentStore(root = mirrorRoot, rootName = root.name, ignoreRules = ignoreRules)
        return ObjectContentStore(
            client = bucket,
            mirror = mirror,
            state = MirrorState(stateFile),
            binding = binding,
            rowsAtStart = { rowsAtStart(root.name) },
            keyPrefix = "",
            pollSeconds = 3600,
            dirtyPaths = { emptySet() },
            mirrorRoot = mirrorRoot,
            ignoreRules = ignoreRules,
        )
    }

    private fun rowsAtStart(root: RootName): RowsAtStart {
        val bindingEpoch = retirements.bindingEpoch(root)
        val rows = idMap.bindings().filter { it.path.root == root }
            .mapTo(mutableSetOf()) { BindingRef(it.path.path, it.id) }
        return RowsAtStart(rows, bindingEpoch)
    }

    private fun boot(root: RootName, binding: RootBinding, store: ObjectContentStore) {
        if (latch.observe(root, binding) != BindingStatus.TRUSTED) store.rebind()
        store.hydrate()
    }

    override fun close() {
        primaryStore.close()
        peerStore.close()
        driver.close()
        dataDir.toFile().deleteRecursively()
    }
}
