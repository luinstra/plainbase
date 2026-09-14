package com.plainbase.frameworks.runtime

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.GitRepoLocks
import com.plainbase.frameworks.lifecycle.GitMaintenanceTasks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** G1 wiring control for the actual primary, extra-root, and object history selection callbacks. */
class RootHistorySelectionMaintenanceTest : FunSpec({

    test("all selected LOCAL callbacks use the supplied maintenance owner and exact provider executors") {
        val primaryPath = Path.of("unused-primary")
        val extraPath = Path.of("unused-extra")
        val config = localConfig(primaryPath, extraPath)
        val registry = RootRegistry.of(config.roots.list)
        val dispatched = ConcurrentLinkedQueue<GitExecutor>()
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val tasks = GitMaintenanceTasks(
            workerFactory = { name, block ->
                Thread(block, name).also { worker ->
                    worker.isDaemon = false
                    worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                        uncaughtFailure.compareAndSet(null, failure)
                    }
                    workers += worker
                }
            },
            jobRunner = { dispatched += it },
        )
        val objectLocks = lazy(::GitRepoLocks)
        val selection = prepareRootHistorySelection(
            config = config,
            registry = registry,
            primaryRepoPath = { it.value },
            extraRepoPaths = mapOf(RootName.require("extra") to { it.value }),
            objectHistory = DeferredObjectHistory(),
            objectLocks = objectLocks,
            maintenanceTasks = tasks,
        )

        val claimedPrimary = selection.byRoot.getValue(RootName.PRIMARY).shouldBeInstanceOf<GitCliHistoryProvider>()
        val claimedExtra = selection.byRoot.getValue(RootName.require("extra")).shouldBeInstanceOf<GitCliHistoryProvider>()
        val localAuto = selectHistoryProvider(
            config = config,
            contentRoot = primaryPath,
            maintenanceTasks = tasks,
        ).shouldBeInstanceOf<GitCliHistoryProvider>()

        maintenanceCallback(claimedPrimary).invoke()
        maintenanceCallback(claimedExtra).invoke()
        maintenanceCallback(localAuto).invoke()
        closeBounded(tasks, workers)

        objectLocks.isInitialized() shouldBe false
        uncaughtFailure.get() shouldBe null
        dispatched.toList() shouldContainExactlyInAnyOrder listOf(
            providerExecutor(claimedPrimary),
            providerExecutor(claimedExtra),
            providerExecutor(localAuto),
        )
    }

    test("OBJECT onCommit follows a rejected maintenance dispatch and keeps the supplied lock monitor") {
        val objectHistory = DeferredObjectHistory()
        val commits = AtomicInteger()
        objectHistory.arm(ObjectHistoryCallbacks({ it.value }, { commits.incrementAndGet() }))
        val acceptedEntered = CountDownLatch(1)
        val acceptedRelease = CountDownLatch(1)
        val acceptedCompleted = CountDownLatch(1)
        val dispatched = ConcurrentLinkedQueue<GitExecutor>()
        val workers = ConcurrentLinkedQueue<Thread>()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val tasks = GitMaintenanceTasks(
            workerFactory = { name, block ->
                Thread(block, name).also { worker ->
                    worker.isDaemon = false
                    worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                        uncaughtFailure.compareAndSet(null, failure)
                    }
                    workers += worker
                }
            },
            jobRunner = { exec ->
                dispatched += exec
                acceptedEntered.countDown()
                check(acceptedRelease.await(5, TimeUnit.SECONDS)) { "accepted maintenance release timed out" }
                acceptedCompleted.countDown()
            },
        )
        val config = objectConfig()
        val selection = prepareRootHistorySelection(
            config = config,
            registry = RootRegistry.of(config.roots.list),
            primaryRepoPath = objectHistory::repoPath,
            extraRepoPaths = emptyMap(),
            objectHistory = objectHistory,
            objectLocks = lazy(::GitRepoLocks),
            maintenanceTasks = tasks,
        )
        val provider = selection.byRoot.getValue(RootName.PRIMARY).shouldBeInstanceOf<GitCliHistoryProvider>()
        val callback = maintenanceCallback(provider)
        val expectedMonitor = selection.objectLocks.value.repoWrite

        val closerFailure = AtomicReference<Throwable?>()
        var closer: Thread? = null
        try {
            callback.invoke()
            acceptedEntered.await(5, TimeUnit.SECONDS) shouldBe true
            dispatched.size shouldBe 1
            dispatched.single().workTreeForTest() shouldBe config.dataDir.resolve("mirror")
            dispatched.single().homeForTest() shouldBe config.dataDir.resolve("git-home")
            (dispatched.single() === providerExecutor(provider)) shouldBe false
            commits.get() shouldBe 1

            closer = Thread({ runCatching { tasks.close() }.onFailure(closerFailure::set) }, "g1-object-close")
                .also { it.isDaemon = true }
            closer.start()
            awaitAdmissionClosed(tasks)
            closer.isAlive shouldBe true
            callback.invoke()
            commits.get() shouldBe 2
            providerRepoWriteMonitor(provider) shouldBe expectedMonitor

            acceptedRelease.countDown()
            acceptedCompleted.await(5, TimeUnit.SECONDS) shouldBe true
            workers.forEach { worker ->
                worker.join(5_000)
                worker.isAlive shouldBe false
            }
            closer.join(5_000)
            closer.isAlive shouldBe false
            tasks.unfinishedJobsForTest() shouldBe 0
            closerFailure.get() shouldBe null
            uncaughtFailure.get() shouldBe null
        } finally {
            acceptedRelease.countDown()
            workers.forEach { worker ->
                worker.join(5_000)
                if (worker.isAlive) worker.interrupt()
                worker.join(1_000)
                worker.isAlive shouldBe false
            }
            restoreLeakedSlots(tasks)
            closer?.join(5_000)
            closer?.let { it.isAlive shouldBe false }
        }
    }
})

private fun localConfig(primaryPath: Path, extraPath: Path): PlainbaseConfig = PlainbaseConfig(
    contentDir = primaryPath,
    dataDir = Path.of("unused-data"),
    host = "127.0.0.1",
    port = 8080,
    git = GitConfig(enabled = true),
    roots = RootsConfig.of(
        listOf(
            Root(RootName.PRIMARY, RootBackend.Local(primaryPath), editable = true, history = HistoryMode.NATIVE),
            Root(RootName.require("extra"), RootBackend.Local(extraPath), editable = true, history = HistoryMode.NATIVE),
        ),
        origin = RootsOrigin.EXPLICIT,
    ),
)

private fun objectConfig(): PlainbaseConfig = PlainbaseConfig(
    contentDir = Path.of("unused-object-content"),
    dataDir = Path.of("unused-object-data"),
    host = "127.0.0.1",
    port = 8080,
    git = GitConfig(enabled = true),
    storage = StorageConfig(
        backend = StorageBackend.OBJECT,
        endpoint = "http://unused-object-endpoint",
        bucket = "unused-bucket",
        accessKeyId = "unused-key",
        secretAccessKey = "unused-secret",
    ),
)

@Suppress("UNCHECKED_CAST")
private fun maintenanceCallback(provider: GitCliHistoryProvider): () -> Unit =
    provider.javaClass.getDeclaredField("maintenance").let { field ->
        field.isAccessible = true
        requireNotNull(field.get(provider) as (() -> Unit)?)
    }

private fun providerExecutor(provider: GitCliHistoryProvider): GitExecutor =
    provider.javaClass.getDeclaredField("exec").let { field ->
        field.isAccessible = true
        field.get(provider) as GitExecutor
    }

private fun providerRepoWriteMonitor(provider: GitCliHistoryProvider): Any? =
    provider.javaClass.getDeclaredField("repoWriteMonitor").let { field ->
        field.isAccessible = true
        field.get(provider)
    }

private fun awaitAdmissionClosed(tasks: GitMaintenanceTasks) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline && !tasks.admissionClosedForTest()) Thread.yield()
    tasks.admissionClosedForTest() shouldBe true
}

private fun restoreLeakedSlots(tasks: GitMaintenanceTasks) {
    val leakedSlots = tasks.unfinishedJobsForTest()
    if (leakedSlots == 0) return
    val release = tasks.javaClass.getDeclaredMethod("releaseJob").also { it.isAccessible = true }
    repeat(leakedSlots) { release.invoke(tasks) }
    tasks.unfinishedJobsForTest() shouldBe 0
}

private fun closeBounded(tasks: GitMaintenanceTasks, workers: Collection<Thread> = emptyList()) {
    val closerFailure = AtomicReference<Throwable?>()
    val closer = thread(isDaemon = true, name = "g1-selection-close") {
        runCatching { tasks.close() }.onFailure(closerFailure::set)
    }
    closer.join(5_000)
    val productCloseTimeout = if (closer.isAlive) {
        AssertionError("product close did not complete within the bounded 5-second wait")
    } else {
        null
    }

    var cleanupFailure: Throwable? = null
    fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            cleanupFailure = cleanupFailure?.also { it.addSuppressed(failure) } ?: failure
        }
    }

    cleanup {
        workers.forEach { worker ->
            worker.join(5_000)
            if (worker.isAlive) {
                worker.interrupt()
                worker.join(1_000)
            }
            worker.isAlive shouldBe false
        }
    }
    if (productCloseTimeout != null) cleanup { restoreLeakedSlots(tasks) }
    cleanup {
        closer.join(1_000)
        check(!closer.isAlive) { "bounded closer cleanup timed out" }
    }

    val closerError = closerFailure.get()
    if (productCloseTimeout != null) {
        closerError?.let(productCloseTimeout::addSuppressed)
        cleanupFailure?.let(productCloseTimeout::addSuppressed)
        throw productCloseTimeout
    }
    closerError?.let { failure ->
        cleanupFailure?.let(failure::addSuppressed)
        throw failure
    }
    cleanupFailure?.let { throw it }
}

private fun GitExecutor.workTreeForTest(): Path =
    javaClass.getDeclaredField("workTree").let { field ->
        field.isAccessible = true
        field.get(this) as Path
    }

private fun GitExecutor.homeForTest(): Path =
    javaClass.getDeclaredField("home").let { field ->
        field.isAccessible = true
        field.get(this) as Path
    }
