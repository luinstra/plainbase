package com.plainbase.frameworks.git

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Native-tagged coverage for the real Git process owner. This is source and JVM-discovery coverage until the approved
 * Linux PID1 runner executes it; macOS/native execution does not satisfy the G3z requirement.
 */
@Tag("native")
class GitExecutorProcessNativeTest {
    @Test
    fun `real Git normal result and stdin helper complete under the owner`() {
        withOwnedNativeGit { root, home ->
            val exec = GitExecutor(root, home)
            assertTrue(exec.versionProbe().ok, "installed Git must be runnable")
            assertTrue(exec.run(listOf("init")).ok, "the disposable repository must initialize")
            val content = "native process owner\n".toByteArray()
            val result = exec.run(listOf("hash-object", "--no-filters", "--stdin"), stdin = content)
            assertEquals(0, result.exitCode)
            assertTrue(result.stderr.isEmpty())
            assertNotNull(GitExecutor.parseSha(result.stdout))
        }
    }

    @Test
    fun `synthetic bare Z never waives a multi-thread process`() {
        val raw = "77 (synthetic leader) Z 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 2 0 1234"
        val stat = parseLinuxProcessStat(raw)
        assertNotNull(stat)
        assertFalse(linuxStatProvesOriginalSingleThreadZombie(stat, expectedPid = 77L, expectedStartTicks = 1234L))
    }
}

private const val NATIVE_FIXTURE_RUN_TIMEOUT_MILLIS = 30_000L
private const val NATIVE_FIXTURE_CLEANUP_TIMEOUT_MILLIS = 10_000L

private fun <T> withOwnedNativeGit(block: (root: Path, home: Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-git-process-native")
    val home = Files.createTempDirectory("plainbase-git-process-native-home")
    val result = AtomicReference<Result<T>?>(null)
    val workerFailure = AtomicReference<Throwable?>()
    val worker = thread(start = false, isDaemon = true, name = "plainbase-git-process-native-fixture") {
        result.set(runCatching { block(root, home) })
    }.also { owned ->
        owned.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
            workerFailure.compareAndSet(null, failure)
        }
    }
    var primaryFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var interrupted = Thread.interrupted()
    fun recordCleanupFailure(failure: Throwable) {
        if (failure is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
        cleanupFailure = mergeNativeFixtureFailure(cleanupFailure, failure)
    }
    fun attemptCleanup(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            recordCleanupFailure(failure)
        }
    }
    try {
        worker.start()
        val runWait = joinNativeFixture(worker, NATIVE_FIXTURE_RUN_TIMEOUT_MILLIS)
        interrupted = interrupted || runWait.interrupted
        if (runWait.interrupted) {
            primaryFailure = InterruptedException("native Git fixture wait was interrupted")
        } else if (!runWait.stopped) {
            primaryFailure = TimeoutException(
                "native Git fixture exceeded its ${NATIVE_FIXTURE_RUN_TIMEOUT_MILLIS}ms run bound",
            )
        } else {
            val completed = result.get()
            primaryFailure = if (completed == null) {
                IllegalStateException("native Git fixture completed without a result")
            } else {
                completed.exceptionOrNull()
            }
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
    } finally {
        try {
            attemptCleanup { if (worker.isAlive) worker.interrupt() }
            val cleanupWait = joinNativeFixture(worker, NATIVE_FIXTURE_CLEANUP_TIMEOUT_MILLIS)
            interrupted = interrupted || cleanupWait.interrupted
            if (cleanupWait.interrupted) {
                recordCleanupFailure(InterruptedException("native Git fixture cleanup wait was interrupted"))
            }
            workerFailure.get()?.let(::recordCleanupFailure)
            val workerResultFailure = result.get()?.exceptionOrNull()
            if (workerResultFailure != null) {
                val primary = primaryFailure
                if (primary == null) {
                    recordCleanupFailure(workerResultFailure)
                } else if (primary !== workerResultFailure && primary.suppressed.none { it === workerResultFailure }) {
                    primary.addSuppressed(workerResultFailure)
                }
            }
            if (worker.isAlive) {
                recordCleanupFailure(
                    IllegalStateException("retaining native Git fixture root=$root home=$home: worker survived its bound"),
                )
            } else {
                attemptCleanup {
                    check(root.toFile().deleteRecursively() || Files.notExists(root)) {
                        "could not delete native Git fixture root $root"
                    }
                }
                attemptCleanup {
                    check(home.toFile().deleteRecursively() || Files.notExists(home)) {
                        "could not delete native Git fixture home $home"
                    }
                }
            }
        } catch (failure: Throwable) {
            recordCleanupFailure(failure)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    if (primaryFailure != null) {
        cleanupFailure?.let { mergeNativeFixtureFailure(primaryFailure, it) }
        throw primaryFailure
    }
    cleanupFailure?.let { throw it }
    return requireNotNull(result.get()) { "native Git fixture completed without a result" }.getOrThrow()
}

private data class NativeFixtureJoin(val stopped: Boolean, val interrupted: Boolean)

private fun joinNativeFixture(worker: Thread, timeoutMillis: Long): NativeFixtureJoin {
    var interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (worker.isAlive && System.nanoTime() < deadline) {
        try {
            val remaining = deadline - System.nanoTime()
            worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
        } catch (_: InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
    }
    return NativeFixtureJoin(!worker.isAlive, interrupted)
}

private fun mergeNativeFixtureFailure(existing: Throwable?, candidate: Throwable): Throwable {
    if (existing == null) return candidate
    if (existing !== candidate && existing.suppressed.none { it === candidate }) existing.addSuppressed(candidate)
    return existing
}
