package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private val foregroundLogger = KotlinLogging.logger {}

private const val FOREGROUND_FIXTURE_RUN_TIMEOUT_MILLIS = 30_000L
private const val FOREGROUND_FIXTURE_CLEANUP_TIMEOUT_MILLIS = 5_000L
private const val FOREGROUND_WORK_STABILITY_MILLIS = 100L

/**
 * Stage 0c G2/G2b: installed Git controls for the shared foreground settings. These are real Git processes over
 * disposable repositories; the only synthetic edge is the explicitly forced primary failure in the fallback case.
 */
class GitForegroundSettingsTest : FunSpec({

    test("installed Git honors both detach pins over local true values and performs real primary work") {
        withForegroundGitRepoHome { fixture ->
            val root = fixture.root
            val exec = fixture.exec
            val home = fixture.home
            exec.versionProbe().ok shouldBe true
            exec.run(listOf("init")).ok shouldBe true
            setLocal(exec, "maintenance.autoDetach", "true")
            setLocal(exec, "gc.autoDetach", "true")

            val provider = providerOver(exec, root, home, maintenance = {})
            repeat(4) { n ->
                provider.commit(TreePath.require("docs/primary-$n.md"), "primary-$n\n".toByteArray())
            }
            setLocal(exec, "maintenance.loose-objects.auto", "-1")
            setLocal(exec, "maintenance.loose-objects.enabled", "true")
            setLocal(exec, "maintenance.gc.enabled", "false")

            val effective = exec.run(
                listOf(
                    "config",
                    "--show-origin",
                    "--show-scope",
                    "--get-regexp",
                    "^(maintenance|gc)\\.autodetach$",
                ),
            )
            effective.ok shouldBe true
            fixture.diagnostic("primary-config", effective.stdoutText)
            effective.stdoutText shouldContain "maintenance.autodetach true"
            effective.stdoutText shouldContain "gc.autodetach true"
            effective.stdoutText shouldContain "maintenance.autodetach false"
            effective.stdoutText shouldContain "gc.autodetach false"

            val trace = home.resolve("primary-trace.json")
            val primary = exec.run(
                listOf("maintenance", "run", "--auto", "--quiet"),
                env = mapOf("GIT_TRACE2_EVENT" to trace.toString()),
            )
            primary.ok shouldBe true

            val traceText = Files.readString(trace)
            fixture.diagnostic("primary-trace", traceText)
            traceText shouldContain "\"name\":\"pack-objects\""
            traceText shouldContain "\"child_exit\""
            val stats = objectStats(exec)
            fixture.diagnostic("primary-counts", stats.toString())
            stats.getValue("packs").shouldBeGreaterThan(0)
            stats.getValue("in-pack").shouldBeGreaterThan(0)
            fixture.confirmCompletion("primary trace child and packed-object counts")
        }
    }

    test("forced primary failure delegates fallback to real Git and records automatic pack work") {
        withForegroundGitRepoHome { fixture ->
            val root = fixture.root
            val exec = fixture.exec
            val home = fixture.home
            exec.run(listOf("init")).ok shouldBe true
            setLocal(exec, "maintenance.autoDetach", "true")
            setLocal(exec, "gc.autoDetach", "true")

            val effective = exec.run(
                listOf(
                    "config",
                    "--show-origin",
                    "--show-scope",
                    "--get-regexp",
                    "^(maintenance|gc)\\.autodetach$",
                ),
            )
            effective.ok shouldBe true
            fixture.diagnostic("fallback-config", effective.stdoutText)
            val effectiveLines = effective.stdoutText.lineSequence().filter { it.isNotBlank() }.toList()
            effectiveLines.count {
                it.contains("local") && it.contains(".git/config") &&
                    it.trimEnd().endsWith("maintenance.autodetach true")
            } shouldBe 1
            effectiveLines.count {
                it.contains("local") && it.contains(".git/config") &&
                    it.trimEnd().endsWith("gc.autodetach true")
            } shouldBe 1
            effectiveLines.count {
                it.contains("command line:") && it.trimEnd().endsWith("maintenance.autodetach false")
            } shouldBe 1
            effectiveLines.count {
                it.contains("command line:") && it.trimEnd().endsWith("gc.autodetach false")
            } shouldBe 1
            listOf("maintenance.autoDetach", "gc.autoDetach").forEach { key ->
                val resolved = exec.run(listOf("config", "--get", key))
                resolved.ok shouldBe true
                resolved.stdoutText.trim() shouldBe "false"
            }

            val provider = providerOver(exec, root, home, maintenance = {})
            repeat(4) { n ->
                provider.commit(TreePath.require("docs/packed-$n.md"), "packed-$n\n".toByteArray())
            }
            exec.run(listOf("repack", "-ad")).ok shouldBe true
            repeat(4) { n ->
                provider.commit(TreePath.require("docs/loose-$n.md"), "loose-$n\n".toByteArray())
            }

            val objectIds = exec.run(listOf("rev-list", "--objects", "--all")).stdoutText.lineSequence()
                .map { it.substringBefore(' ') }
                .filter { it.matches(Regex("^[0-9a-f]{40}$")) }
                .distinct()
                .toList()
            val packedObjects = (objectIds.joinToString("\n") + "\n").toByteArray()
            listOf("foreground-one", "foreground-two").forEach { prefix ->
                exec.run(
                    listOf("pack-objects", "--revs", "--no-reuse-object", ".git/objects/pack/$prefix"),
                    stdin = packedObjects,
                ).ok shouldBe true
            }
            repeat(3) { n ->
                exec.run(listOf("hash-object", "-w", "--stdin"), stdin = "unreachable-$n\n".toByteArray()).ok shouldBe true
            }
            setLocal(exec, "gc.auto", "1")
            setLocal(exec, "gc.autoPackLimit", "1")
            val before = objectStats(exec)
            before.getValue("packs").shouldBeGreaterThan(1)
            before.getValue("count").shouldBeGreaterThan(0)

            val primaryArgs = listOf("maintenance", "run", "--auto", "--quiet")
            val fallbackArgs = listOf("gc", "--auto")
            val fallbackTrace = home.resolve("fallback-trace.json")
            val observed = spyk(exec)
            every { observed.run(match { it == primaryArgs }, any(), any()) } returns
                GitResult(exitCode = 17, stdout = ByteArray(0), stderr = "forced primary failure")
            every { observed.run(match { it == fallbackArgs }, any(), any()) } answers {
                exec.run(fallbackArgs, env = mapOf("GIT_TRACE2_EVENT" to fallbackTrace.toString()))
            }

            runAutoMaintenance(observed)

            verify(exactly = 1) { observed.run(match { it == primaryArgs }, any(), any()) }
            verify(exactly = 1) { observed.run(match { it == fallbackArgs }, any(), any()) }
            val after = awaitAutomaticWork(exec, before)
            fixture.diagnostic(
                "fallback-counts",
                "before=$before after=$after",
            )
            fixture.diagnostic(
                "fallback-trace",
                if (Files.exists(fallbackTrace)) Files.readString(fallbackTrace) else "<absent>",
            )
            (after.getValue("packs") < before.getValue("packs") || after.getValue("count") < before.getValue("count")) shouldBe true
            after["count"] shouldBe 0
            fixture.confirmCompletion("fallback stable pack/object counts")
        }
    }

    test("provider save and live-index sync preserve adopted bytes while hook and built-in fsmonitor stay disabled") {
        withForegroundGitRepoHome { fixture ->
            val root = fixture.root
            val exec = fixture.exec
            val home = fixture.home
            val path = TreePath.require("docs/adopted.md")
            val file = root.resolve(path.value)
            Files.createDirectories(file.parent)
            val oldBytes = "adopted before\n".toByteArray()
            Files.write(file, oldBytes)
            exec.run(listOf("init")).ok shouldBe true
            exec.run(listOf("add", "--", path.value)).ok shouldBe true
            exec.run(
                listOf("commit", "-m", "adopted seed"),
                env = IDENTITY_ENV,
            ).ok shouldBe true

            val hook = home.resolve("recording-fsmonitor-hook.sh")
            val receipt = home.resolve("fsmonitor-receipt")
            Files.writeString(
                hook,
                "#!/bin/sh\n" +
                    "printf 'fsmonitor %s %s\\n' \"${'$'}1\" \"${'$'}2\" >> \"${'$'}HOME/fsmonitor-receipt\"\n" +
                    "printf 'plainbase-token\\0/\\0'\n",
            )
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"))
            setLocal(exec, "core.fsmonitor", hook.toString())
            setLocal(exec, "core.fsmonitorHookVersion", "2")

            val newBytes = "adopted after\n".toByteArray()
            Files.write(file, newBytes)
            val provider = providerOver(exec, root, home, maintenance = {})
            val saved = provider.commit(path, newBytes)
            fixture.diagnostic(
                "hook-receipt-after-provider",
                if (Files.exists(receipt)) Files.readString(receipt) else "<absent>",
            )
            Files.exists(receipt) shouldBe false
            val status = exec.run(listOf("status", "--porcelain"))
            fixture.diagnostic("adopted-status", status.stdoutText.ifEmpty { "<empty>" })
            status.ok shouldBe true
            status.stdoutText shouldBe ""
            Files.readAllBytes(file).contentEquals(newBytes) shouldBe true

            openOracle(root).use { repo ->
                repo.headCommits().size shouldBe 2
                val head = repo.headCommits().first()
                head.name shouldBe saved.sha
                repo.blobBytes(head, path.value)?.contentEquals(newBytes) shouldBe true
            }
            exec.run(listOf("write-tree")).ok shouldBe true

            setLocal(exec, "core.fsmonitor", "true")
            Files.deleteIfExists(receipt)
            val builtInEffective = exec.run(
                listOf("config", "--show-origin", "--show-scope", "--get-regexp", "^core\\.fsmonitor$"),
            )
            fixture.diagnostic("built-in-fsmonitor-config", builtInEffective.stdoutText)
            builtInEffective.ok shouldBe true
            val coreLines = builtInEffective.stdoutText.lineSequence().filter { it.contains("core.fsmonitor") }.toList()
            coreLines.size shouldBe 2
            coreLines.count { it.contains("local") && it.contains(".git/config") && it.trimEnd().endsWith("core.fsmonitor true") } shouldBe
                1
            coreLines.count { it.contains("command line:") } shouldBe 1
            coreLines.single { it.contains("command line:") }.substringAfterLast('\t').trim() shouldBe "core.fsmonitor"
            val builtInStatus = exec.run(listOf("status", "--porcelain"))
            builtInStatus.ok shouldBe true
            builtInStatus.stdoutText shouldBe ""
            Files.exists(receipt) shouldBe false
            Files.exists(root.resolve(".git/fsmonitor--daemon.ipc")) shouldBe false
            fixture.confirmCompletion("provider bytes, history, clean index, and disabled fsmonitor")
        }
    }
})

private val IDENTITY_ENV = mapOf(
    "GIT_AUTHOR_NAME" to "Plainbase Test",
    "GIT_AUTHOR_EMAIL" to "test@plainbase.local",
    "GIT_AUTHOR_DATE" to "@1780272000 +0000",
    "GIT_COMMITTER_NAME" to "Plainbase Test",
    "GIT_COMMITTER_EMAIL" to "test@plainbase.local",
    "GIT_COMMITTER_DATE" to "@1780272000 +0000",
)

private fun setLocal(exec: GitExecutor, key: String, value: String) {
    exec.run(listOf("config", "--local", key, value)).ok shouldBe true
}

private fun objectStats(exec: GitExecutor): Map<String, Int> =
    exec.run(listOf("count-objects", "-v")).stdoutText.lineSequence().mapNotNull { line ->
        val key = line.substringBefore(' ', missingDelimiterValue = "").removeSuffix(":")
        val value = line.substringAfter(' ', missingDelimiterValue = "").trim().toIntOrNull()
        if (key.isNotEmpty() && value != null) key to value else null
    }.toMap()

private fun awaitAutomaticWork(exec: GitExecutor, before: Map<String, Int>): Map<String, Int> {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FOREGROUND_FIXTURE_RUN_TIMEOUT_MILLIS)
    var latest = objectStats(exec)
    while (System.nanoTime() < deadline) {
        val reduced = latest.getValue("packs") < before.getValue("packs") || latest.getValue("count") < before.getValue("count")
        if (reduced && latest.getValue("count") == 0) {
            try {
                Thread.sleep(FOREGROUND_WORK_STABILITY_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            val stable = objectStats(exec)
            if (stable == latest) return stable
            latest = stable
        } else {
            try {
                Thread.sleep(25L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            latest = objectStats(exec)
        }
    }
    return latest
}

private class ForegroundFixture(
    val root: Path,
    val exec: GitExecutor,
    val home: Path,
) {
    private var completionReceipt: String? = null

    fun diagnostic(name: String, value: String) {
        foregroundLogger.info {
            "foreground fixture diagnostic=$name root=$root home=$home\n$value"
        }
    }

    fun confirmCompletion(observation: String) {
        check(completionReceipt == null) { "foreground fixture completion was already confirmed" }
        completionReceipt = observation
        diagnostic("completion", observation)
    }

    fun hasCompletionReceipt(): Boolean = completionReceipt != null
}

private fun <T> withForegroundGitRepoHome(block: (ForegroundFixture) -> T): T {
    val root = Files.createTempDirectory("plainbase-git-foreground")
    val home = Files.createTempDirectory("plainbase-git-foreground-home")
    val fixture = ForegroundFixture(root, GitExecutor(workTree = root, home = home), home)
    val result = AtomicReference<Result<T>?>(null)
    val worker = thread(start = false, isDaemon = true, name = "plainbase-git-foreground-fixture") {
        result.set(runCatching { block(fixture) })
    }
    var parentInterrupted = Thread.interrupted()
    var primaryFailure: Throwable? = null
    fun recordCleanupFailure(candidate: Throwable) {
        val existing = primaryFailure
        if (existing == null) {
            primaryFailure = candidate
        } else {
            addJvmSuppressedIfNew(existing, candidate)
        }
    }
    try {
        if (parentInterrupted) {
            primaryFailure = InterruptedException("foreground Git fixture entry was interrupted")
        } else {
            worker.start()
            val runWait = joinForeground(worker, FOREGROUND_FIXTURE_RUN_TIMEOUT_MILLIS)
            parentInterrupted = parentInterrupted || runWait.interrupted
            if (runWait.interrupted) {
                primaryFailure = InterruptedException("foreground Git fixture wait was interrupted")
                worker.interrupt()
            } else if (!runWait.stopped) {
                primaryFailure = TimeoutException(
                    "foreground Git fixture exceeded its ${FOREGROUND_FIXTURE_RUN_TIMEOUT_MILLIS}ms run bound",
                )
                worker.interrupt()
            } else {
                val completed = result.get()
                if (completed == null) {
                    primaryFailure = IllegalStateException("foreground Git fixture completed without a result")
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
                val cleanup = cleanupForegroundFixture(
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
    return requireNotNull(result.get()) { "foreground Git fixture completed without a result" }.getOrThrow()
}

private data class ForegroundJoinResult(val stopped: Boolean, val interrupted: Boolean)

private data class ForegroundCleanupResult(
    val failure: Throwable?,
    val workerFailure: Throwable?,
    val interrupted: Boolean,
)

private fun addJvmSuppressedIfNew(primary: Throwable, candidate: Throwable) {
    if (candidate === primary || primary.suppressed.any { it === candidate }) return
    primary.addSuppressed(candidate)
}

private fun cleanupForegroundFixture(
    fixture: ForegroundFixture,
    worker: Thread,
    allowDelete: Boolean,
    workerFailure: () -> Throwable?,
): ForegroundCleanupResult {
    var interrupted = false
    if (worker.isAlive) {
        val cleanupWait = joinForeground(worker, FOREGROUND_FIXTURE_CLEANUP_TIMEOUT_MILLIS)
        interrupted = cleanupWait.interrupted
    }
    val actualWorkerFailure = workerFailure()
    if (worker.isAlive || !allowDelete || !fixture.hasCompletionReceipt()) {
        val reason = when {
            worker.isAlive -> "owned fixture worker survived its cleanup bound"
            !allowDelete -> "primary failure or interruption left fixture work unconfirmed"
            else -> "fixture returned without a positive completion observation"
        }
        val retained = IllegalStateException("retaining foreground Git fixture root=${fixture.root} home=${fixture.home}: $reason")
        foregroundLogger.warn(retained) { retained.message ?: "retaining foreground Git fixture" }
        return ForegroundCleanupResult(retained, actualWorkerFailure, interrupted)
    }
    val rootDeleted = runCatching { fixture.root.toFile().deleteRecursively() }.getOrDefault(false)
    val homeDeleted = if (rootDeleted) runCatching { fixture.home.toFile().deleteRecursively() }.getOrDefault(false) else false
    if (!rootDeleted || !homeDeleted) {
        val retained = IllegalStateException(
            "retaining foreground Git fixture after cleanup failure root=${fixture.root} home=${fixture.home}",
        )
        foregroundLogger.warn(retained) { retained.message ?: "retaining foreground Git fixture" }
        return ForegroundCleanupResult(retained, actualWorkerFailure, interrupted)
    }
    return ForegroundCleanupResult(null, actualWorkerFailure, interrupted)
}

private fun joinForeground(worker: Thread, timeoutMillis: Long): ForegroundJoinResult {
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
    return ForegroundJoinResult(!worker.isAlive, interrupted)
}
