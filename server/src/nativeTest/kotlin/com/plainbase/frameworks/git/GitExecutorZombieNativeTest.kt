package com.plainbase.frameworks.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val G3Z_RUN_TIMEOUT_MILLIS = 15_000L
private const val G3Z_CLEANUP_TIMEOUT_MILLIS = 3_000L
private const val G3Z_POLL_MILLIS = 10L

/** The actual Linux PID1 regression for the retained-process completion predicate. */
@Tag("native")
class GitExecutorZombieNativeTest {

    @Test
    fun reparentedZombieCompletesInvocation() {
        val pid1Property = System.getProperty("plainbase.test.g3z.pid1")
        if (pid1Property == null) {
            assumeTrue(false, "topology-required: plainbase.test.g3z.pid1 must select the PID1 namespace")
        }
        assertEquals("true", pid1Property, "plainbase.test.g3z.pid1 must be true for the mandatory topology gate")
        assertEquals("Linux", System.getProperty("os.name"), "G3z requires Linux /proc semantics")
        assertEquals(1L, ProcessHandle.current().pid(), "G3z requires the test executable to be namespace PID1")
        val expectedRuntime = requireNotNull(System.getProperty("plainbase.test.g3z.expected-runtime")) {
            "missing expected G3z runtime property: plainbase.test.g3z.expected-runtime"
        }
        val expectedUid = requireNotNull(System.getProperty("plainbase.test.g3z.expected-uid")) {
            "missing expected G3z UID property: plainbase.test.g3z.expected-uid"
        }
        val expectedGid = requireNotNull(System.getProperty("plainbase.test.g3z.expected-gid")) {
            "missing expected G3z GID property: plainbase.test.g3z.expected-gid"
        }
        val actualRuntime = System.getProperty("org.graalvm.nativeimage.imagecode") ?: "jvm"
        assertEquals(expectedRuntime, actualRuntime, "G3z runtime identity must match the selected gate")
        assertEquals(expectedUid, readProcIdentity("Uid"), "G3z effective UID must match the invoking runner")
        assertEquals(expectedGid, readProcIdentity("Gid"), "G3z effective GID must match the invoking runner")

        withReparentedZombieFixture()
    }
}

private data class G3zStatSnapshot(
    val stat: LinuxProcessStat,
    val parentPid: Long,
)

private data class G3zProcessIdentity(
    val label: String,
    val pid: Long,
    val startTicks: Long,
)

private data class G3zCapturedProcess(
    val handle: ProcessHandle,
    val identity: G3zProcessIdentity,
)

private data class G3zProcessDecision(
    val complete: Boolean,
    val firstStartTicks: Long?,
)

private data class G3zHelperCompletion(
    val name: String,
    val complete: Boolean,
)

private class G3zProductionObservationRecorder : GitInvocationObservationListener {
    private val retained = ConcurrentHashMap<Long, GitProcessObservation>()
    private val retainedIdentities = ConcurrentHashMap<Long, G3zProcessIdentity>()
    private val retentionCaptureFailures = CopyOnWriteArrayList<Throwable>()
    private val processCompletions = ConcurrentHashMap<Long, CopyOnWriteArrayList<G3zProcessDecision>>()
    private val helperIdentities = ConcurrentHashMap<Long, Thread>()
    private val helperCompletions = ConcurrentHashMap<Long, CopyOnWriteArrayList<G3zHelperCompletion>>()

    override fun processRetained(observation: GitProcessObservation) {
        val pid = observation.handle.pid()
        if (retained.putIfAbsent(pid, observation) != null) return
        val alive = runCatching { observation.handle.isAlive }
            .onFailure(retentionCaptureFailures::add)
            .getOrNull()
        if (alive != true) return
        val stat = readG3zStat(observation.handle)
        if (stat == null) {
            val stillAlive = runCatching { observation.handle.isAlive }
                .onFailure(retentionCaptureFailures::add)
                .getOrNull()
            if (stillAlive != false) {
                retentionCaptureFailures +=
                    IllegalStateException("unable to capture retained production PID identity: $pid")
            }
            return
        }
        retainedIdentities[pid] = G3zProcessIdentity("production-${observation.role}", pid, stat.stat.startTicks)
    }

    override fun processCompletionObserved(observation: GitProcessObservation, complete: Boolean) {
        val pid = observation.handle.pid()
        processCompletions.computeIfAbsent(pid) { CopyOnWriteArrayList() }.add(
            G3zProcessDecision(complete, observation.firstStartTicks),
        )
    }

    override fun helperCompletionObserved(helper: Thread, complete: Boolean) {
        helperCompletions.computeIfAbsent(helper.threadId()) { CopyOnWriteArrayList() }.add(
            G3zHelperCompletion(helper.name, complete),
        )
    }

    fun helperCreated(helper: Thread) {
        helperIdentities[helper.threadId()] = helper
    }

    fun hasRetainedChild(pid: Long): Boolean = retained[pid]?.role == "descendant"

    fun hasProcessDecision(pid: Long, complete: Boolean, expectedStartTicks: Long): Boolean =
        processCompletions[pid]?.any { receipt ->
            receipt.complete == complete && receipt.firstStartTicks == expectedStartTicks
        } == true

    fun helperNames(): Set<String> = helperIdentities.values.map(Thread::getName).toSet()

    fun allHelpersComplete(expectedNames: Set<String>): Boolean =
        helperIdentities.values
            .filter { it.name in expectedNames }
            .all { helper -> helperCompletions[helper.threadId()]?.any(G3zHelperCompletion::complete) == true }

    fun helperCount(): Int = helperIdentities.size

    fun retainedProcesses(): List<GitProcessObservation> = retained.values.sortedBy { it.handle.pid() }

    fun retainedProcessIdentities(): List<G3zProcessIdentity> = retainedIdentities.values.sortedBy { it.pid }

    fun retentionCaptureFailures(): List<Throwable> = retentionCaptureFailures.toList()
}

private fun withReparentedZombieFixture() {
    val root = Files.createTempDirectory("plainbase-g3z-root")
    val home = Files.createTempDirectory("plainbase-g3z-home")
    val binary = home.resolve("controlled-git")
    val releaseParent = home.resolve("release-parent")
    val releaseZombie = home.resolve("release-zombie")
    val releaseLive = home.resolve("release-live")
    val parentPidFile = home.resolve("parent.pid")
    val zombiePidFile = home.resolve("zombie.pid")
    val livePidFile = home.resolve("live.pid")
    val stdinReceiptFile = home.resolve("stdin.receipt")
    val readyFile = home.resolve("ready")
    writeControlledExecutable(binary)

    val result = AtomicReference<Result<GitResult>?>(null)
    val workerFailure = AtomicReference<Throwable?>(null)
    val production = G3zProductionObservationRecorder()
    val helpers = CopyOnWriteArrayList<Thread>()
    val expectedHelperNames = setOf("git-stdout-drain", "git-stderr-drain", "git-stdin-writer")
    val worker = thread(start = false, isDaemon = true, name = "plainbase-g3z-git-caller") {
        result.set(
            runCatching {
                GitExecutor(
                    workTree = root,
                    home = home,
                    timeoutSeconds = 10,
                    gitBinary = binary.toString(),
                    helperFactory = { name, block ->
                        Thread(block, name).apply {
                            isDaemon = true
                            helpers += this
                            production.helperCreated(this)
                        }
                    },
                    observationListener = production,
                ).run(
                    args = listOf("status"),
                    stdin = "g3z-input\n".toByteArray(),
                )
            },
        )
    }.also { owned ->
        owned.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
            workerFailure.compareAndSet(null, failure)
        }
    }

    var parentHandle: ProcessHandle? = null
    var zombieHandle: ProcessHandle? = null
    var liveHandle: ProcessHandle? = null
    var primaryFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var interrupted = Thread.interrupted()
    val identities = linkedMapOf<ProcessHandle, G3zProcessIdentity>()
    val fixtureCaptureFailures = CopyOnWriteArrayList<Throwable>()

    fun recordCleanupFailure(failure: Throwable) {
        if (failure is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
        cleanupFailure = mergeG3zFailure(cleanupFailure, failure)
    }

    fun attemptCleanup(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            recordCleanupFailure(failure)
        }
    }

    try {
        if (interrupted) throw InterruptedException("G3z fixture entry was interrupted")
        worker.start()
        awaitG3z("controlled executable readiness") { Files.exists(readyFile) }
        parentHandle = processHandle(readPid(parentPidFile))
        zombieHandle = processHandle(readPid(zombiePidFile))
        liveHandle = processHandle(readPid(livePidFile))
        val parent = requireNotNull(parentHandle)
        val zombie = requireNotNull(zombieHandle)
        val live = requireNotNull(liveHandle)
        fun captureIdentity(label: String, handle: ProcessHandle) {
            val snapshot = requireNotNull(readG3zStat(handle)) { "missing $label stat for pid=${handle.pid()}" }
            assertEquals(handle.pid(), snapshot.stat.pid, "$label stat PID must match ProcessHandle")
            identities[handle] = G3zProcessIdentity(label, snapshot.stat.pid, snapshot.stat.startTicks)
        }
        captureIdentity("parent", parent)
        captureIdentity("zombie", zombie)
        captureIdentity("live", live)
        fun captureDescendantIdentities(owner: String, handle: ProcessHandle) {
            val descendants = runCatching { handle.descendants().toList() }
                .onFailure(fixtureCaptureFailures::add)
                .getOrDefault(emptyList())
            descendants.forEach { descendant ->
                val snapshot = readG3zStat(descendant)
                if (snapshot != null) {
                    identities.putIfAbsent(
                        descendant,
                        G3zProcessIdentity("$owner-descendant", snapshot.stat.pid, snapshot.stat.startTicks),
                    )
                } else if (g3zLiveness(descendant) != false) {
                    fixtureCaptureFailures +=
                        IllegalStateException("unable to capture $owner descendant identity: ${descendant.pid()}")
                }
            }
        }
        captureDescendantIdentities("parent", parent)
        captureDescendantIdentities("zombie", zombie)
        captureDescendantIdentities("live", live)
        assertTrue(parent.isAlive, "controlled parent must be live before its release")
        assertTrue(zombie.isAlive, "zombie candidate must be live before parent exit")
        assertTrue(live.isAlive, "the live-process control must be live before release")
        assertTrue(requireNotNull(readG3zStat(live)).stat.state != 'Z', "live control must not be a zombie")
        awaitG3z("stdin consumption") {
            Files.exists(stdinReceiptFile) && Files.readString(stdinReceiptFile).trimEnd('\n') == "g3z-input"
        }
        assertEquals("g3z-input", Files.readString(stdinReceiptFile).trimEnd('\n'))

        awaitG3z("child ancestry observation before parent exit") {
            readG3zStat(zombie)?.parentPid == parent.pid()
        }
        val beforeParentExit = requireNotNull(readG3zStat(zombie))
        val beforeLiveRelease = requireNotNull(readG3zStat(live))
        assertEquals(zombie.pid(), beforeParentExit.stat.pid)
        assertTrue(beforeParentExit.stat.state != 'Z', "the retained child must be live before release")
        assertEquals(1L, beforeParentExit.stat.numThreads, "the controlled child must be single-threaded")
        assertTrue(beforeLiveRelease.stat.state != 'Z', "the live control must be live before release")
        assertEquals(1L, beforeLiveRelease.stat.numThreads, "the live control must be single-threaded")
        awaitG3z("production owner retaining both child observations") {
            production.hasRetainedChild(zombie.pid()) &&
                production.hasRetainedChild(live.pid()) &&
                production.hasProcessDecision(
                    zombie.pid(),
                    complete = false,
                    expectedStartTicks = beforeParentExit.stat.startTicks,
                ) &&
                production.hasProcessDecision(
                    live.pid(),
                    complete = false,
                    expectedStartTicks = beforeLiveRelease.stat.startTicks,
                )
        }
        awaitG3z("production owner retained process identities") {
            production.retainedProcessIdentities().size >= 3 && production.retentionCaptureFailures().isEmpty()
        }
        assertTrue(production.retainedProcessIdentities().size >= 3, "production owner must retain at least three process identities")
        assertTrue(production.retentionCaptureFailures().isEmpty(), "production process identity capture must succeed")

        Files.writeString(releaseParent, "release\n")
        awaitG3z("direct parent exit and reaping") { !parent.isAlive }
        assertFalse(parent.isAlive, "GitExecutor must wait for and reap the direct parent")
        awaitG3z("child reparenting to namespace PID1") {
            readG3zStat(zombie)?.parentPid == 1L && readG3zStat(live)?.parentPid == 1L
        }
        assertTrue(zombie.isAlive, "the retained child must remain observable after parent exit")
        val liveAfterParentExit = requireNotNull(readG3zStat(live))
        assertEquals(beforeLiveRelease.stat.startTicks, liveAfterParentExit.stat.startTicks)
        assertTrue(liveAfterParentExit.stat.state != 'Z', "the live control must remain live after parent exit")

        Files.writeString(releaseZombie, "release\n")
        awaitG3z("stable single-thread zombie") {
            val snapshot = readG3zStat(zombie)
            snapshot != null &&
                zombie.isAlive &&
                snapshot.stat.state == 'Z' &&
                snapshot.stat.numThreads == 1L &&
                snapshot.stat.startTicks == beforeParentExit.stat.startTicks
        }
        val zombieSnapshot = requireNotNull(readG3zStat(zombie))
        assertEquals(zombie.pid(), zombieSnapshot.stat.pid)
        assertEquals(1L, zombieSnapshot.parentPid, "the zombie must remain reparented to PID1")
        assertEquals('Z', zombieSnapshot.stat.state)
        assertEquals(1L, zombieSnapshot.stat.numThreads)
        assertEquals(beforeParentExit.stat.startTicks, zombieSnapshot.stat.startTicks)
        assertTrue(zombie.isAlive, "ProcessHandle.isAlive must independently report the zombie as alive")
        assertTrue(live.isAlive, "the live-process control must stay pending until its release")
        assertTrue(
            production.hasProcessDecision(
                live.pid(),
                complete = false,
                expectedStartTicks = beforeLiveRelease.stat.startTicks,
            ),
            "production observer must keep the live control pending",
        )
        assertNull(result.get(), "GitExecutor must not complete while the live control remains pending")
        assertTrue(worker.isAlive, "the invocation caller must still be waiting on the live control")
        assertEquals(expectedHelperNames, production.helperNames(), "production helper factory must capture all helpers")

        Files.writeString(releaseLive, "release\n")
        val completionJoin = joinG3z(worker, G3Z_RUN_TIMEOUT_MILLIS)
        interrupted = interrupted || completionJoin.interrupted
        if (completionJoin.interrupted) {
            recordCleanupFailure(InterruptedException("G3z completion join was interrupted"))
        }
        assertTrue(completionJoin.stopped, "GitExecutor must complete after all controls release")
        val completed = requireNotNull(result.get()) { "GitExecutor worker completed without a result" }
        assertNull(completed.exceptionOrNull(), "GitExecutor worker must not throw")
        val gitResult = completed.getOrThrow()
        assertEquals(0, gitResult.exitCode, "the original controlled parent result must be returned")
        assertTrue(gitResult.stdoutText.contains("g3z-stdout"), "stdout helper must be joined with its output")
        assertTrue(gitResult.stderr.contains("g3z-stderr"), "stderr helper must be joined with its output")
        awaitG3z("production completion of retained zombie") {
            production.hasProcessDecision(
                zombie.pid(),
                complete = true,
                expectedStartTicks = beforeParentExit.stat.startTicks,
            )
        }
        awaitG3z("production completion of the live control after release") {
            production.hasProcessDecision(
                live.pid(),
                complete = true,
                expectedStartTicks = beforeLiveRelease.stat.startTicks,
            )
        }
        awaitG3z("production helper completion") { production.allHelpersComplete(expectedHelperNames) }
        assertTrue(production.allHelpersComplete(expectedHelperNames), "all production helpers must complete")
        assertEquals(3, production.helperCount(), "exactly three production helpers must be created")
    } catch (failure: Throwable) {
        primaryFailure = failure
    } finally {
        try {
            attemptCleanup { Files.writeString(releaseParent, "release\n") }
            attemptCleanup { Files.writeString(releaseZombie, "release\n") }
            attemptCleanup { Files.writeString(releaseLive, "release\n") }
            val captured = linkedMapOf<String, G3zCapturedProcess>()
            val captureFailures = mutableListOf<Throwable>()
            fun captureProcess(handle: ProcessHandle, identity: G3zProcessIdentity) {
                captured.putIfAbsent("${identity.pid}:${identity.startTicks}", G3zCapturedProcess(handle, identity))
            }
            identities.forEach { (handle, identity) -> captureProcess(handle, identity) }
            fun captureProductionProcesses() {
                production.retainedProcesses().forEach { observation ->
                    val identity = production.retainedProcessIdentities()
                        .firstOrNull { it.pid == observation.handle.pid() }
                    if (identity != null) {
                        captureProcess(observation.handle, identity)
                    } else if (g3zLiveness(observation.handle) != false) {
                        captureFailures +=
                            IllegalStateException("retained production PID has no confirmed identity: ${observation.handle.pid()}")
                    }
                }
            }
            captureProductionProcesses()

            fun terminateCapturedProcesses() {
                captured.values.toList().asReversed().forEach { capturedProcess ->
                    attemptCleanup {
                        when (g3zLiveness(capturedProcess.handle)) {
                            false -> Unit
                            null -> throw IllegalStateException(
                                "refusing cleanup with unknown liveness for pid=${capturedProcess.identity.pid}",
                            )

                            true -> {
                                if (!g3zIdentityMatches(capturedProcess.handle, capturedProcess.identity)) {
                                    throw IllegalStateException(
                                        "refusing cleanup after PID identity changed for " +
                                            "${capturedProcess.identity.label}: pid=${capturedProcess.identity.pid}",
                                    )
                                }
                                if (!g3zProcessQuiescent(capturedProcess.handle, capturedProcess.identity)) {
                                    capturedProcess.handle.destroyForcibly()
                                }
                            }
                        }
                    }
                }
            }

            fun confirmCapturedProcesses() {
                val deadline = System.nanoTime() +
                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(G3Z_CLEANUP_TIMEOUT_MILLIS)
                while (System.nanoTime() < deadline && captured.values.any { !g3zProcessConfirmed(it) }) {
                    try {
                        Thread.sleep(G3Z_POLL_MILLIS)
                    } catch (failure: InterruptedException) {
                        recordCleanupFailure(failure)
                        Thread.interrupted()
                    }
                }
                captured.values.filterNot(::g3zProcessConfirmed).forEach { capturedProcess ->
                    recordCleanupFailure(
                        IllegalStateException("G3z captured process was not confirmed stopped: ${capturedProcess.identity}"),
                    )
                }
            }

            terminateCapturedProcesses()
            confirmCapturedProcesses()

            val cleanupWait = joinG3z(worker, G3Z_CLEANUP_TIMEOUT_MILLIS)
            interrupted = interrupted || cleanupWait.interrupted
            if (cleanupWait.interrupted) {
                recordCleanupFailure(InterruptedException("G3z fixture cleanup wait was interrupted"))
            }
            helpers.forEach { helper ->
                val helperWait = joinG3z(helper, G3Z_CLEANUP_TIMEOUT_MILLIS)
                interrupted = interrupted || helperWait.interrupted
                if (helperWait.interrupted) {
                    recordCleanupFailure(InterruptedException("G3z helper cleanup wait was interrupted"))
                }
                if (!helperWait.stopped) {
                    recordCleanupFailure(IllegalStateException("retaining G3z helper ${helper.name}#${helper.threadId()}"))
                }
            }
            captureProductionProcesses()
            terminateCapturedProcesses()
            confirmCapturedProcesses()
            captureFailures += fixtureCaptureFailures
            captureFailures += production.retentionCaptureFailures()
            val retainedIdentityCount = production.retainedProcessIdentities().size
            if (captured.size < retainedIdentityCount) {
                captureFailures += IllegalStateException(
                    "cleanup captured ${captured.size} processes for $retainedIdentityCount retained production identities",
                )
            }
            captureFailures.forEach(::recordCleanupFailure)
            workerFailure.get()?.let(::recordCleanupFailure)
            result.get()?.exceptionOrNull()?.let { workerResultFailure ->
                val primary = primaryFailure
                if (primary == null) {
                    recordCleanupFailure(workerResultFailure)
                } else if (primary !== workerResultFailure && primary.suppressed.none { it === workerResultFailure }) {
                    primary.addSuppressed(workerResultFailure)
                }
            }

            val handlesQuiescent = captured.values.all(::g3zProcessConfirmed)
            val workerStopped = !worker.isAlive
            val helpersStopped = helpers.all { !it.isAlive }
            val identityScoped = captureFailures.isEmpty() &&
                captured.size >= identities.size &&
                captured.size >= retainedIdentityCount &&
                production.retainedProcesses().isNotEmpty() &&
                helpers.size == 3 &&
                production.helperCount() == 3
            if (!workerStopped || !helpersStopped || !handlesQuiescent || !identityScoped) {
                val retained = IllegalStateException(
                    "retaining G3z fixture root=$root home=$home: " +
                        "workerStopped=$workerStopped helpersStopped=$helpersStopped " +
                        "capturedProcessCount=${captured.size} processesQuiescent=$handlesQuiescent " +
                        "identityScoped=$identityScoped",
                )
                recordCleanupFailure(retained)
            } else {
                val rootDeleted = runCatching { root.toFile().deleteRecursively() }.getOrDefault(false)
                val homeDeleted = runCatching { home.toFile().deleteRecursively() }.getOrDefault(false)
                if (!rootDeleted || !homeDeleted) {
                    recordCleanupFailure(
                        IllegalStateException("retaining G3z fixture after cleanup failure root=$root home=$home"),
                    )
                }
            }
        } catch (failure: Throwable) {
            recordCleanupFailure(failure)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    if (primaryFailure != null) {
        cleanupFailure?.let { mergeG3zFailure(primaryFailure, it) }
        throw primaryFailure
    }
    cleanupFailure?.let { throw it }
}

private fun writeControlledExecutable(binary: Path) {
    Files.writeString(
        binary,
        """#!/bin/sh
(
    while [ ! -f "${'$'}HOME/release-zombie" ]; do sleep 0.01; done
    exit 0
) </dev/null >/dev/null 2>&1 &
zombie=${'$'}!
(
    while [ ! -f "${'$'}HOME/release-live" ]; do sleep 0.01; done
    exit 0
) </dev/null >/dev/null 2>&1 &
live=${'$'}!
IFS= read -r stdin_line
printf '%s\n' "${'$'}stdin_line" > "${'$'}HOME/stdin.receipt"
printf '%s\n' "${'$'}${'$'}" > "${'$'}HOME/parent.pid"
printf '%s\n' "${'$'}zombie" > "${'$'}HOME/zombie.pid"
printf '%s\n' "${'$'}live" > "${'$'}HOME/live.pid"
printf 'ready\n' > "${'$'}HOME/ready"
printf 'g3z-stdout\n'
printf 'g3z-stderr\n' >&2
while [ ! -f "${'$'}HOME/release-parent" ]; do sleep 0.01; done
exit 0
""".trimIndent() + "\n",
    )
    check(binary.toFile().setExecutable(true)) { "controlled Git executable was not made executable: $binary" }
}

private fun processHandle(pid: Long): ProcessHandle =
    ProcessHandle.of(pid).orElseThrow { IllegalStateException("G3z process handle disappeared for pid=$pid") }

private fun readPid(path: Path): Long =
    Files.readString(path).trim().toLongOrNull()?.takeIf { it > 0L }
        ?: throw IllegalStateException("invalid G3z PID file: $path")

private fun readG3zStat(handle: ProcessHandle): G3zStatSnapshot? {
    val raw = runCatching { Files.readString(Path.of("/proc", handle.pid().toString(), "stat")) }.getOrNull() ?: return null
    val parsed = parseLinuxProcessStat(raw) ?: return null
    val close = raw.lastIndexOf(')')
    if (close <= 0) return null
    val fields = raw.substring(close + 1).trim().split(Regex("\\s+"))
    val parentPid = fields.getOrNull(1)?.toLongOrNull() ?: return null
    return G3zStatSnapshot(parsed, parentPid)
}

private fun g3zIdentityMatches(handle: ProcessHandle, identity: G3zProcessIdentity): Boolean {
    val snapshot = readG3zStat(handle) ?: return false
    return snapshot.stat.pid == identity.pid && snapshot.stat.startTicks == identity.startTicks
}

private fun g3zLiveness(handle: ProcessHandle): Boolean? = runCatching { handle.isAlive }.getOrNull()

private fun g3zProcessQuiescent(handle: ProcessHandle, identity: G3zProcessIdentity): Boolean {
    when (g3zLiveness(handle)) {
        false -> return true
        null -> return false
        true -> Unit
    }
    val snapshot = readG3zStat(handle) ?: return false
    return snapshot.stat.pid == identity.pid &&
        snapshot.stat.startTicks == identity.startTicks &&
        snapshot.stat.state == 'Z' &&
        snapshot.stat.numThreads == 1L
}

private fun g3zProcessConfirmed(captured: G3zCapturedProcess): Boolean {
    val alive = g3zLiveness(captured.handle) ?: return false
    if (!alive) return true
    return g3zProcessQuiescent(captured.handle, captured.identity)
}

private data class G3zJoinResult(val stopped: Boolean, val interrupted: Boolean)

private fun joinG3z(worker: Thread, timeoutMillis: Long): G3zJoinResult {
    var interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (worker.isAlive && System.nanoTime() < deadline) {
        try {
            val remaining = deadline - System.nanoTime()
            worker.join(maxOf(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)))
        } catch (_: InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
    }
    return G3zJoinResult(!worker.isAlive, interrupted)
}

private fun awaitG3z(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(G3Z_RUN_TIMEOUT_MILLIS)
    var satisfied = false
    while (!satisfied && System.nanoTime() < deadline) {
        satisfied = runCatching(condition).getOrDefault(false)
        if (!satisfied) {
            try {
                Thread.sleep(G3Z_POLL_MILLIS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
        }
    }
    assertTrue(satisfied, "$description did not become true within ${G3Z_RUN_TIMEOUT_MILLIS}ms")
}

private fun readProcIdentity(label: String): String? =
    runCatching {
        Files.readAllLines(Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
    }.getOrNull()

private fun mergeG3zFailure(existing: Throwable?, candidate: Throwable): Throwable {
    if (existing == null) return candidate
    if (existing !== candidate && existing.suppressed.none { it === candidate }) existing.addSuppressed(candidate)
    return existing
}
