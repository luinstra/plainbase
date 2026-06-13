package com.plainbase.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coalesces watch events into serialized [rebuild] runs (§B2). An event's only effect is
 * [schedule]; all state convergence happens inside the rebuild itself — so the worst a scheduling
 * bug can cause is an extra full pass, never corruption.
 *
 *  - **Trailing debounce 500 ms:** each event re-arms the timer; the rebuild fires when the tree
 *    goes quiet.
 *  - **Max-latency cap 5 s:** a continuous event storm (a huge `git checkout`) cannot starve the
 *    rebuild — it fires no later than 5 s after the first uncoalesced event, then re-arms. Both
 *    are code constants for now (env knobs are a later nicety).
 *  - **Single-flight + dirty flag:** at most one rebuild is queued behind a running one; N events
 *    arriving during a rebuild collapse into exactly one follow-up.
 *
 * Pure timing logic over an injectable [clock] and [Alarm], so the unit tests drive it with fake
 * time; production uses the default single-daemon-thread alarm, which also makes rebuilds
 * single-flight structurally (they run on the alarm thread). A throwing [rebuild] is contained and
 * logged — the scheduler keeps serving events.
 */
class RebuildScheduler(
    private val rebuild: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val alarm: Alarm = ExecutorAlarm(),
) : AutoCloseable {

    /** One-shot timer port: runs [action] about [delayMillis] from now. Faked in tests, real thread in production. */
    fun interface Alarm {
        fun after(delayMillis: Long, action: () -> Unit)
    }

    private val lock = Any()

    // All guarded by [lock]. `firstEventAt == null` means nothing is pending; while a rebuild is
    // running it doubles as the dirty flag — events during the run repopulate it, and rebuild
    // completion re-arms exactly once.
    private var firstEventAt: Long? = null
    private var lastEventAt = 0L
    private var armed = false
    private var running = false
    private var closed = false

    /** Records one watch event: re-arms the trailing debounce, bounded by the 5 s cap. Cheap, any thread. */
    fun schedule() {
        synchronized(lock) {
            if (closed) return
            val now = clock()
            if (firstEventAt == null) firstEventAt = now
            lastEventAt = now
            if (!running) arm(now)
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        (alarm as? AutoCloseable)?.close()
    }

    /** Caller holds [lock] and pending events exist. One alarm outstanding at a time — re-arming is deadline-checked in [onAlarm]. */
    private fun arm(now: Long) {
        if (armed) return
        armed = true
        alarm.after((deadline() - now).coerceAtLeast(0), ::onAlarm)
    }

    /** The fire time of the pending rebuild: debounce-quiet, but never later than the cap after the first event. */
    private fun deadline(): Long = minOf(lastEventAt + DEBOUNCE_MILLIS, checkNotNull(firstEventAt) + MAX_LATENCY_MILLIS)

    private fun onAlarm() {
        synchronized(lock) {
            armed = false
            if (closed || running || firstEventAt == null) return
            val now = clock()
            if (now < deadline()) {
                // Later events pushed the debounce out; sleep the remainder instead of cancelling.
                arm(now)
                return
            }
            firstEventAt = null
            running = true
        }
        try {
            rebuild()
        } catch (e: Exception) {
            logger.error(e) { "scheduled rebuild failed; the next change schedules another full pass" }
        }
        synchronized(lock) {
            running = false
            // Events that arrived during the run (the dirty flag): exactly one follow-up.
            if (!closed && firstEventAt != null) arm(clock())
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 500L
        const val MAX_LATENCY_MILLIS = 5_000L

        private val logger = KotlinLogging.logger {}
    }
}

/** The production [RebuildScheduler.Alarm]: one daemon thread, owned (and closed) by its scheduler. */
private class ExecutorAlarm : RebuildScheduler.Alarm, AutoCloseable {

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
