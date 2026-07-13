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
 * grace period regardless): every [Step] is individually bounded and DECLARES that bound, and [run] waits on a
 * daemon worker for the sum of them ([budgetMillis]) - so the budget cannot be SHORTER than what it fronts. A
 * budget that is shorter does not bound the steps, it TRUNCATES them, and the step it truncates is the slow one:
 * a final DR bundle ship, which is the loss this class was written to prevent. Overrunning the derived budget
 * means a step blew its OWN internal timeout, which is the only condition on which abandoning it is right - and
 * the overrun NAMES it. A long-but-live step is WARNed about at [warnAfterMillis] and then WAITED for.
 *
 * A step that throws is logged and the remaining steps still run: a wedged watcher must not cost us the DR
 * bundle. Order is the caller's, and it is load-bearing (see `serve()`).
 */
internal class GracefulShutdown(
    private val steps: List<Step>,
    /**
     * The hard bound, DERIVED from what the steps themselves promise rather than guessed at. A fixed number here
     * silently forked from the collaborators behind it (a 30s executor grace, a 10-minute bundle transfer) and
     * cut them off mid-work.
     */
    val budgetMillis: Long = steps.sumOf { it.boundMillis },
    /** When to say a teardown is taking unusually long - advisory only; the wait continues to [budgetMillis]. */
    private val warnAfterMillis: Long = WARN_AFTER_MILLIS,
) {

    /**
     * One named teardown action, and [boundMillis] - the longest its collaborator can honestly take, which is
     * the sum of ITS OWN internal timeouts (see the `serve()` call site). It is not a wish: nothing here can
     * interrupt a step, so a bound that undersells its collaborator only lies to the budget above.
     *
     * The default suits a step with no internal wait at all (a lock release, a transport close). Expected to be
     * quiet; a throw is contained, never propagated.
     */
    class Step(val name: String, val boundMillis: Long = FAST_STEP_BOUND_MILLIS, val close: () -> Unit)

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
                } catch (e: Throwable) {
                    // Throwable, NOT Exception, and this is the one place in the tree where that is right: the
                    // JVM is already on its way out, so there is nothing left for a rethrow to protect - while an
                    // Error escaping this loop would skip every step BEHIND it, which is the DR bundle ship and
                    // the DATA_DIR lock release. Containment is the whole contract; it cannot have a hole in it.
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
        val warnAt = minOf(warnAfterMillis, budgetMillis)
        try {
            if (finished.await(warnAt, TimeUnit.MILLISECONDS)) return
            if (warnAt < budgetMillis) {
                // Advisory, never a deadline: the step may simply be a big DR bundle going up a slow link, and
                // cutting it here is exactly the bug. It tells the operator what their runtime's grace period is
                // now racing, while we keep waiting for the step's OWN bound.
                logger.warn {
                    "shutdown step '${inFlight.load()}' has been running for ${warnAt}ms and is still going; waiting up to " +
                        "${budgetMillis}ms for it. If the runtime SIGKILLs first, raise its grace period (docker stop -t, " +
                        "terminationGracePeriodSeconds) - a truncated shutdown can leave the final DR bundle unshipped."
                }
                if (finished.await(budgetMillis - warnAt, TimeUnit.MILLISECONDS)) return
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt() // restore it, never swallow (the ExecutorAlarm idiom)
            logger.warn { "shutdown wait was interrupted while step '${inFlight.load()}' was in flight; exiting without waiting further" }
            return
        }
        logger.warn {
            "shutdown exceeded its ${budgetMillis}ms budget with step '${inFlight.load()}' still in flight; exiting " +
                "without waiting further. The step has overrun its own declared bound, so it is wedged rather than slow."
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** A step with no internal wait of its own (a lock release, a transport close) - generous, and never reached. */
        const val FAST_STEP_BOUND_MILLIS = 5_000L

        /**
         * Comfortably over the sub-second happy path and under Kubernetes' 30s default grace, so an operator on
         * defaults hears about a long teardown BEFORE their runtime SIGKILLs it. Advisory only (see [awaitFinished]):
         * the budget the wait actually honors is the steps' own, summed.
         */
        const val WARN_AFTER_MILLIS = 25_000L
        private const val WORKER_THREAD = "plainbase-shutdown"
        private const val HOOK_THREAD = "plainbase-shutdown-hook"
    }
}
