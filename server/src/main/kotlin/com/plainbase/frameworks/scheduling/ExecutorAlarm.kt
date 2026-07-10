package com.plainbase.frameworks.scheduling

import com.plainbase.domain.service.RebuildScheduler
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
        // G3/R4: cancel AND (boundedly) JOIN the running task. shutdownNow() INTERRUPTS an in-flight task;
        // awaiting its termination guarantees the interrupted action has returned before close() does. INSPECT
        // the awaitTermination result (never return as if drained on a bare false): on a timeout, shutdownNow()
        // again (re-interrupt in case the first was swallowed) and warn LOUD rather than silently leave a task
        // running past shutdown. Both waits stay bounded/short so a RebuildScheduler close can never hang.
        executor.shutdownNow()
        if (awaitTerminated()) return
        executor.shutdownNow()
        if (!awaitTerminated()) {
            logger.warn { "$threadName did not terminate within ${2 * SHUTDOWN_GRACE_SECONDS}s of shutdown; a task may still be running" }
        }
    }

    /** Bounded await; RESTORES the interrupt (never swallows it) so a shutting-down caller still observes it. */
    private fun awaitTerminated(): Boolean =
        try {
            executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private companion object {
        private val logger = KotlinLogging.logger {}
        const val SHUTDOWN_GRACE_SECONDS = 30L
    }
}
