package com.plainbase.frameworks.git

import com.plainbase.frameworks.objectstore.HybridFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R1/R2 ship/close lifecycle airtightness (JVM, no real git needed for the reservation-level cases - the
 * captured ship task is never run, so `ship()` never shells out): every ASYNC ship goes through the ONE owned
 * executor, `shipInFlight` is reserved-and-dispatched atomically so a second worker can never slip through the
 * old onAlarm check-vs-set gap, a rejected dispatch RELEASES the reservation (cadence never wedges), and
 * `close()` JOINS the ship engine before it returns.
 */
class GitBundleDrShipLifecycleTest : FunSpec({

    test("R2: onAlarm reserves-and-dispatches atomically - a second onAlarm while one is reserved dispatches NO second worker") {
        withLifecycleHarness { bundleDr, executor ->
            // First onAlarm: shipInFlight is false -> RESERVE + dispatch exactly one worker (never run: it stays captured).
            bundleDr.onAlarm()
            executor.captured.size shouldBe 1

            // Second onAlarm while the first is still reserved: the atomic reserve sees shipInFlight already true and
            // dispatches NOTHING. (The OLD code decided `run` off shipInFlight but set it only inside the worker, so a
            // second onAlarm before that late set would dispatch a duplicate - this is the closed gap.)
            bundleDr.onAlarm()
            executor.captured.size shouldBe 1
        }
    }

    test("R2: a rejected dispatch RELEASES the reservation so the cadence cannot wedge with shipInFlight stuck true") {
        withLifecycleHarness { bundleDr, executor ->
            executor.shutdown() // every execute() now throws RejectedExecutionException

            bundleDr.onAlarm() // reserves, dispatch REJECTED -> reservation released
            bundleDr.onAlarm() // must reserve + attempt again (would be a silent no-op if shipInFlight were wedged true)

            executor.rejectedAttempts shouldBe 2
        }
    }

    test("R1: close() drains and JOINS the owned ship executor before returning (no worker outlives close)") {
        HybridFixture().use { hybrid ->
            withDataDirHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                // A REAL single-thread executor we also hold, so we can submit a slow marker worker and prove close() joins it.
                val shipExecutor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
                val started = CountDownLatch(1)
                val finished = AtomicBoolean(false)
                val bundleDr = GitBundleDr(
                    exec = exec,
                    objectStore = hybrid.store,
                    mirrorRoot = hybrid.mirrorRoot,
                    tmpDir = tmpDir,
                    sentinelPath = sentinelPath,
                    identity = testIdentity(),
                    clock = fixedClock(),
                    repoPath = { path -> hybrid.mirror.resolveRepoRelativePath(path) },
                    gitHome = gitHomeDir,
                    locks = GitRepoLocks(),
                    shipExecutor = shipExecutor,
                )
                // A worker already in flight when close() is called (the mirror has no .git, so the post-drain flush
                // classifies DEFINITIVELY_INCOMPLETE and is skipped - this isolates the drain/join behavior).
                shipExecutor.execute {
                    started.countDown()
                    Thread.sleep(200)
                    finished.set(true)
                }
                started.await(5, TimeUnit.SECONDS)

                bundleDr.close()

                finished.get().shouldBeTrue() // close() blocked on the drain until the in-flight worker completed
                shipExecutor.isShutdown shouldBe true
            }
        }
    }
})

/** Constructs a [GitBundleDr] over a fresh mirror with a CAPTURING ship executor (submitted tasks are recorded,
 *  never run) so the reservation/dispatch logic can be asserted without a real git ship. */
private fun withLifecycleHarness(block: (GitBundleDr, CapturingExecutorService) -> Unit) {
    HybridFixture().use { hybrid ->
        withDataDirHarness { gitHomeDir, tmpDir, sentinelPath ->
            val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
            val executor = CapturingExecutorService()
            val bundleDr = GitBundleDr(
                exec = exec,
                objectStore = hybrid.store,
                mirrorRoot = hybrid.mirrorRoot,
                tmpDir = tmpDir,
                sentinelPath = sentinelPath,
                identity = testIdentity(),
                clock = fixedClock(),
                repoPath = { path -> hybrid.mirror.resolveRepoRelativePath(path) },
                gitHome = gitHomeDir,
                locks = GitRepoLocks(),
                shipExecutor = executor,
            )
            block(bundleDr, executor)
        }
    }
}

/** An [java.util.concurrent.ExecutorService] that CAPTURES submitted tasks (never runs them) and, once shut down,
 *  rejects every submit - counting the rejected attempts so a test can prove a reservation was released + retried. */
private class CapturingExecutorService : AbstractExecutorService() {
    val captured = mutableListOf<Runnable>()
    var rejectedAttempts = 0
    private var down = false

    override fun execute(command: Runnable) {
        if (down) {
            rejectedAttempts++
            throw RejectedExecutionException("captured executor is shut down")
        }
        captured.add(command)
    }

    override fun shutdown() {
        down = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        down = true
        return captured.toMutableList()
    }

    override fun isShutdown(): Boolean = down
    override fun isTerminated(): Boolean = down
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}
