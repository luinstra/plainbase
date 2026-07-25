package com.plainbase.frameworks.filesystem

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * [DataDirLock.close] is idempotent - load-bearing since graceful shutdown, where the SIGTERM hook's teardown
 * and `serve()`'s outer `finally` can both reach it. A bare `FileLock.release()` on the second call would throw
 * ClosedChannelException (the channel is gone by then), leaking a stack trace out of the shutdown path.
 */
class DataDirLockTest : FunSpec({

    fun <T> withDataDir(block: (Path) -> T): T {
        val data = Files.createTempDirectory("pb-lock")
        return try {
            block(data)
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    test("close is idempotent, and the lock is genuinely released - a re-acquire still succeeds") {
        withDataDir { data ->
            val lock = DataDirLock.tryAcquire(data).shouldNotBeNull()
            DataDirLock.tryAcquire(data).shouldBeNull() // held: the second instance is refused

            lock.close()
            lock.close()
            lock.close()

            DataDirLock.tryAcquire(data).shouldNotBeNull().close()
        }
    }
})
