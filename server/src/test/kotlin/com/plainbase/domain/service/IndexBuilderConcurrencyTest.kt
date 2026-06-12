package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.page.PageIndex
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * The chunk-5 publication and scale criteria, plus rebuild serialization.
 *
 * **Atomic swap (no `@Volatile`, lock-free readers):** readers hammering [IndexBuilder.current]
 * during repeated rebuilds must only ever observe a COMPLETE snapshot — internally consistent maps
 * and a page count belonging to one of the two tree states, never a torn in-between. The snapshot
 * is deeply immutable and published via a single `AtomicReference.set`, so any violation here
 * means the builder leaked shared mutable state.
 *
 * **Serialized rebuilds:** [IndexBuilder.rebuild] is `@Synchronized` — unsynchronized, an
 * earlier-scanned rebuild finishing LATER would publish over a newer snapshot (lost update).
 *
 * **Scale:** a 1,000-page corpus indexes in < 5s on CI (full pass: scan, identity, URLs, render).
 */
class IndexBuilderConcurrencyTest : FunSpec({

    test("concurrent readers always see a complete old-or-new snapshot across rebuilds") {
        val smallCount = 40
        val largeCount = 80
        withTempTree(seed = { root ->
            repeat(smallCount) { writePage(root, "docs/page-%03d.md".format(it), pageContent(it)) }
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild().pages.size shouldBe smallCount

                val stop = AtomicBoolean(false)
                val started = CountDownLatch(4)
                val reads = AtomicInteger()
                val failures = ConcurrentLinkedQueue<String>()
                val readers = List(4) {
                    thread {
                        started.countDown()
                        while (!stop.get()) {
                            val snapshot = harness.builder.current
                            // Internal consistency: a torn snapshot would break these invariants.
                            if (snapshot.pages.size != smallCount && snapshot.pages.size != largeCount) {
                                failures += "page count ${snapshot.pages.size}"
                            }
                            if (snapshot.byId.size != snapshot.pages.size) failures += "byId size mismatch"
                            if (snapshot.byPath.size != snapshot.pages.size) failures += "byPath size mismatch"
                            if (snapshot.byUrlPath.size != snapshot.pages.size) failures += "byUrlPath size mismatch"
                            if (snapshot.pages.any { snapshot.byPath[it.path] !== it }) failures += "byPath identity mismatch"
                            reads.incrementAndGet()
                        }
                    }
                }
                started.await()

                // Alternate the tree between the two states, rebuilding each time, readers live.
                repeat(6) { round ->
                    if (round % 2 == 0) {
                        (smallCount until largeCount).forEach { writePage(root, "docs/page-%03d.md".format(it), pageContent(it)) }
                    } else {
                        (smallCount until largeCount).forEach { root.resolve("docs/page-%03d.md".format(it)).toFile().delete() }
                    }
                    harness.builder.rebuild()
                }
                stop.set(true)
                readers.forEach { it.join() }

                failures.toList().shouldBeEmpty()
                check(reads.get() > 0) { "readers never observed a snapshot" }
            }
        }
    }

    test("rebuilds serialize: a slow earlier rebuild cannot clobber a later one") {
        // Deterministic by construction: the first rebuild's scan parks on a latch, the second
        // rebuild is observed BLOCKED at the monitor (so it provably cannot run concurrently), and
        // only then is the first released — the second must therefore scan and publish LAST.
        withTempTree(seed = { root -> writePage(root, "page.md", "# Page\n") }) { root ->
            val store = LocalContentStore(root)
            val firstScanEntered = CountDownLatch(1)
            val releaseFirstScan = CountDownLatch(1)
            val scans = AtomicInteger()
            val gating = object : ContentStore by store {
                override fun scan(): ScanResult {
                    if (scans.getAndIncrement() == 0) {
                        firstScanEntered.countDown()
                        releaseFirstScan.await(10, TimeUnit.SECONDS) // self-releases on a failed run
                    }
                    return store.scan()
                }
            }

            IndexHarness(root, contentStore = gating).use { harness ->
                val secondSnapshot = AtomicReference<PageIndex>()

                val first = thread(name = "rebuild-first") { harness.builder.rebuild() }
                firstScanEntered.await(10, TimeUnit.SECONDS) shouldBe true

                val second = thread(name = "rebuild-second") { secondSnapshot.set(harness.builder.rebuild()) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
                second.state shouldBe Thread.State.BLOCKED

                releaseFirstScan.countDown()
                first.join(10_000)
                second.join(10_000)

                // The second rebuild scanned after the first finished, and ITS snapshot is the
                // published one — unsynchronized, the slow first rebuild would publish last and
                // regress `current` to its stale world.
                scans.get() shouldBe 2
                harness.builder.current shouldBeSameInstanceAs secondSnapshot.get()
            }
        }
    }

    test("a 1,000-page corpus indexes in under 5 seconds") {
        val pageCount = 1000
        withTempTree(seed = { root ->
            repeat(pageCount) { n ->
                writePage(root, "section-%02d/page-%03d.md".format(n % 10, n), pageContent(n))
            }
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot: Int
                val elapsed = measureTimeMillis { snapshot = harness.builder.rebuild().pages.size }
                snapshot shouldBe pageCount
                elapsed.toInt() shouldBeLessThan 5000
            }
        }
    }
})
