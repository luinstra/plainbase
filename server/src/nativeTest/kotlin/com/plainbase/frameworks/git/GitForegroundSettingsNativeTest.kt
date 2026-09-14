package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val foregroundNativeLogger = KotlinLogging.logger {}

private const val FOREGROUND_NATIVE_RUN_TIMEOUT_MILLIS = 30_000L
private const val FOREGROUND_NATIVE_CLEANUP_TIMEOUT_MILLIS = 5_000L

/** Lean native-source coverage for the real provider save/live-index process surface. */
@Tag("native")
class GitForegroundSettingsNativeTest {

    @Test
    fun `real Git provider save preserves bytes and a clean live index with foreground pins`() {
        withForegroundNativeGitRepo { fixture ->
            val root = fixture.root
            val home = fixture.home
            val exec = fixture.exec
            assertTrue(exec.versionProbe().ok, "installed Git must be runnable")
            assertTrue(exec.run(listOf("init")).ok, "the disposable repository must initialize")
            assertTrue(exec.run(listOf("config", "--local", "core.fsmonitor", "true")).ok)
            val effective = exec.run(
                listOf(
                    "config",
                    "--show-origin",
                    "--show-scope",
                    "--get-regexp",
                    "^(maintenance|gc)\\.autodetach$|^core\\.fsmonitor$",
                ),
            )
            assertTrue(effective.ok, "pinned foreground settings must be readable")
            fixture.diagnostic("effective-config", effective.stdoutText)
            assertTrue(effective.stdoutText.contains("maintenance.autodetach false"))
            assertTrue(effective.stdoutText.contains("gc.autodetach false"))
            val coreLines = effective.stdoutText.lineSequence().filter { it.contains("core.fsmonitor") }.toList()
            assertEquals(2, coreLines.size)
            assertEquals(
                1,
                coreLines.count {
                it.contains("local") && it.contains(".git/config") &&
                it.trimEnd().endsWith("core.fsmonitor true")
            },
            )
            assertEquals(1, coreLines.count { it.contains("command line:") })
            assertEquals("core.fsmonitor", coreLines.single { it.contains("command line:") }.substringAfterLast('\t').trim())

            val path = TreePath.require("docs/native-foreground.md")
            val bytes = "native foreground save\n".toByteArray()
            Files.createDirectories(root.resolve("docs"))
            Files.write(root.resolve(path.value), bytes)
            val provider = GitCliHistoryProvider(
                exec = exec,
                workTree = root,
                gitHome = home,
                defaultAuthor = CommitIdentity("Plainbase", "plainbase@localhost"),
                defaultCommitter = CommitIdentity("Plainbase", "plainbase@localhost"),
                clock = object : Clock {
                    override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
                },
                maintenance = {},
            )
            val commit = provider.commit(path, bytes)

            assertEquals(commit.sha, exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim())
            assertTrue(exec.run(listOf("show", "HEAD:docs/native-foreground.md")).stdout.contentEquals(bytes))
            val status = exec.run(listOf("status", "--porcelain"))
            assertTrue(status.ok)
            assertEquals("", status.stdoutText)
            assertTrue(Files.readAllBytes(root.resolve(path.value)).contentEquals(bytes))
            fixture.confirmCompletion("provider bytes and clean live index")
        }
    }
}

private class NativeForegroundFixture(
    val root: Path,
    val exec: GitExecutor,
    val home: Path,
) {
    private var completionReceipt: String? = null

    fun diagnostic(name: String, value: String) {
        foregroundNativeLogger.info {
            "foreground native fixture diagnostic=$name root=$root home=$home\n$value"
        }
    }

    fun confirmCompletion(observation: String) {
        check(completionReceipt == null) { "foreground native fixture completion was already confirmed" }
        completionReceipt = observation
        diagnostic("completion", observation)
    }

    fun hasCompletionReceipt(): Boolean = completionReceipt != null
}

private fun withForegroundNativeGitRepo(block: (NativeForegroundFixture) -> Unit) {
    val root = Files.createTempDirectory("plainbase-git-foreground-native")
    val home = Files.createTempDirectory("plainbase-git-foreground-native-home")
    val fixture = NativeForegroundFixture(root, GitExecutor(workTree = root, home = home), home)
    val result = AtomicReference<Result<Unit>?>(null)
    val worker = thread(start = false, isDaemon = true, name = "plainbase-git-foreground-native-fixture") {
        result.set(runCatching { block(fixture) })
    }
    var parentInterrupted = Thread.interrupted()
    var primaryFailure: Throwable? = null
    fun recordCleanupFailure(candidate: Throwable) {
        val existing = primaryFailure
        if (existing == null) {
            primaryFailure = candidate
        } else {
            addNativeSuppressedIfNew(existing, candidate)
        }
    }
    try {
        if (parentInterrupted) {
            primaryFailure = InterruptedException("foreground native Git fixture entry was interrupted")
        } else {
            worker.start()
            val runWait = joinNativeForeground(worker, FOREGROUND_NATIVE_RUN_TIMEOUT_MILLIS)
            parentInterrupted = parentInterrupted || runWait.interrupted
            if (runWait.interrupted) {
                primaryFailure = InterruptedException("foreground native Git fixture wait was interrupted")
                worker.interrupt()
            } else if (!runWait.stopped) {
                primaryFailure = TimeoutException(
                    "foreground native Git fixture exceeded its ${FOREGROUND_NATIVE_RUN_TIMEOUT_MILLIS}ms run bound",
                )
                worker.interrupt()
            } else {
                val completed = result.get()
                if (completed == null) {
                    primaryFailure = IllegalStateException("foreground native Git fixture completed without a result")
                } else {
                    primaryFailure = completed.exceptionOrNull()
                }
            }
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
    } finally {
        try {
            try {
                val cleanup = cleanupNativeForegroundFixture(
                    fixture = fixture,
                    worker = worker,
                    allowDelete = primaryFailure == null,
                    workerFailure = { result.get()?.exceptionOrNull() },
                )
                parentInterrupted = parentInterrupted || cleanup.interrupted
                cleanup.workerFailure?.let(::recordCleanupFailure)
                cleanup.failure?.let(::recordCleanupFailure)
            } catch (cleanupFailure: Throwable) {
                recordCleanupFailure(cleanupFailure)
            }
        } finally {
            if (parentInterrupted) Thread.currentThread().interrupt()
        }
    }
    primaryFailure?.let { throw it }
    requireNotNull(result.get()) { "foreground native Git fixture completed without a result" }.getOrThrow()
}

private data class NativeForegroundJoinResult(val stopped: Boolean, val interrupted: Boolean)

private data class NativeForegroundCleanupResult(
    val failure: Throwable?,
    val workerFailure: Throwable?,
    val interrupted: Boolean,
)

private fun addNativeSuppressedIfNew(primary: Throwable, candidate: Throwable) {
    if (candidate === primary || primary.suppressed.any { it === candidate }) return
    primary.addSuppressed(candidate)
}

private fun cleanupNativeForegroundFixture(
    fixture: NativeForegroundFixture,
    worker: Thread,
    allowDelete: Boolean,
    workerFailure: () -> Throwable?,
): NativeForegroundCleanupResult {
    var interrupted = false
    if (worker.isAlive) {
        val cleanupWait = joinNativeForeground(worker, FOREGROUND_NATIVE_CLEANUP_TIMEOUT_MILLIS)
        interrupted = cleanupWait.interrupted
    }
    val actualWorkerFailure = workerFailure()
    if (worker.isAlive || !allowDelete || !fixture.hasCompletionReceipt()) {
        val reason = when {
            worker.isAlive -> "owned fixture worker survived its cleanup bound"
            !allowDelete -> "primary failure or interruption left fixture work unconfirmed"
            else -> "fixture returned without a positive completion observation"
        }
        val retained = IllegalStateException(
            "retaining foreground native Git fixture root=${fixture.root} home=${fixture.home}: $reason",
        )
        foregroundNativeLogger.warn(retained) { retained.message ?: "retaining foreground native Git fixture" }
        return NativeForegroundCleanupResult(retained, actualWorkerFailure, interrupted)
    }
    val rootDeleted = runCatching { fixture.root.toFile().deleteRecursively() }.getOrDefault(false)
    val homeDeleted = if (rootDeleted) runCatching { fixture.home.toFile().deleteRecursively() }.getOrDefault(false) else false
    if (!rootDeleted || !homeDeleted) {
        val retained = IllegalStateException(
            "retaining foreground native Git fixture after cleanup failure root=${fixture.root} home=${fixture.home}",
        )
        foregroundNativeLogger.warn(retained) { retained.message ?: "retaining foreground native Git fixture" }
        return NativeForegroundCleanupResult(retained, actualWorkerFailure, interrupted)
    }
    return NativeForegroundCleanupResult(null, actualWorkerFailure, interrupted)
}

private fun joinNativeForeground(worker: Thread, timeoutMillis: Long): NativeForegroundJoinResult {
    var interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (worker.isAlive && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        try {
            worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    return NativeForegroundJoinResult(!worker.isAlive, interrupted)
}
