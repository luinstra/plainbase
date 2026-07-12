package com.plainbase.frameworks.lifecycle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The teardown contract `serve()` leans on: the SIGTERM hook and the clean-exit `finally` BOTH call
 * [GracefulShutdown.run], so it must be idempotent, ordered, throw-tolerant and bounded. The SIGTERM path
 * itself (the signal reaching the hook, and the log line it leaves) is verified against the real binary, not
 * here: this repo has rejected process-harness tests three times over, and an in-JVM test cannot fake a signal.
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

    test("an overrunning step exhausts the budget and returns - a shutdown hook must never hang the JVM") {
        val release = CountDownLatch(1)
        val ran = ConcurrentLinkedQueue<String>()
        val steps = listOf(
            GracefulShutdown.Step("wedged") { release.await(30, TimeUnit.SECONDS) },
            GracefulShutdown.Step("lock") { ran += "lock" },
        )

        val (elapsed, ranOnReturn) = try {
            val startedAt = System.nanoTime()
            GracefulShutdown(steps, budgetMillis = 200).run()
            // Both readings must be taken BEFORE the release below, or the freed worker races the assertions.
            (System.nanoTime() - startedAt) / 1_000_000 to ran.toList()
        } finally {
            release.countDown() // never leave the worker parked for the rest of the suite
        }

        // Returned on the budget, not on the wedged step - and gave up BEFORE the step behind it could run.
        elapsed shouldBeLessThan 5_000
        ranOnReturn shouldContainExactly emptyList()
    }

    test("installHook registers a real JVM shutdown hook") {
        val hook = GracefulShutdown(recorder().second).installHook()

        // removeShutdownHook returns true only for a hook the runtime actually holds - and de-registering it
        // is also what keeps this test from firing a teardown at the end of the whole suite.
        Runtime.getRuntime().removeShutdownHook(hook) shouldBe true
    }
})
