package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
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
 * deadlock-free. JVM-only Kotest, the [WritePipelineConcurrencyTest] sibling idiom.
 */
class WritePipelineCreateRebuildRaceTest : FunSpec({

    test("a watcher rebuild racing a create publishes a stale-but-consistent view; the create's rebuild converges") {
        withTempTree({ root -> writePage(root, "seed.md", "---\ntitle: Seed\n---\n\n# Seed\n\nseed body.\n") }) { root ->
            val real = LocalContentStore(root)
            // Recorded published snapshots (path lists), in publish order — listeners run synchronously inside
            // the serialized rebuild, so the recording order IS the publish order. Synchronized for visibility;
            // every access is otherwise ordered by the IndexBuilder monitor + the final join() happens-before.
            val recorded: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
            val recorder = IndexBuilder.PublicationListener { snapshot -> recorded.add(snapshot.pages.map { it.path.value }) }

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
                    outcome.set(pipeline.create(createGrantForTests(), CreateIntent(pageId, TreePath.require("race.md"), bytes)))
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
                harness.builder.current.byPath.getValue(racePath).id shouldBe pageId
                harness.builder.current.byId.getValue(pageId).path shouldBe racePath
                harness.dirtyPages.all().isEmpty() shouldBe true
                Files.readAllBytes(root.resolve("race.md")) shouldBe bytes
            }
        }
    }
})
