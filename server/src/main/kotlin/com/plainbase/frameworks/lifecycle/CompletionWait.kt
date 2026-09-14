package com.plainbase.frameworks.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit

/** Runs required completion waits through interrupts and restores the caller's flag once at the boundary. */
internal class CompletionWait private constructor(
    private var interrupted: Boolean,
    private val onInterrupt: () -> Unit,
) {

    private fun rememberObservedInterrupt() {
        interrupted = true
        onInterrupt()
    }

    fun captureCurrentInterrupt() {
        if (Thread.interrupted()) rememberObservedInterrupt()
    }

    /** Remembers an interrupt already consumed by a blocking API such as Object.wait. */
    fun rememberInterrupt() {
        rememberObservedInterrupt()
    }

    fun awaitForever(
        await: (Long) -> Unit,
        completed: () -> Boolean,
        onTick: () -> Unit = {},
    ) {
        while (!completed()) {
            try {
                await(WAIT_SLICE_MILLIS)
            } catch (_: InterruptedException) {
                rememberObservedInterrupt()
            }
            onTick()
        }
    }

    fun awaitUntil(
        deadlineNanos: Long,
        await: (Long) -> Unit,
        completed: () -> Boolean,
        onTick: () -> Unit = {},
        waitSliceMillis: Long = WAIT_SLICE_MILLIS,
    ): Boolean {
        require(waitSliceMillis > 0L) { "waitSliceMillis must be positive" }
        while (!completed()) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            try {
                await(minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(waitSliceMillis)))
            } catch (_: InterruptedException) {
                rememberObservedInterrupt()
            }
            onTick()
        }
        return true
    }

    fun restoreInterrupt() {
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private const val WAIT_SLICE_MILLIS = 100L

        inline fun <T> run(
            noinline onInterrupt: () -> Unit = {},
            block: CompletionWait.() -> T,
        ): T {
            val initiallyInterrupted = Thread.interrupted()
            val wait = CompletionWait(initiallyInterrupted, onInterrupt)
            if (initiallyInterrupted) onInterrupt()
            return try {
                wait.block()
            } finally {
                wait.restoreInterrupt()
            }
        }
    }
}

internal class CleanupWarningState(
    private val warnAfterMillis: Long = WARN_AFTER_MILLIS,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    data class Forecast(val name: String, val boundMillis: Long, val active: Boolean = true)

    private data class Phase(
        val name: String,
        val boundMillis: Long,
        val enteredAtNanos: Long,
        var completed: Boolean = false,
        var warned: Boolean = false,
    )

    private data class DueWarning(val deadlineNanos: Long, val message: String)

    private val lock = Any()
    private var configured = emptyList<Forecast>()
    private var started = false
    private var completed = false
    private var startedAtNanos = 0L
    private var aggregateDeadlineNanos = Long.MAX_VALUE
    private var aggregateMillis = 0L
    private var aggregateWarned = false
    private var thresholdWarned = false
    private var warningCount = 0
    private var warningCap = 2
    private val phases = linkedMapOf<String, Phase>()

    fun configure(forecasts: List<Forecast>) {
        synchronized(lock) {
            if (!started && configured.isEmpty()) configured = forecasts
        }
    }

    fun start(pendingConstruction: Boolean, maintenanceForecastMillis: Long? = null) {
        synchronized(lock) {
            if (started) return
            started = true
            startedAtNanos = nowNanos()
            val constructionMillis = if (pendingConstruction) CONSTRUCTION_WAIT_FORECAST_MILLIS else 0L
            val activeMillis = configured.filter(Forecast::active).sumOf { it.boundMillis }
            aggregateMillis = activeMillis + constructionMillis + (maintenanceForecastMillis ?: 0L)
            aggregateDeadlineNanos = if (aggregateMillis == 0L) {
                Long.MAX_VALUE
            } else {
                saturatingAdd(startedAtNanos, millisToNanos(aggregateMillis))
            }
            warningCap = configured.size + (if (pendingConstruction) 1 else 0) + 2
        }
    }

    fun enterPhase(name: String, boundMillis: Long) {
        synchronized(lock) {
            if (!started) start(pendingConstruction = false)
            phases.putIfAbsent(name, Phase(name, boundMillis, nowNanos()))
        }
    }

    fun completePhase(name: String) {
        synchronized(lock) { phases[name]?.completed = true }
    }

    fun poll() {
        val warnings = synchronized(lock) {
            if (!started || completed) return
            val now = nowNanos()
            val pendingDescription = phases.values.firstOrNull { !it.completed }?.let { phase ->
                " while phase '${phase.name}' has been pending for ${elapsedMillis(now, phase.enteredAtNanos)}ms"
            } ?: " with no cleanup phase pending"
            val due = buildList {
                if (!thresholdWarned && elapsedMillis(now) >= warnAfterMillis) {
                    thresholdWarned = true
                    add(
                        DueWarning(
                            millisDeadline(startedAtNanos, warnAfterMillis),
                            "$warnAfterMillis ms warning$pendingDescription",
                        ),
                    )
                }
                phases.values.filter { !it.completed && !it.warned }.forEach { phase ->
                    if (elapsedMillis(now, phase.enteredAtNanos) >= phase.boundMillis) {
                        phase.warned = true
                        add(
                            DueWarning(
                                millisDeadline(phase.enteredAtNanos, phase.boundMillis),
                                "phase '${phase.name}' exceeded its ${phase.boundMillis}ms forecast after " +
                                    "${elapsedMillis(now, phase.enteredAtNanos)}ms",
                            ),
                        )
                    }
                }
                if (!aggregateWarned && now >= aggregateDeadlineNanos) {
                    aggregateWarned = true
                    add(
                        DueWarning(
                            aggregateDeadlineNanos,
                            "initial aggregate forecast of ${aggregateMillis}ms was exceeded after " +
                                "${elapsedMillis(now)}ms$pendingDescription",
                        ),
                    )
                }
            }
            due.sortedBy(DueWarning::deadlineNanos)
                .groupBy(DueWarning::deadlineNanos)
                .values
                .map { group -> group.joinToString("; ") { it.message } }
                .take((warningCap - warningCount).coerceAtLeast(0))
                .also { warningCount += it.size }
        }
        warnings.forEach { message -> logger.warn { "shutdown wait: $message" } }
    }

    fun complete() {
        val message = synchronized(lock) {
            if (!started || completed) return
            completed = true
            "shutdown complete in ${elapsedMillis(nowNanos())}ms"
        }
        logger.info { message }
    }

    internal fun warningCountForTest(): Int = synchronized(lock) { warningCount }

    private fun elapsedMillis(now: Long, from: Long = startedAtNanos): Long =
        TimeUnit.NANOSECONDS.toMillis((now - from).coerceAtLeast(0L))

    private fun millisDeadline(from: Long, millis: Long): Long = saturatingAdd(from, millisToNanos(millis))

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        const val CONSTRUCTION_WAIT_FORECAST_MILLIS = 5_000L
        const val WARN_AFTER_MILLIS = 8_000L

        private fun millisToNanos(millis: Long): Long =
            if (millis >= Long.MAX_VALUE / NANOS_PER_MILLISECOND) Long.MAX_VALUE else millis * NANOS_PER_MILLISECOND

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
