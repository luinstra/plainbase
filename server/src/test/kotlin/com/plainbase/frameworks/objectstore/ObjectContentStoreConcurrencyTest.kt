package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.CitationFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * R11 (the named interleave latches) + the monitor-scope tripwire (M1's no-lock-across-network
 * rule, asserted, not reasoned) + poll-delete exclusion (R3).
 */
class ObjectContentStoreConcurrencyTest : FunSpec({

    val hasher = CitationFactory()::contentHash

    test(
        "R11: a racing poll-apply never clobbers a just-in-flight write's etag, and no spurious " +
            "precondition failure follows the write",
    ) {
        HybridFixture(pollSeconds = 3600).use { hybrid ->
            val path = TreePath.require("race.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val updated = "v2".toByteArray()
            hybrid.fake.armInterleave = true

            val writer = thread {
                hybrid.store.compareAndSwapWrite(path, hasher(original), updated, hasher)
            }
            hybrid.fake.pollCycleReached.await() // the write reached its point of return, not yet applied
            hybrid.store.pollOnce() // races the apply - must see the OLD (pre-write) bucket state
            hybrid.fake.putMayComplete.countDown() // release the write to complete its apply
            writer.join(5_000)

            hybrid.store.read(path) shouldBe updated
            hybrid.state.etagOf(path) shouldBe hybrid.fake.currentEtag(hybrid.mirror.resolveRepoRelativePath(path))

            // No spurious precondition failure follows: a correctly-based next save succeeds normally.
            val followUp = hybrid.store.compareAndSwapWrite(path, hasher(updated), "v3".toByteArray(), hasher)
            followUp shouldBe CasResult.Written(hasher("v3".toByteArray()))
        }
    }

    test(
        "poll-apply failure (seam g, JVM twin - pollOnce is internal, unreachable from the nativeTest source " +
            "set that owns ObjectMirrorApplyFailureTest's other three sites): one key's mirror write throws, is " +
            "skipped (entry absent), and the batch continues for the other key",
    ) {
        HybridFixture().use { hybrid ->
            val ok = TreePath.require("poll-ok.md")
            val failing = TreePath.require("poll-failing.md")
            hybrid.mirror.write(ok, "ok-old".toByteArray())
            hybrid.mirror.write(failing, "failing-old".toByteArray())
            hybrid.mirror.scan()
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(ok), "ok-new".toByteArray())
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(failing), "failing-new".toByteArray())
            hybrid.mirrorAtomics.shouldFailForTarget = { target -> "poll-failing" in target.toString() }

            hybrid.store.pollOnce()

            hybrid.mirror.read(ok) shouldBe "ok-new".toByteArray()
            hybrid.state.etagOf(ok) shouldBe hybrid.fake.currentEtag(hybrid.mirror.resolveRepoRelativePath(ok))
            hybrid.state.etagOf(failing) shouldBe null
            hybrid.mirror.read(failing) shouldBe "failing-old".toByteArray()
        }
    }

    test("poll-delete exclusion: a dirty-journaled key survives even when its bucket object is genuinely gone") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("dirty-survivor.md")
            hybrid.seedExisting(path, "v1".toByteArray())
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            runBlocking { hybrid.fake.delete(key) } // the bucket object is gone (upstream delete)
            hybrid.dirtyPaths += path // a live dirty-journal row - never touched by poll/hydrate deletes

            hybrid.store.pollOnce()

            hybrid.mirror.read(path).shouldNotBeNull()
        }
    }

    test("monitor scope: no network op ever runs while applyLockForTests is held, across every mutator + the poll") {
        HybridFixture().use { hybrid ->
            val violations = mutableListOf<String>()
            hybrid.fake.onNetworkOp = {
                if (Thread.holdsLock(hybrid.store.applyLockForTests)) {
                    violations += Thread.currentThread().stackTrace.take(4).joinToString(" <- ")
                }
            }
            val path = TreePath.require("monitor.md")
            hybrid.store.createExclusive(path, "v1".toByteArray(), hasher)
            hybrid.store.compareAndSwapWrite(path, hasher("v1".toByteArray()), "v2".toByteArray(), hasher)
            hybrid.store.write(path, "v3".toByteArray())
            hybrid.store.pollOnce()

            violations.shouldBeEmpty()
        }
    }

    test("watch.close() interrupts and JOINs an in-flight poll before returning (no use-after-close on the transport)") {
        HybridFixture(pollSeconds = 1).use { hybrid ->
            hybrid.seedExisting(TreePath.require("x.md"), "v".toByteArray())
            val inFlight = CountDownLatch(1)
            val held = CountDownLatch(1)
            hybrid.fake.onNetworkOp = {
                inFlight.countDown()
                held.await(10, TimeUnit.SECONDS) // simulate a slow GET/LIST in flight in the poll thread
            }
            val watch = hybrid.store.watch { }
            inFlight.await(5, TimeUnit.SECONDS) shouldBe true // a poll op is now blocked mid-network

            // close() must INTERRUPT the blocked op (breaking held.await) and JOIN - so it returns FAST,
            // not after the join timeout. A missing interrupt would leave the poll stuck until the 10 s
            // held.await, so a sub-2 s return proves the interrupt-then-join ordering works.
            val elapsed = measureTime { watch.close() }
            elapsed shouldBeLessThan 2.seconds
            held.countDown() // nothing is waiting on it now - the poll thread already exited
        }
    }

    test(
        "BLOCKING: a poll-cycle fault (a throwing onChange / scheduler fault) does NOT permanently kill the poll " +
            "thread - the watch loop WARNs and the NEXT cycle still runs (Q13 retry-next-cycle promise)",
    ) {
        HybridFixture(pollSeconds = 1).use { hybrid ->
            val path = TreePath.require("evt.md")
            val firstDelivered = CountDownLatch(1)
            val secondDelivered = CountDownLatch(1)
            var deliveries = 0
            val onChange: (TreePath) -> Unit = {
                deliveries++
                if (deliveries == 1) {
                    firstDelivered.countDown()
                    throw RuntimeException("simulated scheduler fault on the first delivery")
                } else {
                    secondDelivered.countDown()
                }
            }
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(path), "v1".toByteArray())
            val watch = hybrid.store.watch(onChange)
            try {
                firstDelivered.await(10, TimeUnit.SECONDS) shouldBe true // cycle 1 delivered v1; onChange threw
                // Seed a SECOND change: only a still-alive poll thread can deliver it on a later cycle.
                hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(path), "v2".toByteArray())
                secondDelivered.await(10, TimeUnit.SECONDS) shouldBe true // proves the thread survived the throw
            } finally {
                watch.close()
            }
        }
    }

    test(
        "BLOCKING: a MirrorState.persist() fault during a poll cycle is BEST-EFFORT (swallowed) - the cycle still " +
            "applies the change to the authoritative in-memory map and never throws",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("persistfault.md")
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(path), "v1".toByteArray())
            hybrid.stateAtomics.failAlways() // every MirrorState persist flush faults (full/faulted DATA_DIR)

            hybrid.store.pollOnce() // must NOT throw - persist is best-effort

            hybrid.state.etagOf(path).shouldNotBeNull() // the in-memory map is authoritative and updated
            hybrid.mirror.scan()
            hybrid.store.read(path) shouldBe "v1".toByteArray() // the mirror got the change despite the failed flush
        }
    }
})
