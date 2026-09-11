package com.plainbase.frameworks.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Owns resources acquired by one server graph and exposes their fixed teardown order. */
@Suppress("TooGenericExceptionCaught")
internal class ServerResourceOwner : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val state = java.lang.Object()
    private val serviceDrainLock = Any()
    private val overallCloseLock = Any()
    private val activeTicket = ThreadLocal<ConstructionTicket?>()
    private val entries = mutableListOf<OwnedEntry>()
    private var activeConstructors = 0
    private var sealed = false
    private var serviceDrainStarted = false
    private var serviceDrainFinished = false
    private var overallCloseFinished = false

    /** Admits the whole synchronous provider before it resolves dependencies or performs effects. */
    fun <T> construct(name: String, block: () -> T): T {
        val inherited = activeTicket.get()
        val ticket = synchronized(state) {
            if (sealed && inherited == null) {
                throw IllegalStateException("construction '$name' started after server resource ownership sealed")
            }
            activeConstructors++
            ConstructionTicket()
        }
        activeTicket.set(ticket)
        try {
            return block()
        } catch (failure: Throwable) {
            rollback(ticket, failure)
            throw failure
        } finally {
            if (inherited == null) {
                activeTicket.remove()
            } else {
                activeTicket.set(inherited)
            }
            synchronized(state) {
                activeConstructors--
                state.notifyAll()
            }
        }
    }

    /** Captures the exact acquired instance; callers that borrow an object simply do not call this method. */
    fun <T> own(phase: ServerResourcePhase, instance: T, close: (T) -> Unit): T {
        val ticket = activeTicket.get()
        synchronized(state) {
            if (sealed && ticket == null) {
                throw IllegalStateException("resource '${phase.label}' acquired after server resource ownership sealed")
            }
            val entry = OwnedEntry(phase) { close(instance) }
            entries += entry
            ticket?.owned?.add(entry)
        }
        return instance
    }

    /** Runs the complete ordered service portion while leaving Koin and DATA_DIR ownership intact. */
    fun drainServices() {
        checkNotSelfDrain()
        synchronized(serviceDrainLock) {
            drainServicesLocked()
        }
    }

    /** Closes services first, then the Koin context and DATA_DIR lock. */
    override fun close() {
        checkNotSelfDrain()
        synchronized(overallCloseLock) {
            if (overallCloseFinished) return
            drainServices()
            closeEntries(ServerResourcePhase.KOIN_CONTEXT)
            closeEntries(ServerResourcePhase.DATA_DIR_LOCK)
            overallCloseFinished = true
        }
    }

    /** Produces the only cleanup phase list used by [GracefulShutdown]. */
    fun steps(bounds: Map<ServerResourcePhase, Long> = emptyMap()): List<GracefulShutdown.Step> =
        ServerResourcePhase.entries.map { phase ->
            GracefulShutdown.Step(phase.label, bounds[phase] ?: GracefulShutdown.FAST_STEP_BOUND_MILLIS) {
                closePhase(phase)
            }
        }

    private fun closePhase(phase: ServerResourcePhase) {
        checkNotSelfDrain()
        synchronized(serviceDrainLock) {
            if (phase.service) {
                beginServiceDrain()
                closeEntries(phase)
                if (phase == ServerResourcePhase.APP_DATABASE) {
                    serviceDrainFinished = true
                }
            } else {
                drainServicesLocked()
                closeEntries(phase)
            }
        }
    }

    private fun drainServicesLocked() {
        if (serviceDrainFinished) return
        beginServiceDrain()
        SERVICE_PHASES.forEach { phase ->
            closeEntries(phase)
        }
        serviceDrainFinished = true
    }

    private fun beginServiceDrain() {
        synchronized(state) {
            if (serviceDrainStarted) return
            sealed = true
            serviceDrainStarted = true
        }
        awaitConstructors()
    }

    private fun awaitConstructors() {
        var interrupted = false
        synchronized(state) {
            while (activeConstructors != 0) {
                try {
                    state.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun closeEntries(phase: ServerResourcePhase) {
        val phaseEntries = synchronized(state) { entries.filter { it.phase == phase } }
        phaseEntries.forEach { entry ->
            entry.close()?.let { failure ->
                logger.warn(failure) { "closing ${phase.label} failed" }
            }
        }
    }

    private fun rollback(ticket: ConstructionTicket, primary: Throwable) {
        val rollbackEntries = synchronized(state) {
            ticket.owned.asReversed().toList().also { entries.removeAll(it) }.also { ticket.owned.clear() }
        }
        rollbackEntries.forEach { entry ->
            entry.close()?.let { cleanup ->
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun checkNotSelfDrain() {
        check(activeTicket.get() == null) {
            "a construction ticket cannot drain or close its owning resources"
        }
    }

    private class ConstructionTicket {
        val owned = mutableListOf<OwnedEntry>()
    }

    private class OwnedEntry(
        val phase: ServerResourcePhase,
        closeAction: () -> Unit,
    ) {
        private val closeAction = closeAction
        private val started = AtomicBoolean(false)
        private val completed = CountDownLatch(1)

        @Volatile
        private var failure: Throwable? = null

        fun close(): Throwable? {
            if (started.compareAndSet(false, true)) {
                try {
                    closeAction()
                } catch (closeFailure: Throwable) {
                    failure = closeFailure
                } finally {
                    completed.countDown()
                }
                return failure
            }
            var interrupted = false
            while (true) {
                try {
                    completed.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            return failure
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private val SERVICE_PHASES = ServerResourcePhase.entries.filter { it.service }
    }
}

/** Ordered ownership slots for the server's acquired resources. */
internal enum class ServerResourcePhase(val label: String, val service: Boolean) {
    HTTP("http server", true),
    WATCHERS("watchers", true),
    SCHEDULER("rebuild scheduler", true),
    MAINTENANCE("git maintenance", true),
    DISASTER_RECOVERY("git bundle DR", true),
    OBJECT_TRANSPORT("object store transport", true),
    SEARCH_DATABASE("search database", true),
    APP_DATABASE("app database", true),
    KOIN_CONTEXT("Koin context", false),
    DATA_DIR_LOCK("DATA_DIR lock", false),
}
