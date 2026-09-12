package com.plainbase.frameworks.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.objectstore.HybridFixture
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** G4: the real DR caller keeps its real shared locks and bundle path until observed completion. */
class GitBundleDrActualCompletionTest : FunSpec({

    test("G4: interrupted real ship retains both shared locks and history.bundle until helper confirmation") {
        val warningAtNanos = AtomicLong(0L)
        val warningAppender = GitCompletionWarningAppender("g4-real-ship-caller") {
            warningAtNanos.compareAndSet(0L, System.nanoTime())
        }.apply { start() }
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.addAppender(warningAppender)
        try {
            withActualG4Fixture { fixture ->
                val locks = GitRepoLocks()
                val seedExecutor = GitExecutor(workTree = fixture.hybrid.mirrorRoot, home = fixture.gitHome)
                val seedProvider = GitCliHistoryProvider(
                    exec = seedExecutor,
                    workTree = fixture.hybrid.mirrorRoot,
                    gitHome = fixture.gitHome,
                    defaultAuthor = testIdentity(),
                    defaultCommitter = testIdentity(),
                    clock = fixedClock(),
                    repoPath = { path -> fixture.hybrid.mirror.resolveRepoRelativePath(path) },
                    maintenance = {},
                    repoWriteMonitor = locks.repoWrite,
                )
                fixture.invoke("g4-seed-commit") {
                    seedProvider.commit(TreePath.require("g4-seed.md"), "g4 seed\n".toByteArray())
                }
                val seededHead = GitExecutor.parseSha(
                    fixture.invoke("g4-seed-head") { seedExecutor.run(listOf("rev-parse", "HEAD")) }.stdout,
                )
                seededHead.shouldNotBeNull()

                val releaseObservation = AtomicBoolean(false)
                fixture.registerRelease { releaseObservation.set(true) }
                val selectedHelper = AtomicReference<Thread?>()
                val helperObservedComplete = CountDownLatch(1)
                val helperObservationHeld = AtomicBoolean(false)
                val observedProcesses = ConcurrentHashMap<Long, ProcessHandle>()
                val observer = object : GitInvocationCompletionObserver {
                    override fun processComplete(observation: GitProcessObservation): Boolean {
                        observedProcesses[observation.handle.pid()] = observation.handle
                        fixture.registerProcess(observation.handle)
                        return runCatching { !observation.handle.isAlive }.getOrDefault(false)
                    }

                    override fun helperComplete(helper: Thread): Boolean {
                        val complete = !helper.isAlive
                        if (helper.name == SELECTED_HELPER_NAME && complete) {
                            selectedHelper.compareAndSet(null, helper)
                            helperObservedComplete.countDown()
                            if (!releaseObservation.get()) {
                                helperObservationHeld.set(true)
                                return false
                            }
                        }
                        return complete
                    }
                }
                val helperFactory: (String, () -> Unit) -> Thread = { name, block ->
                    Thread(block, name).apply {
                        isDaemon = true
                        fixture.registerWorker(this)
                        fixture.registerHelper(this)
                    }
                }
                val observedExecutor = GitExecutor(
                    workTree = fixture.hybrid.mirrorRoot,
                    home = fixture.gitHome,
                    completionObserver = observer,
                    helperFactory = helperFactory,
                )
                val bundleDr = GitBundleDr(
                    exec = observedExecutor,
                    objectStore = fixture.hybrid.store,
                    mirrorRoot = fixture.hybrid.mirrorRoot,
                    tmpDir = fixture.tmpDir,
                    sentinelPath = fixture.sentinelPath,
                    identity = testIdentity(),
                    clock = fixedClock(),
                    repoPath = { path -> fixture.hybrid.mirror.resolveRepoRelativePath(path) },
                    gitHome = fixture.gitHome,
                    locks = locks,
                    alarm = RebuildScheduler.Alarm { _, _ -> },
                    shipExecutor = fixture.shipExecutor,
                )
                val bundlePath = fixture.tmpDir.resolve("history.bundle")
                val callerFinished = AtomicBoolean(false)
                val callerFailure = AtomicReference<Throwable?>()
                val callerReturned = AtomicBoolean(false)
                val interruptRestored = AtomicBoolean(false)
                val caller = fixture.worker("g4-real-ship-caller") {
                    try {
                        bundleDr.ship()
                        callerReturned.set(true)
                    } catch (failure: Throwable) {
                        callerFailure.set(failure)
                    } finally {
                        interruptRestored.set(Thread.currentThread().isInterrupted)
                        callerFinished.set(true)
                    }
                }

                caller.start()
                awaitCondition("the real bundle and completion identities") {
                    Files.exists(bundlePath) &&
                        observedProcesses.isNotEmpty() &&
                        observedProcesses.values.all { handle -> runCatching { !handle.isAlive }.getOrDefault(false) } &&
                        helperObservedComplete.count == 0L &&
                        selectedHelper.get()?.isAlive == false &&
                        helperObservationHeld.get()
                }
                val bundleBytes = Files.readAllBytes(bundlePath)
                bundleBytes.isNotEmpty().shouldBeTrue()
                fixture.invoke("g4-bundle-verify") {
                    seedExecutor.run(listOf("bundle", "verify", bundlePath.toString()))
                }.ok.shouldBeTrue()
                fixture.registeredHelpers.map(Thread::getName).contains(SELECTED_HELPER_NAME).shouldBeTrue()
                observedProcesses.keys.isNotEmpty().shouldBeTrue()

                val repoReady = CountDownLatch(1)
                val shipReady = CountDownLatch(1)
                val repoAcquired = CountDownLatch(1)
                val shipAcquired = CountDownLatch(1)
                val repoContender = fixture.worker("g4-repoWrite-contender") {
                    repoReady.countDown()
                    synchronized(locks.repoWrite) { repoAcquired.countDown() }
                }
                val shipContender = fixture.worker("g4-ship-contender") {
                    shipReady.countDown()
                    synchronized(locks.ship) { shipAcquired.countDown() }
                }
                repoContender.start()
                shipContender.start()
                awaitCondition("both actual monitor contenders are blocked") {
                    repoReady.count == 0L && shipReady.count == 0L &&
                        repoContender.state == Thread.State.BLOCKED && shipContender.state == Thread.State.BLOCKED
                }

                caller.interrupt()
                awaitCondition("the actual completion-pending warning") { warningAtNanos.get() != 0L }
                val warningNanos = warningAtNanos.get()
                awaitElapsedSince(warningNanos, TimeUnit.MILLISECONDS.toNanos(MINIMUM_WARNING_HOLD_MILLIS))

                assertSoftly {
                    caller.isAlive.shouldBeTrue()
                    callerFinished.get().shouldBeFalse()
                    callerReturned.get().shouldBeFalse()
                    callerFailure.get().shouldBeNull()
                    repoAcquired.count shouldBe 1L
                    shipAcquired.count shouldBe 1L
                    Files.exists(bundlePath).shouldBeTrue()
                }
                Files.readAllBytes(bundlePath).contentEquals(bundleBytes).shouldBeTrue()
                fixture.hybrid.fake.putCount shouldBe 0
                warningAppender.pendingWarnings().single().formattedMessage.let { message ->
                    message shouldContain "git bundle completion pending"
                    message shouldContain "cause=INTERRUPTION"
                    message shouldContain SELECTED_HELPER_NAME
                }

                releaseObservation.set(true)
                awaitCondition("the interrupted ship caller to finish") { callerFinished.get() }
                awaitCondition("both actual contenders to acquire after release") {
                    repoAcquired.count == 0L && shipAcquired.count == 0L &&
                        !repoContender.isAlive && !shipContender.isAlive
                }
                awaitCondition("the real temporary bundle to be deleted") { !Files.exists(bundlePath) }

                callerReturned.get().shouldBeFalse()
                val failure = callerFailure.get()
                failure.shouldNotBeNull()
                (failure is GitCommandException).shouldBeTrue()
                failure.message shouldContain "bundle create"
                interruptRestored.get().shouldBeTrue()
                fixture.hybrid.fake.putCount shouldBe 0
                fixture.hybrid.fake.currentBytes(".plainbase/history.bundle") shouldBe null
                GitExecutor.parseSha(
                    fixture.invoke("g4-final-head") { seedExecutor.run(listOf("rev-parse", "HEAD")) }.stdout,
                ) shouldBe seededHead
                fixture.registeredHelpers.all { helper -> !helper.isAlive }.shouldBeTrue()
                observedProcesses.values.all { handle -> runCatching { !handle.isAlive }.getOrDefault(false) }
                    .shouldBeTrue()
            }
        } finally {
            rootLogger.detachAppender(warningAppender)
            warningAppender.stop()
        }
    }

    test("G4: final ship Error retains a distinct alarm failure without self-suppression") {
        listOf(false, true).forEach { sameSentinel ->
            val finalFailure = AssertionError("g4 final ship failure")
            val priorFailure = if (sameSentinel) finalFailure else AssertionError("g4 alarm close failure")
            val alarmCloseCalls = AtomicInteger()
            val uploadEntries = AtomicInteger()
            val bundlePresentAtUpload = AtomicBoolean(false)
            val executorTerminatedAtUpload = AtomicBoolean(false)
            val shipExecutorTerminatedAtUpload = AtomicBoolean(false)
            val dataDir = AtomicReference<Path>()

            withActualG4Fixture { fixture ->
                dataDir.set(fixture.dataDir)
                val locks = GitRepoLocks()
                val seedExecutor = GitExecutor(workTree = fixture.hybrid.mirrorRoot, home = fixture.gitHome)
                val seedProvider = GitCliHistoryProvider(
                    exec = seedExecutor,
                    workTree = fixture.hybrid.mirrorRoot,
                    gitHome = fixture.gitHome,
                    defaultAuthor = testIdentity(),
                    defaultCommitter = testIdentity(),
                    clock = fixedClock(),
                    repoPath = { path -> fixture.hybrid.mirror.resolveRepoRelativePath(path) },
                    maintenance = {},
                    repoWriteMonitor = locks.repoWrite,
                )
                fixture.invoke("g4-final-ship-seed") {
                    seedProvider.commit(TreePath.require("g4-final-ship.md"), "g4 final ship\n".toByteArray())
                }

                val observedProcesses = ConcurrentHashMap<Long, ProcessHandle>()
                val observer = object : GitInvocationCompletionObserver {
                    override fun processComplete(observation: GitProcessObservation): Boolean {
                        observedProcesses[observation.handle.pid()] = observation.handle
                        fixture.registerProcess(observation.handle)
                        return runCatching { !observation.handle.isAlive }.getOrDefault(false)
                    }

                    override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
                }
                val helperFactory: (String, () -> Unit) -> Thread = { name, block ->
                    Thread(block, name).apply {
                        isDaemon = true
                        fixture.registerWorker(this)
                        fixture.registerHelper(this)
                    }
                }
                val observedExecutor = GitExecutor(
                    workTree = fixture.hybrid.mirrorRoot,
                    home = fixture.gitHome,
                    completionObserver = observer,
                    helperFactory = helperFactory,
                )
                val bundlePath = fixture.tmpDir.resolve("history.bundle")
                fixture.hybrid.fake.onNetworkOp = {
                    if (uploadEntries.incrementAndGet() == 1) {
                        bundlePresentAtUpload.set(
                            Files.isRegularFile(bundlePath) && runCatching { Files.size(bundlePath) > 0 }.getOrDefault(false),
                        )
                        executorTerminatedAtUpload.set(
                            observedProcesses.isNotEmpty() &&
                                observedProcesses.values.all { handle -> runCatching { !handle.isAlive }.getOrDefault(false) } &&
                                fixture.registeredHelpers.all { helper -> !helper.isAlive },
                        )
                        shipExecutorTerminatedAtUpload.set(fixture.shipExecutor.isTerminated)
                        throw finalFailure
                    }
                }
                val alarm = object : RebuildScheduler.Alarm, AutoCloseable {
                    override fun after(delayMillis: Long, action: () -> Unit) = Unit

                    override fun close() {
                        alarmCloseCalls.incrementAndGet()
                        throw priorFailure
                    }
                }
                val bundleDr = GitBundleDr(
                    exec = observedExecutor,
                    objectStore = fixture.hybrid.store,
                    mirrorRoot = fixture.hybrid.mirrorRoot,
                    tmpDir = fixture.tmpDir,
                    sentinelPath = fixture.sentinelPath,
                    identity = testIdentity(),
                    clock = fixedClock(),
                    repoPath = { path -> fixture.hybrid.mirror.resolveRepoRelativePath(path) },
                    gitHome = fixture.gitHome,
                    locks = locks,
                    alarm = alarm,
                    shipExecutor = fixture.shipExecutor,
                )

                val thrown = fixture.invoke("g4-final-close") { runCatching { bundleDr.close() }.exceptionOrNull() }
                val actualFailure = requireNotNull(thrown)
                (actualFailure === finalFailure).shouldBeTrue()
                alarmCloseCalls.get() shouldBe 1
                uploadEntries.get() shouldBe 1
                bundlePresentAtUpload.get().shouldBeTrue()
                executorTerminatedAtUpload.get().shouldBeTrue()
                shipExecutorTerminatedAtUpload.get().shouldBeTrue()
                if (sameSentinel) {
                    actualFailure.suppressed.none { suppressed -> suppressed === finalFailure }.shouldBeTrue()
                } else {
                    actualFailure.suppressed.count { suppressed -> suppressed === priorFailure } shouldBe 1
                }
                Files.notExists(bundlePath).shouldBeTrue()
                fixture.hybrid.fake.currentBytes(".plainbase/history.bundle").shouldBeNull()
            }

            Files.notExists(requireNotNull(dataDir.get())).shouldBeTrue()
        }
    }
})

private const val SELECTED_HELPER_NAME = "git-stdout-drain"
private const val G4_FIXTURE_WAIT_MILLIS = 10_000L
private const val MINIMUM_WARNING_HOLD_MILLIS = 1_200L
private const val CLEANUP_WAIT_MILLIS = 10_000L

private class GitCompletionWarningAppender(
    private val expectedThreadName: String,
    private val onPendingWarning: () -> Unit,
) : AppenderBase<ILoggingEvent>() {
    private val events = ConcurrentLinkedQueue<ILoggingEvent>()

    override fun append(event: ILoggingEvent) {
        events += event
        if (event.level == Level.WARN &&
            event.threadName == expectedThreadName &&
            event.formattedMessage.contains("git bundle completion pending")
        ) {
            onPendingWarning()
        }
    }

    fun pendingWarnings(): List<ILoggingEvent> = events.filter { event ->
        event.level == Level.WARN &&
            event.threadName == expectedThreadName &&
            event.formattedMessage.contains("git bundle completion pending")
    }
}

private class ActualG4Fixture(
    val dataDir: Path,
) {
    val hybrid = HybridFixture()
    val gitHome: Path = dataDir.resolve("git-home")
    val tmpDir: Path = dataDir.resolve("tmp")
    val sentinelPath: Path = dataDir.resolve("restore-pending")
    val shipExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "g4-unused-ship-executor").apply { isDaemon = true }
    }
    private val workers = ConcurrentLinkedQueue<Thread>()
    private val helpers = ConcurrentLinkedQueue<Thread>()
    private val processes = ConcurrentHashMap<Long, ProcessHandle>()
    private val releases = ConcurrentLinkedQueue<() -> Unit>()
    private val workerFailures = ConcurrentLinkedQueue<Throwable>()

    val registeredHelpers: List<Thread> get() = helpers.toList()

    fun registerRelease(release: () -> Unit) {
        releases += release
    }

    fun registerWorker(worker: Thread) {
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> workerFailures += failure }
        workers += worker
    }

    fun worker(name: String, block: () -> Unit): Thread = thread(start = false, isDaemon = true, name = name, block = block).also {
        registerWorker(it)
    }

    fun <T> invoke(name: String, block: () -> T): T {
        val outcome = AtomicReference<Result<T>?>()
        val invocation = worker(name) { outcome.set(runCatching(block)) }
        invocation.start()
        awaitWorker(invocation, name)
        return requireNotNull(outcome.get()) { "G4 worker $name completed without publishing an outcome" }.getOrThrow()
    }

    fun registerHelper(helper: Thread) {
        helpers += helper
    }

    fun registerProcess(process: ProcessHandle) {
        processes[process.pid()] = process
    }

    fun cleanup(): ActualG4FixtureCleanup {
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null
        fun record(candidate: Throwable) {
            if (candidate is InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
            failure = mergeFailure(failure, candidate)
        }
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (candidate: Throwable) {
                record(candidate)
            }
        }

        releases.toList().forEach { release -> attempt(release) }
        val processSnapshot = processes.values.toList()
        processSnapshot.forEach { process ->
            attempt {
                if (process.isAlive && !process.destroyForcibly() && process.isAlive) {
                    error("G4 process ${process.pid()} rejected forced termination")
                }
            }
        }
        val processDeadline = deadlineAfter(CLEANUP_WAIT_MILLIS)
        awaitProcesses(processSnapshot, processDeadline, ::record)

        val workerSnapshot = workers.toList()
        workerSnapshot.filter(Thread::isAlive).forEach { worker -> attempt { worker.interrupt() } }
        val workerDeadline = deadlineAfter(CLEANUP_WAIT_MILLIS)
        workerSnapshot.forEach { worker -> joinWorker(worker, workerDeadline, ::record) }

        attempt { shipExecutor.shutdownNow() }
        val executorDeadline = deadlineAfter(CLEANUP_WAIT_MILLIS)
        attempt {
            while (!shipExecutor.isTerminated) {
                val remaining = executorDeadline - System.nanoTime()
                if (remaining <= 0L) error("G4 ship executor did not terminate")
                shipExecutor.awaitTermination(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)
            }
        }
        workerFailures.toList().forEach(::record)

        val liveProcesses = processSnapshot.filter { process -> runCatching { process.isAlive }.getOrDefault(true) }
        val liveWorkers = workerSnapshot.filter(Thread::isAlive)
        val executorLive = !shipExecutor.isTerminated
        if (liveProcesses.isNotEmpty() || liveWorkers.isNotEmpty() || executorLive) {
            record(
                IllegalStateException(
                    "retaining G4 fixture dataDir=$dataDir mirror=${hybrid.mirrorRoot}; " +
                        "live processes=${liveProcesses.joinToString(",") { it.pid().toString() }} " +
                        "workers=${liveWorkers.joinToString(",") { it.name }} executorLive=$executorLive",
                ),
            )
        } else {
            // The direct ship caller has finished; only now are the store and its files safe to close/remove.
            attempt { hybrid.store.close() }
            attempt {
                check(hybrid.mirrorRoot.toFile().deleteRecursively() || Files.notExists(hybrid.mirrorRoot)) {
                    "could not delete G4 mirror ${hybrid.mirrorRoot}"
                }
            }
            attempt {
                check(dataDir.toFile().deleteRecursively() || Files.notExists(dataDir)) {
                    "could not delete G4 data dir $dataDir"
                }
            }
        }
        return ActualG4FixtureCleanup(failure, interrupted)
    }

    private fun awaitProcesses(processes: List<ProcessHandle>, deadline: Long, record: (Throwable) -> Unit) {
        while (processes.any { process -> runCatching { process.isAlive }.getOrDefault(true) }) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                record(IllegalStateException("G4 process termination wait exceeded its bounded deadline"))
                return
            }
            try {
                Thread.sleep(minOf(25L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
            } catch (candidate: InterruptedException) {
                record(candidate)
            }
        }
    }

    private fun joinWorker(worker: Thread, deadline: Long, record: (Throwable) -> Unit) {
        while (worker.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                record(IllegalStateException("G4 worker ${worker.name} join exceeded its bounded deadline"))
                return
            }
            try {
                worker.join(minOf(25L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
            } catch (candidate: InterruptedException) {
                record(candidate)
            }
        }
    }

    private fun awaitWorker(worker: Thread, name: String) {
        val deadline = deadlineAfter(G4_FIXTURE_WAIT_MILLIS)
        while (worker.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) error("G4 worker $name exceeded its bounded invocation deadline")
            worker.join(minOf(25L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
        }
    }
}

private data class ActualG4FixtureCleanup(val failure: Throwable?, val interrupted: Boolean)

private fun <T> withActualG4Fixture(block: (ActualG4Fixture) -> T): T {
    val dataDir = Files.createTempDirectory("plainbase-g4-data")
    val fixture = ActualG4Fixture(dataDir)
    var outcome: Result<T>? = null
    var primary: Throwable? = null
    var cleanupFailure: Throwable? = null
    var interrupted = Thread.interrupted()
    try {
        outcome = runCatching { block(fixture) }
        primary = outcome.exceptionOrNull()
        if (primary is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
    } finally {
        val cleanup = fixture.cleanup()
        interrupted = interrupted || cleanup.interrupted
        cleanup.failure?.let { cleanupFailure = mergeFailure(cleanupFailure, it) }
        if (interrupted) Thread.currentThread().interrupt()
    }
    val originalFailure = primary
    if (originalFailure != null) {
        cleanupFailure?.let { mergeFailure(originalFailure, it) }
        throw originalFailure
    }
    cleanupFailure?.let { throw it }
    return requireNotNull(outcome).getOrThrow()
}

private fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = deadlineAfter(G4_FIXTURE_WAIT_MILLIS)
    while (!condition()) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) error("timed out waiting for $description")
        Thread.sleep(minOf(10L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
    }
}

private fun awaitElapsedSince(startNanos: Long, durationNanos: Long) {
    val deadline = startNanos + durationNanos
    awaitCondition("the modeled completion hold to exceed ${TimeUnit.NANOSECONDS.toMillis(durationNanos)}ms") {
        System.nanoTime() >= deadline
    }
}

private fun deadlineAfter(millis: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)

private fun mergeFailure(existing: Throwable?, candidate: Throwable): Throwable {
    if (existing == null) return candidate
    if (existing !== candidate && existing.suppressed.none { suppressed -> suppressed === candidate }) {
        existing.addSuppressed(candidate)
    }
    return existing
}
