package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

/**
 * The watch pipeline end to end — real `WatchService`, real [RebuildScheduler] (real clock), real
 * [com.plainbase.domain.service.IndexBuilder] — wired in `serve`'s §B2 startup order (watch FIRST,
 * then the initial rebuild). Counts rebuilds, never events: event counts are platform noise
 * (macOS's polling service batches arbitrarily), while the rebuild bound and the converged
 * snapshot are the §B2 promises.
 *
 * The < 5 s edit-to-index assertion binds Linux only (master criterion 7 as adjudicated in §B1/R2:
 * inotify on the deployment platform; macOS polling latency is a documented platform note) — other
 * platforms still run the full pipeline and must converge, just without the stopwatch.
 */
class WatcherPipelineTest : FunSpec({

    test("a 1,000-file burst converges with at most 3 rebuilds and a complete final snapshot") {
        withTempTree(seed = {}) { root ->
            val store = LocalContentStore(root)
            IndexHarness(root, contentStore = store).use { harness ->
                val rebuilds = AtomicInteger()
                RebuildScheduler(rebuild = {
                    rebuilds.incrementAndGet()
                    harness.builder.rebuild()
                }).use { scheduler ->
                    store.watch { scheduler.schedule() }.use {
                        repeat(1_000) { writePage(root, "page-%04d.md".format(it), "# Page $it\n") }
                        awaitUntil(120_000, "burst never converged to 1,000 pages") {
                            harness.builder.current.pages.size == 1_000
                        }
                        Thread.sleep(RebuildScheduler.DEBOUNCE_MILLIS * 3) // let any trailing rebuild land
                    }
                }
                harness.builder.current.pages.size shouldBe 1_000
                rebuilds.get() shouldBeLessThanOrEqual 3
            }
        }
    }

    test("an external edit reaches the published page index — within 5s on Linux (criterion 7, page-index half)") {
        withTempTree(seed = { root ->
            writePage(root, "docs/note.md", "---\ntitle: Old Title\n---\n\nbody\n")
        }) { root ->
            val store = LocalContentStore(root)
            IndexHarness(root, contentStore = store).use { harness ->
                RebuildScheduler(rebuild = { harness.builder.rebuild() }).use { scheduler ->
                    store.watch { scheduler.schedule() }.use {
                        harness.builder.rebuild() // startup build AFTER watch registration (§B2 ordering)

                        val start = System.nanoTime()
                        writePage(root, "docs/note.md", "---\ntitle: New Title\n---\n\nbody\n")
                        awaitUntil(90_000, "the external edit never reached the published index") {
                            harness.builder.current.byPath[TreePath.require("docs/note.md")]?.title == "New Title"
                        }
                        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
                        if (System.getProperty("os.name").lowercase().startsWith("linux")) {
                            elapsedMillis shouldBeLessThan 5_000
                        }
                    }
                }
            }
        }
    }
})

/** Polls [condition] until true or fails the test after [timeoutMillis]. */
private fun awaitUntil(timeoutMillis: Long, message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "$message (within ${timeoutMillis}ms)" }
        Thread.sleep(25)
    }
}
