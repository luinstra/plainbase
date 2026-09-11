package com.plainbase.frameworks.objectstore

import com.plainbase.IdentitySafeFailureAccumulator
import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.lifecycle.Stage0cParentDeadline
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** W2: the OBJECT poller callback and its actual worker identity are joined before close returns. */
class ObjectContentStoreLifecycleTest : FunSpec({

    test("W2: closing an object watcher waits for its in-flight real poll callback") {
        val parent = Stage0cParentDeadline(25_000)
        val hybrid = HybridFixture(pollSeconds = 1)
        val target = TreePath.require("w2-object-callback.md")
        hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(target), "# Poll callback\n".toByteArray())
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val callbackDone = AtomicBoolean(false)
        val closeStartedAtNanos = AtomicLong()
        val closeFailure = AtomicReference<Throwable?>()
        val closer = hybrid.store.watch(
            onChange = { path ->
                if (path == target) {
                    callbackEntered.countDown()
                    while (true) {
                        try {
                            if (callbackRelease.await(10, TimeUnit.MILLISECONDS)) break
                        } catch (_: InterruptedException) {
                            // The callback intentionally remains live until the close barrier releases it.
                        }
                    }
                    callbackDone.set(true)
                }
            },
            onFailure = {},
            onCoverage = {},
            onBreak = {},
        )
        var closeThread: Thread? = null
        val poller = requireNotNull(hybrid.store.pollerForTest())
        var retain = false
        var cleanupInterrupted = false
        val failures = IdentitySafeFailureAccumulator()
        fun joinOwned(current: Thread, description: String, maxMillis: Long): Boolean = try {
            parent.join(current, description, maxMillis)
        } catch (interrupted: InterruptedException) {
            cleanupInterrupted = true
            Thread.interrupted()
            failures.add(interrupted)
            false
        }
        fun startCloser(name: String): Thread = thread(name = name) {
            try {
                closer.close()
            } catch (failure: Throwable) {
                closeFailure.compareAndSet(null, failure)
            }
        }
        try {
            parent.await(callbackEntered, "W2 object callback entry", 10_000) shouldBe true
            val currentCloseThread = thread(name = "plainbase-stage0c-w2-object-close") {
                closeStartedAtNanos.set(System.nanoTime())
                try {
                    closer.close()
                } catch (failure: Throwable) {
                    closeFailure.compareAndSet(null, failure)
                }
            }
            closeThread = currentCloseThread
            val closeStart = waitForTimestamp(closeStartedAtNanos, parent)
            parent.waitUntilElapsed(closeStart, 10_500, "W2 object old bounded join discriminator")
            poller.isAlive shouldBe true
            currentCloseThread.isAlive shouldBe true
            callbackDone.get() shouldBe false
            callbackRelease.countDown()
            parent.join(currentCloseThread, "W2 object close", 10_000) shouldBe true
            currentCloseThread.isAlive shouldBe false
            closeFailure.get() shouldBe null
            callbackDone.get() shouldBe true
            parent.join(poller, "W2 object poller", 10_000) shouldBe true
            poller.isAlive shouldBe false
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                cleanupInterrupted = true
                Thread.interrupted()
            }
            failures.add(failure)
        } finally {
            callbackRelease.countDown()
            val cleanupThread = closeThread ?: startCloser("plainbase-stage0c-w2-object-cleanup")
            closeThread = cleanupThread
            if (!joinOwned(cleanupThread, "W2 object failure cleanup", 10_000)) retain = true
            if (!cleanupThread.isAlive && poller.isAlive) {
                val retryThread = startCloser("plainbase-stage0c-w2-object-retry")
                if (!joinOwned(retryThread, "W2 object watcher cleanup", 10_000)) retain = true
            }
            if (!joinOwned(poller, "W2 object callback worker cleanup", 10_000)) retain = true
            if (poller.isAlive) retain = true
            failures.add(closeFailure.get())
            if (retain) failures.add(IllegalStateException("W2 object fixture retained a surviving watcher or poller"))
            if (!retain) runCatching { hybrid.close() }.onFailure(failures::add)
            if (cleanupInterrupted) Thread.currentThread().interrupt()
        }
        failures.failure?.let { throw it }
    }

    test("W2: a healthy object watcher closes without pending poll work") {
        val parent = Stage0cParentDeadline(10_000)
        val hybrid = HybridFixture(pollSeconds = 60)
        val closer = hybrid.store.watch(onChange = {}, onFailure = {}, onCoverage = {}, onBreak = {})
        val closeFailure = AtomicReference<Throwable?>()
        val closeThread = thread(name = "plainbase-stage0c-w2-object-healthy-close") {
            try {
                closer.close()
            } catch (failure: Throwable) {
                closeFailure.compareAndSet(null, failure)
            }
        }
        var retain = false
        var cleanupInterrupted = false
        val failures = IdentitySafeFailureAccumulator()
        fun joinOwned(current: Thread, description: String, maxMillis: Long): Boolean = try {
            parent.join(current, description, maxMillis)
        } catch (interrupted: InterruptedException) {
            cleanupInterrupted = true
            Thread.interrupted()
            failures.add(interrupted)
            false
        }
        try {
            parent.join(closeThread, "W2 healthy object close", 5_000) shouldBe true
            closeThread.isAlive shouldBe false
            closeFailure.get() shouldBe null
            requireNotNull(hybrid.store.pollerForTest()).isAlive shouldBe false
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                cleanupInterrupted = true
                Thread.interrupted()
            }
            failures.add(failure)
        } finally {
            if (!joinOwned(closeThread, "W2 healthy object close cleanup", 5_000)) retain = true
            if (closeThread.isAlive) retain = true
            if (requireNotNull(hybrid.store.pollerForTest()).isAlive) retain = true
            failures.add(closeFailure.get())
            if (retain) failures.add(IllegalStateException("W2 healthy object fixture retained a survivor"))
            if (!retain) runCatching { hybrid.close() }.onFailure(failures::add)
            if (cleanupInterrupted) Thread.currentThread().interrupt()
        }
        failures.failure?.let { throw it }
    }
})

private fun waitForTimestamp(timestamp: AtomicLong, parent: Stage0cParentDeadline): Long {
    while (timestamp.get() == 0L) {
        parent.check("W2 object close entry")
        Thread.yield()
    }
    return timestamp.get()
}
