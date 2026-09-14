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

    override fun after(delayMillis: Long, action: () -> Unit) {
        executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        CompletionWait.run {
            executor.shutdownNow()
            if (awaitTerminated(SHUTDOWN_GRACE_SECONDS)) return@run
            executor.shutdownNow()
            if (awaitTerminated(SHUTDOWN_GRACE_SECONDS)) return@run
            logger.warn {
                "$threadName did not terminate within ${2 * SHUTDOWN_GRACE_SECONDS}s of shutdown; " +
                    "waiting for the scheduled action to finish"
            }
            awaitForever(
                await = { executor.awaitTermination(it, TimeUnit.MILLISECONDS) },
                completed = executor::isTerminated,
            )
        }
    }

    internal fun isTerminatedForTest(): Boolean = executor.isTerminated

    /** Retains each real 30-second threshold, then waits for actual executor termination. */
    private fun CompletionWait.awaitTerminated(graceSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds)
        return awaitUntil(
            deadlineNanos = deadline,
            await = { nanos -> executor.awaitTermination(nanos, TimeUnit.NANOSECONDS) },
            completed = executor::isTerminated,
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val SHUTDOWN_GRACE_SECONDS = 30L

        /** Forecast emitted after the two real grace waits; close still waits for actual termination. */
        const val CLOSE_BOUND_MILLIS: Long = 2 * SHUTDOWN_GRACE_SECONDS * 1_000
    }
}
