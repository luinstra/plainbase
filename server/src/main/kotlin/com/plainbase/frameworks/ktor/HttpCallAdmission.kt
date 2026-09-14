package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.lifecycle.CompletionWait
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Job
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The one admission/drain ledger for a CIO server. Admission is closed without waiting; the caller that owns the
 * HTTP resource later waits for each original call Job to finish, including structured route children.
 */
internal class HttpCallAdmission {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val state = java.lang.Object()
    private val admitted = Collections.newSetFromMap(IdentityHashMap<Job, Boolean>())
    private val nextObservation = AtomicReference<((CallJobObservation) -> Unit)?>(null)
    private var accepting = true

    internal data class CallJobObservation(
        val originalJob: Job,
        val attributeJob: Job,
        val attributeInstalled: Boolean,
    )

    /** Captures the original call Job once and installs its final-completion retirement callback. */
    fun tryAdmit(call: ApplicationCall): Boolean {
        val attributeJob = call.attributes.getOrNull(ORIGINAL_CALL_JOB_KEY)
        val attributeInstalled = attributeJob == null
        val job = attributeJob
            ?: call.coroutineContext[Job]?.also { call.attributes.put(ORIGINAL_CALL_JOB_KEY, it) }
            ?: return false
        if (!tryAdmitJob(job)) return false
        nextObservation.getAndSet(null)?.invoke(
            CallJobObservation(
                originalJob = job,
                attributeJob = call.attributes[ORIGINAL_CALL_JOB_KEY],
                attributeInstalled = attributeInstalled,
            ),
        )
        return true
    }

    private fun tryAdmitJob(job: Job): Boolean {
        synchronized(state) {
            if (admitted.contains(job)) return true
            if (!accepting) return false
            if (!admitted.add(job)) return true
            // Register while the admission state is held. A Job that completed just before this line invokes the
            // callback immediately; the monitor is re-entrant, so the already-completed path is still retired safely.
            job.invokeOnCompletion { retire(job) }
            return true
        }
    }

    /** Stops new calls at the Setup boundary and never waits for an admitted call. */
    fun closeAdmission() {
        synchronized(state) {
            accepting = false
            if (admitted.isEmpty()) state.notifyAll()
        }
    }

    /** Waits for the final call jobs; interruption is remembered and restored after the barrier is complete. */
    fun awaitFinalCalls() {
        CompletionWait.run {
            synchronized(state) {
                awaitForever(
                    await = { state.wait(it) },
                    completed = { admitted.isEmpty() },
                )
            }
        }
    }

    internal fun isAcceptingForTest(): Boolean = synchronized(state) { accepting }

    internal fun admittedCountForTest(): Int = synchronized(state) { admitted.size }

    internal fun tryAdmitJobForTest(job: Job): Boolean = tryAdmitJob(job)

    internal fun captureNextObservationForTest(observer: (CallJobObservation) -> Unit) {
        check(nextObservation.compareAndSet(null, observer)) { "an admission observation is already armed" }
    }

    private fun retire(job: Job) {
        synchronized(state) {
            admitted.remove(job)
            state.notifyAll()
        }
    }

    companion object {
        /** Shared key for the original Job stored on each call. */
        val ORIGINAL_CALL_JOB_KEY: AttributeKey<Job> = AttributeKey("plainbase.original-cio-call-job")
    }
}
