package com.plainbase.frameworks.scheduling

import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.lifecycle.CompletionWait
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The production [RebuildScheduler.Alarm]: one daemon thread, owned (and closed) by its scheduler.
 * [threadName] defaults to the original rebuild-scheduler label (byte-identical for that caller); C5's
 * [com.plainbase.frameworks.git.GitBundleDr] ship-cadence debounce passes its own name (review fold MINOR
 * - the shared default was mislabeling the DR debounce thread in a stack dump).
 */
internal class ExecutorAlarm(private val threadName: String = "plainbase-rebuild-scheduler") : RebuildScheduler.Alarm, AutoCloseable {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }
    private var shutdownGraceMillis = SHUTDOWN_GRACE_SECONDS * 1_000
    private var onGraceExhausted: () -> Unit = {}

    override fun after(delayMillis: Long, action: () -> Unit) {
        executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        CompletionWait.run {
            executor.shutdownNow()
            if (awaitTerminated()) return@run
            executor.shutdownNow()
            if (awaitTerminated()) return@run
            logger.warn {
                "$threadName did not terminate within ${formatDuration(2 * shutdownGraceMillis)} of shutdown; " +
                    "waiting for the scheduled action to finish"
            }
            var callbackInvoked = false
            awaitForever(
                await = {
                    if (!callbackInvoked) {
                        callbackInvoked = true
                        onGraceExhausted()
                    }
                    executor.awaitTermination(it, TimeUnit.MILLISECONDS)
                },
                completed = executor::isTerminated,
            )
        }
    }

    /** Configure before the close thread starts. */
    internal fun configureShutdownWaitForTest(graceMillis: Long, onGraceExhausted: () -> Unit) {
        require(graceMillis > 0L) { "graceMillis must be positive" }
        check(!executor.isShutdown) { "cannot configure a closed alarm" }
        shutdownGraceMillis = graceMillis
        this.onGraceExhausted = onGraceExhausted
    }

    internal fun isTerminatedForTest(): Boolean = executor.isTerminated

    /** Retains each configured grace threshold, then waits for actual executor termination. */
    private fun CompletionWait.awaitTerminated(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownGraceMillis)
        return awaitUntil(
            deadlineNanos = deadline,
            await = { nanos -> executor.awaitTermination(nanos, TimeUnit.NANOSECONDS) },
            completed = executor::isTerminated,
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val SHUTDOWN_GRACE_SECONDS = 30L

        /** Forecast for two default grace waits; close may wait longer for actual termination. */
        const val CLOSE_BOUND_MILLIS: Long = 2 * SHUTDOWN_GRACE_SECONDS * 1_000

        private fun formatDuration(millis: Long): String =
            if (millis % 1_000L == 0L) "${millis / 1_000}s" else "${millis}ms"
    }
}
