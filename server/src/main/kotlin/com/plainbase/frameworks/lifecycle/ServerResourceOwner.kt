package com.plainbase.frameworks.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Owns resources acquired by one server graph and exposes their fixed teardown order. */
@Suppress("TooGenericExceptionCaught")
internal class ServerResourceOwner(
    internal val warningState: CleanupWarningState = CleanupWarningState(),
) : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val state = java.lang.Object()
    private val serviceDrainLock = Any()
    private val overallCloseLock = Any()
    private val activeTicket = ThreadLocal<ConstructionTicket?>()
    private val entries = mutableListOf<OwnedEntry>()
    private var activeConstructors = 0
    private var sealed = false
    private var serviceDrainStarted = false

    @Volatile
    private var serviceDrainFinished = false

    @Volatile
    private var serviceAdmissionFinished = false

    private var serviceWorker: Thread? = null
    private val serviceCompleted = CountDownLatch(1)
    private var overallCloseStarted = false

    @Volatile
    private var overallCloseFinished = false

    private var overallWorker: Thread? = null
    private val overallCompleted = CountDownLatch(1)
    private var configuredBounds: Map<ServerResourcePhase, Long> = emptyMap()
    private val warningInitializationLock = Any()

    private var maintenanceTasks: GitMaintenanceTasks? = null

    @Volatile
    private var warningRunStarted = false

    @Volatile
    private var warningPhases: Set<ServerResourcePhase> = emptySet()

    @Volatile
    private var warningConstruction = false

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

    /** Registers the graph's maintenance registry and exposes its dynamic warning forecast. */
    internal fun ownMaintenance(tasks: GitMaintenanceTasks): GitMaintenanceTasks {
        val owned = own(ServerResourcePhase.MAINTENANCE, tasks, GitMaintenanceTasks::close)
        synchronized(state) {
            check(maintenanceTasks == null) { "Git maintenance tasks were registered more than once" }
            maintenanceTasks = owned
        }
        return owned
    }

    /** Runs the complete ordered service portion while leaving Koin and DATA_DIR ownership intact. */
    fun drainServices() {
        checkNotSelfDrain()
        val current = Thread.currentThread()
        val worker = synchronized(serviceDrainLock) {
            if (serviceDrainFinished) return
            if (!serviceDrainStarted) {
                initializeWarningRun()
                serviceDrainStarted = true
                sealConstruction()
                thread(start = false, name = SERVICE_WORKER) { runServiceDrain() }.also {
                    it.isDaemon = false
                    serviceWorker = it
                    it.start()
                }
            }
            serviceWorker
        }
        if (worker === current) return
        CompletionWait.run {
            awaitForever(
                await = { serviceCompleted.await(it, java.util.concurrent.TimeUnit.MILLISECONDS) },
                completed = { serviceDrainFinished },
                onTick = warningState::poll,
            )
        }
    }

    /** Closes services first, then the Koin context and DATA_DIR lock. */
    override fun close() {
        checkNotSelfDrain()
        val current = Thread.currentThread()
        val worker = synchronized(overallCloseLock) {
            if (overallCloseFinished) return
            if (!overallCloseStarted) {
                initializeWarningRun()
                overallCloseStarted = true
                thread(start = false, name = OVERALL_WORKER) { runOverallCleanup() }.also {
                    it.isDaemon = false
                    overallWorker = it
                    it.start()
                }
            }
            overallWorker
        }
        if (worker === current) return
        CompletionWait.run {
            awaitForever(
                await = { overallCompleted.await(it, java.util.concurrent.TimeUnit.MILLISECONDS) },
                completed = { overallCloseFinished },
                onTick = warningState::poll,
            )
        }
    }

    /** Produces the only cleanup phase list used by [GracefulShutdown]. */
    fun steps(bounds: Map<ServerResourcePhase, Long> = emptyMap()): List<GracefulShutdown.Step> {
        val phaseBounds = ServerResourcePhase.entries.associateWith { phase ->
            bounds[phase] ?: GracefulShutdown.FAST_STEP_BOUND_MILLIS
        }
        synchronized(state) {
            if (configuredBounds.isEmpty()) configuredBounds = phaseBounds
        }
        return ServerResourcePhase.entries.map { phase ->
            val bound = phaseBounds.getValue(phase)
            GracefulShutdown.Step(phase.label, bound) {
                runOverallStep(phase, bound)
            }
        }
    }

    internal fun hasPendingConstructors(): Boolean = synchronized(state) { activeConstructors != 0 }

    internal fun serviceWorkerForTest(): Thread? = synchronized(serviceDrainLock) { serviceWorker }

    internal fun overallWorkerForTest(): Thread? = synchronized(overallCloseLock) { overallWorker }

    /** Starts the shared warning clock at the first actual drain. */
    internal fun initializeWarningRun() {
        synchronized(warningInitializationLock) {
            if (warningRunStarted) return
            val maintenanceForecast = maintenanceTasks?.forecastMillis()
            val (initialPhases, pending) = synchronized(state) {
                val acquired = entries.mapTo(linkedSetOf()) { it.phase }
                val hasPending = activeConstructors != 0
                sealed = true
                acquired to hasPending
            }
            warningConstruction = pending
            warningState.configure(
                ServerResourcePhase.entries.map { phase ->
                    CleanupWarningState.Forecast(
                        phase.label,
                        if (phase == ServerResourcePhase.MAINTENANCE) {
                            maintenanceForecast ?: phaseBound(phase)
                        } else {
                            phaseBound(phase)
                        },
                        active = phase in initialPhases,
                    )
                },
            )
            warningState.start(pendingConstruction = pending)
            warningRunStarted = true
        }
    }

    private fun runOverallStep(phase: ServerResourcePhase, boundMillis: Long) {
        val current = Thread.currentThread()
        val ownsOverall = synchronized(overallCloseLock) {
            if (overallCloseFinished) return
            if (!overallCloseStarted) {
                initializeWarningRun()
                overallCloseStarted = true
                overallWorker = current
            }
            overallWorker === current
        }
        if (!ownsOverall) {
            CompletionWait.run {
                awaitForever(
                    await = { overallCompleted.await(it, java.util.concurrent.TimeUnit.MILLISECONDS) },
                    completed = { overallCloseFinished },
                    onTick = warningState::poll,
                )
            }
            return
        }
        CompletionWait.run { closePhase(phase, boundMillis) }
        if (phase == ServerResourcePhase.DATA_DIR_LOCK) finishOverallCleanup()
    }

    private fun runOverallCleanup() {
        CompletionWait.run {
            try {
                ServerResourcePhase.entries.forEach { phase ->
                    closePhase(phase, phaseBound(phase))
                    captureCurrentInterrupt()
                }
            } finally {
                finishOverallCleanup()
            }
        }
    }

    private fun runServiceDrain() {
        CompletionWait.run {
            try {
                SERVICE_PHASES.forEach { phase ->
                    closePhase(phase, phaseBound(phase))
                    captureCurrentInterrupt()
                }
            } catch (failure: Throwable) {
                logger.warn(failure) { "service drain worker failed; continuing to overall cleanup" }
            } finally {
                finishServiceDrain()
            }
        }
    }

    private fun CompletionWait.closePhase(phase: ServerResourcePhase, boundMillis: Long) {
        if (phase.service) {
            ensureServiceAdmission()
            val phaseForecast = if (phase == ServerResourcePhase.MAINTENANCE) {
                maintenanceTasks?.closeAdmissionAndForecastMillis() ?: boundMillis
            } else {
                boundMillis
            }
            refreshWarningPhases()
            enterWarningPhase(phase, phaseForecast)
            closeEntries(phase)
            if (phase == SERVICE_PHASES.last()) finishServiceDrain()
        } else {
            awaitServiceDrain()
            refreshWarningPhases()
            enterWarningPhase(phase, boundMillis)
            closeEntries(phase)
        }
        warningState.completePhase(phase.label)
    }

    private fun CompletionWait.ensureServiceAdmission() {
        val current = Thread.currentThread()
        val worker = synchronized(serviceDrainLock) {
            if (!serviceDrainStarted) {
                initializeWarningRun()
                serviceDrainStarted = true
                sealConstruction()
                serviceWorker = current
            }
            serviceWorker
        }
        if (worker !== current) {
            awaitServiceDrain()
            return
        }
        if (!serviceAdmissionFinished) {
            if (warningConstruction) {
                warningState.enterPhase(CONSTRUCTION_PHASE, CleanupWarningState.CONSTRUCTION_WAIT_FORECAST_MILLIS)
            }
            awaitConstructors()
            if (warningConstruction) warningState.completePhase(CONSTRUCTION_PHASE)
            refreshWarningPhases()
            serviceAdmissionFinished = true
        }
    }

    private fun CompletionWait.awaitServiceDrain() {
        if (serviceDrainFinished) return
        if (serviceWorker === Thread.currentThread()) return
        awaitForever(
            await = { serviceCompleted.await(it, java.util.concurrent.TimeUnit.MILLISECONDS) },
            completed = { serviceDrainFinished },
            onTick = warningState::poll,
        )
    }

    private fun finishServiceDrain() {
        synchronized(state) {
            if (serviceDrainFinished) return
            serviceDrainFinished = true
        }
        serviceCompleted.countDown()
    }

    private fun sealConstruction() {
        synchronized(state) { sealed = true }
    }

    private fun phaseBound(phase: ServerResourcePhase): Long = synchronized(state) {
        if (phase == ServerResourcePhase.MAINTENANCE && maintenanceTasks != null) {
            0L
        } else {
            configuredBounds[phase] ?: GracefulShutdown.FAST_STEP_BOUND_MILLIS
        }
    }

    private fun refreshWarningPhases() {
        warningPhases = synchronized(state) { entries.mapTo(linkedSetOf()) { it.phase } }
    }

    private fun CompletionWait.awaitConstructors() {
        while (true) {
            synchronized(state) {
                if (activeConstructors == 0) return
                try {
                    state.wait(CONSTRUCTOR_WAIT_SLICE_MILLIS)
                } catch (_: InterruptedException) {
                    rememberInterrupt()
                }
            }
            warningState.poll()
        }
    }

    private fun enterWarningPhase(phase: ServerResourcePhase, boundMillis: Long) {
        if (phase in warningPhases) warningState.enterPhase(phase.label, boundMillis)
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

    private fun finishOverallCleanup() {
        synchronized(overallCloseLock) {
            if (overallCloseFinished) return
            overallCloseFinished = true
        }
        overallCompleted.countDown()
        warningState.complete()
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
            return CompletionWait.run {
                if (started.compareAndSet(false, true)) {
                    try {
                        closeAction()
                    } catch (closeFailure: Throwable) {
                        failure = closeFailure
                    } finally {
                        completed.countDown()
                    }
                    captureCurrentInterrupt()
                    return@run failure
                }
                awaitForever(
                    await = { completed.await(it, java.util.concurrent.TimeUnit.MILLISECONDS) },
                    completed = { completed.count == 0L },
                )
                failure
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private val SERVICE_PHASES = ServerResourcePhase.entries.filter { it.service }
        private const val SERVICE_WORKER = "plainbase-service-drain"
        private const val OVERALL_WORKER = "plainbase-overall-cleanup"
        private const val CONSTRUCTION_PHASE = "construction"
        private const val CONSTRUCTOR_WAIT_SLICE_MILLIS = 100L
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
