package com.plainbase.frameworks.lifecycle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One monotonic deadline shared by a long control's setup, assertions, and cleanup. */
internal class Stage0cParentDeadline(timeoutMillis: Long) {
    internal val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    fun remainingMillis(maxMillis: Long = Long.MAX_VALUE): Long =
        minOf(maxMillis, TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L)))

    fun check(description: String = "parent watchdog") {
        check(remainingMillis() > 0L) { "$description exceeded its absolute parent deadline" }
    }

    fun await(latch: CountDownLatch, description: String, maxMillis: Long = Long.MAX_VALUE): Boolean {
        check(description)
        val remaining = remainingMillis(maxMillis)
        return remaining > 0L && latch.await(remaining, TimeUnit.MILLISECONDS)
    }

    @Suppress("UnusedParameter")
    fun join(thread: Thread, description: String, maxMillis: Long = Long.MAX_VALUE): Boolean {
        val callDeadlineNanos = minOf(deadlineNanos, callDeadline(maxMillis))
        while (thread.isAlive) {
            val remainingNanos = callDeadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            thread.join(minOf(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)))
        }
        return true
    }

    fun waitUntilElapsed(startedAtNanos: Long, targetMillis: Long, description: String) {
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos) < targetMillis) {
            check(description)
            Thread.sleep(minOf(100L, remainingMillis(targetMillis)))
        }
    }

    private fun callDeadline(maxMillis: Long): Long =
        if (maxMillis == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis.coerceAtLeast(0L))
        }
}
