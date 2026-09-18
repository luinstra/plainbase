package com.plainbase.frameworks.lifecycle

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.IdentitySafeFailureAccumulator
import com.plainbase.OwnedResourceCapture
import com.plainbase.OwnershipFixtureRecord
import com.plainbase.OwnershipOutput
import com.plainbase.RecordingAlarm
import com.plainbase.boundedOwnershipRun
import com.plainbase.currentOwnershipFixtureForTest
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.sqldelight.BeginImmediateSqliteDriver
import com.plainbase.objectConfigForOwnership
import com.plainbase.registerOwnershipHandle
import com.plainbase.withEmptyListEndpoint
import com.plainbase.withOwnershipFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** W1: the real alarm's two grace waits, actual rebuild termination, and an independent DATA_DIR lock probe. */
class Stage0cW1AlarmShutdownTest : FunSpec({

    test("W1: a real rebuild remains live through both alarm waits and retains DATA_DIR ownership") {
        val parent = Stage0cParentDeadline(PARENT_WATCHDOG_MILLIS)
        withEmptyListEndpoint { endpoint ->
            withOwnershipFixture(
                runTimeoutMillis = PARENT_WATCHDOG_MILLIS,
                joinTimeoutMillis = ExecutorAlarm.CLOSE_BOUND_MILLIS,
                parentDeadlineNanos = parent.deadlineNanos,
            ) { content, data ->
                val output = OwnershipOutput()
                val captured = OwnedResourceCapture()
                val fixture = requireNotNull(currentOwnershipFixtureForTest())
                val defaults = captured.openers()
                val armed = AtomicBoolean(false)
                val startEntered = CountDownLatch(1)
                val startRelease = CountDownLatch(1)
                val hookPublished = CountDownLatch(1)
                val queryEntered = CountDownLatch(1)
                val queryRelease = CountDownLatch(1)
                val queryCompleted = CountDownLatch(1)
                val scheduledCompleted = CountDownLatch(1)
                val graceExhausted = CountDownLatch(1)
                val initialRebuilds = AtomicInteger()
                val graceExhaustedInvocations = AtomicInteger()
                val graceExhaustedAtNanos = AtomicLong()
                val heldWorkReleasedAtNanos = AtomicLong()
                val scheduledThread = AtomicReference<Thread?>()
                val scheduledFailure = AtomicReference<Throwable?>()
                val queryFailure = AtomicReference<Throwable?>()
                val statement = AtomicReference<String?>()
                val queryThread = AtomicReference<Thread?>()
                val activeQueryConnection = AtomicReference<Connection?>()
                val scheduler = AtomicReference<RebuildScheduler?>()
                val alarm = AtomicReference<RecordingAlarm?>()
                val hook = AtomicReference<Thread?>()
                val closeEvents = Collections.synchronizedList(mutableListOf<String>())
                val thresholdLock = AtomicReference<String?>()
                val thresholdDriverOpen = AtomicBoolean(false)
                val thresholdSearchOpen = AtomicBoolean(false)
                val thresholdObjectOpen = AtomicBoolean(false)
                val thresholdShutdownPending = AtomicBoolean(false)
                val probeFailure = AtomicReference<Throwable?>()
                val cleanupFailures = IdentitySafeFailureAccumulator()
                val cleanupFailureLock = Any()
                var outerInterrupted = false
                fun releaseHeldWork() {
                    heldWorkReleasedAtNanos.compareAndSet(0L, System.nanoTime())
                    queryRelease.countDown()
                }
                fun recordCleanupFailure(failure: Throwable?) {
                    synchronized(cleanupFailureLock) {
                        cleanupFailures.add(failure)
                    }
                }
                fun recordObserverFailure(failure: Throwable?) {
                    synchronized(cleanupFailureLock) {
                        cleanupFailures.add(failure)
                        probeFailure.compareAndSet(null, failure)
                    }
                }

                val openers = ServerOpeners(
                    openDriver = { path ->
                        HoldingSqlDriver(
                            delegate = defaults.openDriver(path),
                            armed = armed,
                            scheduledThread = scheduledThread,
                            entered = queryEntered,
                            release = queryRelease,
                            completed = queryCompleted,
                            failure = queryFailure,
                            statement = statement,
                            queryThread = queryThread,
                            activeConnection = activeQueryConnection,
                        )
                    },
                    openLocal = defaults.openLocal,
                    openObject = defaults.openObject,
                    openSearch = defaults.openSearch,
                )
                var observer: Thread? = null
                try {
                    val status = boundedOwnershipRun {
                        com.plainbase.runServer(
                            objectConfigForOwnership(content, data, endpoint, gitEnabled = false),
                            output,
                            openers = openers,
                            control = com.plainbase.frameworks.lifecycle.ServerRunControl(
                                onContextAcquired = captured::captureContext,
                                onHookInstalled = {
                                    hook.set(it)
                                    hookPublished.countDown()
                                },
                                createScheduler = { builder ->
                                    val actualAlarm = RecordingAlarm(
                                        events = closeEvents,
                                        delegate = ExecutorAlarm("plainbase-stage0c-w1-alarm").also { delegate ->
                                            delegate.configureShutdownWaitForTest(TEST_GRACE_MILLIS) {
                                                graceExhaustedAtNanos.set(System.nanoTime())
                                                graceExhaustedInvocations.incrementAndGet()
                                                graceExhausted.countDown()
                                            }
                                        },
                                    )
                                    val actualScheduler = RebuildScheduler(
                                        rebuild = {
                                            scheduledThread.set(Thread.currentThread())
                                            try {
                                                builder.rebuild()
                                            } catch (failure: Throwable) {
                                                scheduledFailure.set(failure)
                                                throw failure
                                            } finally {
                                                scheduledCompleted.countDown()
                                            }
                                        },
                                        alarm = actualAlarm,
                                    )
                                    alarm.set(actualAlarm)
                                    scheduler.set(actualScheduler)
                                    registerOwnershipHandle(
                                        "scheduler",
                                        actualScheduler,
                                        actualScheduler::close,
                                        actualAlarm::isClosedForTest,
                                    )
                                    actualScheduler
                                },
                                initialRebuild = { builder ->
                                    initialRebuilds.incrementAndGet()
                                    builder.rebuild()
                                },
                                startServer = { _ ->
                                    check(armed.compareAndSet(false, true)) { "scheduler was armed more than once" }
                                    requireNotNull(scheduler.get()).schedule()
                                    startEntered.countDown()
                                    val observerRef = AtomicReference<Thread?>()
                                    fixture.track(
                                        "W1 observer",
                                        observerRef,
                                        close = {
                                            releaseHeldWork()
                                            startRelease.countDown()
                                            observerRef.get()?.interrupt()
                                        },
                                        complete = { observerRef.get()?.isAlive != true },
                                    )
                                    val actualObserver = thread(isDaemon = true, name = "plainbase-stage0c-w1-observer") {
                                        var shutdown: Thread? = null
                                        var observerInterrupted = false
                                        try {
                                            parent.await(queryEntered, "W1 scheduled rebuild query entry", 30_000) shouldBe true
                                            val actualHook = requireNotNull(hook.get())
                                            val shutdownRef = AtomicReference<Thread?>()
                                            shutdown = thread(name = "plainbase-stage0c-w1-hook-run") {
                                                actualHook.run()
                                            }
                                            shutdownRef.set(shutdown)
                                            fixture.track(
                                                "W1 hook helper",
                                                shutdownRef,
                                                close = {
                                                    releaseHeldWork()
                                                    startRelease.countDown()
                                                    shutdownRef.get()?.interrupt()
                                                },
                                                complete = { shutdownRef.get()?.isAlive != true },
                                            )
                                            val actualAlarm = requireNotNull(alarm.get())
                                            parent.await(actualAlarm.closeEntered, "W1 scheduler drain entry", 30_000) shouldBe true
                                            val alarmStartedAt = actualAlarm.closeStartedAtNanos.get()
                                            check(alarmStartedAt > 0L) { "W1 alarm close entry was not recorded" }
                                            check(
                                                parent.await(
                                                    graceExhausted,
                                                    "W1 alarm grace exhaustion",
                                                    SHUTDOWN_LATCH_TIMEOUT_MILLIS,
                                                ),
                                            ) {
                                                "W1 alarm did not reach its indefinite wait"
                                            }
                                            val graceExhaustedAt = graceExhaustedAtNanos.get()
                                            check(
                                                graceExhaustedAt - alarmStartedAt >=
                                                    TimeUnit.MILLISECONDS.toNanos(2L * TEST_GRACE_MILLIS),
                                            ) { "W1 alarm grace callback fired before both grace periods elapsed" }
                                            thresholdShutdownPending.set(shutdown.isAlive)
                                            thresholdLock.set(probeDataDirLock(data, parent, fixture))
                                            thresholdDriverOpen.set(!requireNotNull(activeQueryConnection.get()).isClosed)
                                            thresholdSearchOpen.set(!captured.searchConnectionsClosedForTest())
                                            val objectStore = requireNotNull(captured.objectStore.get())
                                            thresholdObjectOpen.set(objectStore.available() && !objectStore.isClosedForTest())
                                            check(!closeEvents.contains("object")) { "object closed before scheduled rebuild completion" }
                                            check(!closeEvents.contains("search")) { "search closed before scheduled rebuild completion" }
                                            check(!closeEvents.contains("driver")) { "driver closed before scheduled rebuild completion" }
                                            check(!actualAlarm.isClosedForTest()) { "W1 alarm terminated before held work was released" }
                                            releaseHeldWork()
                                            parent.join(shutdown, "W1 shutdown hook", 30_000) shouldBe true
                                            check(actualAlarm.isClosedForTest()) { "delegated scheduler alarm did not terminate" }
                                            graceExhaustedInvocations.get() shouldBe 1
                                            check(
                                                actualAlarm.closeCompletedAtNanos.get() > heldWorkReleasedAtNanos.get(),
                                            ) { "W1 scheduler close completed before held work was released" }
                                        } catch (failure: Throwable) {
                                            if (failure is InterruptedException) {
                                                observerInterrupted = true
                                                Thread.interrupted()
                                            }
                                            recordObserverFailure(failure)
                                        } finally {
                                            releaseHeldWork()
                                            startRelease.countDown()
                                            val joined = shutdown?.let {
                                                try {
                                                    parent.join(it, "W1 shutdown failure cleanup", 30_000)
                                                } catch (failure: Throwable) {
                                                    if (failure is InterruptedException) {
                                                        observerInterrupted = true
                                                        Thread.interrupted()
                                                    }
                                                    recordObserverFailure(failure)
                                                    false
                                                }
                                            } ?: true
                                            if (!joined) {
                                                recordObserverFailure(
                                                    IllegalStateException("W1 shutdown helper survived bounded cleanup"),
                                                )
                                            }
                                            if (observerInterrupted) Thread.currentThread().interrupt()
                                        }
                                    }
                                    observerRef.set(actualObserver)
                                    observer = actualObserver
                                    try {
                                        startRelease.await()
                                    } catch (failure: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        throw failure
                                    }
                                },
                                closeHttp = { server ->
                                    closeEvents += "http"
                                    server.stop()
                                },
                                closeWatcher = { watcher ->
                                    closeEvents += "watcher"
                                    watcher.close()
                                },
                                closeObject = { store ->
                                    closeEvents += "object"
                                    captured.closeObject(store)
                                },
                                closeSearch = { search ->
                                    closeEvents += "search"
                                    captured.closeSearch(search)
                                },
                                closeDriver = { driver ->
                                    closeEvents += "driver"
                                    captured.closeDriver(driver)
                                },
                                closeContext = { context ->
                                    closeEvents += "context"
                                    captured.closeContext(context)
                                },
                            ),
                        )
                    }
                    status shouldBe 0
                    parent.join(requireNotNull(observer), "W1 observer", 30_000) shouldBe true
                    probeFailure.get()?.let { throw it }
                    thresholdShutdownPending.get() shouldBe true
                    thresholdLock.get() shouldBe "HELD"
                    thresholdDriverOpen.get() shouldBe true
                    thresholdSearchOpen.get() shouldBe true
                    thresholdObjectOpen.get() shouldBe true
                    scheduledFailure.get() shouldBe null
                    queryFailure.get() shouldBe null
                    queryCompleted.count shouldBe 0L
                    scheduledCompleted.count shouldBe 0L
                    requireNotNull(alarm.get()).isClosedForTest() shouldBe true
                    initialRebuilds.get() shouldBe 1
                    checkNotNull(statement.get()).isNotBlank() shouldBe true
                    queryThread.get() shouldBe scheduledThread.get()
                    probeDataDirLock(data, parent) shouldBe "AVAILABLE"
                } catch (failure: Throwable) {
                    if (failure is InterruptedException) {
                        outerInterrupted = true
                        Thread.interrupted()
                    }
                    recordCleanupFailure(failure)
                } finally {
                    releaseHeldWork()
                    startRelease.countDown()
                    val joined = observer?.let {
                        try {
                            parent.join(it, "W1 observer failure cleanup", 30_000)
                        } catch (failure: Throwable) {
                            if (failure is InterruptedException) {
                                outerInterrupted = true
                                Thread.interrupted()
                            }
                            recordCleanupFailure(failure)
                            false
                        }
                    } ?: true
                    if (!joined) {
                        recordCleanupFailure(IllegalStateException("W1 observer survived bounded cleanup"))
                    }
                    if (outerInterrupted) Thread.currentThread().interrupt()
                }
                cleanupFailures.failure?.let { throw it }
            }
        }
    }

    test("W1: a healthy actual owner return closes the graph without arming a held rebuild") {
        val parent = Stage0cParentDeadline(30_000)
        withEmptyListEndpoint { endpoint ->
            withOwnershipFixture(parentDeadlineNanos = parent.deadlineNanos) { content, data ->
                val output = OwnershipOutput()
                val captured = OwnedResourceCapture()
                val initialRebuilds = AtomicInteger()
                val actualAlarm = AtomicReference<RecordingAlarm?>()
                val status = boundedOwnershipRun {
                    com.plainbase.runServer(
                        com.plainbase.objectConfigForOwnership(content, data, endpoint, gitEnabled = false),
                        output,
                        openers = captured.openers(),
                        control = com.plainbase.frameworks.lifecycle.ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            createScheduler = { builder ->
                                val alarm = RecordingAlarm(delegate = ExecutorAlarm("plainbase-stage0c-w1-healthy"))
                                actualAlarm.set(alarm)
                                RebuildScheduler(rebuild = { builder.rebuild() }, alarm = alarm)
                            },
                            initialRebuild = { builder ->
                                initialRebuilds.incrementAndGet()
                                builder.rebuild()
                            },
                            startServer = { _ -> },
                            closeObject = captured::closeObject,
                            closeSearch = captured::closeSearch,
                            closeDriver = captured::closeDriver,
                            closeContext = captured::closeContext,
                        ),
                    )
                }
                status shouldBe 0
                initialRebuilds.get() shouldBe 1
                requireNotNull(actualAlarm.get()).isClosedForTest() shouldBe true
                captured.objectStore.get()?.isClosedForTest() shouldBe true
                captured.searchConnectionsClosedForTest() shouldBe true
                captured.connection.get()?.isClosed shouldBe true
                captured.contextCloseCompletedForTest() shouldBe true
                probeDataDirLock(data, parent) shouldBe "AVAILABLE"
            }
        }
    }
})

private class HoldingSqlDriver(
    private val delegate: SqlDriver,
    private val armed: AtomicBoolean,
    private val scheduledThread: AtomicReference<Thread?>,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
    private val completed: CountDownLatch,
    private val failure: AtomicReference<Throwable?>,
    private val statement: AtomicReference<String?>,
    private val queryThread: AtomicReference<Thread?>,
    private val activeConnection: AtomicReference<Connection?>,
) : SqlDriver by delegate {

    private val held = AtomicBoolean(false)

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val isScheduledQuery = armed.get() && Thread.currentThread() === scheduledThread.get() && held.compareAndSet(false, true)
        if (!isScheduledQuery) return delegate.executeQuery(identifier, sql, mapper, parameters, binders)

        statement.set(sql)
        queryThread.set(Thread.currentThread())
        activeConnection.set((delegate as? BeginImmediateSqliteDriver)?.getConnection())
        entered.countDown()
        var interrupted = false
        try {
            while (true) {
                try {
                    if (release.await(100, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return try {
                delegate.executeQuery(identifier, sql, mapper, parameters, binders)
            } catch (caught: Throwable) {
                failure.set(caught)
                throw caught
            } finally {
                completed.countDown()
                if (interrupted) Thread.currentThread().interrupt()
            }
        } catch (caught: Throwable) {
            failure.compareAndSet(null, caught)
            throw caught
        }
    }
}

internal fun probeDataDirLock(
    dataDir: Path,
    parent: Stage0cParentDeadline,
    fixture: OwnershipFixtureRecord? = currentOwnershipFixtureForTest(),
): String {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val mainRuntime = requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")) {
        "plainbase.test.mainRuntimeClasspath is required"
    }
    val probeCodeSource = Path.of(
        requireNotNull(DataDirLockProbe::class.java.protectionDomain.codeSource) { "probe code source is missing" }
            .location.toURI(),
    ).toAbsolutePath().normalize()
    val classpath = mainRuntime + File.pathSeparator + probeCodeSource
    val command = listOf(
        java,
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        classpath,
        DataDirLockProbe::class.java.name,
        dataDir.toString(),
    )
    var owned: Stage0cOwnedChild? = null
    val failures = IdentitySafeFailureAccumulator()
    var interrupted = false
    var protocol: String? = null
    try {
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        processBuilder.environment()["PLAINBASE_LOG_LEVEL"] = "ERROR"
        val process = processBuilder.start()
        process.outputStream.close()
        val child = Stage0cOwnedChild(process, parent, "plainbase-stage0c-w1-lock-probe")
        owned = child
        fixture?.track("W1 lock probe", child, child::close, child::isComplete)
        protocol = child.awaitProtocol()
    } catch (failure: Throwable) {
        interrupted = failure is InterruptedException
        failures.add(failure)
    } finally {
        owned?.let { child ->
            runCatching { child.close() }.exceptionOrNull()?.let(failures::add)
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
    failures.failure?.let { throw it }
    return requireNotNull(protocol)
}

/** Fresh-JVM lock probe; same-JVM closeReceipt observations are deliberately not used for W1. */
object DataDirLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = DataDirLock.tryAcquire(Path.of(requireNotNull(args.singleOrNull())))
        if (lock == null) {
            println("HELD")
        } else {
            lock.close()
            println("AVAILABLE")
        }
    }
}

private const val TEST_GRACE_MILLIS = 100L
private const val SHUTDOWN_LATCH_TIMEOUT_MILLIS = 5_000L
private const val PARENT_WATCHDOG_MILLIS = 180_000L
