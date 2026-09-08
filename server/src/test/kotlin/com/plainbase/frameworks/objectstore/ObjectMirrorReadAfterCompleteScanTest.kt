package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.ObjectManifest
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.TestIdProvider
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertContentEquals

/** Pins the OBJECT_LIST read-after-complete-scan cache-loss window and its ordinary rename/delete controls. */
class ObjectMirrorReadAfterCompleteScanTest : FunSpec({

    test("should retain the materialized binding when the mirror loses the renamed page after a complete scan") {
        val fixture = fixture()

        ObjectAbsenceWorld().use { world ->
            val initial = prepareInitial(world, fixture)
            renameInBucket(fixture)
            world.store.pollOnce()
            requirePostPollState(world, fixture, initial, setOf(fixture.b, fixture.anchor))

            val real = world.store
            var scanCalls = 0
            var deletionCalls = 0
            var capturedScan: ScanResult? = null
            var capturedManifest: ObjectManifest? = null
            val reads = mutableListOf<ReadObservation>()
            val wrapped = object : ContentStore by real {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    scanCalls shouldBe 1
                    val result = real.scan()
                    result.complete shouldBe true
                    result.files.map { it.path }.toSet() shouldBe setOf(fixture.b, fixture.anchor)
                    capturedScan = result
                    capturedManifest = requireNotNull(real.latestManifest())
                    assertContentEquals(fixture.victimBytes, Files.readAllBytes(world.mirrorRoot.resolve(fixture.b.value)))

                    deletionCalls += 1
                    deletionCalls shouldBe 1
                    try {
                        Files.delete(world.mirrorRoot.resolve(fixture.b.value))
                    } catch (failure: IOException) {
                        throw AssertionError("the probe could not delete the B mirror file", failure)
                    }
                    Files.exists(world.mirrorRoot.resolve(fixture.anchor.value)) shouldBe true
                    real.available() shouldBe true
                    return result
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += ReadObservation(path, result)
                    if (path == fixture.b) {
                        result shouldBe StoreRead.NoBytes
                        world.idMap.find(fixture.bRooted).shouldBeNull()
                    }
                    return result
                }
            }

            val snapshot = world.builder(contentStore = wrapped).rebuild()
            val recordedScan = requireNotNull(capturedScan)
            val recordedManifest = requireNotNull(capturedManifest)

            scanCalls shouldBe 1
            deletionCalls shouldBe 1
            recordedScan.complete shouldBe true
            recordedScan.files.map { it.path }.toSet() shouldBe setOf(fixture.b, fixture.anchor)
            reads.filter { it.path == fixture.b } shouldBe listOf(ReadObservation(fixture.b, StoreRead.NoBytes))
            real.available() shouldBe true
            requireNotNull(real.latestManifest()) shouldBe recordedManifest
            assertContentEquals(fixture.victimBytes, requireNotNull(fixture.bucket.currentBytes(fixture.b.value)))
            Files.exists(world.mirrorRoot.resolve(fixture.b.value)) shouldBe false

            snapshotRootedPaths(snapshot) shouldBe setOf(fixture.anchorRooted)
            val anchorPage = snapshot.byRootedId.getValue(fixture.anchorRootedId)
            anchorPage.materialized shouldBe true
            real.readClassified(fixture.anchor) shouldBe StoreRead.Bytes(fixture.anchorBytes)
            snapshot.byRootedId[fixture.victimRootedId] shouldBe null
            snapshot.byPath[fixture.aRooted] shouldBe null
            snapshot.byPath[fixture.bRooted] shouldBe null
            world.checkpoints.load()[fixture.anchorRootedId] shouldBe anchorPage.urlPath
            anchorPage.urlPath shouldBe initial.anchorUrlPath

            val actualLive = liveProjection(world)
            val actualRetired = retiredProjection(world)
            val actualCheckpoints = world.checkpoints.load()
            val actualLimbo = world.limbo.current()
            val safeLive = mapOf(
                fixture.aRooted to (fixture.victimId to true),
                fixture.anchorRooted to (fixture.anchorId to true),
            )
            val safeCheckpoints = mapOf(
                fixture.victimRootedId to initial.victimUrlPath,
                fixture.anchorRootedId to initial.anchorUrlPath,
            )
            val safeLimbo = mapOf(RootName.PRIMARY to setOf(BindingRef(fixture.a, fixture.victimId)))

            withClue(
                renderStateMatrix(actualLive, actualRetired, actualCheckpoints, actualLimbo),
            ) {
                assertSoftly {
                    withClue("live bindings") { actualLive shouldBe safeLive }
                    withClue("retired bindings") { actualRetired shouldBe emptySet() }
                    withClue("checkpoint map") { actualCheckpoints shouldBe safeCheckpoints }
                    withClue("RootLimbo.current()") { actualLimbo shouldBe safeLimbo }
                }
            }

            world.store.pollOnce()
            val recovered = world.builder().rebuild()
            snapshotRootedPaths(recovered) shouldBe setOf(fixture.bRooted, fixture.anchorRooted)
            recovered.byRootedId.getValue(fixture.victimRootedId).path shouldBe fixture.b
            recovered.byPath[fixture.aRooted] shouldBe null
            world.idMap.find(fixture.aRooted).shouldBeNull()
            val recoveredBinding = world.idMap.find(fixture.bRooted).shouldNotBeNull()
            recoveredBinding.id shouldBe fixture.victimId
            recoveredBinding.materialized shouldBe true
            retiredProjection(world) shouldBe emptySet()
            world.checkpoints.load()[fixture.victimRootedId] shouldBe
                recovered.byRootedId.getValue(fixture.victimRootedId).urlPath
            world.limbo.current() shouldBe emptyMap()
            assertContentEquals(fixture.victimBytes, requireNotNull(fixture.bucket.currentBytes(fixture.b.value)))
        }
    }

    test("should preserve the renamed page when its mirror bytes remain readable") {
        val fixture = fixture()

        ObjectAbsenceWorld().use { world ->
            val initial = prepareInitial(world, fixture)
            renameInBucket(fixture)
            world.store.pollOnce()
            requirePostPollState(world, fixture, initial, setOf(fixture.b, fixture.anchor))

            val real = world.store
            var scanCalls = 0
            val reads = mutableListOf<ReadObservation>()
            val wrapped = object : ContentStore by real {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    scanCalls shouldBe 1
                    val result = real.scan()
                    result.complete shouldBe true
                    result.files.map { it.path }.toSet() shouldBe setOf(fixture.b, fixture.anchor)
                    real.available() shouldBe true
                    return result
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += ReadObservation(path, result)
                    return result
                }
            }

            val snapshot = world.builder(contentStore = wrapped).rebuild()

            scanCalls shouldBe 1
            reads.filter { it.path == fixture.b } shouldBe
                listOf(ReadObservation(fixture.b, StoreRead.Bytes(fixture.victimBytes)))
            snapshotRootedPaths(snapshot) shouldBe setOf(fixture.bRooted, fixture.anchorRooted)
            snapshot.byRootedId.getValue(fixture.victimRootedId).path shouldBe fixture.b
            snapshot.byRootedId.getValue(fixture.anchorRootedId).path shouldBe fixture.anchor
            liveProjection(world) shouldBe mapOf(
                fixture.bRooted to (fixture.victimId to true),
                fixture.anchorRooted to (fixture.anchorId to true),
            )
            world.idMap.find(fixture.aRooted).shouldBeNull()
            world.idMap.retiredBindings() shouldBe emptyList()
            world.checkpoints.load().keys shouldBe setOf(fixture.victimRootedId, fixture.anchorRootedId)
            world.checkpoints.load()[fixture.victimRootedId] shouldBe
                snapshot.byRootedId.getValue(fixture.victimRootedId).urlPath
            world.checkpoints.load()[fixture.anchorRootedId] shouldBe initial.anchorUrlPath
            retiredProjection(world) shouldBe emptySet()
            world.limbo.current() shouldBe emptyMap()
            assertContentEquals(fixture.victimBytes, requireNotNull(fixture.bucket.currentBytes(fixture.b.value)))
        }
    }

    test("should retire the old binding when the bucket genuinely deletes the page") {
        val fixture = fixture()

        ObjectAbsenceWorld().use { world ->
            val initial = prepareInitial(world, fixture)
            fixture.bucket.remove(fixture.a.value)
            world.store.pollOnce()
            requirePostPollState(world, fixture, initial, setOf(fixture.anchor))

            val real = world.store
            var scanCalls = 0
            val reads = mutableListOf<ReadObservation>()
            val wrapped = object : ContentStore by real {
                override fun scan(): ScanResult {
                    scanCalls += 1
                    scanCalls shouldBe 1
                    val result = real.scan()
                    result.complete shouldBe true
                    result.files.map { it.path }.toSet() shouldBe setOf(fixture.anchor)
                    real.available() shouldBe true
                    return result
                }

                override fun readClassified(path: TreePath): StoreRead {
                    val result = real.readClassified(path)
                    reads += ReadObservation(path, result)
                    return result
                }
            }

            val snapshot = world.builder(contentStore = wrapped).rebuild()

            scanCalls shouldBe 1
            reads.filter { it.path == fixture.anchor } shouldBe
                listOf(ReadObservation(fixture.anchor, StoreRead.Bytes(fixture.anchorBytes)))
            snapshotRootedPaths(snapshot) shouldBe setOf(fixture.anchorRooted)
            snapshot.byRootedId.getValue(fixture.anchorRootedId).path shouldBe fixture.anchor
            liveProjection(world) shouldBe mapOf(fixture.anchorRooted to (fixture.anchorId to true))
            world.idMap.find(fixture.aRooted).shouldBeNull()
            world.idMap.find(fixture.bRooted).shouldBeNull()
            retiredProjection(world) shouldBe setOf(fixture.aRooted to fixture.victimId)
            world.checkpoints.load() shouldBe mapOf(fixture.anchorRootedId to initial.anchorUrlPath)
            world.limbo.current() shouldBe emptyMap()
        }
    }
})

private val OBJECT_BINDING = RootBinding("https://r2.example|handbook|")

private class Fixture(
    val a: TreePath,
    val b: TreePath,
    val anchor: TreePath,
    val victimId: PageId,
    val anchorId: PageId,
    val victimBytes: ByteArray,
    val anchorBytes: ByteArray,
    val bucket: FakeObjectStore,
) {
    val aRooted = RootedPath(RootName.PRIMARY, a)
    val bRooted = RootedPath(RootName.PRIMARY, b)
    val anchorRooted = RootedPath(RootName.PRIMARY, anchor)
    val victimRootedId = RootedPageId(RootName.PRIMARY, victimId)
    val anchorRootedId = RootedPageId(RootName.PRIMARY, anchorId)
}

private data class InitialState(
    val victimUrlPath: TreePath,
    val anchorUrlPath: TreePath,
)

private data class ReadObservation(val path: TreePath, val result: StoreRead)

private fun fixture(): Fixture {
    val a = page("a.md")
    val b = page("b.md")
    val anchor = page("anchor.md")
    val ids = TestIdProvider()
    val victimId = ids.next()
    val anchorId = ids.next()
    val victimBytes = materializedPage(victimId, "victim")
    val anchorBytes = materializedPage(anchorId, "anchor")
    val bucket = FakeObjectStore().apply {
        seed(a.value, victimBytes)
        seed(anchor.value, anchorBytes)
    }
    return Fixture(a, b, anchor, victimId, anchorId, victimBytes, anchorBytes, bucket)
}

private fun prepareInitial(world: ObjectAbsenceWorld, fixture: Fixture): InitialState {
    val snapshot = world.boot(fixture.bucket, OBJECT_BINDING).rebuild()
    world.topology.topology(RootName.PRIMARY).shouldNotBeNull().status shouldBe BindingStatus.TRUSTED
    snapshotRootedPaths(snapshot) shouldBe setOf(fixture.aRooted, fixture.anchorRooted)
    liveProjection(world) shouldBe mapOf(
        fixture.aRooted to (fixture.victimId to true),
        fixture.anchorRooted to (fixture.anchorId to true),
    )
    world.idMap.retiredBindings() shouldBe emptyList()
    world.limbo.current() shouldBe emptyMap()

    val victimUrlPath = requireNotNull(snapshot.byRootedId.getValue(fixture.victimRootedId).urlPath)
    val anchorUrlPath = requireNotNull(snapshot.byRootedId.getValue(fixture.anchorRootedId).urlPath)
    world.checkpoints.load() shouldBe mapOf(
        fixture.victimRootedId to victimUrlPath,
        fixture.anchorRootedId to anchorUrlPath,
    )
    val manifest = requireNotNull(world.store.latestManifest())
    manifest.binding shouldBe OBJECT_BINDING
    manifest.listed shouldBe setOf(fixture.a, fixture.anchor)
    manifest.rowsAtStart shouldBe emptySet()

    return InitialState(victimUrlPath, anchorUrlPath)
}

private fun renameInBucket(fixture: Fixture) {
    val savedVictimBytes = requireNotNull(fixture.bucket.currentBytes(fixture.a.value))
    assertContentEquals(fixture.victimBytes, savedVictimBytes)
    fixture.bucket.remove(fixture.a.value)
    fixture.bucket.seed(fixture.b.value, savedVictimBytes)
}

private fun requirePostPollState(
    world: ObjectAbsenceWorld,
    fixture: Fixture,
    initial: InitialState,
    listed: Set<TreePath>,
) {
    val manifest = requireNotNull(world.store.latestManifest())
    manifest.binding shouldBe OBJECT_BINDING
    manifest.listed shouldBe listed
    manifest.rowsAtStart shouldBe setOf(
        BindingRef(fixture.a, fixture.victimId),
        BindingRef(fixture.anchor, fixture.anchorId),
    )
    manifest.bindingEpoch shouldBe world.retirements.bindingEpoch(RootName.PRIMARY)
    val victimBinding = world.idMap.find(fixture.aRooted).shouldNotBeNull()
    victimBinding.id shouldBe fixture.victimId
    victimBinding.materialized shouldBe true
    world.idMap.find(fixture.bRooted).shouldBeNull()
    world.checkpoints.load()[fixture.victimRootedId] shouldBe initial.victimUrlPath
}

private fun snapshotRootedPaths(snapshot: PageIndex): Set<RootedPath> =
    snapshot.pages.mapTo(mutableSetOf()) { RootedPath(it.root, it.path) }

private fun liveProjection(world: ObjectAbsenceWorld): Map<RootedPath, Pair<PageId, Boolean>> =
    world.idMap.bindings().associate { it.path to (it.id to it.materialized) }

private fun retiredProjection(world: ObjectAbsenceWorld): Set<Pair<RootedPath, PageId>> =
    world.idMap.retiredBindings().mapTo(mutableSetOf()) { it.path to it.id }

private fun renderStateMatrix(
    live: Map<RootedPath, Pair<PageId, Boolean>>,
    retired: Set<Pair<RootedPath, PageId>>,
    checkpoints: Map<RootedPageId, TreePath?>,
    limbo: Map<RootName, Set<BindingRef>>,
): String = listOf(
    "actual live bindings=" + live.entries.sortedBy { it.key.toString() },
    "actual retired bindings=" + retired.sortedBy { it.toString() },
    "actual checkpoints=" + checkpoints.entries.sortedBy { it.key.toString() },
    "actual limbo=" + limbo.entries.sortedBy { it.key.toString() }.map { it.key to it.value.sortedBy(BindingRef::toString) },
).joinToString(separator = "\n")
