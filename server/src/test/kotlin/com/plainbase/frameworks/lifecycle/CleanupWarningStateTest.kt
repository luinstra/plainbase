package com.plainbase.frameworks.lifecycle

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** W0 controls for the shared warning forecast, phase snapshots, cap, and completion boundary. */
class CleanupWarningStateTest : FunSpec({

    test("W0: the immutable t0 aggregate, phase-entry snapshots, and phase-count-plus-two cap are shared") {
        val now = AtomicLong()
        val state = CleanupWarningState(nowNanos = now::get)
        val events = captureCleanupLogs {
            state.configure(
                listOf(
                    CleanupWarningState.Forecast("phase-a", 100),
                    CleanupWarningState.Forecast("phase-b", 200),
                ),
            )
            state.start(pendingConstruction = true, maintenanceForecastMillis = 1_000)
            state.enterPhase("construction", CleanupWarningState.CONSTRUCTION_WAIT_FORECAST_MILLIS)
            state.enterPhase("phase-a", 100)

            now.set(1_000_000_000)
            state.enterPhase("phase-b", 200) // This late phase starts its own forecast at t=1s.
            now.set(8_000_000_000)
            state.poll()
            state.poll() // One threshold, one aggregate, and one warning per phase at most.
            state.complete()
        }

        val warnings = events.filter { it.level == Level.WARN }.map(ILoggingEvent::getFormattedMessage)
        warnings shouldHaveSize 5 // 2 configured phases + construction + aggregate + 8s threshold.
        warnings.joinToString("\n") shouldContain "initial aggregate forecast of 6300ms"
        warnings.single { it.contains("phase 'phase-a'") } shouldContain "8000ms"
        warnings.single { it.contains("phase 'phase-b'") } shouldContain "7000ms"
        events.count { it.level == Level.INFO } shouldBe 1 // Completion INFO is outside the warning cap.
    }

    test("W0: repeated interrupts do not skip a later phase and are restored at the outer wait boundary") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finalRan = AtomicBoolean(false)
        val restored = AtomicBoolean(false)
        val shutdown = GracefulShutdown(
            listOf(
                GracefulShutdown.Step("slow") {
                    entered.countDown()
                    while (!release.await(100, TimeUnit.MILLISECONDS)) {
                        // Keep waiting for the explicit release while the other caller receives interrupts.
                    }
                },
                GracefulShutdown.Step("final") { finalRan.set(true) },
            ),
        )
        val winner = thread { shutdown.run() }
        val waiter = thread {
            shutdown.run()
            restored.set(Thread.currentThread().isInterrupted)
        }
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            repeat(5) {
                waiter.interrupt()
                delay(10)
            }
            release.countDown()
            waiter.join(5_000)
            winner.join(5_000)
            waiter.isAlive shouldBe false
            winner.isAlive shouldBe false
            finalRan.get() shouldBe true
            restored.get() shouldBe true
        } finally {
            release.countDown()
            waiter.join(5_000)
            winner.join(5_000)
        }
    }
})

private fun captureCleanupLogs(block: () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(CleanupWarningState::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.toList()
}
