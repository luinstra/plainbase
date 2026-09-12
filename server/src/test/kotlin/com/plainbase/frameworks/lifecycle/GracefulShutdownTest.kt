package com.plainbase.frameworks.lifecycle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The teardown contract `serve()` leans on: the SIGTERM hook and the clean-exit `finally` BOTH call
 * [GracefulShutdown.run], so it must be idempotent, ordered, throw-tolerant and forecast-aware.
 * ServerBootCliContractTest separately characterizes startup, SIGTERM delivery and process exit status.
 */
class GracefulShutdownTest : FunSpec({

    /** Records the order the steps ran in; each name appears once per call, so a double-run shows up as a duplicate. */
    fun recorder(): Pair<ConcurrentLinkedQueue<String>, List<GracefulShutdown.Step>> {
        val ran = ConcurrentLinkedQueue<String>()
        return ran to listOf("watchers", "scheduler", "transport", "lock").map { name ->
            GracefulShutdown.Step(name) { ran += name }
        }
    }

    test("runs every step exactly once, in the caller's order") {
        val (ran, steps) = recorder()

        GracefulShutdown(steps).run()

        ran.toList() shouldContainExactly listOf("watchers", "scheduler", "transport", "lock")
    }

    test("a second run() is a no-op - the clean-exit finally after the SIGTERM hook already tore down") {
        val (ran, steps) = recorder()
        val shutdown = GracefulShutdown(steps)

        shutdown.run()
        shutdown.run()
        shutdown.run()

        ran.toList() shouldContainExactly listOf("watchers", "scheduler", "transport", "lock")
    }

    test("concurrent callers tear down once, and the loser WAITS for the winner rather than racing ahead") {
        // The real race: the hook thread and the main thread returning from start(wait = true). The loser must
        // not return early - `serve()`'s outer finally touches the lock the teardown is still releasing.
        val ran = ConcurrentLinkedQueue<String>()
        val entered = CountDownLatch(1)
        val steps = listOf(
            GracefulShutdown.Step("slow") {
                entered.countDown()
                Thread.sleep(200)
                ran += "slow"
            },
            GracefulShutdown.Step("last") { ran += "last" },
        )
        val shutdown = GracefulShutdown(steps)

        val winner = thread { shutdown.run() }
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        shutdown.run() // the loser: must block until the teardown above completes
        val ranWhenTheLoserReturned = ran.toList()
        winner.join()

        ranWhenTheLoserReturned shouldContainExactly listOf("slow", "last")
    }

    test("a throwing step is contained - the steps after it still run (a wedged watcher must not cost us the DR bundle)") {
        val ran = ConcurrentLinkedQueue<String>()
        val steps = listOf(
            GracefulShutdown.Step("boom") { error("watcher close blew up") },
            GracefulShutdown.Step("git bundle DR") { ran += "git bundle DR" },
            GracefulShutdown.Step("lock") { ran += "lock" },
        )

        GracefulShutdown(steps).run()

        ran.toList() shouldContainExactly listOf("git bundle DR", "lock")
    }

    test("an ERROR is contained too - containment with a hole in it costs exactly the steps it was built to save") {
        // The JVM is already going down, so there is nothing left for a rethrow to protect - while an Error escaping
        // the loop skips every step BEHIND it, which is the DR bundle ship and the DATA_DIR lock release.
        val ran = ConcurrentLinkedQueue<String>()
        val steps = listOf(
            GracefulShutdown.Step("boom") { throw NoClassDefFoundError("a close path nobody had loaded yet") },
            GracefulShutdown.Step("git bundle DR") { ran += "git bundle DR" },
            GracefulShutdown.Step("lock") { ran += "lock" },
        )

        GracefulShutdown(steps).run()

        ran.toList() shouldContainExactly listOf("git bundle DR", "lock")
    }

    test("a slow-but-LIVE step is WARNED about, never cut - its successors still run") {
        // The bug's behavioral half: the warn threshold used to BE the deadline, so a slow (not wedged) step lost
        // the steps behind it - the DR bundle ship, and the lock release after it.
        val ran = ConcurrentLinkedQueue<String>()
        val steps = listOf(
            GracefulShutdown.Step("git bundle DR") {
                Thread.sleep(300)
                ran += "git bundle DR"
            },
            GracefulShutdown.Step("lock") { ran += "lock" },
        )

        GracefulShutdown(steps, warnAfterMillis = 50).run()

        ran.toList() shouldContainExactly listOf("git bundle DR", "lock")
    }

    test("a step that outlives its forecast still completes before shutdown returns") {
        val release = CountDownLatch(1)
        val ran = ConcurrentLinkedQueue<String>()
        val steps = listOf(
            GracefulShutdown.Step("slow", boundMillis = 50) { release.await() },
            GracefulShutdown.Step("lock") { ran += "lock" },
        )

        val releaser = thread(isDaemon = true) {
            Thread.sleep(200)
            release.countDown()
        }
        val startedAt = System.nanoTime()
        GracefulShutdown(steps).run()
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        releaser.join(5_000)

        elapsed shouldBeGreaterThanOrEqual 150
        ran.toList() shouldContainExactly listOf("lock")
    }

    test("installHook registers a real JVM shutdown hook") {
        val hook = GracefulShutdown(recorder().second).installHook()

        // removeShutdownHook returns true only for a hook the runtime actually holds - and de-registering it
        // is also what keeps this test from firing a teardown at the end of the whole suite.
        Runtime.getRuntime().removeShutdownHook(hook) shouldBe true
    }
})
