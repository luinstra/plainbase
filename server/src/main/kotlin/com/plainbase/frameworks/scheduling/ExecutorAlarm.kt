package com.plainbase.frameworks.scheduling

import com.plainbase.domain.service.RebuildScheduler
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The production [RebuildScheduler.Alarm]: one daemon thread, owned (and closed) by its scheduler. */
internal class ExecutorAlarm : RebuildScheduler.Alarm, AutoCloseable {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "plainbase-rebuild-scheduler").apply { isDaemon = true }
    }

    override fun after(delayMillis: Long, action: () -> Unit) {
        executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
