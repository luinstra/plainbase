package com.plainbase.frameworks.scheduling

import com.plainbase.domain.service.RebuildScheduler
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The production [RebuildScheduler.Alarm]: one daemon thread, owned (and closed) by its scheduler.
 * [threadName] defaults to the original rebuild-scheduler label (byte-identical for that caller); C5's
 * [com.plainbase.frameworks.git.GitBundleDr] ship-cadence debounce passes its own name (review fold MINOR
 * - the shared default was mislabeling the DR debounce thread in a stack dump).
 */
internal class ExecutorAlarm(threadName: String = "plainbase-rebuild-scheduler") : RebuildScheduler.Alarm, AutoCloseable {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    override fun after(delayMillis: Long, action: () -> Unit) {
        executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
