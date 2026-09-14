package com.plainbase.frameworks.filesystem

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * [DataDirLock.close] is idempotent - load-bearing since graceful shutdown, where the SIGTERM hook's teardown
 * and `serve()`'s outer `finally` can both reach it. A bare `FileLock.release()` on the second call would throw
 * ClosedChannelException (the channel is gone by then), leaking a stack trace out of the shutdown path.
 */
class DataDirLockTest : FunSpec({

    fun <T> withDataDir(block: (Path, () -> Unit) -> T): T {
        val data = Files.createTempDirectory("pb-lock")
        var retain = false
        return try {
            block(data) { retain = true }
        } finally {
            if (!retain) data.toFile().deleteRecursively()
        }
    }

    test("close is idempotent, and the lock is genuinely released - a re-acquire still succeeds") {
        withDataDir { data, _ ->
            val lock = DataDirLock.tryAcquire(data).shouldNotBeNull()
            try {
                DataDirLock.tryAcquire(data).shouldBeNull() // held: the second instance is refused

                lock.close()
                lock.close()
                lock.close()

                DataDirLock.tryAcquire(data).shouldNotBeNull().close()
            } finally {
                lock.close()
            }
        }
    }

    test("should close the real channel when tryLock fails with an Error") {
        withDataDir { data, keep ->
            val failure = AssertionError("native lock seam failed")
            var opened: FileChannel? = null
            var testFailure: Throwable? = null
            try {
                val actual = shouldThrow<AssertionError> {
                    DataDirLock.tryAcquireWith(
                        dataDir = data,
                        tryLock = { channel ->
                            opened = channel
                            throw failure
                        },
                    )
                }

                actual shouldBe failure
                opened?.isOpen shouldBe false
            } catch (caught: Throwable) {
                testFailure = caught
                throw caught
            } finally {
                if (!closeRetainedChannel(opened, testFailure)) keep()
            }
        }
    }

    test("should preserve an Error when closing the real failed-acquisition channel also throws") {
        withDataDir { data, keep ->
            val failure = AssertionError("native lock seam failed")
            val cleanup = IllegalStateException("channel close failed")
            var opened: FileChannel? = null
            var testFailure: Throwable? = null
            try {
                val actual = shouldThrow<AssertionError> {
                    DataDirLock.tryAcquireWith(
                        dataDir = data,
                        tryLock = { channel ->
                            opened = channel
                            throw failure
                        },
                        closeFailedChannel = { channel ->
                            channel.close()
                            throw cleanup
                        },
                    )
                }

                actual shouldBe failure
                opened?.isOpen shouldBe false
                actual.suppressed.single() shouldBe cleanup
            } catch (caught: Throwable) {
                testFailure = caught
                throw caught
            } finally {
                if (!closeRetainedChannel(opened, testFailure)) keep()
            }
        }
    }
})

private fun closeRetainedChannel(channel: FileChannel?, primary: Throwable?): Boolean {
    if (channel?.isOpen != true) return true
    try {
        channel.close()
        check(!channel.isOpen) { "retained DATA_DIR lock channel remained open" }
        return true
    } catch (cleanup: Throwable) {
        if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        return !channel.isOpen
    }
}
