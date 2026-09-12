package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** Focused lifecycle proof for the original call Job and its structured children. */
class HttpCallAdmissionTest : FunSpec({

    test("retirement waits for a structured child after admission closes") {
        val admission = HttpCallAdmission()
        val completedJob = Job()
        completedJob.complete()
        admission.tryAdmitJobForTest(completedJob) shouldBe true
        admission.admittedCountForTest() shouldBe 0
        val parent = Job()
        val childStarted = CountDownLatch(1)
        val childRelease = CountDownLatch(1)
        val child = CoroutineScope(parent).launch {
            childStarted.countDown()
            check(childRelease.await(10, TimeUnit.SECONDS)) { "structured child release timed out" }
        }
        val childCompleted = CountDownLatch(1)
        child.invokeOnCompletion { childCompleted.countDown() }
        val waiterDone = CountDownLatch(1)
        var waiterStarted = false
        val waiter = thread(start = false, name = "plainbase-call-admission-waiter") {
            admission.awaitFinalCalls()
            waiterDone.countDown()
        }
        var interrupted = false
        var primary: Throwable? = null
        try {
            check(childStarted.await(5, TimeUnit.SECONDS)) { "structured child did not start" }
            admission.tryAdmitJobForTest(parent) shouldBe true
            admission.closeAdmission()
            admission.tryAdmitJobForTest(parent) shouldBe true
            parent.complete()
            admission.admittedCountForTest() shouldBe 1
            waiter.start()
            waiterStarted = true
            waiterDone.await(100, TimeUnit.MILLISECONDS) shouldBe false

            childRelease.countDown()
            check(childCompleted.await(5, TimeUnit.SECONDS)) { "structured child did not complete" }
            check(waiterDone.await(5, TimeUnit.SECONDS)) { "admission waiter did not finish" }
            admission.admittedCountForTest() shouldBe 0
        } catch (failure: Throwable) {
            if (failure is InterruptedException) interrupted = true
            primary = failure
        } finally {
            childRelease.countDown()
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var cleanupFailure: Throwable? = null
            fun retainCleanup(failure: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure
                } else if (failure !== cleanupFailure && requireNotNull(cleanupFailure).suppressed.none { it === failure }) {
                    requireNotNull(cleanupFailure).addSuppressed(failure)
                }
            }
            fun joinCleanup(worker: Thread): Boolean {
                while (worker.isAlive && System.nanoTime() < cleanupDeadline) {
                    try {
                        worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(cleanupDeadline - System.nanoTime())))
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                return !worker.isAlive
            }
            fun awaitCleanup(latch: CountDownLatch): Boolean {
                while (latch.count != 0L && System.nanoTime() < cleanupDeadline) {
                    try {
                        latch.await(
                            maxOf(1L, TimeUnit.NANOSECONDS.toMillis(cleanupDeadline - System.nanoTime())),
                            TimeUnit.MILLISECONDS,
                        )
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                return latch.count == 0L
            }
            try {
                parent.cancel()
            } catch (failure: Throwable) {
                retainCleanup(failure)
            }
            try {
                if (!awaitCleanup(childCompleted)) {
                    retainCleanup(TimeoutException("structured child survived cleanup"))
                }
            } catch (failure: Throwable) {
                if (failure is InterruptedException) interrupted = true
                retainCleanup(failure)
            }
            try {
                if (waiter.isAlive) waiter.interrupt()
                if (!joinCleanup(waiter)) retainCleanup(TimeoutException("admission waiter survived cleanup"))
            } catch (failure: Throwable) {
                retainCleanup(failure)
            }
            try {
                if (waiterStarted && !awaitCleanup(waiterDone)) {
                    retainCleanup(TimeoutException("admission waiter did not complete"))
                }
            } catch (failure: Throwable) {
                if (failure is InterruptedException) interrupted = true
                retainCleanup(failure)
            }
            cleanupFailure?.let { failure ->
                if (primary == null) {
                    primary = failure
                } else if (failure !== primary && requireNotNull(primary).suppressed.none { it === failure }) {
                    requireNotNull(primary).addSuppressed(failure)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        primary?.let { throw it }
    }
})
