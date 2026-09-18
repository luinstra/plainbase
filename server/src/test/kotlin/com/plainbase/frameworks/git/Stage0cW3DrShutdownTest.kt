package com.plainbase.frameworks.git

import com.plainbase.IdentitySafeFailureAccumulator
import com.plainbase.boundedOwnershipRun
import com.plainbase.currentOwnershipFixtureForTest
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.lifecycle.Stage0cParentDeadline
import com.plainbase.frameworks.lifecycle.probeDataDirLock
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.objectstore.ObjectStoreClient
import com.plainbase.frameworks.objectstore.PutOutcome
import com.plainbase.frameworks.objectstore.S3ObjectClient
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.objectConfigForOwnership
import com.plainbase.withOwnershipFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** W3 controls for real cadence/ship workers, final flush ordering, and repeated-interrupt preservation. */
class Stage0cW3DrShutdownTest : FunSpec({

    test("W3: a real ship is joined before the final PUT and before transport close") {
        val parent = Stage0cParentDeadline(PARENT_WATCHDOG_MILLIS)
        withOwnershipFixture(
            runTimeoutMillis = PARENT_WATCHDOG_MILLIS,
            joinTimeoutMillis = PARENT_WATCHDOG_MILLIS,
            parentDeadlineNanos = parent.deadlineNanos,
        ) { content, data ->
            val fixture = requireNotNull(currentOwnershipFixtureForTest())
            val endpoint = W3HistoryBundleEndpoint.start()
            val failures = IdentitySafeFailureAccumulator()
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val owner = ServerResourceOwner()
            val ownerCloseStarted = AtomicBoolean(false)
            val ownerClosed = AtomicBoolean(false)
            val ownerCloseFailure = AtomicReference<Throwable?>()
            val ownerCloseThread = AtomicReference<Thread?>()

            fun startOwnerClose() {
                if (!ownerCloseStarted.compareAndSet(false, true)) return
                val closeWorker = thread(start = false, name = "plainbase-stage0c-w3-owner-close") {
                    try {
                        owner.close()
                    } catch (failure: Throwable) {
                        ownerCloseFailure.compareAndSet(null, failure)
                    } finally {
                        ownerClosed.set(true)
                    }
                }
                ownerCloseThread.set(closeWorker)
                fixture.track("W3 owner close worker", closeWorker, { closeWorker.interrupt() }) { !closeWorker.isAlive }
                closeWorker.start()
            }

            fun closeOwner() {
                startOwnerClose()
                val closeWorker = ownerCloseThread.get()
                if (closeWorker != null && closeWorker !== Thread.currentThread()) {
                    check(parent.join(closeWorker, "W3 owner close cleanup", parent.remainingMillis())) {
                        "W3 owner close worker survived its parent deadline"
                    }
                }
            }

            fixture.track("W3 owner", owner, ::closeOwner) { ownerClosed.get() }
            fixture.track("W3 endpoint", endpoint, endpoint::close, endpoint::isComplete)

            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            val lockClosed = AtomicBoolean(false)
            val lockFailure = AtomicReference<Throwable?>()
            fun closeLock() {
                events += "data-dir-lock-close-enter"
                try {
                    lock.close()
                } catch (failure: Throwable) {
                    lockFailure.compareAndSet(null, failure)
                    throw failure
                } finally {
                    lockClosed.set(true)
                    events += "data-dir-lock-close-exit"
                }
            }
            owner.own(ServerResourcePhase.DATA_DIR_LOCK, lock) { closeLock() }
            fixture.track("W3 DATA_DIR lock", lock, ::closeLock) { lockClosed.get() }

            val release = CountDownLatch(1)
            val graceExhausted = CountDownLatch(1)
            val graceExhaustedInvocations = AtomicInteger()
            val graceExhaustedAtNanos = AtomicLong()
            val heldWorkReleasedAtNanos = AtomicLong()
            val shipWorkerRef = AtomicReference<Thread?>()
            val executorShutdownEntered = CountDownLatch(1)
            val executorShutdownAtNanos = AtomicLong()
            val executorTerminated = CountDownLatch(1)
            val executorTerminatedAtNanos = AtomicLong()
            val allowExecutorTermination = CountDownLatch(1)
            val deferredInterrupt = AtomicBoolean(false)
            val shipTaskCompleted = CountDownLatch(1)
            val shipTaskFailure = AtomicReference<Throwable?>()
            val shipTaskEnteredAfterExecuteInterrupted = AtomicBoolean(false)
            fun releaseHeldWork() {
                heldWorkReleasedAtNanos.compareAndSet(0L, System.nanoTime())
                release.countDown()
            }
            val underlyingExecutor = object : ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                { runnable ->
                    Thread(runnable, "plainbase-stage0c-w3-ship").also {
                        it.isDaemon = false
                        shipWorkerRef.set(it)
                    }
                },
            ) {
                override fun terminated() {
                    executorTerminatedAtNanos.set(System.nanoTime())
                    executorTerminated.countDown()
                    super.terminated()
                }

                override fun afterExecute(runnable: Runnable?, thrown: Throwable?) {
                    if (Thread.currentThread().isInterrupted) shipTaskEnteredAfterExecuteInterrupted.set(true)
                    var interrupted = false
                    while (true) {
                        try {
                            if (allowExecutorTermination.await(100, TimeUnit.MILLISECONDS)) break
                        } catch (caught: InterruptedException) {
                            interrupted = true
                            Thread.interrupted()
                        }
                    }
                    if (interrupted || deferredInterrupt.getAndSet(false)) Thread.currentThread().interrupt()
                    super.afterExecute(runnable, thrown)
                }
            }
            val recordingExecutor = ShutdownRecordingExecutor(
                delegate = underlyingExecutor,
                shutdownEntered = executorShutdownEntered,
                shutdownAtNanos = executorShutdownAtNanos,
                taskCompleted = shipTaskCompleted,
                taskFailure = shipTaskFailure,
            )
            fixture.track(
                "W3 ship executor",
                underlyingExecutor,
                close = {
                    releaseHeldWork()
                    allowExecutorTermination.countDown()
                    recordingExecutor.shutdown()
                    while (!underlyingExecutor.isTerminated) {
                        parent.check("W3 ship executor cleanup")
                        underlyingExecutor.awaitTermination(parent.remainingMillis(100).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                    }
                },
                complete = { underlyingExecutor.isTerminated },
            )

            val firstPutEntered = CountDownLatch(1)
            val finalPutEntered = CountDownLatch(1)
            val firstDelegateCompleted = CountDownLatch(1)
            val firstDelegateSucceeded = AtomicBoolean(false)
            val firstBundleExists = AtomicBoolean(false)
            val firstBundleBytes = AtomicReference<ByteArray?>()
            val firstBundlePath = AtomicReference<Path?>()
            val firstDelegateFailure = AtomicReference<Throwable?>()
            val finalDelegateCompleted = CountDownLatch(1)
            val finalDelegateSucceeded = AtomicBoolean(false)
            val finalDelegateFailure = AtomicReference<Throwable?>()
            val executorTerminatedBeforeFinal = AtomicBoolean(false)
            val realClientRef = AtomicReference<S3ObjectClient>()
            val wrappedClientRef = AtomicReference<W3HistoryBundleClient>()
            val config = objectConfigForW3(content, data, endpoint.url)
            val store = ObjectContentStoreFactory.buildWithClient(
                config = config,
                ignoreRules = IgnoreRules(),
                dirtyPaths = { emptySet() },
                isDirty = { false },
                rowsAtStart = { RowsAtStart(emptySet(), BindingEpoch(0)) },
                clientFactory = { clientConfig ->
                    S3ObjectClient(clientConfig).also(realClientRef::set).let { real ->
                        W3HistoryBundleClient(
                            delegate = real,
                            holdFirstPut = true,
                            firstPutEntered = firstPutEntered,
                            release = release,
                            firstDelegateCompleted = firstDelegateCompleted,
                            firstDelegateSucceeded = firstDelegateSucceeded,
                            firstBundleExists = firstBundleExists,
                            firstBundleBytes = firstBundleBytes,
                            firstBundlePath = firstBundlePath,
                            firstDelegateFailure = firstDelegateFailure,
                            deferredInterrupt = deferredInterrupt,
                            finalDelegateCompleted = finalDelegateCompleted,
                            finalDelegateSucceeded = finalDelegateSucceeded,
                            finalDelegateFailure = finalDelegateFailure,
                            finalPutEntered = finalPutEntered,
                            executor = underlyingExecutor,
                            executorTerminatedBeforeFinal = executorTerminatedBeforeFinal,
                        ).also(wrappedClientRef::set)
                    }
                },
            )
            val realClient = requireNotNull(realClientRef.get())
            val wrappedClient = requireNotNull(wrappedClientRef.get())
            val transportClosed = AtomicBoolean(false)
            val transportFailure = AtomicReference<Throwable?>()
            fun closeTransport() {
                events += "object-transport-close-enter"
                try {
                    store.close()
                } catch (failure: Throwable) {
                    transportFailure.compareAndSet(null, failure)
                    throw failure
                } finally {
                    transportClosed.set(true)
                    events += "object-transport-close-exit"
                }
            }
            owner.own(ServerResourcePhase.OBJECT_TRANSPORT, store) { closeTransport() }
            fixture.track("W3 object store", store, ::closeTransport) { transportClosed.get() }
            fixture.track("W3 retained S3 client", realClient, realClient::close) { !realClient.transportActiveForTest() }

            Files.createDirectories(data.resolve("mirror"))
            val gitHome = data.resolve("git-home")
            val tmpDir = data.resolve("tmp")
            val sentinelPath = data.resolve("restore-pending")
            val mirrorRoot = data.resolve("mirror")
            val exec = GitExecutor(mirrorRoot, gitHome)
            val locks = GitRepoLocks()
            val provider = GitCliHistoryProvider(
                exec = exec,
                workTree = mirrorRoot,
                gitHome = gitHome,
                defaultAuthor = testIdentity(),
                defaultCommitter = testIdentity(),
                clock = fixedClock(),
                repoPath = { path -> store.mirror.resolveRepoRelativePath(path) },
                maintenance = {},
                repoWriteMonitor = locks.repoWrite,
                objectMode = true,
            )
            provider.prepare()
            provider.commit(TreePath.require("w3-ship.md"), "ship\n".toByteArray())
            val seededHead = exec.run(listOf("rev-parse", "HEAD"))
            check(seededHead.ok) { "seed commit did not produce HEAD: ${seededHead.stderr}" }
            val expectedHead = requireNotNull(GitExecutor.parseSha(seededHead.stdout))
            val bundleDr = GitBundleDr(
                exec = exec,
                objectStore = store,
                mirrorRoot = mirrorRoot,
                tmpDir = tmpDir,
                sentinelPath = sentinelPath,
                identity = testIdentity(),
                clock = fixedClock(),
                repoPath = { path -> store.mirror.resolveRepoRelativePath(path) },
                gitHome = gitHome,
                locks = locks,
                shipExecutor = recordingExecutor,
            )
            bundleDr.configureShutdownWaitForTest(TEST_GRACE_MILLIS) {
                graceExhaustedAtNanos.set(System.nanoTime())
                graceExhaustedInvocations.incrementAndGet()
                graceExhausted.countDown()
            }
            val drClosed = AtomicBoolean(false)
            val drCloseCompletedAtNanos = AtomicLong()
            val drFailure = AtomicReference<Throwable?>()
            fun closeDr() {
                events += "dr-close-enter"
                try {
                    bundleDr.close()
                    drCloseCompletedAtNanos.set(System.nanoTime())
                } catch (failure: Throwable) {
                    drFailure.compareAndSet(null, failure)
                    throw failure
                } finally {
                    drClosed.set(true)
                    events += "dr-close-exit"
                }
            }
            owner.own(ServerResourcePhase.DISASTER_RECOVERY, bundleDr) { closeDr() }
            fixture.track("W3 DR", bundleDr, ::closeDr) { drClosed.get() && underlyingExecutor.isTerminated }

            var interrupted = false
            var shipFacts: W3ShipRunFacts? = null
            try {
                shipFacts = boundedOwnershipRun {
                    val cleanupFailures = IdentitySafeFailureAccumulator()
                    var cleanupInterrupted = false
                    var closeWorker: Thread? = null
                    try {
                        bundleDr.onCommitAsync()
                        check(parent.await(firstPutEntered, "W3 named history.bundle PUT entry", 20_000)) {
                            "W3 named history.bundle PUT did not enter"
                        }
                        val shipWorker = requireNotNull(shipWorkerRef.get())
                        fixture.track("W3 admitted ship worker", shipWorker, { shipWorker.interrupt() }) { !shipWorker.isAlive }
                        startOwnerClose()
                        closeWorker = requireNotNull(ownerCloseThread.get())
                        check(parent.await(executorShutdownEntered, "W3 ship executor shutdown entry", 10_000)) {
                            "W3 ship executor shutdown did not enter"
                        }
                        check(
                            parent.await(
                                graceExhausted,
                                "W3 ship executor grace exhaustion",
                                SHUTDOWN_LATCH_TIMEOUT_MILLIS,
                            ),
                        ) {
                            "W3 ship executor did not reach its indefinite wait"
                        }
                        val graceExhaustedAt = graceExhaustedAtNanos.get()
                        check(
                            graceExhaustedAt - executorShutdownAtNanos.get() >=
                                TimeUnit.MILLISECONDS.toNanos(2L * TEST_GRACE_MILLIS),
                        ) { "W3 ship executor grace callback fired before both grace periods elapsed" }
                        closeWorker.isAlive.shouldBeTrue()
                        realClient.transportActiveForTest().shouldBeTrue()
                        events.contains("object-transport-close-enter").shouldBeFalse()
                        events.contains("data-dir-lock-close-enter").shouldBeFalse()
                        endpoint.puts shouldBe emptyList()
                        firstBundleExists.get().shouldBeTrue()
                        requireNotNull(firstBundleBytes.get()).isNotEmpty().shouldBeTrue()
                        probeDataDirLock(data, parent, fixture) shouldBe "HELD"

                        releaseHeldWork()
                        check(parent.await(firstDelegateCompleted, "W3 delegated history.bundle PUT completion")) {
                            "W3 delegated history.bundle PUT did not complete"
                        }
                        check(parent.await(shipTaskCompleted, "W3 complete DR ship runnable")) {
                            "W3 DR ship runnable did not complete"
                        }
                        shipTaskFailure.get() shouldBe null
                        shipTaskEnteredAfterExecuteInterrupted.get().shouldBeFalse()
                        finalPutEntered.await(1, TimeUnit.SECONDS)
                        allowExecutorTermination.countDown()
                        check(parent.join(closeWorker, "W3 owner close", parent.remainingMillis())) {
                            "W3 owner close did not terminate"
                        }
                        graceExhaustedInvocations.get() shouldBe 1
                        check(drCloseCompletedAtNanos.get() > heldWorkReleasedAtNanos.get()) {
                            "W3 DR close completed before held work was released"
                        }
                        probeDataDirLock(data, parent, fixture) shouldBe "AVAILABLE"
                    } catch (failure: Throwable) {
                        if (failure is InterruptedException) {
                            cleanupInterrupted = true
                            Thread.interrupted()
                        }
                        cleanupFailures.add(failure)
                    } finally {
                        releaseHeldWork()
                        allowExecutorTermination.countDown()
                        if (!ownerCloseStarted.get()) startOwnerClose()
                        closeWorker = ownerCloseThread.get()
                        if (closeWorker != null && closeWorker !== Thread.currentThread()) {
                            try {
                                if (!parent.join(closeWorker, "W3 owner close failure cleanup", parent.remainingMillis())) {
                                    cleanupFailures.add(IllegalStateException("W3 owner close worker survived bounded cleanup"))
                                }
                            } catch (failure: InterruptedException) {
                                cleanupInterrupted = true
                                Thread.interrupted()
                                cleanupFailures.add(failure)
                            }
                        }
                        if (ownerClosed.get()) runCatching { endpoint.close() }.onFailure(cleanupFailures::add)
                        if (cleanupInterrupted) Thread.currentThread().interrupt()
                    }
                    cleanupFailures.failure?.let { throw it }
                    W3ShipRunFacts(
                        firstBundlePath = firstBundlePath.get(),
                        firstBundleBytes = firstBundleBytes.get(),
                    )
                }
                val facts = requireNotNull(shipFacts)
                check(facts.firstBundlePath != null) { "W3 first bundle path was not retained" }
                check(facts.firstBundleBytes != null) { "W3 first bundle bytes were not retained" }
            } catch (failure: Throwable) {
                if (failure is InterruptedException) {
                    interrupted = true
                    Thread.interrupted()
                }
                failures.add(failure)
            } finally {
                releaseHeldWork()
                allowExecutorTermination.countDown()
                if (!ownerCloseStarted.get()) startOwnerClose()
                val closeWorker = ownerCloseThread.get()
                if (closeWorker != null && closeWorker !== Thread.currentThread()) {
                    try {
                        if (!parent.join(closeWorker, "W3 outer owner close cleanup", parent.remainingMillis())) {
                            failures.add(IllegalStateException("W3 outer owner close worker survived bounded cleanup"))
                        }
                    } catch (failure: InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                        failures.add(failure)
                    }
                }
                if (ownerClosed.get()) runCatching { endpoint.close() }.onFailure(failures::add)
                if (interrupted) Thread.currentThread().interrupt()
            }

            failures.failure?.let { throw it }
            ownerCloseFailure.get()?.let { throw it }
            drFailure.get()?.let { throw it }
            transportFailure.get()?.let { throw it }
            lockFailure.get()?.let { throw it }
            endpoint.failure.get()?.let { throw it }
            firstDelegateFailure.get()?.let { throw it }
            finalDelegateFailure.get()?.let { throw it }
            val facts = requireNotNull(shipFacts)
            firstDelegateSucceeded.get().shouldBeTrue()
            finalDelegateCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
            finalDelegateSucceeded.get().shouldBeTrue()
            executorTerminated.await(1, TimeUnit.SECONDS).shouldBeTrue()
            wrappedClient.firstPutCount shouldBe 2
            val receipts = endpoint.puts.toList()
            receipts.size shouldBe 2
            receipts.all { it.path.endsWith("/.plainbase/history.bundle") }.shouldBeTrue()
            val bundleBytes = requireNotNull(facts.firstBundleBytes)
            receipts.first().bytes.contentEquals(bundleBytes).shouldBeTrue()
            receipts.forEachIndexed { index, receipt ->
                verifyBundleReceipt(exec, receipt, expectedHead, data.resolve("verify-history-$index.bundle"))
            }
            executorTerminatedBeforeFinal.get().shouldBeTrue()
            realClient.transportActiveForTest().shouldBeFalse()
            underlyingExecutor.isTerminated.shouldBeTrue()
            lockClosed.get().shouldBeTrue()
            probeDataDirLock(data, parent, fixture) shouldBe "AVAILABLE"
            val eventSnapshot = synchronized(events) { events.toList() }
            check(eventSnapshot.indexOf("dr-close-exit") < eventSnapshot.indexOf("object-transport-close-enter")) {
                "object transport closed before DR close completed: $eventSnapshot"
            }
            check(eventSnapshot.indexOf("object-transport-close-exit") < eventSnapshot.indexOf("data-dir-lock-close-enter")) {
                "DATA_DIR lock closed before object transport: $eventSnapshot"
            }
        }
    }

    test("W3: the real cadence callback drains before final flush and transport close") {
        val parent = Stage0cParentDeadline(PARENT_WATCHDOG_MILLIS)
        withOwnershipFixture(
            runTimeoutMillis = PARENT_WATCHDOG_MILLIS,
            joinTimeoutMillis = PARENT_WATCHDOG_MILLIS,
            parentDeadlineNanos = parent.deadlineNanos,
        ) { content, data ->
            val graceExhausted = CountDownLatch(1)
            val graceExhaustedInvocations = AtomicInteger()
            val graceExhaustedAtNanos = AtomicLong()
            val realAlarm = ExecutorAlarm(threadName = "plainbase-stage0c-w3-cadence")
            realAlarm.configureShutdownWaitForTest(TEST_GRACE_MILLIS) {
                graceExhaustedAtNanos.set(System.nanoTime())
                graceExhaustedInvocations.incrementAndGet()
                graceExhausted.countDown()
            }
            val alarm = W3HoldingAlarm(realAlarm)
            val graph = W3RealGraph(content, data, parent, alarm, holdFirstPut = false)
            val failures = IdentitySafeFailureAccumulator()
            var interrupted = false
            boundedOwnershipRun {
                try {
                graph.provider.prepare()
                graph.provider.commit(TreePath.require("w3-cadence.md"), "cadence-1\n".toByteArray())
                val firstHead = graph.head()
                graph.dr.onCommit()
                check(parent.await(graph.client.firstDelegateCompleted, "W3 cadence warm-up upload")) {
                    "W3 cadence warm-up upload did not complete"
                }
                graph.client.firstDelegateSucceeded.get().shouldBeTrue()

                graph.provider.commit(TreePath.require("w3-cadence.md"), "cadence-2\n".toByteArray())
                val finalHead = graph.head()
                graph.dr.onCommitAsync()
                check(parent.await(alarm.workerEntered, "W3 supplied cadence callback entry")) {
                    "W3 supplied cadence callback did not enter"
                }
                graph.startOwnerClose()
                check(parent.await(alarm.closeEntered, "W3 delegated cadence alarm close entry")) {
                    "W3 delegated cadence alarm close did not enter"
                }
                val closeEntry = alarm.closeEnteredAtNanos.get()
                check(
                    parent.await(
                        graceExhausted,
                        "W3 cadence alarm grace exhaustion",
                        SHUTDOWN_LATCH_TIMEOUT_MILLIS,
                    ),
                ) {
                    "W3 cadence alarm did not reach its indefinite wait"
                }
                val graceExhaustedAt = graceExhaustedAtNanos.get()
                check(
                    graceExhaustedAt - closeEntry >= TimeUnit.MILLISECONDS.toNanos(2L * TEST_GRACE_MILLIS),
                ) { "W3 cadence alarm grace callback fired before both grace periods elapsed" }

                alarm.requestedDelayMillis.get() shouldBe GitBundleDr.SHIP_MAX_LATENCY_MILLIS
                alarm.delegatedDelayMillis.get() shouldBe 0L
                alarm.suppliedAction.get() shouldBe alarm.executedAction.get()
                alarm.callbackInvocations.get() shouldBe 0
                alarm.callbackCompleted.count shouldBe 1L
                alarm.real.isTerminatedForTest().shouldBeFalse()
                requireNotNull(graph.ownerCloseThread.get()).isAlive.shouldBeTrue()
                graph.executorShutdownEntered.count shouldBe 1L
                graph.endpoint.puts.size shouldBe 1
                graph.client.finalPutEntered.count shouldBe 1L
                graph.events.contains("object-transport-close-enter").shouldBeFalse()
                graph.events.contains("data-dir-lock-close-enter").shouldBeFalse()
                graph.probe() shouldBe "HELD"

                alarm.releaseHeldWork()
                check(parent.await(alarm.callbackCompleted, "W3 supplied cadence callback completion")) {
                    "W3 supplied cadence callback did not complete"
                }
                check(parent.await(alarm.closeCompleted, "W3 real cadence alarm termination")) {
                    "W3 real cadence alarm close did not complete"
                }
                graceExhaustedInvocations.get() shouldBe 1
                check(alarm.closeCompletedAtNanos.get() > alarm.heldWorkReleasedAtNanos.get()) {
                    "W3 cadence alarm close completed before held work was released"
                }
                alarm.callbackInvocations.get() shouldBe 1
                alarm.callbackFailure.get() shouldBe null
                (alarm.interruptsObserved.get() > 0).shouldBeTrue()
                alarm.workerFlagRestored.get().shouldBeTrue()
                alarm.real.isTerminatedForTest().shouldBeTrue()
                graph.executorShutdownEntered.await(10, TimeUnit.SECONDS).shouldBeTrue()
                graph.executorTerminated.await(10, TimeUnit.SECONDS).shouldBeTrue()
                graph.allowExecutorTermination.countDown()
                graph.joinOwner(parent, "W3 cadence owner close")
                graph.ownerClosed.get().shouldBeTrue()
                graph.drClosed.get().shouldBeTrue()
                graph.transportClosed.get().shouldBeTrue()
                check(parent.await(graph.client.finalDelegateCompleted, "W3 cadence final upload")) {
                    "W3 cadence final upload did not complete"
                }
                graph.client.finalDelegateSucceeded.get().shouldBeTrue()
                graph.client.executorTerminatedBeforeFinal.get().shouldBeTrue()
                graph.client.firstPutCount shouldBe 2
                val receipts = graph.endpoint.puts.toList()
                receipts.size shouldBe 2
                receipts.all { it.path.endsWith(HISTORY_BUNDLE_SUFFIX) }.shouldBeTrue()
                receipts[0].bytes.contentEquals(requireNotNull(graph.client.firstBundleBytes.get())).shouldBeTrue()
                verifyBundleReceipt(graph.exec, receipts[0], firstHead, data.resolve("verify-cadence-warmup.bundle"))
                verifyBundleReceipt(graph.exec, receipts[1], finalHead, data.resolve("verify-cadence-final.bundle"))
                graph.realClient.transportActiveForTest().shouldBeFalse()
                graph.underlyingExecutor.isTerminated.shouldBeTrue()
                graph.lockClosed.get().shouldBeTrue()
                graph.probe() shouldBe "AVAILABLE"
                (graph.events.indexOf("dr-close-exit") < graph.events.indexOf("object-transport-close-enter")).shouldBeTrue()
                (graph.events.indexOf("object-transport-close-exit") < graph.events.indexOf("data-dir-lock-close-enter")).shouldBeTrue()
                } catch (failure: Throwable) {
                    if (failure is InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                    }
                    failures.add(failure)
                } finally {
                    alarm.releaseHeldWork()
                    graph.allowExecutorTermination.countDown()
                    try {
                        graph.startOwnerClose()
                        try {
                            graph.joinOwner(parent, "W3 cadence failure cleanup")
                        } catch (failure: Throwable) {
                            if (failure is InterruptedException) {
                                interrupted = true
                                Thread.interrupted()
                            }
                            failures.add(failure)
                        }
                        if (graph.ownerClosed.get()) runCatching { graph.endpoint.close() }.onFailure(failures::add)
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                }
                failures.failure?.let { throw it }
                graph.ownerCloseFailure.get()?.let { throw it }
                graph.drFailure.get()?.let { throw it }
                graph.transportFailure.get()?.let { throw it }
                graph.lockFailure.get()?.let { throw it }
                graph.endpoint.failure.get()?.let { throw it }
                graph.client.firstDelegateFailure.get()?.let { throw it }
                graph.client.finalDelegateFailure.get()?.let { throw it }
            }
        }
    }

    test("W3: repeated interrupts during real executor drain preserve the final attempt") {
        withOwnershipFixture(
            runTimeoutMillis = INTERRUPT_WATCHDOG_MILLIS,
            joinTimeoutMillis = INTERRUPT_WATCHDOG_MILLIS,
        ) { content, data ->
            val graph = W3RealGraph(
                content,
                data,
                Stage0cParentDeadline(INTERRUPT_WATCHDOG_MILLIS),
                ExecutorAlarm(threadName = "plainbase-stage0c-w3-repeated-interrupt"),
                holdFirstPut = true,
            )
            val parent = graph.parent
            val failures = IdentitySafeFailureAccumulator()
            var interrupted = false
            boundedOwnershipRun {
                try {
                graph.provider.prepare()
                graph.provider.commit(TreePath.require("w3-interrupt.md"), "interrupt\n".toByteArray())
                val expectedHead = graph.head()
                graph.dr.onCommitAsync()
                check(parent.await(graph.client.firstPutEntered, "W3 admitted interrupted PUT entry")) {
                    "W3 admitted interrupted PUT did not enter"
                }
                graph.startOwnerClose()
                check(parent.await(graph.executorShutdownEntered, "W3 interrupted executor shutdown entry")) {
                    "W3 interrupted executor shutdown did not enter"
                }
                val closeWorker = requireNotNull(graph.executorShutdownThread.get())
                closeWorker.interrupt()
                check(parent.await(graph.firstInterruptedWait, "W3 first interrupted termination wait")) {
                    "W3 first interrupted termination wait was not observed"
                }
                closeWorker.interrupt()
                check(parent.await(graph.secondInterruptedWait, "W3 second interrupted termination wait")) {
                    "W3 second interrupted termination wait was not observed"
                }
                graph.client.firstPutCount shouldBe 1
                graph.client.finalPutEntered.count shouldBe 1L
                graph.endpoint.puts.size shouldBe 0
                requireNotNull(graph.ownerCloseThread.get()).isAlive.shouldBeTrue()
                graph.events.contains("object-transport-close-enter").shouldBeFalse()
                graph.events.contains("data-dir-lock-close-enter").shouldBeFalse()
                graph.release.countDown()
                check(parent.await(graph.client.firstDelegateCompleted, "W3 admitted interrupted PUT completion")) {
                    "W3 admitted interrupted PUT did not complete"
                }
                graph.allowExecutorTermination.countDown()
                check(parent.await(graph.shipTaskCompleted, "W3 admitted ship task completion")) {
                    "W3 admitted ship task did not complete"
                }
                graph.shipTaskFailure.get() shouldBe null
                check(parent.await(graph.executorTerminated, "W3 interrupted executor termination")) {
                    "W3 interrupted executor did not terminate"
                }
                check(parent.await(graph.client.finalDelegateCompleted, "W3 repeated-interrupt final upload")) {
                    "W3 repeated-interrupt final upload did not complete"
                }
                graph.client.finalDelegateSucceeded.get().shouldBeTrue()
                graph.client.executorTerminatedBeforeFinal.get().shouldBeTrue()
                graph.joinOwner(parent, "W3 repeated-interrupt owner close")
                graph.ownerClosed.get().shouldBeTrue()
                graph.drClosed.get().shouldBeTrue()
                graph.transportClosed.get().shouldBeTrue()
                graph.drReturnedInterrupt.get().shouldBeTrue()
                graph.client.firstPutCount shouldBe 2
                val receipts = graph.endpoint.puts.toList()
                receipts.size shouldBe 2
                receipts.all { it.path.endsWith(HISTORY_BUNDLE_SUFFIX) }.shouldBeTrue()
                receipts[0].bytes.contentEquals(requireNotNull(graph.client.firstBundleBytes.get())).shouldBeTrue()
                verifyBundleReceipt(graph.exec, receipts[0], expectedHead, data.resolve("verify-interrupt-admitted.bundle"))
                verifyBundleReceipt(graph.exec, receipts[1], expectedHead, data.resolve("verify-interrupt-final.bundle"))
                graph.realClient.transportActiveForTest().shouldBeFalse()
                graph.underlyingExecutor.isTerminated.shouldBeTrue()
                graph.lockClosed.get().shouldBeTrue()
                graph.probe() shouldBe "AVAILABLE"
                (graph.events.indexOf("dr-close-exit") < graph.events.indexOf("object-transport-close-enter")).shouldBeTrue()
                (graph.events.indexOf("object-transport-close-exit") < graph.events.indexOf("data-dir-lock-close-enter")).shouldBeTrue()
                } catch (failure: Throwable) {
                    if (failure is InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                    }
                    failures.add(failure)
                } finally {
                    graph.release.countDown()
                    graph.allowExecutorTermination.countDown()
                    try {
                        graph.startOwnerClose()
                        try {
                            graph.joinOwner(parent, "W3 repeated-interrupt failure cleanup")
                        } catch (failure: Throwable) {
                            if (failure is InterruptedException) {
                                interrupted = true
                                Thread.interrupted()
                            }
                            failures.add(failure)
                        }
                        if (graph.ownerClosed.get()) runCatching { graph.endpoint.close() }.onFailure(failures::add)
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                }
                failures.failure?.let { throw it }
                graph.ownerCloseFailure.get()?.let { throw it }
                graph.drFailure.get()?.let { throw it }
                graph.transportFailure.get()?.let { throw it }
                graph.lockFailure.get()?.let { throw it }
                graph.endpoint.failure.get()?.let { throw it }
                graph.client.firstDelegateFailure.get()?.let { throw it }
                graph.client.finalDelegateFailure.get()?.let { throw it }
            }
        }
    }
})

private const val TEST_GRACE_MILLIS = 100L
private const val SHUTDOWN_LATCH_TIMEOUT_MILLIS = 5_000L
private const val PARENT_WATCHDOG_MILLIS = 180_000L
private const val INTERRUPT_WATCHDOG_MILLIS = 45_000L
private const val HISTORY_BUNDLE_SUFFIX = "/.plainbase/history.bundle"

private data class W3PutReceipt(val path: String, val bytes: ByteArray)

private class W3HistoryBundleEndpoint private constructor(
    private val server: EmbeddedServer<*, *>,
    val url: String,
    val puts: java.util.concurrent.CopyOnWriteArrayList<W3PutReceipt>,
    val failure: AtomicReference<Throwable?>,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun isComplete(): Boolean = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    companion object {
        fun start(): W3HistoryBundleEndpoint {
            val puts = java.util.concurrent.CopyOnWriteArrayList<W3PutReceipt>()
            val failure = AtomicReference<Throwable?>()
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                routing {
                    route("{path...}") {
                        handle {
                            val path = call.request.uri.substringBefore('?')
                            if (call.request.httpMethod == HttpMethod.Put && path.endsWith(HISTORY_BUNDLE_SUFFIX)) {
                                try {
                                    puts += W3PutReceipt(path, call.receiveChannel().toByteArray())
                                    call.response.header(HttpHeaders.ETag, "\"w3-${puts.size}\"")
                                    call.respond(HttpStatusCode.OK)
                                } catch (caught: Throwable) {
                                    failure.compareAndSet(null, caught)
                                    call.respond(HttpStatusCode.InternalServerError)
                                }
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        }
                    }
                }
            }.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            return W3HistoryBundleEndpoint(server, "http://127.0.0.1:$port", puts, failure)
        }
    }
}

private class ShutdownRecordingExecutor(
    private val delegate: ExecutorService,
    private val shutdownEntered: CountDownLatch,
    private val shutdownAtNanos: AtomicLong,
    private val taskCompleted: CountDownLatch,
    private val taskFailure: AtomicReference<Throwable?>,
) : ExecutorService by delegate {
    override fun execute(command: Runnable) {
        delegate.execute {
            try {
                command.run()
            } catch (failure: Throwable) {
                taskFailure.compareAndSet(null, failure)
                throw failure
            } finally {
                taskCompleted.countDown()
            }
        }
    }

    override fun shutdown() {
        shutdownAtNanos.compareAndSet(0L, System.nanoTime())
        shutdownEntered.countDown()
        delegate.shutdown()
    }
}

private class W3HoldingAlarm(val real: ExecutorAlarm) : RebuildScheduler.Alarm, AutoCloseable {
    val release = CountDownLatch(1)
    val requestedDelayMillis = AtomicLong(-1L)
    val delegatedDelayMillis = AtomicLong(-1L)
    val suppliedAction = AtomicReference<(() -> Unit)?>(null)
    val executedAction = AtomicReference<(() -> Unit)?>(null)
    val workerThread = AtomicReference<Thread?>()
    val workerEntered = CountDownLatch(1)
    val callbackCompleted = CountDownLatch(1)
    val callbackFailure = AtomicReference<Throwable?>()
    val callbackInvocations = AtomicInteger()
    val interruptsObserved = AtomicInteger()
    val workerFlagRestored = AtomicBoolean(false)
    val closeEntered = CountDownLatch(1)
    val closeCompleted = CountDownLatch(1)
    val closeEnteredAtNanos = AtomicLong()
    val closeCompletedAtNanos = AtomicLong()
    val heldWorkReleasedAtNanos = AtomicLong()

    fun releaseHeldWork() {
        heldWorkReleasedAtNanos.compareAndSet(0L, System.nanoTime())
        release.countDown()
    }

    override fun after(delayMillis: Long, action: () -> Unit) {
        check(suppliedAction.compareAndSet(null, action)) { "W3 cadence alarm was armed more than once" }
        requestedDelayMillis.set(delayMillis)
        delegatedDelayMillis.set(0L)
        real.after(0L) {
            val current = Thread.currentThread()
            workerThread.set(current)
            executedAction.set(action)
            workerEntered.countDown()
            var interrupted = false
            while (true) {
                try {
                    if (release.await(100, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                    interruptsObserved.incrementAndGet()
                    Thread.interrupted()
                }
            }
            try {
                callbackInvocations.incrementAndGet()
                action()
            } catch (failure: Throwable) {
                callbackFailure.compareAndSet(null, failure)
                throw failure
            } finally {
                if (interrupted) current.interrupt()
                workerFlagRestored.set(!interrupted || current.isInterrupted)
                callbackCompleted.countDown()
            }
        }
    }

    override fun close() {
        closeEnteredAtNanos.compareAndSet(0L, System.nanoTime())
        closeEntered.countDown()
        try {
            real.close()
            closeCompletedAtNanos.set(System.nanoTime())
        } finally {
            closeCompleted.countDown()
        }
    }
}

private class W3ShipExecutor(
    private val delegate: ExecutorService,
    private val allowTermination: CountDownLatch,
    private val deferredInterrupt: AtomicBoolean,
    val shutdownEntered: CountDownLatch,
    val shutdownAtNanos: AtomicLong,
    val shutdownThread: AtomicReference<Thread?>,
    val terminated: CountDownLatch,
    val taskCompleted: CountDownLatch,
    val taskFailure: AtomicReference<Throwable?>,
) : ExecutorService by delegate {
    val firstInterruptedWait = CountDownLatch(1)
    val secondInterruptedWait = CountDownLatch(1)
    private val interruptedWaits = AtomicInteger()

    override fun execute(command: Runnable) {
        delegate.execute {
            try {
                command.run()
            } catch (failure: Throwable) {
                taskFailure.compareAndSet(null, failure)
                throw failure
            } finally {
                taskCompleted.countDown()
            }
        }
    }

    override fun shutdown() {
        shutdownAtNanos.compareAndSet(0L, System.nanoTime())
        shutdownThread.compareAndSet(null, Thread.currentThread())
        shutdownEntered.countDown()
        delegate.shutdown()
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = try {
        delegate.awaitTermination(timeout, unit)
    } catch (interrupted: InterruptedException) {
        when (interruptedWaits.incrementAndGet()) {
            1 -> firstInterruptedWait.countDown()
            2 -> secondInterruptedWait.countDown()
        }
        throw interrupted
    }

    private fun recordAfterExecuteInterrupt(): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        var interrupted = false
        while (true) {
            try {
                if (allowTermination.await(100, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
        }
        return interrupted
    }

    fun afterExecute() {
        val interrupted = recordAfterExecuteInterrupt()
        if (interrupted || deferredInterrupt.getAndSet(false)) Thread.currentThread().interrupt()
    }
}

private class W3RealGraph(
    content: Path,
    data: Path,
    val parent: Stage0cParentDeadline,
    val alarm: RebuildScheduler.Alarm,
    holdFirstPut: Boolean,
) {
    private val fixture = requireNotNull(currentOwnershipFixtureForTest())
    private val dataDirPath = data
    val endpoint = W3HistoryBundleEndpoint.start()
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    val owner = ServerResourceOwner()
    val ownerCloseStarted = AtomicBoolean(false)
    val ownerClosed = AtomicBoolean(false)
    val ownerCloseFailure = AtomicReference<Throwable?>()
    val ownerCloseThread = AtomicReference<Thread?>()
    val drClosed = AtomicBoolean(false)
    val drFailure = AtomicReference<Throwable?>()
    val drReturnedInterrupt = AtomicBoolean(false)
    val transportClosed = AtomicBoolean(false)
    val transportFailure = AtomicReference<Throwable?>()
    val lockClosed = AtomicBoolean(false)
    val lockFailure = AtomicReference<Throwable?>()
    val release = CountDownLatch(1)
    val allowExecutorTermination = CountDownLatch(1)
    val deferredInterrupt = AtomicBoolean(false)
    val executorShutdownEntered = CountDownLatch(1)
    val executorShutdownAtNanos = AtomicLong()
    val executorShutdownThread = AtomicReference<Thread?>()
    val executorTerminated = CountDownLatch(1)
    val executorTerminatedAtNanos = AtomicLong()
    val shipTaskCompleted = CountDownLatch(1)
    val shipTaskFailure = AtomicReference<Throwable?>()
    val firstInterruptedWait: CountDownLatch
        get() = shipExecutor.firstInterruptedWait
    val secondInterruptedWait: CountDownLatch
        get() = shipExecutor.secondInterruptedWait
    val underlyingExecutor: ThreadPoolExecutor
    val shipExecutor: W3ShipExecutor
    val realClient: S3ObjectClient
    val client: W3HistoryBundleClient
    val store: com.plainbase.frameworks.objectstore.ObjectContentStore
    val exec: GitExecutor
    val locks: GitRepoLocks
    val provider: GitCliHistoryProvider
    val dr: GitBundleDr
    val realAlarm: ExecutorAlarm

    init {
        fixture.track("W3 endpoint", endpoint, endpoint::close, endpoint::isComplete)
        fixture.track("W3 owner", owner, ::closeOwner) { ownerClosed.get() }

        val lock = requireNotNull(DataDirLock.tryAcquire(data))
        owner.own(ServerResourcePhase.DATA_DIR_LOCK, lock) { closeLock(lock) }
        fixture.track("W3 DATA_DIR lock", lock, { closeLock(lock) }) { lockClosed.get() }

        underlyingExecutor = object : ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { runnable -> Thread(runnable, "plainbase-stage0c-w3-real-ship").also { it.isDaemon = false } },
        ) {
            override fun terminated() {
                executorTerminatedAtNanos.set(System.nanoTime())
                executorTerminated.countDown()
                super.terminated()
            }

            override fun afterExecute(runnable: Runnable?, thrown: Throwable?) {
                shipExecutor.afterExecute()
                super.afterExecute(runnable, thrown)
            }
        }
        shipExecutor = W3ShipExecutor(
            delegate = underlyingExecutor,
            allowTermination = allowExecutorTermination,
            deferredInterrupt = deferredInterrupt,
            shutdownEntered = executorShutdownEntered,
            shutdownAtNanos = executorShutdownAtNanos,
            shutdownThread = executorShutdownThread,
            terminated = executorTerminated,
            taskCompleted = shipTaskCompleted,
            taskFailure = shipTaskFailure,
        )
        fixture.track(
            "W3 ship executor",
            underlyingExecutor,
            close = {
                release.countDown()
                allowExecutorTermination.countDown()
                shipExecutor.shutdown()
                while (!underlyingExecutor.isTerminated) {
                    parent.check("W3 ship executor cleanup")
                    underlyingExecutor.awaitTermination(parent.remainingMillis(100).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                }
            },
            complete = { underlyingExecutor.isTerminated },
        )

        val realClientRef = AtomicReference<S3ObjectClient>()
        val wrappedClientRef = AtomicReference<W3HistoryBundleClient>()
        store = ObjectContentStoreFactory.buildWithClient(
            config = objectConfigForW3(content, data, endpoint.url),
            ignoreRules = IgnoreRules(),
            dirtyPaths = { emptySet() },
            isDirty = { false },
            rowsAtStart = { RowsAtStart(emptySet(), BindingEpoch(0)) },
            clientFactory = { clientConfig ->
                S3ObjectClient(clientConfig).also(realClientRef::set).let { real ->
                    W3HistoryBundleClient(
                        delegate = real,
                        holdFirstPut = holdFirstPut,
                        firstPutEntered = CountDownLatch(1),
                        release = release,
                        firstDelegateCompleted = CountDownLatch(1),
                        firstDelegateSucceeded = AtomicBoolean(false),
                        firstBundleExists = AtomicBoolean(false),
                        firstBundleBytes = AtomicReference(),
                        firstBundlePath = AtomicReference(),
                        firstDelegateFailure = AtomicReference(),
                        deferredInterrupt = deferredInterrupt,
                        finalDelegateCompleted = CountDownLatch(1),
                        finalDelegateSucceeded = AtomicBoolean(false),
                        finalDelegateFailure = AtomicReference(),
                        finalPutEntered = CountDownLatch(1),
                        executor = underlyingExecutor,
                        executorTerminatedBeforeFinal = AtomicBoolean(false),
                    ).also(wrappedClientRef::set)
                }
            },
        )
        realClient = requireNotNull(realClientRef.get())
        client = requireNotNull(wrappedClientRef.get())
        owner.own(ServerResourcePhase.OBJECT_TRANSPORT, store) { closeTransport() }
        fixture.track("W3 object store", store, ::closeTransport) { transportClosed.get() }
        fixture.track("W3 retained S3 client", realClient, realClient::close) { !realClient.transportActiveForTest() }

        val mirrorRoot = data.resolve("mirror")
        Files.createDirectories(mirrorRoot)
        val gitHome = data.resolve("git-home")
        val tmpDir = data.resolve("tmp")
        val sentinelPath = data.resolve("restore-pending")
        exec = GitExecutor(mirrorRoot, gitHome)
        locks = GitRepoLocks()
        provider = GitCliHistoryProvider(
            exec = exec,
            workTree = mirrorRoot,
            gitHome = gitHome,
            defaultAuthor = testIdentity(),
            defaultCommitter = testIdentity(),
            clock = fixedClock(),
            repoPath = { path -> store.mirror.resolveRepoRelativePath(path) },
            maintenance = {},
            repoWriteMonitor = locks.repoWrite,
            objectMode = true,
        )
        realAlarm = when (alarm) {
            is W3HoldingAlarm -> alarm.real
            is ExecutorAlarm -> alarm
            else -> error("W3 requires a real ExecutorAlarm")
        }
        dr = GitBundleDr(
            exec = exec,
            objectStore = store,
            mirrorRoot = mirrorRoot,
            tmpDir = tmpDir,
            sentinelPath = sentinelPath,
            identity = testIdentity(),
            clock = fixedClock(),
            repoPath = { path -> store.mirror.resolveRepoRelativePath(path) },
            gitHome = gitHome,
            locks = locks,
            alarm = alarm,
            shipExecutor = shipExecutor,
        )
        owner.own(ServerResourcePhase.DISASTER_RECOVERY, dr) { closeDr() }
        fixture.track("W3 DR", dr, ::closeDr) {
            drClosed.get() && underlyingExecutor.isTerminated && realAlarm.isTerminatedForTest()
        }
    }

    fun head(): String {
        val result = exec.run(listOf("rev-parse", "HEAD"))
        check(result.ok) { "real W3 commit did not produce HEAD: ${result.stderr}" }
        return requireNotNull(GitExecutor.parseSha(result.stdout))
    }

    fun probe(): String = probeDataDirLock(dataDirPath, parent, fixture)

    fun startOwnerClose() {
        if (!ownerCloseStarted.compareAndSet(false, true)) return
        val closeWorker = thread(start = false, name = "plainbase-stage0c-w3-owner-close") {
            try {
                owner.close()
            } catch (failure: Throwable) {
                ownerCloseFailure.compareAndSet(null, failure)
            } finally {
                ownerClosed.set(true)
            }
        }
        ownerCloseThread.set(closeWorker)
        fixture.track("W3 owner close worker", closeWorker, { closeWorker.interrupt() }) { !closeWorker.isAlive }
        closeWorker.start()
    }

    fun joinOwner(parent: Stage0cParentDeadline, description: String) {
        val closeWorker = ownerCloseThread.get()
        if (closeWorker != null && closeWorker !== Thread.currentThread()) {
            check(parent.join(closeWorker, description, parent.remainingMillis())) {
                "$description survived its parent deadline"
            }
        }
    }

    private fun closeOwner() {
        startOwnerClose()
        joinOwner(parent, "W3 owner close cleanup")
    }

    private fun closeDr() {
        events += "dr-close-enter"
        try {
            dr.close()
            drReturnedInterrupt.set(Thread.currentThread().isInterrupted)
        } catch (failure: Throwable) {
            drFailure.compareAndSet(null, failure)
            throw failure
        } finally {
            drClosed.set(true)
            events += "dr-close-exit"
        }
    }

    private fun closeTransport() {
        events += "object-transport-close-enter"
        try {
            store.close()
        } catch (failure: Throwable) {
            transportFailure.compareAndSet(null, failure)
            throw failure
        } finally {
            transportClosed.set(true)
            events += "object-transport-close-exit"
        }
    }

    private fun closeLock(lock: DataDirLock) {
        events += "data-dir-lock-close-enter"
        try {
            lock.close()
        } catch (failure: Throwable) {
            lockFailure.compareAndSet(null, failure)
            throw failure
        } finally {
            lockClosed.set(true)
            events += "data-dir-lock-close-exit"
        }
    }
}

private class W3HistoryBundleClient(
    private val delegate: ObjectStoreClient,
    private val holdFirstPut: Boolean,
    val firstPutEntered: CountDownLatch,
    private val release: CountDownLatch,
    val firstDelegateCompleted: CountDownLatch,
    val firstDelegateSucceeded: AtomicBoolean,
    private val firstBundleExists: AtomicBoolean,
    val firstBundleBytes: AtomicReference<ByteArray?>,
    private val firstBundlePath: AtomicReference<Path?>,
    val firstDelegateFailure: AtomicReference<Throwable?>,
    private val deferredInterrupt: AtomicBoolean,
    val finalDelegateCompleted: CountDownLatch,
    val finalDelegateSucceeded: AtomicBoolean,
    val finalDelegateFailure: AtomicReference<Throwable?>,
    val finalPutEntered: CountDownLatch,
    private val executor: ExecutorService,
    val executorTerminatedBeforeFinal: AtomicBoolean,
) : ObjectStoreClient by delegate {
    private val firstPut = AtomicBoolean(true)
    private val putCount = AtomicInteger()

    val firstPutCount: Int
        get() = putCount.get()

    override suspend fun putFromFile(
        key: String,
        source: Path,
        contentType: String?,
        requestTimeoutMillis: Long?,
    ): PutOutcome {
        val isFirstHistoryBundle = key == HISTORY_BUNDLE_KEY && firstPut.compareAndSet(true, false)
        if (!isFirstHistoryBundle) {
            if (key == HISTORY_BUNDLE_KEY) {
                putCount.incrementAndGet()
                finalPutEntered.countDown()
                executorTerminatedBeforeFinal.set(executor.isTerminated)
            }
            val outcome = try {
                delegate.putFromFile(key, source, contentType, requestTimeoutMillis)
            } catch (caught: Throwable) {
                if (key == HISTORY_BUNDLE_KEY) {
                    finalDelegateFailure.compareAndSet(null, caught)
                    finalDelegateCompleted.countDown()
                }
                throw caught
            }
            if (key == HISTORY_BUNDLE_KEY) {
                if (outcome is PutOutcome.Stored) finalDelegateSucceeded.set(true)
                finalDelegateCompleted.countDown()
            }
            return outcome
        }

        putCount.incrementAndGet()
        firstBundlePath.set(source)
        firstBundleExists.set(Files.exists(source))
        firstBundleBytes.set(Files.readAllBytes(source))
        firstPutEntered.countDown()
        var interrupted = false
        if (holdFirstPut) {
            while (true) {
                try {
                    if (release.await(100, TimeUnit.MILLISECONDS)) break
                } catch (caught: InterruptedException) {
                    interrupted = true
                    Thread.interrupted()
                }
            }
        }
        if (Thread.interrupted()) interrupted = true
        try {
            val outcome = delegate.putFromFile(key, source, contentType, requestTimeoutMillis)
            if (outcome is PutOutcome.Stored) firstDelegateSucceeded.set(true)
            firstDelegateCompleted.countDown()
            return outcome
        } catch (caught: Throwable) {
            if (caught is InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
            firstDelegateFailure.compareAndSet(null, caught)
            firstDelegateCompleted.countDown()
            throw caught
        } finally {
            if (interrupted) deferredInterrupt.set(true)
        }
    }

    companion object {
        private const val HISTORY_BUNDLE_KEY = ".plainbase/history.bundle"
    }
}

private data class W3ShipRunFacts(
    val firstBundlePath: Path?,
    val firstBundleBytes: ByteArray?,
)

private fun verifyBundleReceipt(exec: GitExecutor, receipt: W3PutReceipt, expectedHead: String, target: Path) {
    Files.write(target, receipt.bytes)
    val verify = exec.run(listOf("bundle", "verify", target.toString()))
    check(verify.ok) { "actual ${target.fileName} failed git bundle verify: ${verify.stderr}" }
    val refs = exec.run(listOf("bundle", "list-heads", target.toString()))
    check(refs.ok) { "actual ${target.fileName} refs could not be read: ${refs.stderr}" }
    check(refs.stdoutText.lineSequence().any { it.trim().startsWith(expectedHead) }) {
        "actual ${target.fileName} did not contain seeded HEAD $expectedHead: ${refs.stdoutText}"
    }
}

private fun objectConfigForW3(content: Path, data: Path, endpoint: String) =
    objectConfigForOwnership(content, data, endpoint, gitEnabled = true)
