package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * The chunk-5 publication and scale criteria.
 *
 * **Atomic swap (no `@Volatile`, no locks):** readers hammering [IndexBuilder.current] during
 * repeated rebuilds must only ever observe a COMPLETE snapshot — internally consistent maps and a
 * page count belonging to one of the two tree states, never a torn in-between. The snapshot is
 * deeply immutable and published via a single `AtomicReference.set`, so any violation here means
 * the builder leaked shared mutable state.
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

/** A small but realistic page: frontmatter, two headings, one internal link to a sibling. */
private fun pageContent(n: Int): String = buildString {
    appendLine("---")
    appendLine("title: Page %03d".format(n))
    appendLine("---")
    appendLine()
    appendLine("# Page %03d".format(n))
    appendLine()
    appendLine("Body text for page $n with a [sibling link](page-%03d.md).".format((n / 10) * 10))
    appendLine()
    appendLine("## Details")
    appendLine()
    appendLine("More text.")
}
