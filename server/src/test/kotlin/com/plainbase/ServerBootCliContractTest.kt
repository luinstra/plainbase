package com.plainbase

import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.GitResult
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.DriverManager
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

// JVM-only: this contract launches and supervises a real server JVM.
class ServerBootCliContractTest : FunSpec({
    val watchdog = SuiteWatchdog()

    beforeSpec {
        httpClient = createHttpClient()
        watchdog.start()
        verifyMainRuntimeClasspath(watchdog)
    }

    afterSpec {
        try {
            if (::httpClient.isInitialized) httpClient.close()
        } finally {
            watchdog.close()
        }
    }

    test("invalidLocalConfig") {
        watchdog.runCase("invalidLocalConfig") {
            Fixture("invalid-local", watchdog).use { fixture ->
                val result = fixture.run("invalidLocalConfig", port = "80x0")
                result.exitCode shouldBe 1
                result.stderrText shouldContain "serve: PLAINBASE_PORT must be an integer, got '80x0'"
            }
        }
    }

    test("missingPrimaryRoot") {
        watchdog.runCase("missingPrimaryRoot") {
            Fixture("missing-primary", watchdog, missingContent = true).use { fixture ->
                val result = fixture.runHealthy("missingPrimaryRoot")
                val warning =
                    "root 'docs' is not available at ${fixture.content}: it will serve 503 until the path is " +
                        "restored and the server restarted (its pages, aliases and checkpoints are left untouched)"
                result.healthStatus shouldBe 200
                result.healthBody shouldContain "\"status\":\"ok\""
                result.healthBody shouldContain "\"root\":\"docs\""
                result.healthBody shouldContain "\"available\":false"
                result.healthBody shouldContain "\"reason\":\"missing_at_boot\""
                result.stderrText shouldContain warning
                result.signalExitCode shouldBe 0
                result.exitCode shouldBe SIGTERM_EXIT_STATUS
            }
        }
    }

    test("invalidGitRepository") {
        watchdog.runCase("invalidGitRepository") {
            Fixture("invalid-git", watchdog, gitRepository = true).use { fixture ->
                val git = GitExecutor(fixture.content, fixture.home)
                val version = git.versionProbe()
                version.ok shouldBe true
                val match = requireNotNull(Regex("git version (\\d+)\\.(\\d+)").find(version.stdoutText))
                val major = match.groupValues[1].toInt()
                val minor = match.groupValues[2].toInt()
                (major > 2 || (major == 2 && minor >= 31)) shouldBe true
                version.stdoutText shouldContain "git version"
                val access = git.run(listOf("rev-parse", "--is-inside-work-tree"))
                writeGitPreflightEvidence(version, access)
                access.ok shouldBe false
                access.exitCode shouldBe 128
                access.stderr.shouldNotBeBlank()
                access.stdoutText.trim() shouldBe ""

                val result = fixture.run("invalidGitRepository", gitEnabled = true)
                result.exitCode shouldBe 1
                result.stderrText shouldContain "serve: Git mode is on but the repository at ${fixture.content} is not accessible"
                result.stderrText shouldContain "set PLAINBASE_GIT_ENABLED=false to run without history"
            }
        }
    }

    test("invalidObjectEndpointRemainsPreLock") {
        watchdog.runCase("invalidObjectEndpointRemainsPreLock") {
            Fixture("invalid-object-endpoint", watchdog).use { fixture ->
                val result = fixture.runHoldingDataDirLock(
                    "invalidObjectEndpointRemainsPreLock", storageBackend = "object",
                    extraEnvironment = mapOf(
                        "PLAINBASE_S3_ENDPOINT" to "not-an-http-url",
                        "PLAINBASE_S3_BUCKET" to "docs",
                        "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
                        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
                    ),
                )
                result.exitCode shouldBe 1
                result.stderrText shouldContain "serve: storage.object.endpoint is not an absolute http(s) URL"
                result.stderrText shouldNotContain "another Plainbase process is holding"
            }
        }
    }

    test("missingObjectCredentialsRemainPreLock") {
        watchdog.runCase("missingObjectCredentialsRemainPreLock") {
            Fixture("missing-object-credentials", watchdog).use { fixture ->
                val result = fixture.runHoldingDataDirLock(
                    "missingObjectCredentialsRemainPreLock", storageBackend = "object",
                    extraEnvironment = mapOf(
                        "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:1",
                        "PLAINBASE_S3_BUCKET" to "docs",
                    ),
                )
                result.exitCode shouldBe 1
                result.stderrText shouldContain "serve: PLAINBASE_S3_ACCESS_KEY_ID and PLAINBASE_S3_SECRET_ACCESS_KEY are required"
                result.stderrText shouldNotContain "another Plainbase process is holding"
            }
        }
    }

    test("heldDataLock") {
        watchdog.runCase("heldDataLock") {
            Fixture("held-data-lock", watchdog).use { fixture ->
                seedCliV17Database(fixture.data.resolve("plainbase.db"))
                seedCliSearchDatabase(fixture.data.resolve("search.db"))
                val mirror = Files.createDirectories(fixture.data.resolve("mirror"))
                Files.writeString(mirror.resolve("sentinel.md"), "must remain untouched")
                val before = captureCliHeldLockObservation(fixture.data, mirror)
                val lock = requireNotNull(DataDirLock.tryAcquire(fixture.data))
                try {
                    val result = fixture.run("heldDataLock")
                    result.exitCode shouldBe 1
                    result.stderrText shouldContain "serve: another Plainbase process is holding ${fixture.data}"
                    result.stderrText shouldContain "stop it before starting a second instance"
                    val objectResult = fixture.run(
                        "heldDataLockObjectPriorSchema",
                        storageBackend = "object",
                        extraEnvironment = mapOf(
                            "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:1",
                            "PLAINBASE_S3_BUCKET" to "docs",
                            "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
                            "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
                        ),
                    )
                    objectResult.exitCode shouldBe 1
                    objectResult.stderrText shouldContain "serve: another Plainbase process is holding ${fixture.data}"
                    val after = captureCliHeldLockObservation(fixture.data, mirror)
                    after shouldBe before
                    writeCliHeldLockObservation(before, after)
                } finally {
                    lock.close()
                }
            }
        }
    }

    test("warningBeforeBindRefusal") {
        watchdog.runCase("warningBeforeBindRefusal") {
            Fixture("warning-before-bind", watchdog, contentInsideData = true).use { fixture ->
                val result = fixture.run("warningBeforeBindRefusal", host = "0.0.0.0")
                val warning =
                    "roots.docs (${fixture.content.toRealPath()}) is INSIDE " +
                        "DATA_DIR (${fixture.data.toRealPath()}). This serves correctly, " +
                        "but DATA_DIR is app-owned state whose contents are routinely wiped and rebuilt " +
                        "(`search.db` and the object mirror are explicitly disposable) - a wipe here takes this root's " +
                        "content with it. Move the root outside DATA_DIR."
                val refusal =
                    "serve: binds 0.0.0.0 with auth.mode=off but no TLS/trusted-proxy and no insecure override. " +
                        "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
                        "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
                        "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext."
                result.exitCode shouldBe 1
                result.stderrText shouldContain warning
                result.stderrText shouldContain refusal
                (result.stderrText.indexOf(warning) < result.stderrText.indexOf(refusal)) shouldBe true
            }
        }
    }

    test("healthyLocalSigterm") {
        watchdog.runCase("healthyLocalSigterm") {
            Fixture("healthy-local", watchdog).use { fixture ->
                val result = fixture.runHealthy("healthyLocalSigterm", authMode = "builtin")
                result.healthStatus shouldBe 200
                result.healthBody shouldContain "\"status\":\"ok\""
                result.healthBody shouldContain "\"available\":true"
                result.protectedPath shouldBe "/api/v1/tree"
                result.protectedStatus shouldBe 401
                result.protectedBody shouldContain "\"code\":\"unauthorized\""
                result.signalExitCode shouldBe 0
                result.exitCode shouldBe SIGTERM_EXIT_STATUS
            }
        }
    }

    test("occupiedPort") {
        watchdog.runCase("occupiedPort") {
            Fixture("occupied-port", watchdog).use { fixture ->
                val occupied = loopbackSocket()
                try {
                    val result = fixture.run("occupiedPort", port = occupied.localPort.toString())
                    (result.exitCode != 0) shouldBe true
                    result.stderrText shouldContain "java.net.BindException"
                } finally {
                    occupied.close()
                }
            }
        }
    }
}) {
    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance

    override fun testCaseOrder(): TestCaseOrder = TestCaseOrder.Sequential
}

private const val MAIN_RUNTIME_CLASSPATH_PROPERTY = "plainbase.test.mainRuntimeClasspath"
private const val EVIDENCE_DIRECTORY_PROPERTY = "plainbase.test.evidenceDir"
private const val SERVER_BOOT_DEADLINE_MILLIS = 30_000L
private const val SHUTDOWN_DEADLINE_MILLIS = 15_000L
private const val PROCESS_DEADLINE_MILLIS = 30_000L
private const val STREAM_DEADLINE_MILLIS = 5_000L
private const val STREAM_CLOSE_JOIN_DEADLINE_MILLIS = 500L
private const val CLEANUP_DEADLINE_MILLIS = 10_000L
private const val FORCE_KILL_REAP_DEADLINE_MILLIS = 2_000L
private const val SUITE_DEADLINE_MILLIS = 460_000L
private const val SIGTERM_EXIT_STATUS = 143

private lateinit var httpClient: HttpClient

private val evidenceDirectory: Path
    get() =
        Path.of(
            requireNotNull(System.getProperty(EVIDENCE_DIRECTORY_PROPERTY)) {
                "$EVIDENCE_DIRECTORY_PROPERTY is required; launch ./gradlew :server:test"
            },
        ).toAbsolutePath().normalize()

private fun createHttpClient(): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build()

private fun verifyMainRuntimeClasspath(watchdog: SuiteWatchdog) {
    watchdog.check()
    val classpath = System.getProperty(MAIN_RUNTIME_CLASSPATH_PROPERTY).orEmpty()
    classpath.shouldNotBeBlank()
    val entries = classpath.split(File.pathSeparator).filter(String::isNotBlank)
    entries.isNotEmpty() shouldBe true
    Files.createDirectories(evidenceDirectory)
    Files.writeString(evidenceDirectory.resolve("main-runtime-classpath.txt"), entries.joinToString("\n") + "\n")
    Files.writeString(
        evidenceDirectory.resolve("java-encoding.txt"),
        "file.encoding=${System.getProperty("file.encoding")}\n" +
            "native.encoding=${System.getProperty("native.encoding")}\n" +
            "stdout.encoding=${System.getProperty("stdout.encoding")}\n" +
            "stderr.encoding=${System.getProperty("stderr.encoding")}\n" +
            "default.charset=${Charset.defaultCharset()}\n" +
            "locale=${locale()}\n",
    )
}

private class Fixture(
    private val name: String,
    private val watchdog: SuiteWatchdog,
    private val missingContent: Boolean = false,
    private val contentInsideData: Boolean = false,
    private val gitRepository: Boolean = false,
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("plainbase-cli-$name-")
    val workingDirectory: Path = Files.createDirectories(root.resolve("work"))
    val data: Path = Files.createDirectories(root.resolve("data"))
    val home: Path = Files.createDirectories(root.resolve("home"))
    private val tmp: Path = Files.createDirectories(root.resolve("tmp"))
    val content: Path = when {
        missingContent -> root.resolve("missing/content")
        contentInsideData -> Files.createDirectories(data.resolve("content"))
        else -> Files.createDirectories(root.resolve("content"))
    }

    init {
        watchdog.check()
        if (!missingContent) Files.writeString(content.resolve("index.md"), "# Page\n\nA valid CLI fixture.\n")
        if (gitRepository) Files.createDirectories(content.resolve(".git"))
    }

    fun run(
        caseName: String,
        port: String = loopbackSocket().use { it.localPort.toString() },
        host: String = "127.0.0.1",
        gitEnabled: Boolean = false,
        storageBackend: String = "local",
        extraEnvironment: Map<String, String> = emptyMap(),
    ): CompletedChild =
        withChild(caseName, port, host, gitEnabled, storageBackend, extraEnvironment) { child ->
            child.awaitExit(PROCESS_DEADLINE_MILLIS)
        }

    fun runHoldingDataDirLock(
        caseName: String,
        port: String = loopbackSocket().use { it.localPort.toString() },
        host: String = "127.0.0.1",
        gitEnabled: Boolean = false,
        storageBackend: String = "local",
        extraEnvironment: Map<String, String> = emptyMap(),
    ): CompletedChild {
        val lock = requireNotNull(DataDirLock.tryAcquire(data))
        return try {
            run(caseName, port, host, gitEnabled, storageBackend, extraEnvironment)
        } finally {
            lock.close()
        }
    }

    fun runHealthy(caseName: String, authMode: String = "off"): CompletedChild {
        val port = loopbackSocket().use { it.localPort.toString() }
        return withChild(
            caseName,
            port,
            "127.0.0.1",
            false,
            extraEnvironment = mapOf("PLAINBASE_AUTH_MODE" to authMode),
        ) { child ->
            val health = child.awaitHealth(SERVER_BOOT_DEADLINE_MILLIS)
            val protected = if (authMode == "builtin") child.awaitProtectedTree() else null
            val signal = child.sendSigterm()
            child.awaitExit(SHUTDOWN_DEADLINE_MILLIS).copy(
                healthStatus = health.status,
                healthBody = health.body,
                startupMillis = health.startupMillis,
                protectedPath = protected?.path ?: "",
                protectedStatus = protected?.status ?: 0,
                protectedBody = protected?.body ?: "",
                signalAction = signal.action,
                signalExitCode = signal.exitCode,
            )
        }
    }

    private fun withChild(
        caseName: String,
        port: String,
        host: String,
        gitEnabled: Boolean,
        storageBackend: String = "local",
        extraEnvironment: Map<String, String> = emptyMap(),
        block: (RunningChild) -> CompletedChild,
    ): CompletedChild {
        val child =
            try {
                launch(caseName, port, host, gitEnabled, storageBackend, extraEnvironment)
            } catch (failure: Throwable) {
                writeFailureOutcome(caseName, failure)
                throw failure
            }
        var result: CompletedChild? = null
        var primary: Throwable? = null
        try {
            result = block(child)
            writeEvidence(caseName, requireNotNull(result))
        } catch (failure: Throwable) {
            primary = failure
            try {
                child.writeFailureEvidence(caseName, failure)
            } catch (evidenceFailure: Throwable) {
                failure.addSuppressed(evidenceFailure)
            }
        }
        val cleanupFailure = runCatching { child.close() }.exceptionOrNull()
        if (cleanupFailure != null) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure)
                try {
                    child.appendFailure(caseName, cleanupFailure)
                } catch (evidenceFailure: Throwable) {
                    primary.addSuppressed(evidenceFailure)
                }
            } else {
                primary = cleanupFailure
                try {
                    child.writeFailureEvidence(caseName, cleanupFailure)
                } catch (evidenceFailure: Throwable) {
                    cleanupFailure.addSuppressed(evidenceFailure)
                }
            }
        }
        if (primary != null) throw primary
        return requireNotNull(result)
    }

    private fun launch(
        caseName: String,
        port: String,
        host: String,
        gitEnabled: Boolean,
        storageBackend: String,
        extraEnvironment: Map<String, String>,
    ): RunningChild {
        watchdog.check()
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath = System.getProperty(MAIN_RUNTIME_CLASSPATH_PROPERTY).orEmpty()
        require(classpath.isNotBlank()) {
            "plainbase.test.mainRuntimeClasspath is required; launch ./gradlew :server:test"
        }
        val git = resolvedGit()
        val environment = linkedMapOf(
            "PATH" to "${git.parent}${File.pathSeparator}/usr/bin${File.pathSeparator}/bin",
            "JAVA_HOME" to Path.of(System.getProperty("java.home")).toString(),
            "HOME" to home.toString(),
            "TMPDIR" to tmp.toString(),
            "LANG" to locale(),
            "LC_ALL" to locale(),
            "DATA_DIR" to data.toString(),
            "CONTENT_DIR" to content.toString(),
            "PLAINBASE_STORAGE_BACKEND" to storageBackend,
            "PLAINBASE_GIT_ENABLED" to gitEnabled.toString(),
            "PLAINBASE_AUTH_MODE" to "off",
            "PLAINBASE_HOST" to host,
            "PLAINBASE_PORT" to port,
            "PLAINBASE_LOG_LEVEL" to "INFO",
        ).apply { putAll(extraEnvironment) }
        val argv = listOf(
            java.toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath,
            "com.plainbase.ApplicationKt",
            "serve",
        )
        val processBuilder = ProcessBuilder(argv)
        processBuilder.directory(workingDirectory.toFile())
        processBuilder.environment().clear()
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        process.outputStream.close()
        return RunningChild(caseName, port.toIntOrNull() ?: 0, argv, environment, process, watchdog)
    }

    override fun close() {
        val deadline = System.nanoTime() + CLEANUP_DEADLINE_MILLIS * 1_000_000
        deleteTree(root, deadline)
        check(!Files.exists(root)) { "fixture cleanup left files for $name" }
    }
}

private class RunningChild(
    private val caseName: String,
    private val port: Int,
    private val argv: List<String>,
    private val environment: Map<String, String>,
    private val process: Process,
    private val watchdog: SuiteWatchdog,
) : AutoCloseable {
    private val started = System.nanoTime()
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private val drainFailure = AtomicReference<Throwable?>()
    private val stdoutDrain = drain(process.inputStream, stdout, "$caseName-stdout", drainFailure)
    private val stderrDrain = drain(process.errorStream, stderr, "$caseName-stderr", drainFailure)
    private val ownedHandles = linkedSetOf(process.toHandle())
    private var signalSentAt = 0L
    private var streamsFinished = false

    fun awaitHealth(deadlineMillis: Long): HealthObservation {
        val deadline = watchdog.operationDeadline(deadlineMillis)
        var lastFailure = "no response"
        while (System.nanoTime() < deadline) {
            watchdog.check()
            refreshOwnedHandles()
            if (!process.isAlive) error("$caseName exited before healthz: ${diagnostics()}")
            try {
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/healthz"))
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
                if (response.statusCode() == 200) {
                    return HealthObservation(response.statusCode(), response.body(), elapsedMillis(started))
                }
                lastFailure = "HTTP ${response.statusCode()}: ${response.body()}"
            } catch (failure: Exception) {
                lastFailure = failure.message ?: failure::class.java.name
            }
            Thread.sleep(100)
        }
        error("$caseName did not reach healthz within ${deadlineMillis}ms: $lastFailure; ${diagnostics()}")
    }

    fun sendSigterm(): SignalObservation {
        check(process.isAlive) { "$caseName child is not alive before SIGTERM" }
        val action = "/bin/kill -TERM ${process.pid()}"
        signalSentAt = System.nanoTime()
        val signal = ProcessBuilder("/bin/kill", "-TERM", process.pid().toString()).start()
        signal.outputStream.close()
        try {
            if (!signal.waitFor(5, TimeUnit.SECONDS)) {
                signal.destroyForcibly()
                if (!signal.waitFor(1, TimeUnit.SECONDS)) error("$caseName $action did not terminate")
                error("$caseName $action timed out")
            }
            val exitCode = signal.exitValue()
            check(exitCode == 0) { "$caseName $action exited $exitCode" }
            return SignalObservation(action, exitCode)
        } finally {
            signal.inputStream.close()
            signal.errorStream.close()
        }
    }

    fun awaitProtectedTree(): ProtectedObservation {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/tree"))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        return ProtectedObservation("/api/v1/tree", response.statusCode(), response.body())
    }

    fun awaitExit(deadlineMillis: Long): CompletedChild {
        try {
            if (!waitForExit(watchdog.operationDeadline(deadlineMillis))) {
                val timeout = TimeoutException(
                    "$caseName child did not exit within ${deadlineMillis}ms: ${diagnostics()}",
                )
                try {
                    writeFailureEvidence("$caseName.timeout-before-teardown", timeout)
                } catch (evidenceFailure: Throwable) {
                    timeout.addSuppressed(evidenceFailure)
                }
                terminateWithSuppressed(timeout)
                throw timeout
            }
            finishStreams()
            return snapshot()
        } catch (failure: Throwable) {
            if (ownedAlive()) {
                try {
                    writeFailureEvidence("$caseName.failure-before-teardown", failure)
                } catch (evidenceFailure: Throwable) {
                    failure.addSuppressed(evidenceFailure)
                }
                terminateWithSuppressed(failure)
            }
            throw failure
        }
    }

    override fun close() {
        var failure: Throwable? = null
        if (ownedAlive()) {
            try {
                terminateOwned()
            } catch (cleanupFailure: Throwable) {
                failure = cleanupFailure
            }
        }
        try {
            finishStreams()
        } catch (drainFailure: Throwable) {
            val existingFailure = failure
            if (existingFailure == null) failure = drainFailure else existingFailure.addSuppressed(drainFailure)
        }
        if (failure != null) throw failure
    }

    fun writeFailureEvidence(caseName: String, failure: Throwable) {
        writeChildEvidence(caseName, snapshot(), "FAILURE", failure)
    }

    fun appendFailure(caseName: String, failure: Throwable) {
        Files.createDirectories(evidenceDirectory)
        Files.writeString(
            evidenceDirectory.resolve("$caseName.cleanup-failure"),
            "${failure::class.java.name}: ${failure.message}\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun waitForExit(deadline: Long): Boolean {
        while (process.isAlive) {
            watchdog.check()
            refreshOwnedHandles()
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) return false
            process.waitFor(minOf(100L, remaining), TimeUnit.MILLISECONDS)
        }
        return true
    }

    private fun terminateWithSuppressed(primary: Throwable) {
        try {
            terminateOwned()
        } catch (cleanupFailure: Throwable) {
            primary.addSuppressed(cleanupFailure)
        }
    }

    private fun terminateOwned() {
        val cleanupDeadline = System.nanoTime() + CLEANUP_DEADLINE_MILLIS * 1_000_000
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
            error("$caseName owned process survivors after ${CLEANUP_DEADLINE_MILLIS}ms: $survivors")
        }
    }

    private fun waitForOwned(deadline: Long): Boolean {
        var interrupted = false
        try {
            while (System.nanoTime() < deadline) {
                refreshOwnedHandles()
                if (ownedHandles.none { it.isAlive }) return true
                try {
                    Thread.sleep(minOf(25L, remainingMillis(deadline)))
                } catch (_: InterruptedException) {
                    interrupted = true
                    Thread.interrupted()
                }
            }
            refreshOwnedHandles()
            return ownedHandles.none { it.isAlive }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun refreshOwnedHandles() {
        ownedHandles.addAll(process.toHandle().descendants().toList())
    }

    private fun ownedAlive(): Boolean {
        refreshOwnedHandles()
        return ownedHandles.any { it.isAlive }
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
            "$caseName stream drain exceeded ${STREAM_DEADLINE_MILLIS}ms: ${diagnostics()}"
        }
        streamsFinished = true
        drainFailure.get()?.let { failure ->
            throw IllegalStateException("$caseName stream drain failed", failure)
        }
    }

    private fun joinDrain(drain: Thread, deadline: Long) {
        while (drain.isAlive) {
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) return
            drain.join(remaining)
        }
    }

    private fun snapshot(): CompletedChild =
        CompletedChild(
            argv = argv,
            environment = environment,
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            stdout = stdout.toByteArray(),
            stderr = stderr.toByteArray(),
            durationMillis = elapsedMillis(started),
            shutdownMillis = if (signalSentAt == 0L) -1 else elapsedMillis(signalSentAt),
        )

    private fun diagnostics(): String =
        "stdout=${stdout.toString(StandardCharsets.UTF_8)} stderr=${stderr.toString(StandardCharsets.UTF_8)}"
}

private data class CompletedChild(
    val argv: List<String>,
    val environment: Map<String, String>,
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val durationMillis: Long,
    val healthStatus: Int = 0,
    val healthBody: String = "",
    val startupMillis: Long = -1,
    val protectedPath: String = "",
    val protectedStatus: Int = 0,
    val protectedBody: String = "",
    val shutdownMillis: Long = -1,
    val signalAction: String = "",
    val signalExitCode: Int = -1,
) {
    val stdoutText: String get() = stdout.toString(StandardCharsets.UTF_8)
    val stderrText: String get() = stderr.toString(StandardCharsets.UTF_8)
}

private data class HealthObservation(val status: Int, val body: String, val startupMillis: Long)

private data class ProtectedObservation(val path: String, val status: Int, val body: String)

private data class SignalObservation(val action: String, val exitCode: Int)

private class SuiteWatchdog {
    private val expired = AtomicBoolean(false)
    private val activeCase = AtomicReference<String?>()
    private val activeWorker = AtomicReference<Thread?>()
    private val outcomeLock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor(WatchdogThreadFactory)
    private var deadline = 0L
    private var alarm: ScheduledFuture<*>? = null

    fun start() {
        deadline = System.nanoTime() + SUITE_DEADLINE_MILLIS * 1_000_000
        Files.createDirectories(evidenceDirectory)
        alarm = scheduler.schedule({
            val timeout = synchronized(outcomeLock) {
                expired.set(true)
                val caseName = activeCase.getAndSet(null)
                val worker = activeWorker.getAndSet(null)
                caseName?.let { it to worker }
            }
            timeout?.let { (caseName, worker) ->
                try {
                    writeCaseFailure(
                        caseName,
                        TimeoutException("suite watchdog exceeded ${SUITE_DEADLINE_MILLIS}ms"),
                    )
                } catch (_: Throwable) {
                } finally {
                    worker?.interrupt()
                }
            }
        }, SUITE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun check() {
        if (expired.get() || System.nanoTime() >= deadline) {
            expired.set(true)
            throw TimeoutException("suite watchdog exceeded ${SUITE_DEADLINE_MILLIS}ms")
        }
    }

    fun operationDeadline(durationMillis: Long): Long {
        check()
        return minOf(deadline, System.nanoTime() + durationMillis * 1_000_000)
    }

    fun <T> runCase(name: String, block: () -> T): T {
        val worker = Thread.currentThread()
        synchronized(outcomeLock) {
            check()
            activeCase.set(name)
            activeWorker.set(worker)
        }
        return try {
            val result = block()
            synchronized(outcomeLock) {
                try {
                    check()
                    writeCaseOutcome(name, "PASS")
                } finally {
                    activeWorker.compareAndSet(worker, null)
                    activeCase.compareAndSet(name, null)
                }
            }
            result
        } catch (failure: Throwable) {
            try {
                writeCaseFailure(name, failure)
            } catch (evidenceFailure: Throwable) {
                failure.addSuppressed(evidenceFailure)
            }
            throw failure
        } finally {
            synchronized(outcomeLock) {
                activeWorker.compareAndSet(worker, null)
                activeCase.compareAndSet(name, null)
            }
        }
    }

    fun close() {
        alarm?.cancel(false)
        scheduler.shutdownNow()
    }
}

private object WatchdogThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "plainbase-cli-suite-watchdog").apply { isDaemon = true }
}

private fun writeEvidence(caseName: String, result: CompletedChild) {
    writeChildEvidence(caseName, result, "OBSERVED", null)
}

private fun writeGitPreflightEvidence(version: GitResult, access: GitResult) {
    Files.createDirectories(evidenceDirectory)
    Files.writeString(
        evidenceDirectory.resolve("invalidGitRepository.git-preflight"),
        buildString {
            append("version.argv=git --version\nversion.exit=${version.exitCode}\nversion.stdout.begin\n")
            append(version.stdoutText)
            if (!version.stdoutText.endsWith('\n')) append('\n')
            append("version.stdout.end\nversion.stderr.begin\n${version.stderr}")
            if (!version.stderr.endsWith('\n')) append('\n')
            append("version.stderr.end\n")
            append("rev-parse.argv=git rev-parse --is-inside-work-tree\nrev-parse.exit=${access.exitCode}\n")
            append("rev-parse.stdout.begin\n${access.stdoutText}")
            if (!access.stdoutText.endsWith('\n')) append('\n')
            append("rev-parse.stdout.end\nrev-parse.stderr.begin\n${access.stderr}")
            if (!access.stderr.endsWith('\n')) append('\n')
            append("rev-parse.stderr.end\n")
        },
    )
}

private fun writeFailureOutcome(caseName: String, failure: Throwable) {
    try {
        writeCaseFailure(caseName, failure)
    } catch (evidenceFailure: Throwable) {
        failure.addSuppressed(evidenceFailure)
    }
}

private fun writeCaseOutcome(caseName: String, outcome: String) {
    Files.createDirectories(evidenceDirectory)
    Files.writeString(evidenceDirectory.resolve("$caseName.outcome"), "outcome=$outcome\n")
}

private fun writeCaseFailure(caseName: String, failure: Throwable) {
    Files.createDirectories(evidenceDirectory)
    Files.writeString(
        evidenceDirectory.resolve("$caseName.outcome"),
        "outcome=FAILURE\n" +
            "failure_type=${failure::class.java.name}\n" +
            "failure_message=${failure.message}\n" +
            "suppressed=${failure.suppressed.joinToString(" | ") { "${it::class.java.name}: ${it.message}" }}\n",
    )
}

private fun writeChildEvidence(
    caseName: String,
    result: CompletedChild,
    outcome: String,
    failure: Throwable?,
) {
    Files.createDirectories(evidenceDirectory)
    Files.write(evidenceDirectory.resolve("$caseName.stdout"), result.stdout)
    Files.write(evidenceDirectory.resolve("$caseName.stderr"), result.stderr)
    Files.writeString(
        evidenceDirectory.resolve("$caseName.meta"),
        buildString {
            appendLine("outcome=$outcome")
            appendLine("argv=")
            result.argv.forEach(::appendLine)
            appendLine("environment=")
            result.environment.forEach { (key, value) -> appendLine("$key=$value") }
            appendLine("exit=${result.exitCode}")
            appendLine("duration_ms=${result.durationMillis}")
            appendLine("startup_ms=${result.startupMillis}")
            appendLine("shutdown_ms=${result.shutdownMillis}")
            appendLine("protected_path=${result.protectedPath}")
            appendLine("protected_status=${result.protectedStatus}")
            appendLine("protected_body=${result.protectedBody}")
            appendLine("health_status=${result.healthStatus}")
            appendLine("health_body=${result.healthBody}")
            appendLine("signal=${result.signalAction}")
            appendLine("signal_exit=${result.signalExitCode}")
            if (failure != null) {
                appendLine("failure_type=${failure::class.java.name}")
                appendLine("failure_message=${failure.message}")
                appendLine("suppressed=${failure.suppressed.joinToString(" | ") { "${it::class.java.name}: ${it.message}" }}")
            }
        },
    )
}

private fun resolvedGit(): Path {
    val path = System.getenv("PATH").orEmpty()
    return path.split(File.pathSeparator)
        .asSequence()
        .map { Path.of(it).resolve("git") }
        .firstOrNull { Files.isExecutable(it) }
        ?.toRealPath()
        ?: error("git is required on the test JVM PATH")
}

private data class CliV17Signature(val userVersion: Long, val columns: List<String>, val observation: Long)

private data class CliHeldLockObservation(
    val appFamily: Map<String, String>,
    val searchFamily: Map<String, String>,
    val appSchema: CliV17Signature,
    val mirror: Map<String, String>,
)

private fun seedCliV17Database(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:$path").use { raw ->
        raw.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE root_observation (root TEXT NOT NULL PRIMARY KEY, observation_id INTEGER NOT NULL)",
            )
            statement.execute("INSERT INTO root_observation(root, observation_id) VALUES ('docs', 100)")
            statement.execute("PRAGMA user_version = 17")
        }
    }
}

private fun seedCliSearchDatabase(path: Path) {
    SearchDb(path).close()
}

private fun captureCliHeldLockObservation(data: Path, mirror: Path): CliHeldLockObservation =
    CliHeldLockObservation(
        appFamily = cliFingerprintFamily(cliFileFamily(data, "plainbase.db")),
        searchFamily = cliFingerprintFamily(cliFileFamily(data, "search.db")),
        appSchema = cliV17Signature(data.resolve("plainbase.db")),
        mirror = cliFingerprintFamily(cliTreeSnapshot(mirror)),
    )

private fun cliV17Signature(path: Path): CliV17Signature =
    DriverManager.getConnection("jdbc:sqlite:file:${path.toAbsolutePath().normalize()}?mode=ro").use { raw ->
        val version = raw.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
        val columns = raw.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(root_observation)").use { result ->
                buildList {
                    while (result.next()) add(result.getString("name"))
                }
            }
        }
        val observation = raw.createStatement().use { statement ->
            statement.executeQuery("SELECT observation_id FROM root_observation WHERE root = 'docs'").use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
        CliV17Signature(version, columns, observation)
    }

private fun cliFileFamily(dir: Path, stem: String): Map<String, ByteArray> =
    listOf(stem, "$stem-wal", "$stem-shm", "$stem-journal")
        .filter { Files.exists(dir.resolve(it)) }
        .associateWith { Files.readAllBytes(dir.resolve(it)) }

private fun cliTreeSnapshot(root: Path): Map<String, ByteArray> =
    Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }
            .map { root.relativize(it).toString() to Files.readAllBytes(it) }
            .toList()
            .toMap()
    }

private fun cliFingerprintFamily(family: Map<String, ByteArray>): Map<String, String> = family.mapValues { (_, bytes) ->
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun writeCliHeldLockObservation(before: CliHeldLockObservation, after: CliHeldLockObservation) {
    Files.createDirectories(evidenceDirectory)
    Files.writeString(
        evidenceDirectory.resolve("heldDataLockObjectPriorSchema.state"),
        buildString {
            appendLine("before.app_family=${before.appFamily}")
            appendLine("after.app_family=${after.appFamily}")
            appendLine("before.search_family=${before.searchFamily}")
            appendLine("after.search_family=${after.searchFamily}")
            appendLine("before.app_schema=${before.appSchema}")
            appendLine("after.app_schema=${after.appSchema}")
            appendLine("before.mirror=${before.mirror}")
            appendLine("after.mirror=${after.mirror}")
        },
    )
}

private fun locale(): String =
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) "en_US.UTF-8" else "C.UTF-8"

private fun loopbackSocket(): ServerSocket =
    ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }

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
            check(remainingMillis(deadline) != 0L) { "cleanup exceeded ${CLEANUP_DEADLINE_MILLIS}ms" }
            Files.deleteIfExists(it)
        }
    }
}

private fun remainingMillis(deadline: Long): Long {
    val remaining = deadline - System.nanoTime()
    return if (remaining <= 0L) 0L else maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))
}

private fun elapsedMillis(started: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
