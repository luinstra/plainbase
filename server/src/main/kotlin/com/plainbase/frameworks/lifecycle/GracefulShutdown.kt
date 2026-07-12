@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread

/**
 * The server's ONE teardown path, run exactly once however many callers fire it.
 *
 * `embeddedServer(...)` installs no JVM shutdown hook of its own (only Ktor's `EngineMain` does), so until
 * this existed a SIGTERM - how `docker stop`, systemd and Kubernetes ALL stop a process, i.e. the normal
 * production shutdown - killed the JVM while `serve()` was still parked in `start(wait = true)`: its cleanup
 * `finally` never ran, so watchers were never closed, an in-flight rebuild was never drained, and in OBJECT
 * mode the final DR bundle silently never shipped, on every restart. It left no log line either, which is
 * why nobody noticed.
 *
 * The naive fix - a hook that merely calls `server.stop()` so `start()` returns and the existing `finally`
 * runs - races the JVM: once the last hook returns the runtime HALTS, killing the main thread wherever it
 * got to, quite possibly halfway through shipping that bundle. So the teardown runs INSIDE the hook, and
 * [run] is idempotent because the clean-exit `finally` still calls it too and both can fire.
 *
 * Bounded, because a hook that blocks forever is its own outage (the container runtime SIGKILLs at its own
 * grace period regardless): every [Step] is individually bounded, and [run] additionally waits at most
 * [budgetMillis] on a daemon worker - an overrun NAMES the step it is stuck on rather than hanging silently.
 * A step that throws is logged and the remaining steps still run: a wedged watcher must not cost us the DR
 * bundle. Order is the caller's, and it is load-bearing (see `serve()`).
 */
internal class GracefulShutdown(
    private val steps: List<Step>,
    private val budgetMillis: Long = BUDGET_MILLIS,
) {

    /** One named teardown action. Expected to be bounded and quiet; a throw is contained, never propagated. */
    class Step(val name: String, val close: () -> Unit)

    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)

    /** The step in flight - read only to NAME the culprit in the budget-overrun warning. */
    private val inFlight = AtomicReference<String?>(null)

    /**
     * Tears the server down, once. A second caller (the SIGTERM hook racing the clean-exit `finally`, or the
     * reverse) does NOT return early - it waits for the run that won, under the same budget, so "run() returned"
     * means "the teardown finished, or it loudly overran". Safe from any thread.
     */
    fun run() {
        if (started.compareAndSet(false, true)) {
            logger.info { "shutting down: ${steps.joinToString(", ") { it.name }}" }
            thread(name = WORKER_THREAD, isDaemon = true, block = ::runSteps)
        }
        awaitFinished()
    }

    /**
     * Arms [run] on SIGTERM/SIGINT (and on a normal exit). Returns the hook thread so a test can remove it
     * again - a registered hook outlives the test that made it.
     */
    fun installHook(): Thread =
        thread(start = false, name = HOOK_THREAD, block = ::run).also { Runtime.getRuntime().addShutdownHook(it) }

    /** Always counts [finished] down, even on an Error, so a waiter can never be stranded past the budget for nothing. */
    private fun runSteps() {
        val startedAt = System.nanoTime()
        try {
            for (step in steps) {
                inFlight.store(step.name)
                try {
                    step.close()
                } catch (e: Exception) {
                    logger.warn(e) { "shutdown step '${step.name}' failed; continuing with the remaining steps" }
                }
            }
            inFlight.store(null)
            // Logged BEFORE the countdown: the waiter is the shutdown hook, and the JVM halts the moment it
            // returns - a line logged after it would race the halt and could be lost from the operator's log.
            logger.info { "shutdown complete in ${(System.nanoTime() - startedAt) / 1_000_000}ms" }
        } finally {
            finished.countDown()
        }
    }

    private fun awaitFinished() {
        try {
            if (finished.await(budgetMillis, TimeUnit.MILLISECONDS)) return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt() // restore it, never swallow (the ExecutorAlarm idiom)
            logger.warn { "shutdown wait was interrupted while step '${inFlight.load()}' was in flight; exiting without waiting further" }
            return
        }
        logger.warn {
            "shutdown exceeded its ${budgetMillis}ms budget with step '${inFlight.load()}' still in flight; exiting " +
                "without waiting further. If this recurs, raise the runtime's grace period (docker stop -t, " +
                "terminationGracePeriodSeconds) - a truncated shutdown can leave the final DR bundle unshipped."
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Comfortably over the sub-second happy path and under Kubernetes' 30s default grace, so an operator on
         * defaults sees the overrun WARN rather than a bare SIGKILL. `docker stop`'s 10s default is TIGHTER than
         * this budget - a slow final DR bundle can still be cut short there; the operating guide says to raise it.
         */
        const val BUDGET_MILLIS = 25_000L
        const val WORKER_THREAD = "plainbase-shutdown"
        const val HOOK_THREAD = "plainbase-shutdown-hook"
    }
}
