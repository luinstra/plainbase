package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real-child proof that the retained Plainbase hook converges while CIO's actual hook is enabled. */
class ServerRunHookTest : FunSpec({

    test("healthz then SIGTERM completes each real close once and terminates the child") {
        withParentWatchdog { watchdog -> runHookScenario(watchdog) }
    }
})

private const val BOOT_DEADLINE_MILLIS = 30_000L
private const val SHUTDOWN_DEADLINE_MILLIS = 15_000L
private const val STREAM_DEADLINE_MILLIS = 5_000L
private const val STREAM_CLOSE_JOIN_DEADLINE_MILLIS = 500L
private const val PROCESS_TREE_CLEANUP_MILLIS = 10_000L
private const val FORCE_KILL_REAP_DEADLINE_MILLIS = 2_000L
private const val KILL_HELPER_DEADLINE_MILLIS = 5_000L
private const val PARENT_WATCHDOG_DEADLINE_MILLIS = 65_000L
private const val EVIDENCE_DIRECTORY_PROPERTY = "plainbase.test.evidenceDir"
private const val EVIDENCE_PREFIX = "server-run-hook"

private fun runHookScenario(watchdog: ParentWatchdog) {
    val base = Files.createTempDirectory("plainbase-server-hook")
    val content = Files.createDirectory(base.resolve("content"))
    val data = Files.createDirectory(base.resolve("data"))
    val report = base.resolve("receipts.txt")
    val port = ServerSocket(0).use { it.localPort }
    var child: HookRunningChild? = null
    var primary: Throwable? = null
    var observed: ChildObservation? = null
    try {
        Files.writeString(content.resolve("readme.md"), "---\ntitle: Readme\n---\n\n# Readme\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val mainRuntime = requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")) {
            "plainbase.test.mainRuntimeClasspath is required"
        }
        val nativeTestClasses = findNativeTestClasses()
        require(Files.isDirectory(nativeTestClasses)) { "nativeTest code-source output is missing: $nativeTestClasses" }
        val classpath = mainRuntime + File.pathSeparator + nativeTestClasses
        val command = listOf(
            java.toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-Dio.ktor.server.engine.ShutdownHook=true",
            "-cp",
            classpath,
            "com.plainbase.ServerLifecycleLauncherKt",
            "--content",
            content.toString(),
            "--data",
            data.toString(),
            "--report",
            report.toString(),
            "--port",
            port.toString(),
        )
        val environment = isolatedEnvironment(base, data, content)
        val builder = ProcessBuilder(command)
        builder.directory(base.toFile())
        builder.environment().clear()
        builder.environment().putAll(environment)
        child = HookRunningChild(command, environment, builder.start(), watchdog)

        val health = child.awaitHealth(port)
        health.statusCode shouldBe 200
        health.body shouldContain "\"status\":\"ok\""
        child.sendSigterm()
        child.awaitExit(SHUTDOWN_DEADLINE_MILLIS)
    } catch (failure: Throwable) {
        primary = failure
    }

    val closeFailure = runCatching { child?.close() }.exceptionOrNull()
    if (closeFailure != null) {
        primary = primary?.also { it.addSuppressed(closeFailure) } ?: closeFailure
    }
    observed = child?.snapshot()

    try {
        if (primary == null) {
            val result = requireNotNull(observed) { "child observation is missing" }
            result.exitCode shouldBe 143
            result.signalExitCode shouldBe 0
            (result.startupMillis >= 0L) shouldBe true
            (result.shutdownMillis >= 0L) shouldBe true
            val receipts = Files.readAllLines(report)
            receipts.shouldContainExactlyInAnyOrder(
                "close=app lockHeld=true",
                "close=search lockHeld=true",
                "close=context lockHeld=true",
            )
            val combinedOutput = result.stdoutText + result.stderrText
            combinedOutput shouldNotContain "failed; continuing with the remaining steps"
        }
    } catch (failure: Throwable) {
        primary = primary?.also { it.addSuppressed(failure) } ?: failure
    }

    val evidenceFailure = runCatching { writeEvidence(observed, report, primary) }.exceptionOrNull()
    if (evidenceFailure != null) {
        primary = primary?.also { it.addSuppressed(evidenceFailure) } ?: evidenceFailure
    }
    val cleanupFailure = runCatching {
        deleteTree(base, System.nanoTime() + PROCESS_TREE_CLEANUP_MILLIS * 1_000_000)
        check(!Files.exists(base)) { "fixture cleanup left files" }
    }.exceptionOrNull()
    if (cleanupFailure != null) {
        primary = primary?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure
    }
    if (primary != null) throw primary
}

private fun isolatedEnvironment(base: Path, data: Path, content: Path): Map<String, String> {
    val git = resolvedGit()
    val locale = fixedLocale()
    return linkedMapOf(
        "PATH" to "${git.parent}${File.pathSeparator}/usr/bin${File.pathSeparator}/bin",
        "JAVA_HOME" to Path.of(System.getProperty("java.home")).toString(),
        "HOME" to Files.createDirectory(base.resolve("home")).toString(),
        "TMPDIR" to Files.createDirectory(base.resolve("tmp")).toString(),
        "LANG" to locale,
        "LC_ALL" to locale,
        "DATA_DIR" to data.toString(),
        "CONTENT_DIR" to content.toString(),
        "PLAINBASE_LOG_LEVEL" to "INFO",
    )
}

private class HookRunningChild(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val process: Process,
    private val watchdog: ParentWatchdog,
) : AutoCloseable {
    private val startedAt = System.nanoTime()
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private val drainFailure = AtomicReference<Throwable?>()
    private val stdoutDrain = drain(process.inputStream, stdout, "$EVIDENCE_PREFIX-stdout", drainFailure)
    private val stderrDrain = drain(process.errorStream, stderr, "$EVIDENCE_PREFIX-stderr", drainFailure)
    private val ownedHandles = linkedSetOf(process.toHandle())
    private var signalSentAt = 0L
    private var signalExitCode = -1
    private var healthStatus = -1
    private var healthBody = ""
    private var healthyAt = 0L
    private var terminatedAt = 0L
    private var streamsFinished = false

    fun awaitHealth(port: Int): HookHealthObservation {
        val deadline = watchdog.operationDeadline(BOOT_DEADLINE_MILLIS)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        var lastFailure = "no response"
        while (System.nanoTime() < deadline) {
            watchdog.check()
            refreshOwnedHandles()
            if (!process.isAlive) error("child exited before healthz: ${diagnostics()}")
            val response = runCatching {
                client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/healthz"))
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
            }.getOrNull()
            if (response != null) {
                healthStatus = response.statusCode()
                healthBody = response.body()
                if (healthStatus == 200) {
                    healthyAt = System.nanoTime()
                    return HookHealthObservation(healthStatus, healthBody)
                }
                lastFailure = "HTTP $healthStatus: $healthBody"
            }
            Thread.sleep(100)
        }
        error("child did not reach healthz within ${BOOT_DEADLINE_MILLIS}ms: $lastFailure; ${diagnostics()}")
    }

    fun sendSigterm() {
        check(process.isAlive) { "child is not alive before SIGTERM" }
        refreshOwnedHandles()
        signalSentAt = System.nanoTime()
        val signal = ProcessBuilder("/bin/kill", "-TERM", process.pid().toString()).start()
        ownedHandles += signal.toHandle()
        signal.outputStream.close()
        try {
            if (!signal.waitFor(KILL_HELPER_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)) {
                signal.destroyForcibly()
                check(signal.waitFor(KILL_HELPER_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)) {
                    "/bin/kill helper did not terminate"
                }
            }
            signalExitCode = signal.exitValue()
            check(signalExitCode == 0) { "/bin/kill -TERM ${process.pid()} exited $signalExitCode" }
        } finally {
            signal.inputStream.close()
            signal.errorStream.close()
        }
        refreshOwnedHandles()
    }

    fun awaitExit(deadlineMillis: Long) {
        val deadline = watchdog.operationDeadline(deadlineMillis)
        while (process.isAlive) {
            watchdog.check()
            refreshOwnedHandles()
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) error("child did not exit within ${deadlineMillis}ms: ${diagnostics()}")
            process.waitFor(minOf(100L, remaining), TimeUnit.MILLISECONDS)
        }
        terminatedAt = System.nanoTime()
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            terminateOwned()
        } catch (cleanupFailure: Throwable) {
            failure = cleanupFailure
        }
        try {
            finishStreams()
        } catch (streamFailure: Throwable) {
            failure = failure?.also { it.addSuppressed(streamFailure) } ?: streamFailure
        }
        if (failure != null) throw failure
    }

    fun snapshot(): ChildObservation =
        ChildObservation(
            command = command,
            environment = environment,
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            stdout = stdout.toByteArray(),
            stderr = stderr.toByteArray(),
            durationMillis = elapsedMillis(startedAt),
            startupMillis = if (healthyAt == 0L) -1 else elapsedMillis(startedAt, healthyAt),
            shutdownMillis = if (signalSentAt == 0L || terminatedAt == 0L) -1 else elapsedMillis(signalSentAt, terminatedAt),
            signalExitCode = signalExitCode,
            healthStatus = healthStatus,
            healthBody = healthBody,
        )

    private fun terminateOwned() {
        val cleanupDeadline = System.nanoTime() + PROCESS_TREE_CLEANUP_MILLIS * 1_000_000
        val gracefulDeadline = cleanupDeadline - FORCE_KILL_REAP_DEADLINE_MILLIS * 1_000_000
        refreshOwnedHandles()
        ownedHandles.filter { it != process.toHandle() && it.isAlive }.asReversed().forEach { it.destroy() }
        if (process.isAlive) process.destroy()
        if (waitForOwned(gracefulDeadline)) return

        refreshOwnedHandles()
        ownedHandles.filter { it.isAlive }.asReversed().forEach { it.destroyForcibly() }
        if (!waitForOwned(cleanupDeadline)) {
            refreshOwnedHandles()
            val survivors = ownedHandles.filter { it.isAlive }.joinToString(",") { it.pid().toString() }
            error("owned process survivors after ${PROCESS_TREE_CLEANUP_MILLIS}ms: $survivors")
        }
    }

    private fun waitForOwned(deadline: Long): Boolean {
        while (System.nanoTime() < deadline) {
            refreshOwnedHandles()
            if (ownedHandles.none { it.isAlive }) return true
            try {
                Thread.sleep(minOf(25L, remainingMillis(deadline)))
            } catch (_: InterruptedException) {
                Thread.interrupted()
            }
        }
        refreshOwnedHandles()
        return ownedHandles.none { it.isAlive }
    }

    private fun refreshOwnedHandles() {
        if (process.isAlive) ownedHandles.addAll(process.toHandle().descendants().toList())
    }

    private fun finishStreams() {
        if (streamsFinished) return
        val deadline = System.nanoTime() + STREAM_DEADLINE_MILLIS * 1_000_000
        val initialDeadline = deadline - STREAM_CLOSE_JOIN_DEADLINE_MILLIS * 1_000_000
        joinDrain(stdoutDrain, initialDeadline)
        joinDrain(stderrDrain, initialDeadline)
        if (stdoutDrain.isAlive || stderrDrain.isAlive) {
            process.inputStream.close()
            process.errorStream.close()
            joinDrain(stdoutDrain, deadline)
            joinDrain(stderrDrain, deadline)
        }
        check(!stdoutDrain.isAlive && !stderrDrain.isAlive) {
            "stream drain exceeded ${STREAM_DEADLINE_MILLIS}ms: ${diagnostics()}"
        }
        streamsFinished = true
        drainFailure.get()?.let { failure -> throw IllegalStateException("stream drain failed", failure) }
    }

    private fun joinDrain(drain: Thread, deadline: Long) {
        while (drain.isAlive) {
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) return
            try {
                drain.join(remaining)
            } catch (_: InterruptedException) {
                Thread.interrupted()
                return
            }
        }
    }

    private fun diagnostics(): String =
        "stdout=${stdout.toString(StandardCharsets.UTF_8)} stderr=${stderr.toString(StandardCharsets.UTF_8)}"
}

private data class HookHealthObservation(val statusCode: Int, val body: String)

private data class ChildObservation(
    val command: List<String>,
    val environment: Map<String, String>,
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val durationMillis: Long,
    val startupMillis: Long,
    val shutdownMillis: Long,
    val signalExitCode: Int,
    val healthStatus: Int,
    val healthBody: String,
) {
    val stdoutText: String get() = stdout.toString(StandardCharsets.UTF_8)
    val stderrText: String get() = stderr.toString(StandardCharsets.UTF_8)
}

private class ParentWatchdog : AutoCloseable {
    private val owner = Thread.currentThread()
    private val expired = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor(HookWatchdogThreadFactory)
    private val deadline = System.nanoTime() + PARENT_WATCHDOG_DEADLINE_MILLIS * 1_000_000
    private val alarm: ScheduledFuture<*> = scheduler.schedule({
        expired.set(true)
        owner.interrupt()
    }, PARENT_WATCHDOG_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)

    fun check() {
        if (expired.get() || System.nanoTime() >= deadline) {
            expired.set(true)
            Thread.interrupted()
            throw TimeoutException("parent watchdog exceeded ${PARENT_WATCHDOG_DEADLINE_MILLIS}ms")
        }
    }

    fun hasExpired(): Boolean = expired.get() || System.nanoTime() >= deadline

    fun operationDeadline(durationMillis: Long): Long {
        check()
        return minOf(deadline, System.nanoTime() + durationMillis * 1_000_000)
    }

    override fun close() {
        alarm.cancel(false)
        scheduler.shutdownNow()
        Thread.interrupted()
    }
}

private object HookWatchdogThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "plainbase-server-run-hook-parent-watchdog").apply { isDaemon = true }
}

private fun <T> withParentWatchdog(block: (ParentWatchdog) -> T): T {
    val watchdog = ParentWatchdog()
    try {
        val result = block(watchdog)
        watchdog.check()
        return result
    } catch (caught: Throwable) {
        if (watchdog.hasExpired()) {
            Thread.interrupted()
            throw TimeoutException("parent watchdog exceeded ${PARENT_WATCHDOG_DEADLINE_MILLIS}ms").also {
                it.addSuppressed(caught)
            }
        }
        throw caught
    } finally {
        watchdog.close()
    }
}

private fun writeEvidence(observed: ChildObservation?, report: Path, failure: Throwable?) {
    val evidence = Path.of(
        requireNotNull(System.getProperty(EVIDENCE_DIRECTORY_PROPERTY)) {
        "$EVIDENCE_DIRECTORY_PROPERTY is required; launch ./gradlew :server:test"
    },
    ).toAbsolutePath().normalize()
    Files.createDirectories(evidence)
    val result = observed
    if (result == null) {
        Files.writeString(evidence.resolve("$EVIDENCE_PREFIX.meta"), "outcome=FAILURE\nno_child_observation=true\n")
        return
    }
    Files.write(evidence.resolve("$EVIDENCE_PREFIX.stdout"), result.stdout)
    Files.write(evidence.resolve("$EVIDENCE_PREFIX.stderr"), result.stderr)
    if (Files.exists(report)) {
        Files.copy(report, evidence.resolve("$EVIDENCE_PREFIX.receipts"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } else {
        Files.write(evidence.resolve("$EVIDENCE_PREFIX.receipts"), ByteArray(0))
    }
    Files.writeString(
        evidence.resolve("$EVIDENCE_PREFIX.meta"),
        buildString {
            appendLine("outcome=${if (failure == null) "PASS" else "FAILURE"}")
            appendLine("command=")
            result.command.forEach(::appendLine)
            appendLine("environment=")
            result.environment.forEach { (key, value) -> appendLine("$key=$value") }
            appendLine("exit=${result.exitCode}")
            appendLine("duration_ms=${result.durationMillis}")
            appendLine("startup_ms=${result.startupMillis}")
            appendLine("shutdown_ms=${result.shutdownMillis}")
            appendLine("health_status=${result.healthStatus}")
            appendLine("health_body=${result.healthBody}")
            appendLine("kill_exit=${result.signalExitCode}")
            if (failure != null) {
                appendLine("failure_type=${failure::class.java.name}")
                appendLine("failure_message=${failure.message}")
            }
        },
    )
}

private fun findNativeTestClasses(): Path =
    Path.of(
        requireNotNull(Class.forName("com.plainbase.ServerLifecycleLauncherKt").protectionDomain.codeSource) {
            "launcher code source is missing"
        }.location.toURI(),
    ).toAbsolutePath().normalize()

private fun resolvedGit(): Path {
    val path = System.getenv("PATH").orEmpty()
    return path.split(File.pathSeparator)
        .asSequence()
        .map { Path.of(it).resolve("git") }
        .firstOrNull { Files.isExecutable(it) }
        ?.toRealPath()
        ?: error("git is required on the test JVM PATH")
}

private fun fixedLocale(): String =
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) "en_US.UTF-8" else "C.UTF-8"

private fun drain(
    input: InputStream,
    output: ByteArrayOutputStream,
    name: String,
    failure: AtomicReference<Throwable?>,
): Thread =
    thread(start = true, isDaemon = true, name = name) {
        try {
            input.use { it.copyTo(output) }
        } catch (drainFailure: Throwable) {
            failure.compareAndSet(null, drainFailure)
        }
    }

private fun deleteTree(root: Path, deadline: Long) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach {
            check(remainingMillis(deadline) != 0L) { "fixture cleanup exceeded ${PROCESS_TREE_CLEANUP_MILLIS}ms" }
            Files.deleteIfExists(it)
        }
    }
}

private fun remainingMillis(deadline: Long): Long {
    val remaining = deadline - System.nanoTime()
    return if (remaining <= 0L) 0L else maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))
}

private fun elapsedMillis(started: Long): Long = elapsedMillis(started, System.nanoTime())

private fun elapsedMillis(started: Long, ended: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(ended - started)
