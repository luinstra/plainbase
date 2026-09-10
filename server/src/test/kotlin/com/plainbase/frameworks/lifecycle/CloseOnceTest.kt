package com.plainbase.frameworks.lifecycle

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The shared closer's concurrency and terminal-failure contract. */
class CloseOnceTest : FunSpec({

    test("concurrent callers run the close action once and all return after it completes") {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val completed = AtomicInteger()
        val close = CloseOnce("test resource") {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(CLOSE_ONCE_TEST_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)) { "close release timed out" }
        }
        val winner = thread(start = false, name = "close-once-winner") {
            close.close()
            completed.incrementAndGet()
        }
        val losers = List(CLOSE_CALLER_COUNT - 1) { index ->
            thread(start = false, name = "close-once-loser-$index") {
                close.close()
                completed.incrementAndGet()
            }
        }
        val callers = listOf(winner) + losers
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_ONCE_TEST_DEADLINE_MILLIS)
        try {
            winner.start()
            check(started.await(CLOSE_ONCE_TEST_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)) { "close action did not start" }
            losers.forEach { it.start() }
            awaitCloseWaiters(losers, deadline)
            completed.get() shouldBe 0
        } finally {
            release.countDown()
            callers.forEach { caller -> joinUntil(caller, deadline) }
        }

        calls.get() shouldBe 1
        completed.get() shouldBe CLOSE_CALLER_COUNT
        callers.all { !it.isAlive } shouldBe true
    }

    test("a cleanup failure is terminal, observed, and never retried") {
        val failure = IllegalStateException("cleanup sentinel")
        val calls = AtomicInteger()
        val close = CloseOnce("test resource") {
            calls.incrementAndGet()
            throw failure
        }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            close.close()
            close.close()

            calls.get() shouldBe 1
            val warnings = appender.list.filter { it.formattedMessage == "closing test resource failed" }
            warnings.size shouldBe 1
            val logged = warnings.single().throwableProxy
                ?.let { it as? ThrowableProxy }
                ?.throwable
            logged shouldBeSameInstanceAs failure
        } finally {
            root.detachAppender(appender)
        }
    }
})

private const val CLOSE_CALLER_COUNT = 8
private const val CLOSE_ONCE_TEST_DEADLINE_MILLIS = 10_000L

private fun awaitCloseWaiters(losers: List<Thread>, deadline: Long) {
    while (System.nanoTime() < deadline) {
        check(losers.all { it.isAlive }) { "a CloseOnce loser completed before the winner was released" }
        if (losers.all { loser ->
                loser.state == Thread.State.WAITING &&
                    loser.stackTrace.any { frame ->
                        frame.className == CloseOnce::class.java.name && frame.methodName == "close"
                    }
            }
        ) {
            return
        }
        Thread.sleep(1)
    }
    error(
        "CloseOnce losers did not all wait in close: " +
            losers.joinToString { "${it.name}(${it.state})" },
    )
}

private fun joinUntil(caller: Thread, deadline: Long) {
    while (caller.isAlive && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        caller.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
    }
}
