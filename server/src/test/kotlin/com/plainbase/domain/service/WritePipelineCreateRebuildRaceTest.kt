package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * SW-2 create-vs-rebuild TOCTOU (C1b item 4): a deterministic latch-forced interleave at the real
 * contended point — a watcher [IndexBuilder.rebuild] scanning while a [WritePipeline.create] is
 * mid-flight. The watcher takes only the IndexBuilder monitor, so it CAN publish a snapshot whose
 * scan predates the new file (the accepted best-effort window `WritePipeline.kt:104-108` documents).
 * What makes that safe — and what this pins — is the serialization/convergence invariant:
 *  - the intermediate (watcher) snapshot is stale but CONSISTENT: `race.md` is absent entirely,
 *    never a 0-byte/partial ghost (the createLink O_EXCL write guarantees no empty window);
 *  - the create's own rebuild is ordered strictly AFTER the concurrent rebuild by the IndexBuilder
 *    monitor and RE-SCANS disk inside it, so the final published snapshot contains the page exactly
 *    once and the journal is clean.
 *
 * Latches only — no `Thread.sleep`; every `await` asserts its boolean. The main thread orchestrates
 * and holds no lock, so the one-directional pipeline→IndexBuilder lock order (Resolution 5) stays
 * deadlock-free. JVM-only Kotest, the [WritePipelineConcurrencyTest] sibling idiom. This pins snapshot
 * staleness and convergence ONLY on the in-memory driver; SQLite lock contention is pinned by
 * [com.plainbase.frameworks.sqldelight.SqliteBusyBeginImmediateTest].
 */
class WritePipelineCreateRebuildRaceTest : FunSpec({

    test("a watcher rebuild racing a create publishes a stale-but-consistent view; the create's rebuild converges") {
        withTempTree({ root -> writePage(root, "seed.md", "---\ntitle: Seed\n---\n\n# Seed\n\nseed body.\n") }) { root ->
            val real = LocalContentStore(root)
            // Recorded published snapshots (path lists), in publish order — listeners run synchronously inside
            // the serialized rebuild, so the recording order IS the publish order. Synchronized for visibility;
            // every access is otherwise ordered by the IndexBuilder monitor + the final join() happens-before.
            val recorded: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
            val recorder = IndexBuilder.PublicationListener { snapshot, _ -> recorded.add(snapshot.pages.map { it.path.value }) }

            val armed = AtomicBoolean(false)
            val staleScanDone = CountDownLatch(1)
            val releaseRebuild = CountDownLatch(1)
            val fileLanded = CountDownLatch(1)

            // The latched store: injected through IndexHarness so the builder AND the pipeline share it.
            val latched = object : ContentStore by real {
                override fun scan(): ScanResult {
                    val result = real.scan()
                    // Fire ONCE (armed only after setup): hold the watcher rebuild inside the IndexBuilder
                    // monitor with a scan result that provably predates the create's file.
                    if (armed.compareAndSet(true, false)) {
                        staleScanDone.countDown()
                        check(releaseRebuild.await(10, TimeUnit.SECONDS)) { "releaseRebuild never fired" }
                    }
                    return result
                }

                override fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
                    val result = real.createExclusive(path, bytes, hasher)
                    fileLanded.countDown() // the file is on disk while the watcher rebuild still holds the monitor
                    return result
                }
            }

            IndexHarness(root, contentStore = latched, listeners = listOf(recorder)).use { harness ->
                harness.builder.rebuild() // un-armed setup rebuild — indexes the seed page
                val pipeline = harness.writePipeline()
                recorded.clear() // discard the setup publication; the armed-window assertions see only what follows

                // Arm, then run the watcher rebuild on thread A; it blocks inside scan() holding the monitor.
                armed.set(true)
                val threadA = thread { harness.builder.rebuild() }
                check(staleScanDone.await(10, TimeUnit.SECONDS)) { "the watcher scan never latched" }

                // Thread B: the create. It lands the file (fileLanded), then its own rebuild BLOCKS on the
                // IndexBuilder monitor A holds — the exact SW-2 contended point.
                val pageId = PageId.require("01900000-0000-7000-8000-0000000000f4")
                val bytes = "---\nid: ${pageId.value}\ntitle: Race\n---\n\n# Race\n\nraced body.\n".toByteArray()
                val outcome = AtomicReference<WriteOutcome>()
                val threadB = thread {
                    outcome.set(
                        pipeline.create(createGrantForTests(), CreateIntent(pageId, RootName.PRIMARY, TreePath.require("race.md"), bytes)),
                    )
                }

                check(fileLanded.await(10, TimeUnit.SECONDS)) { "the create never landed the file" }
                releaseRebuild.countDown() // A publishes its stale snapshot and exits; B's rebuild then re-scans

                threadA.join(10_000)
                threadB.join(10_000)
                check(!threadA.isAlive) { "the watcher rebuild did not finish" }
                check(!threadB.isAlive) { "the create did not finish" }

                outcome.get().shouldBeInstanceOf<WriteOutcome.Written>()

                val snapshots = recorded.toList()
                // The first armed-window snapshot (A's watcher rebuild) is stale but CONSISTENT: race.md absent
                // entirely — never present-as-ghost, never a 0-byte/partial page.
                snapshots.first().contains("race.md") shouldBe false
                // The LAST published snapshot (B's rebuild re-scan) contains race.md EXACTLY once — converged.
                snapshots.last().count { it == "race.md" } shouldBe 1

                // current agrees (byPath and byId both resolve it), the journal is clean, bytes are verbatim.
                val racePath = TreePath.require("race.md")
                harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, racePath)).id shouldBe pageId
                harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, pageId))!!.path shouldBe racePath
                harness.dirtyPages.all().isEmpty() shouldBe true
                Files.readAllBytes(root.resolve("race.md")) shouldBe bytes
            }
        }
    }

    test("materialized CREATE rebuild repairs an incoming broken link and fires its coordination scan") {
        withTempTree({ root ->
            writePage(
                root,
                "source.md",
                "---\nid: 01900000-0000-7000-8000-000000000101\ntitle: Source\n---\n\n# Source\n\n[incoming](incoming.md)\n",
            )
        }) { root ->
            val probe = MaterializedCreateProbe()
            val scanAfterCreate = CountDownLatch(1)
            val landed = AtomicBoolean(false)
            val real = LocalContentStore(root)
            val coordinated = object : ContentStore by real {
                override fun scan(): ScanResult = real.scan().also {
                    if (landed.get()) scanAfterCreate.countDown()
                }

                override fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult =
                    real.createExclusive(path, bytes, hasher).also { landed.set(true) }
            }
            IndexHarness(
                root,
                contentStore = coordinated,
                decorateIdMap = { probe.attach(it) },
            ).use { harness ->
                harness.observe()
                harness.builder.rebuild()
                LinkChecker().check(harness.builder.current).broken shouldHaveSize 1
                val pageId = PageId.require("01900000-0000-7000-8000-000000000102")
                val bytes = "---\nid: ${pageId.value}\ntitle: Incoming\n---\n\n# Incoming\n".toByteArray()

                harness.writePipeline().create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("incoming.md"), bytes),
                ).shouldBeInstanceOf<WriteOutcome.Written>()

                check(scanAfterCreate.await(10, TimeUnit.SECONDS)) { "materialized CREATE rebuild scan never fired" }
                LinkChecker().check(harness.builder.current).broken shouldBe emptyList()
                probe.confirmationCalls shouldBe 1
                probe.bindCalls shouldBe 2
            }
        }
    }

    test("materialized CREATE confirms bindings after an external content change") {
        withTempTree({ root ->
            writePage(root, "existing.md", "---\nid: 01900000-0000-7000-8000-000000000103\ntitle: Existing\n---\n\n# Existing\nold\n")
        }) { root ->
            val probe = MaterializedCreateProbe()
            val scanAfterCreate = CountDownLatch(1)
            val landed = AtomicBoolean(false)
            val real = LocalContentStore(root)
            val coordinated = materializedCoordinationStore(real, landed, scanAfterCreate)
            IndexHarness(root, contentStore = coordinated, decorateIdMap = { probe.attach(it) }).use { harness ->
                harness.observe()
                harness.builder.rebuild()
                Files.writeString(
                    root.resolve("existing.md"),
                    "---\nid: 01900000-0000-7000-8000-000000000103\ntitle: Existing\n---\n\n# Existing\nexternal\n",
                )
                val pageId = PageId.require("01900000-0000-7000-8000-000000000104")

                harness.writePipeline().create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("incoming.md"), materializedBytes(pageId)),
                ).shouldBeInstanceOf<WriteOutcome.Written>()

                check(scanAfterCreate.await(10, TimeUnit.SECONDS)) { "materialized CREATE rebuild scan never fired" }
                harness.builder.current.byPath[RootedPath(RootName.PRIMARY, TreePath.require("existing.md"))]!!.markdown shouldBe
                    "---\nid: 01900000-0000-7000-8000-000000000103\ntitle: Existing\n---\n\n# Existing\nexternal\n"
                probe.confirmationCalls shouldBe 1
                probe.bindCalls shouldBe 2 // seed bind + incoming CREATE bind; rebuild used the real confirmation.
            }
        }
    }

    test("materialized CREATE falls back to ordered binds after an external ID change") {
        withTempTree({ root ->
            writePage(root, "existing.md", "---\nid: 01900000-0000-7000-8000-000000000105\ntitle: Existing\n---\n\n# Existing\nold\n")
        }) { root ->
            val probe = MaterializedCreateProbe()
            val scanAfterCreate = CountDownLatch(1)
            val landed = AtomicBoolean(false)
            val real = LocalContentStore(root)
            val coordinated = materializedCoordinationStore(real, landed, scanAfterCreate)
            IndexHarness(root, contentStore = coordinated, decorateIdMap = { probe.attach(it) }).use { harness ->
                harness.observe()
                harness.builder.rebuild()
                Files.writeString(
                    root.resolve("existing.md"),
                    "---\nid: 01900000-0000-7000-8000-000000000106\ntitle: Existing\n---\n\n# Existing\nnew id\n",
                )
                val pageId = PageId.require("01900000-0000-7000-8000-000000000107")

                harness.writePipeline().create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("incoming.md"), materializedBytes(pageId)),
                ).shouldBeInstanceOf<WriteOutcome.Written>()

                check(scanAfterCreate.await(10, TimeUnit.SECONDS)) { "materialized CREATE rebuild scan never fired" }
                harness.builder.current.byPath[RootedPath(RootName.PRIMARY, TreePath.require("existing.md"))]!!.id shouldBe
                    PageId.require("01900000-0000-7000-8000-000000000106")
                probe.confirmationCalls shouldBe 1
                probe.bindCalls shouldBe 4 // seed + CREATE bind + two ordered fallback binds after confirmation=false.
            }
        }
    }
})

private fun materializedBytes(pageId: PageId): ByteArray =
    "---\nid: ${pageId.value}\ntitle: Incoming\n---\n\n# Incoming\n".toByteArray()

private fun materializedCoordinationStore(
    real: ContentStore,
    landed: AtomicBoolean,
    scanAfterCreate: CountDownLatch,
): ContentStore = object : ContentStore by real {
    override fun scan(): ScanResult = real.scan().also {
        if (landed.get()) scanAfterCreate.countDown()
    }

    override fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult =
        real.createExclusive(path, bytes, hasher).also { landed.set(true) }
}

private class MaterializedCreateProbe {
    var bindCalls = 0
    var confirmationCalls = 0

    fun attach(value: IdMapRepository): IdMapRepository = MaterializedCreateRecordingIdMap(value, this)

    private class MaterializedCreateRecordingIdMap(
        private val delegate: IdMapRepository,
        private val probe: MaterializedCreateProbe,
    ) : IdMapRepository by delegate {
        override fun bind(path: RootedPath, id: PageId, materialized: Boolean, supersession: Supersession): BindOutcome {
            probe.bindCalls++
            return delegate.bind(path, id, materialized, supersession)
        }

        override fun confirmUnchangedBindings(expected: List<IdBinding>): Boolean {
            probe.confirmationCalls++
            return delegate.confirmUnchangedBindings(expected)
        }
    }
}
