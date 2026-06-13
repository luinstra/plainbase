package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The S5.0 prerequisite, proven: [LocalContentStore]'s scan snapshot is published through an
 * `AtomicReference` swap (no `@Volatile`, no read-path locks), so a reader racing a concurrent
 * rescan always observes a COMPLETE snapshot — the old tree state or the new one, never a torn
 * in-between. Phase 1 held the snapshot in a plain `var` (single-threaded by design); the Phase-2
 * watcher rescans on another thread, which is exactly the race this test hammers.
 */
class LocalContentStoreConcurrencyTest : FunSpec({

    test("readers racing concurrent rescans always see a complete old-or-new snapshot") {
        val baseCount = 40
        val extraCount = 40
        withTempTree(seed = { root ->
            repeat(baseCount) { writePage(root, "page-%03d.md".format(it), "# Page $it\n") }
        }) { root ->
            val store = LocalContentStore(root)
            store.scan()

            val stop = AtomicBoolean(false)
            val started = CountDownLatch(4)
            val reads = AtomicInteger()
            val failures = ConcurrentLinkedQueue<String>()
            val anchor = TreePath.require("page-000.md")
            val readers = List(4) {
                thread {
                    started.countDown()
                    while (!stop.get()) {
                        // One list() answers from ONE captured snapshot: its size must match a
                        // whole tree state. A torn publication would surface as an in-between size.
                        val listed = store.list(null).size
                        if (listed != baseCount && listed != baseCount + extraCount) failures += "listed $listed entries"
                        // The anchor file is in BOTH states, so membership (and the P4 raw-name
                        // resolution behind read) must hold against every published snapshot.
                        if (store.read(anchor) == null) failures += "anchor page unreadable"
                        if (store.stat(anchor) == null) failures += "anchor page unstat-able"
                        reads.incrementAndGet()
                    }
                }
            }
            started.await()

            // Alternate the tree between the two states, rescanning each time, readers live.
            repeat(6) { round ->
                if (round % 2 == 0) {
                    repeat(extraCount) { writePage(root, "extra-%03d.md".format(it), "# Extra $it\n") }
                } else {
                    repeat(extraCount) { root.resolve("extra-%03d.md".format(it)).toFile().delete() }
                }
                store.scan()
            }
            stop.set(true)
            readers.forEach { it.join() }

            failures.toList().shouldBeEmpty()
            reads.get() shouldBeGreaterThan 0
        }
    }
})
