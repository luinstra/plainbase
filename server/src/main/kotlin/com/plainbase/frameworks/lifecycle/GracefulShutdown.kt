@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread

/**
 * The server's ONE teardown path, run exactly once however many callers fire it.
 *
 * The embedded CIO engine can stop the server, but application-owned resources still need an application-owned
 * shutdown hook: a SIGTERM - how `docker stop`, systemd and Kubernetes ALL stop a process, i.e. the normal
 * production shutdown - must drain watchers, an in-flight rebuild, and (in OBJECT mode) the final DR bundle
 * before the runtime halts. The hook owns that work rather than relying on the engine's transport hook.
 *
 * The naive fix - a hook that merely calls `server.stop()` so `start()` returns and the existing `finally`
 * runs - races the JVM: once the last hook returns the runtime HALTS, killing the main thread wherever it
 * got to, quite possibly halfway through shipping that bundle. So the teardown runs INSIDE the hook, and
 * [run] is idempotent because the clean-exit `finally` still calls it too and both can fire.
 *
 * Step bounds are forecasts used for diagnostics, not hard bounds or maximum close durations. They do not authorize
 * returning while a resource worker is live; the external supervisor owns the process deadline.
 *
 * A step that throws is logged and the remaining steps still run: a wedged watcher must not cost us the DR
 * bundle. Order is the caller's, and it is load-bearing (see `serve()`).
 */
internal class GracefulShutdown(
    private val steps: List<Step>,
    /** When to say a teardown is taking unusually long; the wait continues until completion. */
    private val warnAfterMillis: Long = WARN_AFTER_MILLIS,
    private val warningState: CleanupWarningState = CleanupWarningState(warnAfterMillis),
    private val pendingConstruction: () -> Boolean = { false },
    private val maintenanceForecastMillis: () -> Long? = { null },
    /** Lets an owner freeze its acquired-entry forecasts at the first drain instead of at step-list creation. */
    private val warningStateInitializer: (() -> Unit)? = null,
    /** The owner already enters and completes phases when its steps execute. */
    private val managesWarningPhases: Boolean = true,
) {

    /**
     * One named teardown action, and [boundMillis] - the collaborator forecast used by diagnostics, which is the
     * sum of ITS OWN internal timeout forecasts (see the `serve()` call site). It is diagnostic input only: nothing
     * here can interrupt a step, so a forecast that undersells its collaborator only makes the diagnostic less useful.
     *
     * The default suits a step with no internal wait at all (a lock release, a transport close). Expected to be
     * quiet; a throw is contained, never propagated.
     */
    class Step(val name: String, val boundMillis: Long = FAST_STEP_BOUND_MILLIS, val close: () -> Unit)

    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)

    @Volatile
    private var worker: Thread? = null

    internal fun workerForTest(): Thread? = worker

    /**
     * Tears the server down, once. A second caller (the SIGTERM hook racing the clean-exit `finally`, or the
     * reverse) does NOT return early - it waits for the run that won, so "run() returned" means "the teardown
     * finished", regardless of the diagnostic forecast. Safe from any thread.
     */
    fun run() {
        if (started.compareAndSet(false, true)) {
            warningStateInitializer?.invoke() ?: run {
                warningState.configure(steps.map { CleanupWarningState.Forecast(it.name, it.boundMillis) })
                warningState.start(pendingConstruction(), maintenanceForecastMillis())
            }
            logger.info { "shutting down: ${steps.joinToString(", ") { it.name }}" }
            thread(start = false, name = WORKER_THREAD, block = ::runSteps).also {
                it.isDaemon = false
                worker = it
                it.start()
            }
        }
        awaitFinished()
    }

    /**
     * Arms [run] on SIGTERM/SIGINT (and on a normal exit). Returns the hook thread so a test can remove it
     * again - a registered hook outlives the test that made it.
     */
    fun installHook(): Thread =
        thread(start = false, name = HOOK_THREAD, block = ::run).also { Runtime.getRuntime().addShutdownHook(it) }

    /** Always counts [finished] down, even on an Error, so concurrent callers can finish waiting. */
    private fun runSteps() {
        CompletionWait.run {
            try {
                for (step in steps) {
                    runCatching {
                        if (managesWarningPhases) warningState.enterPhase(step.name, step.boundMillis)
                        step.close()
                    }.onFailure { failure ->
                        logger.warn(failure) { "shutdown step '${step.name}' failed; continuing with the remaining steps" }
                    }
                    if (managesWarningPhases) warningState.completePhase(step.name)
                    captureCurrentInterrupt()
                }
                warningState.poll()
                warningState.complete()
            } finally {
                finished.countDown()
            }
        }
    }

    private fun awaitFinished() {
        CompletionWait.run {
            awaitForever(
                await = { finished.await(it, java.util.concurrent.TimeUnit.MILLISECONDS) },
                completed = { finished.count == 0L },
                onTick = warningState::poll,
            )
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** A forecast for a step with no internal wait of its own (a lock release, a transport close). */
        const val FAST_STEP_BOUND_MILLIS = 5_000L

        /**
         * Comfortably over the sub-second happy path and under the TIGHTEST default grace an operator is likely to
         * be running - **`docker stop`'s 10 seconds, not Kubernetes' 30**. That choice is the whole point of the
         * number: this WARN is the one line that tells an operator their runtime is about to SIGKILL a teardown that
         * needed longer, and a threshold picked against the most GENEROUS grace is a diagnostic that arrives after
         * the tightest one has already killed the process. It used to be 25s, so a `docker stop` on defaults killed
         * the server 15 seconds before it would have said why.
         *
         * Advisory ONLY (see [awaitFinished]): the worker waits for completion, while this summed forecast is far
         * larger than any grace period - a final DR bundle ship carries a 10-minute transfer forecast because that
         * is how long shipping a large history over a slow link can honestly take. Cutting the WAIT short would not
         * make the teardown faster; it would only make us SIGKILL ourselves earlier than the runtime would have.
         * Fitting the two together is the OPERATOR's lever (`docker stop -t`,
         * `terminationGracePeriodSeconds`), and `docs/operating-plainbase.md` gives them the arithmetic.
         */
        const val WARN_AFTER_MILLIS = CleanupWarningState.WARN_AFTER_MILLIS
        private const val WORKER_THREAD = "plainbase-shutdown"
        private const val HOOK_THREAD = "plainbase-shutdown-hook"
    }
}
