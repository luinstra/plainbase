package com.plainbase.frameworks.runtime

import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.RootName

/** Queues pre-lock root breaks and delivers them once the graph's own runtime epoch is armed. */
internal class BootRootSignals {
    private val monitor = Any()
    private val pending = ArrayDeque<BreakEvent>()
    private var deliver: ((RootName, BreakCause) -> Unit)? = null
    private var startupFailure: Throwable? = null
    private var draining = false

    fun broke(root: RootName, cause: BreakCause) {
        val callback = synchronized(monitor) {
            failedState()?.let { throw it }
            if (deliver == null || draining) {
                pending.addLast(BreakEvent(root, cause))
                return
            }
            checkNotNull(deliver)
        }
        callback(root, cause)
    }

    @Suppress("TooGenericExceptionCaught")
    fun arm(deliver: (RootName, BreakCause) -> Unit) {
        synchronized(monitor) {
            failedState()?.let { throw it }
            check(this.deliver == null) { "boot root signals already armed" }
            this.deliver = deliver
            draining = true
            try {
                while (pending.isNotEmpty()) {
                    val event = pending.removeFirst()
                    deliver(event.root, event.cause)
                }
            } catch (failure: Throwable) {
                startupFailure = failure
                pending.clear()
                throw failure
            } finally {
                draining = false
            }
        }
    }

    private fun failedState(): IllegalStateException? =
        startupFailure?.let { failure -> IllegalStateException("boot root signals failed", failure) }

    private data class BreakEvent(val root: RootName, val cause: BreakCause)
}
