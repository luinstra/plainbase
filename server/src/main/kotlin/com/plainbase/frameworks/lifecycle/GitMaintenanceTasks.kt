package com.plainbase.frameworks.lifecycle

import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.runAutoMaintenance
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

/** Owns independently triggered, best-effort Git maintenance jobs for one server graph. */
@Suppress("TooGenericExceptionCaught")
internal class GitMaintenanceTasks(
    private val workerFactory: (String, () -> Unit) -> Thread = ::newWorker,
    private val jobRunner: (GitExecutor) -> Unit = ::runAutoMaintenance,
    private val active: Boolean = true,
) : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val state = java.lang.Object()
    private val workerSequence = AtomicLong()
    private var admissionClosed = false
    private var unfinished = 0

    /** Admits one complete primary/fallback obligation, or skips it after close. */
    fun dispatch(exec: GitExecutor): Boolean {
        if (!active) return false
        synchronized(state) {
            if (admissionClosed) return false
            unfinished++
        }

        val worker = try {
            workerFactory(
                "plainbase-git-maintenance-${workerSequence.incrementAndGet()}",
            ) { runJob(exec) }
        } catch (failure: Throwable) {
            releaseJob()
            handleSubmissionFailure(failure)
            return false
        }
        try {
            worker.start()
        } catch (failure: Throwable) {
            releaseJob()
            handleSubmissionFailure(failure)
            return false
        }
        return true
    }

    /** Returns the current unfinished-job forecast without closing admission. */
    internal fun forecastMillis(): Long = synchronized(state) { unfinished * JOB_FORECAST_MILLIS }

    /** Atomically stops new jobs and returns the forecast for the admitted jobs. */
    internal fun closeAdmissionAndForecastMillis(): Long = synchronized(state) {
        admissionClosed = true
        unfinished * JOB_FORECAST_MILLIS
    }

    internal fun unfinishedJobsForTest(): Int = synchronized(state) { unfinished }

    internal fun admissionClosedForTest(): Boolean = synchronized(state) { admissionClosed }

    override fun close() {
        closeAdmissionAndForecastMillis()
        CompletionWait.run {
            awaitForever(
                await = { millis ->
                    synchronized(state) {
                        if (unfinished != 0) state.wait(millis)
                    }
                },
                completed = { synchronized(state) { unfinished == 0 } },
            )
        }
    }

    private fun runJob(exec: GitExecutor) {
        try {
            jobRunner(exec)
        } catch (failure: Throwable) {
            logger.warn(failure) { "auto-maintenance job failed; continuing shutdown" }
        } finally {
            releaseJob()
        }
    }

    private fun releaseJob() {
        synchronized(state) {
            check(unfinished > 0) { "Git maintenance job slot released without an admitted job" }
            unfinished--
            state.notifyAll()
        }
    }

    private fun handleSubmissionFailure(failure: Throwable) {
        if (failure is Error) throw failure
        logger.warn(failure) { "auto-maintenance worker could not be submitted" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val JOB_FORECAST_MILLIS = 2 * GitExecutor.DEFAULT_TIMEOUT_SECONDS * 1000L

        private fun newWorker(name: String, block: () -> Unit): Thread =
            Thread(block, name).also { worker ->
                worker.isDaemon = false
            }

        fun inert(): GitMaintenanceTasks = GitMaintenanceTasks(active = false)
    }
}
