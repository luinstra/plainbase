package com.plainbase.frameworks.lifecycle

import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.GitResult
import com.plainbase.frameworks.git.runAutoMaintenance
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** G1 controls for maintenance admission, one primary/fallback obligation, and worker failure cleanup. */
class GitMaintenanceTasksTest : FunSpec({

    test("inert preparation behavior never admits or creates a worker") {
        val tasks = GitMaintenanceTasks.inert()

        tasks.dispatch(GitExecutor(Path.of("unused-worktree"), Path.of("unused-home"))) shouldBe false
        tasks.unfinishedJobsForTest() shouldBe 0
        tasks.close()
    }

    test("two jobs run independently while close seals admission and waits without interrupting them") {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val daemonStates = ConcurrentLinkedQueue<Boolean>()
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val workerInterruptions = AtomicBoolean(false)
        val workerFailures = AtomicBoolean(false)
        val tasks = GitMaintenanceTasks(
            workerFactory = trackedWorkerFactory(workers, uncaughtFailure),
            jobRunner = {
                try {
                    daemonStates += Thread.currentThread().isDaemon
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "maintenance release timed out" }
                } catch (failure: Throwable) {
                    workerFailures.set(true)
                    if (failure is InterruptedException || Thread.currentThread().isInterrupted) {
                        workerInterruptions.set(true)
                    }
                    throw failure
                } finally {
                    completed.countDown()
                }
            },
        )
        val executor = GitExecutor(Path.of("unused-worktree"), Path.of("unused-home"))
        var closer: Thread? = null
        val closerFailure = AtomicReference<Throwable?>()
        try {
            tasks.dispatch(executor) shouldBe true
            tasks.dispatch(executor) shouldBe true
            entered.await(5, TimeUnit.SECONDS) shouldBe true

            closer = thread(isDaemon = true, name = "g1-maintenance-closer") {
                runCatching { tasks.close() }.onFailure(closerFailure::set)
            }
            awaitAdmissionClosed(tasks)
            tasks.dispatch(executor) shouldBe false
            closer.isAlive shouldBe true
            tasks.unfinishedJobsForTest() shouldBe 2

            release.countDown()
            closer.join(5_000)
            closer.isAlive shouldBe false
            completed.await(5, TimeUnit.SECONDS) shouldBe true
            tasks.unfinishedJobsForTest() shouldBe 0
            daemonStates.toList() shouldContainExactly listOf(false, false)
            workerFailures.get() shouldBe false
            workerInterruptions.get() shouldBe false
            uncaughtFailure.get() shouldBe null
            closerFailure.get() shouldBe null
        } finally {
            release.countDown()
            joinWorkers(workers)
            restoreLeakedSlots(tasks)
            closer?.join(5_000)
            closer?.let { it.isAlive shouldBe false }
        }
    }

    test("primary failure invokes fallback under the same admitted obligation and preserves its interrupt") {
        val executor = spyk(GitExecutor(Path.of("unused-worktree"), Path.of("unused-home")))
        val calls = ConcurrentLinkedQueue<List<String>>()
        val fallbackSawInterrupt = AtomicBoolean(false)
        val finalInterrupt = AtomicBoolean(false)
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        every { executor.run(match { it.firstOrNull() == "maintenance" }, any(), any()) } answers {
            val args = listOf("maintenance", "run", "--auto", "--quiet")
            calls += args
            Thread.currentThread().interrupt()
            GitResult(1, ByteArray(0), "primary failed")
        }
        every { executor.run(match { it.firstOrNull() == "gc" }, any(), any()) } answers {
            calls += listOf("gc", "--auto")
            fallbackSawInterrupt.set(Thread.currentThread().isInterrupted)
            GitResult(0, ByteArray(0), "")
        }
        val tasks = GitMaintenanceTasks(
            workerFactory = trackedWorkerFactory(workers, uncaughtFailure),
            jobRunner = { exec ->
                runAutoMaintenance(exec)
                finalInterrupt.set(Thread.currentThread().isInterrupted)
            },
        )

        tasks.dispatch(executor) shouldBe true
        closeBounded(tasks, workers)

        calls.toList() shouldContainExactly listOf(
            listOf("maintenance", "run", "--auto", "--quiet"),
            listOf("gc", "--auto"),
        )
        fallbackSawInterrupt.get() shouldBe true
        finalInterrupt.get() shouldBe true
        uncaughtFailure.get() shouldBe null
        tasks.unfinishedJobsForTest() shouldBe 0
    }

    test("held fallback keeps close pending as one obligation until fallback completes") {
        val primaryEntered = CountDownLatch(1)
        val fallbackEntered = CountDownLatch(1)
        val fallbackRelease = CountDownLatch(1)
        val fallbackCompleted = CountDownLatch(1)
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val workerFailure = AtomicReference<Throwable?>()
        val executor = spyk(GitExecutor(Path.of("unused-worktree"), Path.of("unused-home")))
        every { executor.run(match { it.firstOrNull() == "maintenance" }, any(), any()) } answers {
            primaryEntered.countDown()
            GitResult(1, ByteArray(0), "primary failed")
        }
        every { executor.run(match { it.firstOrNull() == "gc" }, any(), any()) } answers {
            fallbackEntered.countDown()
            check(fallbackRelease.await(5, TimeUnit.SECONDS)) { "fallback release timed out" }
            fallbackCompleted.countDown()
            GitResult(0, ByteArray(0), "")
        }
        val tasks = GitMaintenanceTasks(
            workerFactory = trackedWorkerFactory(workers, uncaughtFailure),
            jobRunner = { exec ->
                try {
                    runAutoMaintenance(exec)
                } catch (failure: Throwable) {
                    workerFailure.set(failure)
                    throw failure
                }
            },
        )
        val closerFailure = AtomicReference<Throwable?>()
        var closer: Thread? = null
        try {
            tasks.dispatch(executor) shouldBe true
            primaryEntered.await(5, TimeUnit.SECONDS) shouldBe true
            fallbackEntered.await(5, TimeUnit.SECONDS) shouldBe true

            closer = thread(isDaemon = true, name = "g1-held-fallback-closer") {
                runCatching { tasks.close() }.onFailure(closerFailure::set)
            }
            awaitAdmissionClosed(tasks)
            tasks.unfinishedJobsForTest() shouldBe 1
            closer.isAlive shouldBe true
            fallbackCompleted.count shouldBe 1

            fallbackRelease.countDown()
            fallbackCompleted.await(5, TimeUnit.SECONDS) shouldBe true
            joinWorkers(workers)
            closer.join(5_000)
            closer.isAlive shouldBe false
            tasks.unfinishedJobsForTest() shouldBe 0
            workerFailure.get() shouldBe null
            uncaughtFailure.get() shouldBe null
            closerFailure.get() shouldBe null
        } finally {
            fallbackRelease.countDown()
            joinWorkers(workers)
            restoreLeakedSlots(tasks)
            closer?.join(5_000)
            closer?.let { it.isAlive shouldBe false }
        }
    }

    test("worker Error is contained asynchronously and releases its slot") {
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val tasks = GitMaintenanceTasks(
            workerFactory = trackedWorkerFactory(workers, uncaughtFailure),
            jobRunner = { throw AssertionError("worker failure") },
        )

        tasks.dispatch(GitExecutor(Path.of("unused-worktree"), Path.of("unused-home"))) shouldBe true
        closeBounded(tasks, workers)

        uncaughtFailure.get() shouldBe null
        tasks.unfinishedJobsForTest() shouldBe 0
    }

    test("submission failure releases its slot and preserves synchronous Error propagation") {
        val submissionFailure = AssertionError("submission failure")
        val tasks = GitMaintenanceTasks(
            workerFactory = { _, _ -> throw submissionFailure },
        )

        shouldThrow<AssertionError> {
            tasks.dispatch(GitExecutor(Path.of("unused-worktree"), Path.of("unused-home")))
        } shouldBe submissionFailure
        tasks.unfinishedJobsForTest() shouldBe 0
        closeBounded(tasks)
    }

    test("Thread.start submission failure releases its slot and preserves synchronous Error propagation") {
        val submissionFailure = AssertionError("thread start failure")
        val tasks = GitMaintenanceTasks(
            workerFactory = { _, block ->
                object : Thread(block) {
                    override fun start(): Unit = throw submissionFailure
                }
            },
        )

        shouldThrow<AssertionError> {
            tasks.dispatch(GitExecutor(Path.of("unused-worktree"), Path.of("unused-home")))
        } shouldBe submissionFailure
        tasks.unfinishedJobsForTest() shouldBe 0
        closeBounded(tasks)
    }

    test("owner-owned maintenance closes admission before completing its held job") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val tasks = GitMaintenanceTasks(
            workerFactory = trackedWorkerFactory(workers, uncaughtFailure),
            jobRunner = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "owned maintenance release timed out" }
            },
        )
        val owner = ServerResourceOwner()
        val downstreamEntered = CountDownLatch(1)
        val downstreamRelease = CountDownLatch(1)
        val downstreamClosed = CountDownLatch(1)
        owner.ownMaintenance(tasks)
        owner.own(ServerResourcePhase.KOIN_CONTEXT, Any()) {
            downstreamEntered.countDown()
            check(downstreamRelease.await(5, TimeUnit.SECONDS)) { "downstream release timed out" }
            downstreamClosed.countDown()
        }
        val executor = GitExecutor(Path.of("unused-worktree"), Path.of("unused-home"))
        tasks.dispatch(executor) shouldBe true
        entered.await(5, TimeUnit.SECONDS) shouldBe true

        val closerFailure = AtomicReference<Throwable?>()
        var closer: Thread? = null
        try {
            closer = thread(isDaemon = true, name = "g1-owned-maintenance-closer") {
                runCatching { owner.close() }.onFailure(closerFailure::set)
            }
            awaitAdmissionClosed(tasks)
            closer.isAlive shouldBe true
            tasks.unfinishedJobsForTest() shouldBe 1
            downstreamEntered.await(500, TimeUnit.MILLISECONDS) shouldBe false

            release.countDown()
            joinWorkers(workers)
            downstreamRelease.countDown()
            closer.join(5_000)
            closer.isAlive shouldBe false
            tasks.unfinishedJobsForTest() shouldBe 0
            downstreamClosed.await(5, TimeUnit.SECONDS) shouldBe true
            closerFailure.get() shouldBe null
            uncaughtFailure.get() shouldBe null
        } finally {
            release.countDown()
            downstreamRelease.countDown()
            joinWorkers(workers)
            restoreLeakedSlots(tasks)
            closer?.join(5_000)
            closer?.let { it.isAlive shouldBe false }
            closeBounded(tasks)
        }
    }
})

private fun awaitAdmissionClosed(tasks: GitMaintenanceTasks) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline && !tasks.admissionClosedForTest()) {
        Thread.yield()
    }
    tasks.admissionClosedForTest() shouldBe true
}

private fun trackedWorkerFactory(
    workers: ConcurrentLinkedQueue<Thread>,
    uncaughtFailure: AtomicReference<Throwable?>,
): (String, () -> Unit) -> Thread = { name, block ->
    Thread(block, name).also { worker ->
        worker.isDaemon = false
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
            uncaughtFailure.compareAndSet(null, failure)
        }
        workers += worker
    }
}

private fun joinWorkers(workers: Collection<Thread>) {
    workers.forEach { worker ->
        worker.join(5_000)
        if (worker.isAlive) {
            worker.interrupt()
            worker.join(1_000)
        }
        worker.isAlive shouldBe false
    }
}

private fun closeBounded(tasks: GitMaintenanceTasks, workers: Collection<Thread> = emptyList()) {
    val closerFailure = AtomicReference<Throwable?>()
    val closer = thread(isDaemon = true, name = "g1-bounded-close") {
        runCatching { tasks.close() }.onFailure(closerFailure::set)
    }
    closer.join(5_000)
    val productCloseTimeout = if (closer.isAlive) {
        AssertionError("product close did not complete within the bounded 5-second wait")
    } else {
        null
    }

    var cleanupFailure: Throwable? = null
    fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            cleanupFailure = cleanupFailure?.also { it.addSuppressed(failure) } ?: failure
        }
    }

    cleanup { joinWorkers(workers) }
    if (productCloseTimeout != null) cleanup { restoreLeakedSlots(tasks) }
    cleanup {
        closer.join(1_000)
        check(!closer.isAlive) { "bounded closer cleanup timed out" }
    }

    val closerError = closerFailure.get()
    if (productCloseTimeout != null) {
        closerError?.let(productCloseTimeout::addSuppressed)
        cleanupFailure?.let(productCloseTimeout::addSuppressed)
        throw productCloseTimeout
    }
    closerError?.let { failure ->
        cleanupFailure?.let(failure::addSuppressed)
        throw failure
    }
    cleanupFailure?.let { throw it }
}

/** Restores a deliberately leaked fixture slot after bounded close observation; it never certifies worker completion. */
private fun restoreLeakedSlots(tasks: GitMaintenanceTasks) {
    val leakedSlots = tasks.unfinishedJobsForTest()
    if (leakedSlots == 0) return
    val release = tasks.javaClass.getDeclaredMethod("releaseJob").also { it.isAccessible = true }
    repeat(leakedSlots) { release.invoke(tasks) }
    tasks.unfinishedJobsForTest() shouldBe 0
}
