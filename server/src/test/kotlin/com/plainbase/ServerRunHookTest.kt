package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.CompletableFuture
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

    test("natural return drains an authenticated held PUT before dependent close and lock release") {
        withParentWatchdog { watchdog -> runHeldScenario(HeldMode.NATURAL_RETURN, watchdog) }
    }

    test("SIGTERM drains an authenticated held PUT before dependent close and lock release") {
        withParentWatchdog { watchdog -> runHeldScenario(HeldMode.SIGTERM, watchdog) }
    }

    test("SIGTERM permits an authenticated incomplete PUT to finish within configured engine grace") {
        withParentWatchdog { watchdog -> runCancellableBodyScenario(watchdog) }
    }

    test("ordinary LOCAL workload records one real save and whole-owner drain") {
        withParentWatchdog { watchdog -> runLocalMeasurement(watchdog) }
    }
})

private const val BOOT_DEADLINE_MILLIS = 30_000L
private const val SHUTDOWN_DEADLINE_MILLIS = 15_000L
private const val STREAM_DEADLINE_MILLIS = 5_000L
private const val STREAM_CLOSE_JOIN_DEADLINE_MILLIS = 500L
private const val PROCESS_TREE_CLEANUP_MILLIS = 10_000L
private const val FORCE_KILL_REAP_DEADLINE_MILLIS = 2_000L
private const val KILL_HELPER_DEADLINE_MILLIS = 5_000L
private const val PARENT_WATCHDOG_DEADLINE_MILLIS = 90_000L
private const val HELD_OPERATION_DEADLINE_MILLIS = 30_000L
private const val HELD_WRITER_MILLIS = 8_500L
private const val HELD_POLL_MILLIS = 100L
private const val HELD_CLEANUP_MILLIS = 15_000L
private const val PROBE_DEADLINE_MILLIS = 5_000L
private const val CLIENT_CLOSE_ESCALATION_MILLIS = 2_000L
private const val LOCAL_PAGE_COUNT = 32
private const val LOCAL_PAGE_BYTES = 4_096
private const val LOCAL_CLEANUP_MILLIS = 15_000L
private const val MAX_CAPTURED_OUTPUT_BYTES = 1_048_576
private const val EVIDENCE_DIRECTORY_PROPERTY = "plainbase.test.evidenceDir"
private const val EVIDENCE_PREFIX = "server-run-hook"

private enum class HeldMode(val label: String) {
    NATURAL_RETURN("natural"),
    SIGTERM("sigterm"),
}

private class HeldScenario(
    val mode: HeldMode,
    val watchdog: ParentWatchdog,
    val base: Path,
    val data: Path,
    val page: Path,
    val report: Path,
    val token: Path,
    val trigger: Path,
    val armAdmission: Path,
    val admissionArmed: Path,
    val admissionObservation: Path,
    val callCompleted: Path,
    val saveEntered: Path,
    val saveRelease: Path,
    val saveReturned: Path,
    val shutdownEntered: Path,
    val closeFacts: Path,
    val hook: Path,
    val waiterInterruptions: Path,
    val runFinished: Path,
    val helperCompleted: Path,
    val helperFailure: Path,
    val updatedBytes: ByteArray,
    val command: List<String>,
    val environment: Map<String, String>,
    val port: Int,
) {
    val timing = linkedMapOf<String, Long>()
    val startedAt = System.nanoTime()
    var child: HookRunningChild? = null
    var client: HttpClient? = null
    var put: CompletableFuture<HttpResponse<String>>? = null
    var heldProbe: ProbeObservation? = null
    var availableProbe: ProbeObservation? = null
    val probes = mutableListOf<ProbeResources>()
    var clientClose: ClientCloseObservation? = null
    var primary: Throwable? = null
    var interrupted = false

    fun mark(name: String) {
        timing[name] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }

    fun rememberInterrupt() {
        interrupted = true
        Thread.interrupted()
    }
}

private class CancellableBodyScenario(
    val watchdog: ParentWatchdog,
    val base: Path,
    val page: Path,
    val expected: Path,
    val report: Path,
    val token: Path,
    val armAdmission: Path,
    val admissionArmed: Path,
    val admissionObservation: Path,
    val receiveObservation: Path,
    val callCompleted: Path,
    val handlerCompleted: Path,
    val hook: Path,
    val command: List<String>,
    val environment: Map<String, String>,
    val port: Int,
    val body: ByteArray,
) {
    val timing = linkedMapOf<String, Long>()
    val startedAt = System.nanoTime()
    var child: HookRunningChild? = null
    var client: HttpClient? = null
    var clientClose: ClientCloseObservation? = null
    var socket: Socket? = null
    var suffixWriteFailure: Throwable? = null
    var primary: Throwable? = null

    fun mark(name: String) {
        timing[name] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }
}

private fun runHookScenario(watchdog: ParentWatchdog) {
    val base = Files.createTempDirectory("plainbase-server-hook")
    val content = Files.createDirectory(base.resolve("content"))
    val data = Files.createDirectory(base.resolve("data"))
    val report = base.resolve("receipts.txt")
    val port = ServerSocket(0).use { it.localPort }
    var child: HookRunningChild? = null
    var primary: Throwable? = null
    var observed: ChildObservation? = null
    var restoreInterrupt = false
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
        if (failure is InterruptedException) {
            restoreInterrupt = true
            Thread.interrupted()
        }
        primary = failure
    }

    val closeFailure = runCatching { child?.close() }.exceptionOrNull()
    if (child?.cleanupInterrupted() == true) restoreInterrupt = true
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
            assertCloseReceipts(report)
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
    val watchdogClose = watchdog.close()
    if (watchdogClose.interrupted) restoreInterrupt = true
    watchdogClose.failure?.let { primary = retainFailure(primary, it) }
    if ((child == null || child.isQuiescent()) && watchdogClose.terminated) {
        val cleanupFailure = runCatching {
            deleteTree(base, System.nanoTime() + PROCESS_TREE_CLEANUP_MILLIS * 1_000_000)
            check(!Files.exists(base)) { "fixture cleanup left files" }
        }.exceptionOrNull()
        if (cleanupFailure != null) {
            primary = primary?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure
        }
    } else {
        primary = retainFailure(primary, IllegalStateException("healthy hook fixture retained: " + base))
    }
    if (restoreInterrupt) Thread.currentThread().interrupt()
    if (primary != null) throw primary
}

@Suppress("LongMethod")
private fun runLocalMeasurement(watchdog: ParentWatchdog) {
    val base = Files.createTempDirectory("plainbase-local-measurement")
    val content = Files.createDirectory(base.resolve("content"))
    val docs = Files.createDirectory(content.resolve("docs"))
    val data = Files.createDirectory(base.resolve("data"))
    val report = base.resolve("receipts.txt")
    val token = base.resolve("token.fixture")
    val initialRebuild = base.resolve("initial-rebuild").toAbsolutePath()
    val watcherObservation = base.resolve("watcher.observation").toAbsolutePath()
    val shutdownEntered = base.resolve("shutdown.entered").toAbsolutePath()
    val helperCompleted = base.resolve("helper.completed").toAbsolutePath()
    val helperFailure = base.resolve("helper.failure").toAbsolutePath()
    val targetId = localPageId(0)
    val target = docs.resolve("page-00.md")
    val expected = localPage(targetId, 0, "updated").also { Files.write(base.resolve("expected.md"), it) }
    repeat(LOCAL_PAGE_COUNT) { index ->
        Files.write(docs.resolve("page-${index.toString().padStart(2, '0')}.md"), localPage(localPageId(index), index, "initial"))
    }
    val port = ServerSocket(0).use { it.localPort }
    val command = localMeasurementChildCommand(
        content = content,
        data = data,
        report = report,
        port = port,
        token = token,
        initialRebuild = initialRebuild,
        watcherObservation = watcherObservation,
        shutdownEntered = shutdownEntered,
        helperCompleted = helperCompleted,
        helperFailure = helperFailure,
    )
    val environment = isolatedEnvironment(base, data, content)
    var child: HookRunningChild? = null
    var client: HttpClient? = null
    var clientClose: ClientCloseObservation? = null
    var observed: ChildObservation? = null
    var primary: Throwable? = null
    val probes = mutableListOf<ProbeResources>()
    var restoreInterrupt = Thread.interrupted()
    val measurement = linkedMapOf<String, String>()
    try {
        child = launchHookChild(command, environment, watchdog)
        awaitHeldMarker(initialRebuild, child, watchdog, "initial rebuild")
        val rebuild = readKeyValue(initialRebuild)
        rebuild["page_count"] shouldBe LOCAL_PAGE_COUNT.toString()
        measurement["initial_rebuild_ms"] = requireNotNull(rebuild["duration_ms"])
        measurement["root_count"] = "1"
        measurement["page_count"] = LOCAL_PAGE_COUNT.toString()
        measurement["initial_content_bytes"] = (LOCAL_PAGE_COUNT * LOCAL_PAGE_BYTES).toString()
        val health = child.awaitHealth(port)
        health.statusCode shouldBe 200
        health.body shouldContain "\"status\":\"ok\""
        awaitHeldMarker(token, child, watchdog, "authentication token")
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val uri = URI("http://127.0.0.1:$port/api/v1/pages/$targetId")
        val auth = "Bearer " + Files.readString(token)
        val get = client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization", auth).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        get.statusCode() shouldBe 200
        val etag = get.headers().firstValue("ETag").orElseThrow()
        val putStarted = System.nanoTime()
        val put = client.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", auth)
                .header("If-Match", etag)
                .header("Content-Type", "text/markdown")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(expected))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        measurement["authenticated_put_ms"] = elapsedMillis(putStarted).toString()
        put.statusCode() shouldBe 200
        Files.readAllBytes(target).contentEquals(expected) shouldBe true
        measurement["save_durable_bytes"] = Files.size(target).toString()
        measurement["admitted_work_at_signal"] =
            "foreground PUT completed before SIGTERM; watcher/rebuild admission not separately sampled; included in aggregate"
        val signalStarted = System.nanoTime()
        child.sendSigterm()
        awaitHeldMarker(shutdownEntered, child, watchdog, "shutdown entry")
        measurement["signal_to_shutdown_entry_observed_ms"] = elapsedMillis(signalStarted).toString()
        child.awaitExit(SHUTDOWN_DEADLINE_MILLIS)
        awaitHeldMarker(helperCompleted, child, watchdog, "fixture helper completion")
        val helperCompletion = readKeyValue(helperCompleted)
        helperCompletion["completed"] shouldBe "true"
        helperCompletion["survivors"] shouldBe "0"
        awaitHeldMarker(helperFailure, child, watchdog, "fixture helper result")
        readKeyValue(helperFailure)["failure"] shouldBe "none"
        measurement["signal_to_exit_ms"] = elapsedMillis(signalStarted).toString()
        child.close()
        observed = child.snapshot()
        observed.exitCode shouldBe 143
        observed.signalExitCode shouldBe 0
        measurement["owner_completion_ms"] = child.ownerCompletionMillis().toString()
        val watchers = readReceipts(watcherObservation)
        check(watchers.size == 1) { "expected one registered LOCAL watcher, got $watchers" }
        measurement["watcher_count"] = watchers.size.toString()
        assertCloseReceipts(report)
        observed.stderrText shouldNotContain "failed; continuing with the remaining steps"
        val availableProbe = runLockProbe(base, data, environment, watchdog) { probes += it }
        availableProbe.stdoutText shouldBe "AVAILABLE\n"
        availableProbe.stderrText shouldBe ""
        measurement["shutdown_group"] =
            "H+W+R+D=owner_completion_ms; parent_signal_to_exit_ms=outer signal-through-post-exit-checks observation"
        measurement["M/C/S/Q/B/U"] = "0 (Git disabled, LOCAL, OBJECT absent)"
    } catch (failure: Throwable) {
        if (failure is InterruptedException) {
            restoreInterrupt = true
            Thread.interrupted()
        }
        primary = failure
    } finally {
        client?.let { httpClient ->
            val attempt = runCatching {
                closeHttpClient(httpClient, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)) {
                    restoreInterrupt = true
                    Thread.interrupted()
                }
            }.getOrElse { failure ->
                primary = retainFailure(primary, failure)
                null
            }
            clientClose = attempt
            attempt?.failure?.let { primary = retainFailure(primary, it) }
            if (attempt?.interrupted == true) restoreInterrupt = true
        }
        child?.let { runningChild ->
            val closeFailure = runCatching { runningChild.close() }.exceptionOrNull()
            if (runningChild.cleanupInterrupted()) restoreInterrupt = true
            if (closeFailure != null) primary = retainFailure(primary, closeFailure)
            observed = runningChild.snapshot()
        }
        probes.forEach { probe ->
            if (probe.interrupted) restoreInterrupt = true
            probe.cleanupFailure?.let { primary = retainFailure(primary, it) }
            if (!probe.isQuiescent()) {
                val cleanupFailure = cleanupProbe(probe, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) {
                    restoreInterrupt = true
                    Thread.interrupted()
                }
                if (cleanupFailure != null) primary = retainFailure(primary, cleanupFailure)
            }
            if (probe.interrupted) restoreInterrupt = true
        }
        val evidenceFailure = runCatching {
            writeLocalEvidence(observed, report, base, token, measurement, probes.lastOrNull()?.observation, primary)
        }.exceptionOrNull()
        if (evidenceFailure != null) primary = retainFailure(primary, evidenceFailure)
        val watchdogClose = watchdog.close()
        if (watchdogClose.interrupted) restoreInterrupt = true
        watchdogClose.failure?.let { primary = retainFailure(primary, it) }
        val childQuiescent = child?.let { it.isQuiescent() } ?: true
        val clientQuiescent = client == null || clientClose?.isQuiescent == true
        val probesQuiescent = probes.all { it.isQuiescent() }
        val canDelete = childQuiescent && clientQuiescent && probesQuiescent && watchdogClose.terminated
        if (canDelete) {
            val cleanupFailure = runCatching {
                deleteTree(base, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCAL_CLEANUP_MILLIS))
                check(!Files.exists(base)) { "LOCAL measurement fixture cleanup left files" }
            }.exceptionOrNull()
            if (cleanupFailure != null) primary = retainFailure(primary, cleanupFailure)
        } else {
            primary = retainFailure(primary, IllegalStateException("LOCAL measurement fixture retained: $base"))
        }
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
    primary?.let { throw it }
}

private fun localMeasurementChildCommand(
    content: Path,
    data: Path,
    report: Path,
    port: Int,
    token: Path,
    initialRebuild: Path,
    watcherObservation: Path,
    shutdownEntered: Path,
    helperCompleted: Path,
    helperFailure: Path,
): List<String> {
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val mainRuntime = requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")) {
        "plainbase.test.mainRuntimeClasspath is required"
    }
    val nativeTestClasses = findNativeTestClasses()
    require(Files.isDirectory(nativeTestClasses)) { "nativeTest code-source output is missing: $nativeTestClasses" }
    val classpath = mainRuntime + File.pathSeparator + nativeTestClasses
    return listOf(
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
        "--token",
        token.toString(),
        "--initial-rebuild",
        initialRebuild.toString(),
        "--watcher-observation",
        watcherObservation.toString(),
        "--shutdown-entered",
        shutdownEntered.toString(),
        "--helper-completed",
        helperCompleted.toString(),
        "--helper-failure",
        helperFailure.toString(),
    )
}

private fun localPageId(index: Int): String =
    "0199bbbb-cccc-7ddd-8eee-${index.toString().padStart(12, '0')}"

private fun localPage(id: String, index: Int, version: String): ByteArray {
    val prefix = "---\nid: $id\ntitle: Local $index\n---\n\n# Local $index\n\n$version\n"
    val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8)
    check(prefixBytes.size < LOCAL_PAGE_BYTES) { "LOCAL page header exceeds $LOCAL_PAGE_BYTES bytes" }
    return (prefix + "x".repeat(LOCAL_PAGE_BYTES - prefixBytes.size)).toByteArray(StandardCharsets.UTF_8)
}

private fun runHeldScenario(mode: HeldMode, watchdog: ParentWatchdog) {
    val scenario = createHeldScenario(mode, watchdog)
    try {
        executeHeldScenario(scenario)
    } catch (failure: Throwable) {
        if (failure is InterruptedException) scenario.rememberInterrupt()
        scenario.primary = failure
    } finally {
        finishHeldScenario(scenario)
    }
    scenario.primary?.let { throw it }
}

private fun runCancellableBodyScenario(watchdog: ParentWatchdog) {
    val scenario = createCancellableBodyScenario(watchdog)
    try {
        executeCancellableBodyScenario(scenario)
    } catch (failure: Throwable) {
        scenario.primary = failure
    } finally {
        finishCancellableBodyScenario(scenario)
    }
    scenario.primary?.let { throw it }
}

private fun createCancellableBodyScenario(watchdog: ParentWatchdog): CancellableBodyScenario {
    val base = Files.createTempDirectory("plainbase-server-cancellable-body")
    val content = Files.createDirectory(base.resolve("content"))
    val data = Files.createDirectory(base.resolve("data"))
    val page = Files.createDirectories(content.resolve("docs")).resolve("cancellable.md")
    val expected = base.resolve("expected.md")
    val report = base.resolve("receipts.txt")
    val token = base.resolve("token.fixture")
    val armAdmission = base.resolve("arm-admission")
    val admissionArmed = base.resolve("admission-armed")
    val admissionObservation = base.resolve("admission.observation")
    val receiveObservation = base.resolve("receive.observation")
    val callCompleted = base.resolve("call.completed")
    val handlerCompleted = base.resolve("handler.completed")
    val hook = base.resolve("hook.installed")
    val pageId = "0199aaaa-bbbb-7ccc-8ddd-0000000000d1"
    val original = "---\nid: $pageId\ntitle: Cancellable\n---\n\n# Cancellable\n\noriginal.\n"
        .toByteArray(StandardCharsets.UTF_8)
    val body = "---\nid: $pageId\ntitle: Cancellable\n---\n\n# Cancellable\n\nupdated after SIGTERM.\n"
        .toByteArray(StandardCharsets.UTF_8)
    Files.write(page, original)
    Files.write(expected, body)
    val port = ServerSocket(0).use { it.localPort }
    val command = cancellableChildCommand(
        content = content,
        data = data,
        report = report,
        port = port,
        token = token,
        armAdmission = armAdmission,
        admissionArmed = admissionArmed,
        admissionObservation = admissionObservation,
        receiveObservation = receiveObservation,
        callCompleted = callCompleted,
        handlerCompleted = handlerCompleted,
        hook = hook,
    )
    return CancellableBodyScenario(
        watchdog = watchdog,
        base = base,
        page = page,
        expected = expected,
        report = report,
        token = token,
        armAdmission = armAdmission,
        admissionArmed = admissionArmed,
        admissionObservation = admissionObservation,
        receiveObservation = receiveObservation,
        callCompleted = callCompleted,
        handlerCompleted = handlerCompleted,
        hook = hook,
        command = command,
        environment = isolatedEnvironment(base, data, content),
        port = port,
        body = body,
    )
}

private fun executeCancellableBodyScenario(scenario: CancellableBodyScenario) {
    val runningChild = launchHookChild(scenario.command, scenario.environment, scenario.watchdog)
    scenario.child = runningChild
    awaitHeldMarker(scenario.token, runningChild, scenario.watchdog, "cancellable token")
    scenario.mark("token_ready")
    val health = runningChild.awaitHealth(scenario.port)
    health.statusCode shouldBe 200
    health.body shouldContain "\"status\":\"ok\""
    scenario.mark("health_ready")
    awaitHeldMarker(scenario.hook, runningChild, scenario.watchdog, "cancellable real shutdown hook")

    val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    scenario.client = client
    val pageId = "0199aaaa-bbbb-7ccc-8ddd-0000000000d1"
    val uri = URI("http://127.0.0.1:${scenario.port}/api/v1/pages/$pageId")
    val token = Files.readString(scenario.token)
    val get = client.send(
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer $token")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    get.statusCode() shouldBe 200
    val etag = get.headers().firstValue("ETag").orElseThrow()

    writeParentMarker(scenario.armAdmission)
    awaitHeldMarker(scenario.admissionArmed, runningChild, scenario.watchdog, "cancellable admission arm")
    scenario.mark("admission_armed")
    val socket = Socket()
    scenario.socket = socket
    socket.soTimeout = 5_000
    socket.connect(InetSocketAddress("127.0.0.1", scenario.port), 2_000)
    val prefixSize = writeIncompleteCancellablePut(socket, pageId, token, etag, scenario.body)
    awaitHeldMarker(scenario.receiveObservation, runningChild, scenario.watchdog, "cancellable receive entry")
    val admission = readKeyValue(scenario.admissionObservation)
    val receive = readKeyValue(scenario.receiveObservation)
    admission["attribute_installed"] shouldBe "true"
    admission["attribute_identity_same"] shouldBe "true"
    admission["admission_job_identity"].orEmpty().shouldNotBeBlank()
    receive["call_identity"].orEmpty().shouldNotBeBlank()
    receive["receive_job_identity"] shouldBe admission["admission_job_identity"]
    scenario.mark("receive_ready")

    val signalDispatchStartedAt = System.nanoTime()
    runningChild.sendSigterm()
    val signalDispatchReturnedAt = System.nanoTime()
    scenario.mark("sigterm_sent")
    Thread.sleep(1_700)
    var suffixFlushedAt: Long? = null
    try {
        socket.getOutputStream().write(scenario.body, prefixSize, scenario.body.size - prefixSize)
        socket.getOutputStream().flush()
        suffixFlushedAt = System.nanoTime()
    } catch (failure: Throwable) {
        scenario.suffixWriteFailure = failure
    }
    scenario.mark("suffix_attempted")
    suffixFlushedAt?.let { flushedAt ->
        val dispatchToFlushMillis = TimeUnit.NANOSECONDS.toMillis(flushedAt - signalDispatchStartedAt)
        val returnToFlushMillis = TimeUnit.NANOSECONDS.toMillis(flushedAt - signalDispatchReturnedAt)
        scenario.timing["suffix_delay"] = dispatchToFlushMillis
        scenario.timing["suffix_return_to_flush"] = returnToFlushMillis
        check(returnToFlushMillis > 1_000L && dispatchToFlushMillis < 3_000L) {
            "cancellable suffix was not flushed in the 1-3s window: " +
                "dispatch_to_flush=${dispatchToFlushMillis}ms, return_to_flush=${returnToFlushMillis}ms"
        }
    }

    val handlerObserved = awaitOptionalMarker(scenario.handlerCompleted, runningChild, scenario.watchdog)
    val completionObserved = awaitOptionalMarker(scenario.callCompleted, runningChild, scenario.watchdog)
    runningChild.awaitExit(SHUTDOWN_DEADLINE_MILLIS)
    scenario.mark("child_exit")
    val completion = if (completionObserved) readKeyValue(scenario.callCompleted) else emptyMap()
    val handler = if (handlerObserved) readKeyValue(scenario.handlerCompleted) else emptyMap()
    val durable = Files.readAllBytes(scenario.page).contentEquals(scenario.body)
    val normalCompletion = completion["completed"] == "true" && completion["cause"] == "none"
    val handlerFinished = handler["handler_completed"] == "true"
    check(scenario.suffixWriteFailure == null && handlerFinished && normalCompletion && durable) {
        "cancellable PUT did not complete normally: " +
            "suffix_failure=${scenario.suffixWriteFailure?.javaClass?.name ?: "none"}, " +
            "handler_finished=$handlerFinished, completion_cause=${completion["cause"] ?: "missing"}, durable=$durable"
    }
    assertCloseReceipts(scenario.report)
}

private fun finishCancellableBodyScenario(scenario: CancellableBodyScenario) {
    var restoreInterrupt = Thread.interrupted()
    try {
        runCatching { scenario.socket?.close() }
            .exceptionOrNull()?.let { scenario.primary = retainFailure(scenario.primary, it) }
        scenario.client?.let { client ->
            val attempt = runCatching {
                closeHttpClient(client, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)) {
                    restoreInterrupt = true
                    Thread.interrupted()
                }
            }.getOrElse { failure ->
                scenario.primary = retainFailure(scenario.primary, failure)
                null
            }
            scenario.clientClose = attempt
            attempt?.failure?.let { scenario.primary = retainFailure(scenario.primary, it) }
            if (attempt?.interrupted == true) restoreInterrupt = true
        }
        scenario.child?.let { child ->
            val closeFailure = runCatching { child.close() }.exceptionOrNull()
            if (closeFailure != null) scenario.primary = retainFailure(scenario.primary, closeFailure)
            if (child.cleanupInterrupted()) restoreInterrupt = true
        }
        val observed = scenario.child?.snapshot()
        val evidenceFailure = runCatching {
            writeHeldEvidence(
                prefix = "server-run-hook-cancellable",
                observed = observed,
                report = scenario.report,
                base = scenario.base,
                page = scenario.page,
                token = scenario.token,
                timing = scenario.timing,
                heldProbe = null,
                availableProbe = null,
                failure = scenario.primary,
            )
        }.exceptionOrNull()
        if (evidenceFailure != null) scenario.primary = retainFailure(scenario.primary, evidenceFailure)
        val watchdogClose = scenario.watchdog.close()
        if (watchdogClose.interrupted) restoreInterrupt = true
        watchdogClose.failure?.let { scenario.primary = retainFailure(scenario.primary, it) }
        val childQuiescent = scenario.child?.isQuiescent() ?: true
        val clientQuiescent = scenario.client == null || scenario.clientClose?.isQuiescent == true
        val socketQuiescent = scenario.socket == null || scenario.socket?.isClosed == true
        if (childQuiescent && clientQuiescent && socketQuiescent && watchdogClose.terminated) {
            val cleanupFailure = runCatching {
                deleteTree(scenario.base, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HELD_CLEANUP_MILLIS))
                check(!Files.exists(scenario.base)) { "cancellable fixture cleanup left files" }
            }.exceptionOrNull()
            if (cleanupFailure != null) scenario.primary = retainFailure(scenario.primary, cleanupFailure)
        } else {
            scenario.primary = retainFailure(
                scenario.primary,
                IllegalStateException(
                    "cancellable fixture retained because a child/client/socket helper was not quiescent: " + scenario.base,
                ),
            )
        }
    } finally {
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
}

private fun cancellableChildCommand(
    content: Path,
    data: Path,
    report: Path,
    port: Int,
    token: Path,
    armAdmission: Path,
    admissionArmed: Path,
    admissionObservation: Path,
    receiveObservation: Path,
    callCompleted: Path,
    handlerCompleted: Path,
    hook: Path,
): List<String> {
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val mainRuntime = requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")) {
        "plainbase.test.mainRuntimeClasspath is required"
    }
    val nativeTestClasses = findNativeTestClasses()
    require(Files.isDirectory(nativeTestClasses)) { "nativeTest code-source output is missing: $nativeTestClasses" }
    val classpath = mainRuntime + File.pathSeparator + nativeTestClasses
    return listOf(
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
        "--token",
        token.toString(),
        "--cancellable-body",
        "true",
        "--arm-admission",
        armAdmission.toString(),
        "--admission-armed",
        admissionArmed.toString(),
        "--admission-observation",
        admissionObservation.toString(),
        "--receive-observation",
        receiveObservation.toString(),
        "--call-completed",
        callCompleted.toString(),
        "--save-returned",
        handlerCompleted.toString(),
        "--hook",
        hook.toString(),
    )
}

private fun writeIncompleteCancellablePut(
    socket: Socket,
    pageId: String,
    token: String,
    etag: String,
    body: ByteArray,
): Int {
    val prefixSize = body.size - 1
    val headers = (
        "PUT /api/v1/pages/$pageId HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Authorization: Bearer $token\r\n" +
            "If-Match: $etag\r\n" +
            "Content-Type: text/markdown\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)
    socket.getOutputStream().write(headers)
    socket.getOutputStream().write(body, 0, prefixSize)
    socket.getOutputStream().flush()
    return prefixSize
}

private fun awaitOptionalMarker(path: Path, child: HookRunningChild, watchdog: ParentWatchdog): Boolean {
    val deadline = watchdog.operationDeadline(5_000L)
    while (!Files.exists(path)) {
        watchdog.check()
        if (!child.isAliveForTest() || System.nanoTime() >= deadline) return false
        Thread.sleep(25L)
    }
    return true
}

private fun createHeldScenario(mode: HeldMode, watchdog: ParentWatchdog): HeldScenario {
    val base = Files.createTempDirectory("plainbase-server-" + mode.label)
    val content = Files.createDirectory(base.resolve("content"))
    val data = Files.createDirectory(base.resolve("data"))
    val page = Files.createDirectory(content.resolve("docs")).resolve("held.md")
    val expected = base.resolve("expected.md")
    val report = base.resolve("receipts.txt")
    val token = base.resolve("token.fixture")
    val trigger = base.resolve("shutdown.trigger")
    val armAdmission = base.resolve("arm-admission")
    val admissionArmed = base.resolve("admission-armed")
    val admissionObservation = base.resolve("admission.observation")
    val callCompleted = base.resolve("call.completed")
    val saveEntered = base.resolve("save.entered")
    val saveRelease = base.resolve("save.release")
    val saveReturned = base.resolve("save.returned")
    val shutdownEntered = base.resolve("shutdown.entered")
    val closeFacts = base.resolve("close.facts")
    val hook = base.resolve("hook.installed")
    val waiterInterruptions = base.resolve("waiter.interruptions")
    val runFinished = base.resolve("run.finished")
    val helperCompleted = base.resolve("helper.completed")
    val helperFailure = base.resolve("helper.failure")
    val originalBytes = "---\nid: 0199aaaa-bbbb-7ccc-8ddd-0000000000c5\ntitle: Held\n---\n\n# Held\n\noriginal.\n"
        .toByteArray(StandardCharsets.UTF_8)
    val updatedBytes = "---\nid: 0199aaaa-bbbb-7ccc-8ddd-0000000000c5\ntitle: Held\n---\n\n# Held\n\nupdated during shutdown.\n"
        .toByteArray(StandardCharsets.UTF_8)
    Files.write(page, originalBytes)
    Files.write(expected, updatedBytes)
    val port = ServerSocket(0).use { it.localPort }
    val command = heldChildCommand(
        content = content,
        data = data,
        report = report,
        port = port,
        mode = mode,
        page = page,
        expected = expected,
        token = token,
        trigger = trigger,
        armAdmission = armAdmission,
        admissionArmed = admissionArmed,
        admissionObservation = admissionObservation,
        callCompleted = callCompleted,
        saveEntered = saveEntered,
        saveRelease = saveRelease,
        saveReturned = saveReturned,
        shutdownEntered = shutdownEntered,
        closeFacts = closeFacts,
        hook = hook,
        waiterInterruptions = waiterInterruptions,
        runFinished = runFinished,
        helperCompleted = helperCompleted,
        helperFailure = helperFailure,
    )
    return HeldScenario(
        mode = mode,
        watchdog = watchdog,
        base = base,
        data = data,
        page = page,
        report = report,
        token = token,
        trigger = trigger,
        armAdmission = armAdmission,
        admissionArmed = admissionArmed,
        admissionObservation = admissionObservation,
        callCompleted = callCompleted,
        saveEntered = saveEntered,
        saveRelease = saveRelease,
        saveReturned = saveReturned,
        shutdownEntered = shutdownEntered,
        closeFacts = closeFacts,
        hook = hook,
        waiterInterruptions = waiterInterruptions,
        runFinished = runFinished,
        helperCompleted = helperCompleted,
        helperFailure = helperFailure,
        updatedBytes = updatedBytes,
        command = command,
        environment = isolatedEnvironment(base, data, content),
        port = port,
    )
}

private fun executeHeldScenario(scenario: HeldScenario) {
    val runningChild = launchHookChild(scenario.command, scenario.environment, scenario.watchdog)
    scenario.child = runningChild
    awaitHeldMarker(scenario.token, runningChild, scenario.watchdog, "token")
    scenario.mark("token_ready")
    val health = runningChild.awaitHealth(scenario.port)
    health.statusCode shouldBe 200
    health.body shouldContain "\"status\":\"ok\""
    scenario.mark("health_ready")
    awaitHeldMarker(scenario.hook, runningChild, scenario.watchdog, "real shutdown hook")

    val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    scenario.client = client
    val uri = URI("http://127.0.0.1:${scenario.port}/api/v1/pages/0199aaaa-bbbb-7ccc-8ddd-0000000000c5")
    val get = client.send(
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + Files.readString(scenario.token))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    get.statusCode() shouldBe 200
    val etag = get.headers().firstValue("ETag").orElseThrow()
    scenario.mark("get_complete")

    writeParentMarker(scenario.armAdmission)
    awaitHeldMarker(scenario.admissionArmed, runningChild, scenario.watchdog, "admission arm")
    scenario.mark("admission_armed")
    scenario.put = client.sendAsync(
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + Files.readString(scenario.token))
            .header("If-Match", etag)
            .header("Content-Type", "text/markdown")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(scenario.updatedBytes))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    awaitHeldMarker(scenario.saveEntered, runningChild, scenario.watchdog, "save entry")
    scenario.mark("save_ready")
    if (scenario.mode == HeldMode.NATURAL_RETURN) {
        writeParentMarker(scenario.trigger)
    } else {
        runningChild.sendSigterm()
        scenario.mark("sigterm_sent")
    }
    awaitHeldMarker(scenario.shutdownEntered, runningChild, scenario.watchdog, "HTTP close entry")
    scenario.mark("shutdown_entry")
    completeHeldShutdown(scenario, runningChild)
}

private fun completeHeldShutdown(scenario: HeldScenario, runningChild: HookRunningChild) {
    val holdDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HELD_WRITER_MILLIS)
    while (System.nanoTime() < holdDeadline) {
        scenario.watchdog.check()
        check(runningChild.isAliveForTest()) { scenario.mode.label + " child exited while the writer was held" }
        check(readReceipts(scenario.report).isEmpty()) { "dependent close began while the writer was held" }
        Thread.sleep(HELD_POLL_MILLIS)
    }
    scenario.mark("held_complete")
    scenario.heldProbe = runLockProbe(scenario.base, scenario.data, scenario.environment, scenario.watchdog) {
        scenario.probes += it
    }
    scenario.heldProbe?.stdoutText shouldBe "HELD\n"
    scenario.heldProbe?.stderrText shouldBe ""
    scenario.mark("held_lock_probe")

    writeParentMarker(scenario.saveRelease)
    scenario.mark("writer_released")
    awaitHeldMarker(scenario.saveReturned, runningChild, scenario.watchdog, "save delegate return")
    scenario.mark("save_returned")
    awaitHeldMarker(scenario.callCompleted, runningChild, scenario.watchdog, "final call completion")
    scenario.mark("call_completed")
    awaitHeldMarker(scenario.helperCompleted, runningChild, scenario.watchdog, "fixture helper completion")
    val helperCompletion = readKeyValue(scenario.helperCompleted)
    helperCompletion["completed"] shouldBe "true"
    helperCompletion["survivors"] shouldBe "0"
    awaitHeldMarker(scenario.helperFailure, runningChild, scenario.watchdog, "fixture helper result")
    readKeyValue(scenario.helperFailure)["failure"] shouldBe "none"
    awaitHeldMarker(scenario.closeFacts, runningChild, scenario.watchdog, "dependent-close facts")
    val facts = readKeyValue(scenario.closeFacts)
    facts["attribute_installed"] shouldBe "true"
    facts["attribute_identity_same"] shouldBe "true"
    facts["original_job_complete_before_close"] shouldBe "true"
    facts["durable_bytes_before_close"] shouldBe "true"
    scenario.mark("close_facts")
    awaitHeldMarker(scenario.waiterInterruptions, runningChild, scenario.watchdog, "waiter interruption control")
    readKeyValue(scenario.waiterInterruptions)["count"]?.toIntOrNull()?.let { check(it >= 2) } ?: error(
        "waiter interruption count is missing",
    )
    scenario.mark("waiter_interruptions")
    runningChild.awaitExit(SHUTDOWN_DEADLINE_MILLIS)
    scenario.mark("child_exit")
    val result = runningChild.snapshot()
    result.exitCode shouldBe if (scenario.mode == HeldMode.NATURAL_RETURN) 0 else 143
    result.signalExitCode shouldBe if (scenario.mode == HeldMode.NATURAL_RETURN) -1 else 0
    if (scenario.mode == HeldMode.NATURAL_RETURN) {
        val finished = readKeyValue(scenario.runFinished)
        finished["status"] shouldBe "0"
        finished["natural_waiter_interrupted"] shouldBe "true"
    }
    Files.readAllBytes(scenario.page).contentEquals(scenario.updatedBytes) shouldBe true
    assertCloseReceipts(scenario.report)
    scenario.availableProbe = runLockProbe(scenario.base, scenario.data, scenario.environment, scenario.watchdog) {
        scenario.probes += it
    }
    scenario.availableProbe?.stdoutText shouldBe "AVAILABLE\n"
    scenario.availableProbe?.stderrText shouldBe ""
    scenario.mark("available_lock_probe")
}

private fun finishHeldScenario(scenario: HeldScenario) {
    var restoreInterrupt = Thread.interrupted()
    try {
        runCatching { writeParentMarker(scenario.saveRelease) }
            .exceptionOrNull()?.let { scenario.primary = retainFailure(scenario.primary, it) }
        runCatching { writeParentMarker(scenario.trigger) }
            .exceptionOrNull()?.let { scenario.primary = retainFailure(scenario.primary, it) }
        runCatching { scenario.put?.cancel(true) }
            .exceptionOrNull()?.let { scenario.primary = retainFailure(scenario.primary, it) }
        scenario.client?.let { httpClient ->
            val attempt = runCatching {
                closeHttpClient(httpClient, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)) {
                    restoreInterrupt = true
                    scenario.rememberInterrupt()
                }
            }.getOrElse { failure ->
                scenario.primary = retainFailure(scenario.primary, failure)
                null
            }
            scenario.clientClose = attempt
            attempt?.failure?.let { scenario.primary = retainFailure(scenario.primary, it) }
        }
        scenario.child?.let { runningChild ->
            val closeFailure = runCatching { runningChild.close() }.exceptionOrNull()
            if (runningChild.cleanupInterrupted()) {
                restoreInterrupt = true
                scenario.rememberInterrupt()
            }
            if (closeFailure != null) scenario.primary = retainFailure(scenario.primary, closeFailure)
        }
        scenario.probes.forEach { probe ->
            if (probe.interrupted) {
                restoreInterrupt = true
                scenario.rememberInterrupt()
            }
            probe.cleanupFailure?.let { scenario.primary = retainFailure(scenario.primary, it) }
            if (!probe.isQuiescent()) {
                val cleanupFailure = cleanupProbe(probe, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) {
                    restoreInterrupt = true
                    scenario.rememberInterrupt()
                }
                if (cleanupFailure != null) scenario.primary = retainFailure(scenario.primary, cleanupFailure)
            }
            if (probe.interrupted) {
                restoreInterrupt = true
                scenario.rememberInterrupt()
            }
        }
        val observed = scenario.child?.snapshot()
        val evidenceFailure = runCatching {
            writeHeldEvidence(
                prefix = "server-run-hook-" + scenario.mode.label,
                observed = observed,
                report = scenario.report,
                base = scenario.base,
                page = scenario.page,
                token = scenario.token,
                timing = scenario.timing,
                heldProbe = scenario.heldProbe,
                availableProbe = scenario.availableProbe,
                failure = scenario.primary,
            )
        }.exceptionOrNull()
        if (evidenceFailure != null) scenario.primary = retainFailure(scenario.primary, evidenceFailure)
        val watchdogClose = scenario.watchdog.close()
        if (watchdogClose.interrupted) {
            restoreInterrupt = true
            scenario.rememberInterrupt()
        }
        watchdogClose.failure?.let { scenario.primary = retainFailure(scenario.primary, it) }
        val childQuiescent = scenario.child?.let { it.isQuiescent() } ?: true
        val clientQuiescent = scenario.client == null || scenario.clientClose?.isQuiescent == true
        val canDelete =
            childQuiescent &&
                scenario.put?.isDone != false &&
                clientQuiescent &&
                scenario.probes.all { it.isQuiescent() } &&
                watchdogClose.terminated
        if (canDelete) {
            val cleanupFailure = runCatching {
                deleteTree(scenario.base, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HELD_CLEANUP_MILLIS))
                check(!Files.exists(scenario.base)) { scenario.mode.label + " fixture cleanup left files" }
            }.exceptionOrNull()
            if (cleanupFailure != null) scenario.primary = retainFailure(scenario.primary, cleanupFailure)
        } else {
            scenario.primary = retainFailure(
                scenario.primary,
                IllegalStateException(
                    scenario.mode.label + " fixture retained because a child/request/probe helper was not quiescent: " +
                        scenario.base,
                ),
            )
        }
    } finally {
        if (scenario.interrupted || restoreInterrupt) Thread.currentThread().interrupt()
    }
}

private fun heldChildCommand(
    content: Path,
    data: Path,
    report: Path,
    port: Int,
    mode: HeldMode,
    page: Path,
    expected: Path,
    token: Path,
    trigger: Path,
    armAdmission: Path,
    admissionArmed: Path,
    admissionObservation: Path,
    callCompleted: Path,
    saveEntered: Path,
    saveRelease: Path,
    saveReturned: Path,
    shutdownEntered: Path,
    closeFacts: Path,
    hook: Path,
    waiterInterruptions: Path,
    runFinished: Path,
    helperCompleted: Path,
    helperFailure: Path,
): List<String> {
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val mainRuntime = requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")) {
        "plainbase.test.mainRuntimeClasspath is required"
    }
    val nativeTestClasses = findNativeTestClasses()
    require(Files.isDirectory(nativeTestClasses)) { "nativeTest code-source output is missing: " + nativeTestClasses }
    val classpath = mainRuntime + File.pathSeparator + nativeTestClasses
    return listOf(
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
        "--mode",
        mode.label,
        "--page",
        page.toString(),
        "--expected",
        expected.toString(),
        "--token",
        token.toString(),
        "--trigger",
        trigger.toString(),
        "--arm-admission",
        armAdmission.toString(),
        "--admission-armed",
        admissionArmed.toString(),
        "--admission-observation",
        admissionObservation.toString(),
        "--call-completed",
        callCompleted.toString(),
        "--save-entered",
        saveEntered.toString(),
        "--save-release",
        saveRelease.toString(),
        "--save-returned",
        saveReturned.toString(),
        "--shutdown-entered",
        shutdownEntered.toString(),
        "--close-facts",
        closeFacts.toString(),
        "--hook",
        hook.toString(),
        "--waiter-interruptions",
        waiterInterruptions.toString(),
        "--run-finished",
        runFinished.toString(),
        "--helper-completed",
        helperCompleted.toString(),
        "--helper-failure",
        helperFailure.toString(),
    )
}

private fun launchHookChild(
    command: List<String>,
    environment: Map<String, String>,
    watchdog: ParentWatchdog,
): HookRunningChild {
    val builder = ProcessBuilder(command)
    builder.directory(Path.of(environment.getValue("TMPDIR")).toFile())
    builder.environment().clear()
    builder.environment().putAll(environment)
    return HookRunningChild(command, environment, builder.start(), watchdog)
}

private fun awaitHeldMarker(path: Path, child: HookRunningChild, watchdog: ParentWatchdog, name: String) {
    val deadline = watchdog.operationDeadline(HELD_OPERATION_DEADLINE_MILLIS)
    while (!Files.exists(path)) {
        watchdog.check()
        check(child.isAliveForTest()) {
            "child exited before " + name + " marker: " + child.diagnosticsForTest()
        }
        check(System.nanoTime() < deadline) { "missing " + name + " marker after " + HELD_OPERATION_DEADLINE_MILLIS + "ms" }
        Thread.sleep(HELD_POLL_MILLIS)
    }
}

private fun assertCloseReceipts(path: Path) {
    val receipts = readReceipts(path)
    receipts.filter { it.startsWith("close=") }.shouldContainExactlyInAnyOrder(
        "close=app lockHeld=true",
        "close=search lockHeld=true",
        "close=context lockHeld=true",
    )
    receipts.filter { it.startsWith("close-entry=") && !it.contains(" lockHeld=") }
        .shouldContainExactlyInAnyOrder("close-entry=app", "close-entry=search", "close-entry=context")
    receipts.filter { it.startsWith("close-entry=") && it.contains(" lockHeld=") }
        .shouldContainExactlyInAnyOrder(
            "close-entry=app lockHeld=true",
            "close-entry=search lockHeld=true",
            "close-entry=context lockHeld=true",
        )
}

private fun readReceipts(path: Path): List<String> =
    if (Files.exists(path)) Files.readAllLines(path, StandardCharsets.UTF_8) else emptyList()

private fun readKeyValue(path: Path): Map<String, String> =
    Files.readAllLines(path, StandardCharsets.UTF_8)
        .filter { it.isNotBlank() }
        .associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "malformed marker line: " + line }
            line.substring(0, separator) to line.substring(separator + 1)
        }

private fun writeParentMarker(path: Path) {
    Files.createDirectories(requireNotNull(path.parent))
    val temporary = path.resolveSibling("." + path.fileName + ".parent-tmp")
    Files.writeString(temporary, "go=true\n", StandardCharsets.UTF_8)
    try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun retainFailure(current: Throwable?, failure: Throwable): Throwable {
    if (current == null) return failure
    if (current !== failure && current.suppressed.none { it === failure }) current.addSuppressed(failure)
    return current
}

private class ClientCloseObservation(
    val worker: Thread,
    val result: AtomicReference<Result<Unit>?>,
) {
    var interrupted: Boolean = false
    var failure: Throwable? = null
    val isQuiescent: Boolean get() = !worker.isAlive && result.get() != null
}

private fun closeHttpClient(client: HttpClient, deadline: Long, onInterrupt: () -> Unit): ClientCloseObservation {
    val result = AtomicReference<Result<Unit>?>()
    lateinit var observation: ClientCloseObservation
    val worker = thread(start = false, name = "plainbase-checkpoint05-client-close") {
        result.set(runCatching { client.close() })
    }
    observation = ClientCloseObservation(worker, result)
    try {
        worker.start()
    } catch (failure: Throwable) {
        observation.failure = failure
        return observation
    }
    val escalationAt = maxOf(System.nanoTime(), deadline - TimeUnit.MILLISECONDS.toNanos(CLIENT_CLOSE_ESCALATION_MILLIS))
    joinOwnedThread(worker, escalationAt) {
        observation.interrupted = true
        Thread.interrupted()
        onInterrupt()
    }
    if (worker.isAlive) {
        runCatching { worker.interrupt() }.exceptionOrNull()?.let { observation.failure = retainFailure(observation.failure, it) }
        joinOwnedThread(worker, deadline) {
            observation.interrupted = true
            Thread.interrupted()
            onInterrupt()
        }
    }
    if (worker.isAlive) {
        observation.failure = retainFailure(
            observation.failure,
            TimeoutException("HTTP client close exceeded its cleanup deadline"),
        )
    } else {
        val closeResult = result.get()
        val closeFailure = closeResult?.exceptionOrNull() ?: if (closeResult == null) {
                IllegalStateException("HTTP client close completed without a result")
            } else {
                null
            }
        if (closeFailure != null) observation.failure = retainFailure(observation.failure, closeFailure)
    }
    return observation
}

private data class ProbeObservation(
    val command: List<String>,
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val durationMillis: Long,
) {
    val stdoutText: String get() = stdout.toString(StandardCharsets.UTF_8)
    val stderrText: String get() = stderr.toString(StandardCharsets.UTF_8)
}

private class ProbeResources(
    val command: List<String>,
    val process: Process,
    val stdout: BoundedOutputStream,
    val stderr: BoundedOutputStream,
    val drainFailure: AtomicReference<Throwable?>,
) {
    var stdoutDrain: Thread? = null
    var stderrDrain: Thread? = null
    var observation: ProbeObservation? = null
    var interrupted: Boolean = false
    var cleanupFailure: Throwable? = null

    fun isQuiescent(): Boolean =
        !process.isAlive && stdoutDrain?.isAlive != true && stderrDrain?.isAlive != true
}

private fun runLockProbe(
    base: Path,
    data: Path,
    environment: Map<String, String>,
    watchdog: ParentWatchdog,
    register: (ProbeResources) -> Unit = {},
): ProbeObservation {
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val mainRuntime = requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")) {
        "plainbase.test.mainRuntimeClasspath is required"
    }
    val nativeTestClasses = findNativeTestClasses()
    val classpath = mainRuntime + File.pathSeparator + nativeTestClasses
    val command = listOf(
        java.toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        classpath,
        "com.plainbase.ServerLifecycleLauncherKt",
        "--probe-lock",
        "--data",
        data.toAbsolutePath().normalize().toString(),
    )
    val process = ProcessBuilder(command).directory(base.toFile()).apply {
        environment().clear()
        environment().putAll(environment)
    }.start()
    val stdout = BoundedOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
    val stderr = BoundedOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
    val failure = AtomicReference<Throwable?>()
    val resources = ProbeResources(command, process, stdout, stderr, failure)
    register(resources)
    resources.stdoutDrain = drain(process.inputStream, stdout, "plainbase-checkpoint05-probe-stdout", failure)
    resources.stderrDrain = drain(process.errorStream, stderr, "plainbase-checkpoint05-probe-stderr", failure)
    val startedAt = System.nanoTime()
    var primary: Throwable? = null
    var deadline = 0L
    try {
        deadline = watchdog.operationDeadline(PROBE_DEADLINE_MILLIS)
        while (process.isAlive && System.nanoTime() < deadline) {
            watchdog.check()
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) break
            process.waitFor(minOf(HELD_POLL_MILLIS, remaining), TimeUnit.MILLISECONDS)
        }
        if (process.isAlive) throw TimeoutException("lock probe did not terminate")
        check(!process.isAlive) { "lock probe process survived its operation deadline" }
        joinProbeDrains(resources, deadline) {
            resources.interrupted = true
            Thread.interrupted()
        }?.let { throw it }
        failure.get()?.let { throw IllegalStateException("lock probe output drain failed", it) }
        val observation = ProbeObservation(
            command = command,
            exitCode = process.exitValue(),
            stdout = stdout.toByteArray(),
            stderr = stderr.toByteArray(),
            durationMillis = elapsedMillis(startedAt),
        )
        check(observation.exitCode == 0) { "lock probe exited " + observation.exitCode + ": " + observation.stdoutText }
        check(observation.stdoutText == "HELD\n" || observation.stdoutText == "AVAILABLE\n") {
            "lock probe output was not exact: " + observation.stdoutText
        }
        check(observation.stderrText.isEmpty()) { "lock probe wrote unexpected stderr: " + observation.stderrText }
        resources.observation = observation
    } catch (caught: Throwable) {
        if (caught is InterruptedException) {
            resources.interrupted = true
            Thread.interrupted()
        }
        primary = retainFailure(primary, caught)
    } finally {
        if (primary == null && resources.observation == null) {
            primary = IllegalStateException("lock probe completed without an observation")
        }
        val cleanupFailure = cleanupProbe(resources, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) {
            resources.interrupted = true
            Thread.interrupted()
        }
        if (cleanupFailure != null) primary = retainFailure(primary, cleanupFailure)
    }
    primary?.let { throw it }
    return requireNotNull(resources.observation)
}

private fun joinProbeDrains(
    resources: ProbeResources,
    deadline: Long,
    onInterrupt: () -> Unit,
): Throwable? {
    var failure: Throwable? = null
    listOfNotNull(resources.stdoutDrain, resources.stderrDrain).forEach { drain ->
        if (!joinOwnedThread(drain, deadline, onInterrupt) && drain.isAlive) {
            failure = retainFailure(failure, IllegalStateException("lock probe output drain survived observation"))
        }
    }
    return failure
}

private fun cleanupProbe(resources: ProbeResources, deadline: Long, onInterrupt: () -> Unit): Throwable? {
    var failure: Throwable? = null
    runCatching {
        if (resources.process.isAlive) resources.process.destroyForcibly()
    }.exceptionOrNull()?.let { failure = retainFailure(failure, it) }
    runCatching {
        while (resources.process.isAlive) {
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) break
            try {
                resources.process.waitFor(minOf(HELD_POLL_MILLIS, remaining), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.interrupted()
                onInterrupt()
            }
        }
        if (resources.process.isAlive) error("lock probe process survived cleanup")
    }.exceptionOrNull()?.let { failure = retainFailure(failure, it) }
    runCatching { resources.process.inputStream.close() }.exceptionOrNull()?.let { failure = retainFailure(failure, it) }
    runCatching { resources.process.errorStream.close() }.exceptionOrNull()?.let { failure = retainFailure(failure, it) }
    listOfNotNull(resources.stdoutDrain, resources.stderrDrain).forEach { drain ->
        val joined = joinOwnedThread(drain, deadline) {
            resources.interrupted = true
            Thread.interrupted()
            onInterrupt()
        }
        if (!joined && drain.isAlive) {
            failure = retainFailure(failure, IllegalStateException("lock probe output drain survived cleanup"))
        }
    }
    resources.drainFailure.get()?.let { failure = retainFailure(failure, IllegalStateException("lock probe output drain failed", it)) }
    resources.cleanupFailure = failure
    return failure
}

private fun joinOwnedThread(thread: Thread, deadline: Long, onInterrupt: () -> Unit): Boolean {
    while (thread.isAlive) {
        val remaining = remainingMillis(deadline)
        if (remaining == 0L) return false
        try {
            thread.join(minOf(HELD_POLL_MILLIS, remaining))
        } catch (_: InterruptedException) {
            Thread.interrupted()
            onInterrupt()
        }
    }
    return true
}

private class BoundedOutputStream(private val limit: Int) : OutputStream() {
    private val delegate = ByteArrayOutputStream(limit)
    var truncated: Boolean = false
        private set

    override fun write(value: Int) {
        if (delegate.size() < limit) {
            delegate.write(value)
        } else {
            truncated = true
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "invalid output slice" }
        val remaining = limit - delegate.size()
        if (remaining > 0) delegate.write(bytes, offset, minOf(length, remaining))
        if (length > remaining) truncated = true
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    fun text(): String = toByteArray().toString(StandardCharsets.UTF_8)
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
    private val stdout = BoundedOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
    private val stderr = BoundedOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
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
    private var processExitCode = -1
    private var streamsFinished = false
    private var healthClient: HttpClient? = null
    private var healthClientClose: ClientCloseObservation? = null
    private var cleanupWasInterrupted = false

    fun awaitHealth(port: Int): HookHealthObservation {
        val deadline = watchdog.operationDeadline(BOOT_DEADLINE_MILLIS)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        healthClient = client
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
        while (processExitCode < 0) {
            processExitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            if (processExitCode >= 0) break
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) error("child exit status was not available within ${deadlineMillis}ms: ${diagnostics()}")
            process.waitFor(minOf(25L, remaining), TimeUnit.MILLISECONDS)
        }
        terminatedAt = System.nanoTime()
    }

    fun isAliveForTest(): Boolean = process.isAlive

    fun isQuiescent(): Boolean =
        ownedHandles.none { it.isAlive } && !stdoutDrain.isAlive && !stderrDrain.isAlive &&
            (healthClientClose == null || healthClientClose?.isQuiescent == true)

    fun cleanupInterrupted(): Boolean = cleanupWasInterrupted

    fun diagnosticsForTest(): String = diagnostics()

    fun ownerCompletionMillis(): Long {
        check(streamsFinished) { "owner completion requires joined child streams" }
        return Regex("shutdown complete in (\\d+)ms")
            .find(stderr.text())
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: error("child stderr has no completed owner duration")
    }

    override fun close() {
        var failure: Throwable? = null
        healthClient?.let { client ->
            if (healthClientClose == null) {
                val attempt = closeHttpClient(client, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)) {
                    cleanupWasInterrupted = true
                }
                healthClientClose = attempt
                attempt.failure?.let { failure = retainFailure(failure, it) }
                if (attempt.interrupted) cleanupWasInterrupted = true
            }
        }
        try {
            terminateOwned()
        } catch (cleanupFailure: Throwable) {
            failure = retainFailure(failure, cleanupFailure)
        }
        try {
            finishStreams()
        } catch (streamFailure: Throwable) {
            failure = retainFailure(failure, streamFailure)
        }
        if (failure != null) throw failure
    }

    fun snapshot(): ChildObservation =
        ChildObservation(
            command = command,
            environment = environment,
            exitCode = if (processExitCode >= 0) processExitCode else runCatching { process.exitValue() }.getOrDefault(-1),
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
                cleanupWasInterrupted = true
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
        var failure: Throwable? = null
        val deadline = System.nanoTime() + STREAM_DEADLINE_MILLIS * 1_000_000
        val initialDeadline = deadline - STREAM_CLOSE_JOIN_DEADLINE_MILLIS * 1_000_000
        joinDrain(stdoutDrain, initialDeadline)
        joinDrain(stderrDrain, initialDeadline)
        if (stdoutDrain.isAlive || stderrDrain.isAlive) {
            runCatching { process.inputStream.close() }
                .exceptionOrNull()?.let { failure = retainFailure(failure, it) }
            runCatching { process.errorStream.close() }
                .exceptionOrNull()?.let { failure = retainFailure(failure, it) }
            joinDrain(stdoutDrain, deadline)
            joinDrain(stderrDrain, deadline)
        }
        if (stdoutDrain.isAlive || stderrDrain.isAlive) {
            failure = retainFailure(
                failure,
                IllegalStateException("stream drain exceeded ${STREAM_DEADLINE_MILLIS}ms: ${diagnostics()}"),
            )
        }
        drainFailure.get()?.let { drain ->
            failure = retainFailure(failure, IllegalStateException("stream drain failed", drain))
        }
        if (!stdoutDrain.isAlive && !stderrDrain.isAlive) streamsFinished = true
        if (failure != null) throw failure
    }

    private fun joinDrain(drain: Thread, deadline: Long) {
        while (drain.isAlive) {
            val remaining = remainingMillis(deadline)
            if (remaining == 0L) return
            try {
                drain.join(remaining)
            } catch (_: InterruptedException) {
                cleanupWasInterrupted = true
                Thread.interrupted()
            }
        }
    }

    private fun diagnostics(): String =
        "stdout=" + stdout.text() + " stderr=" + stderr.text()
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

private data class WatchdogCloseObservation(
    val interrupted: Boolean,
    val terminated: Boolean,
    val failure: Throwable?,
)

private class ParentWatchdog {
    private val owner = Thread.currentThread()
    private val expired = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor(HookWatchdogThreadFactory)
    private val deadline = System.nanoTime() + PARENT_WATCHDOG_DEADLINE_MILLIS * 1_000_000
    private var closeObservation: WatchdogCloseObservation? = null
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

    @Synchronized
    fun close(): WatchdogCloseObservation {
        closeObservation?.let { return it }
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null
        alarm.cancel(false)
        scheduler.shutdownNow()
        val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!scheduler.isTerminated) {
            val remaining = remainingMillis(closeDeadline)
            if (remaining == 0L) break
            try {
                scheduler.awaitTermination(remaining, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
        }
        val terminated = scheduler.isTerminated
        if (!terminated) failure = TimeoutException("parent watchdog scheduler survived cleanup")
        return WatchdogCloseObservation(interrupted, terminated, failure).also { closeObservation = it }
    }
}

private object HookWatchdogThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "plainbase-server-run-hook-parent-watchdog").apply { isDaemon = true }
}

private fun writeHeldEvidence(
    prefix: String,
    observed: ChildObservation?,
    report: Path,
    base: Path,
    page: Path,
    token: Path,
    timing: Map<String, Long>,
    heldProbe: ProbeObservation?,
    availableProbe: ProbeObservation?,
    failure: Throwable?,
) {
    val evidence = Path.of(
        requireNotNull(System.getProperty(EVIDENCE_DIRECTORY_PROPERTY)) {
            EVIDENCE_DIRECTORY_PROPERTY + " is required; launch ./gradlew :server:test"
        },
    ).toAbsolutePath().normalize()
    Files.createDirectories(evidence)
    if (observed == null) {
        Files.writeString(evidence.resolve(prefix + ".meta"), "outcome=FAILURE\nno_child_observation=true\n")
    } else {
        Files.write(evidence.resolve(prefix + ".stdout"), observed.stdout)
        Files.write(evidence.resolve(prefix + ".stderr"), observed.stderr)
        Files.writeString(
            evidence.resolve(prefix + ".meta"),
            buildString {
                appendLine("outcome=" + if (failure == null) "PASS" else "FAILURE")
                appendLine("command=")
                observed.command.forEach(::appendLine)
                appendLine("environment=")
                observed.environment.forEach { (key, value) -> appendLine(key + "=" + value) }
                appendLine("exit=" + observed.exitCode)
                appendLine("duration_ms=" + observed.durationMillis)
                appendLine("startup_ms=" + observed.startupMillis)
                appendLine("shutdown_ms=" + observed.shutdownMillis)
                appendLine("health_status=" + observed.healthStatus)
                appendLine("health_body=" + observed.healthBody)
                appendLine("kill_exit=" + observed.signalExitCode)
                if (failure != null) {
                    appendLine("failure_type=" + failure::class.java.name)
                    appendLine("failure_message=" + failure.message)
                }
            },
        )
    }
    if (Files.exists(report)) {
        Files.copy(report, evidence.resolve(prefix + ".receipts"), StandardCopyOption.REPLACE_EXISTING)
    } else {
        Files.write(evidence.resolve(prefix + ".receipts"), ByteArray(0))
    }
    if (Files.exists(page)) {
        Files.copy(page, evidence.resolve(prefix + "-final-page.md"), StandardCopyOption.REPLACE_EXISTING)
    }
    Files.writeString(
        evidence.resolve(prefix + ".timing"),
        timing.entries.joinToString(separator = "\n", postfix = "\n") { (name, millis) -> name + "_ms=" + millis },
    )
    listOf(heldProbe to "held", availableProbe to "available").forEach { (probe, label) ->
        if (probe != null) {
            Files.write(evidence.resolve(prefix + "-probe-" + label + ".stdout"), probe.stdout)
            Files.write(evidence.resolve(prefix + "-probe-" + label + ".stderr"), probe.stderr)
            Files.writeString(
                evidence.resolve(prefix + "-probe-" + label + ".meta"),
                buildString {
                    appendLine("exit=" + probe.exitCode)
                    appendLine("duration_ms=" + probe.durationMillis)
                    appendLine("command=")
                    probe.command.forEach(::appendLine)
                },
            )
        }
    }
    Files.list(base).use { entries ->
        entries.filter { it != token && !it.fileName.toString().contains(".tmp") && Files.isRegularFile(it) }.forEach { path ->
            Files.copy(path, evidence.resolve(prefix + "-fixture-" + path.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun writeLocalEvidence(
    observed: ChildObservation?,
    report: Path,
    base: Path,
    token: Path,
    measurement: Map<String, String>,
    availableProbe: ProbeObservation?,
    failure: Throwable?,
) {
    val evidence = Path.of(
        requireNotNull(System.getProperty(EVIDENCE_DIRECTORY_PROPERTY)) {
            EVIDENCE_DIRECTORY_PROPERTY + " is required; launch ./gradlew :server:test"
        },
    ).toAbsolutePath().normalize()
    Files.createDirectories(evidence)
    if (observed != null) {
        Files.write(evidence.resolve("server-run-hook-local.stdout"), observed.stdout)
        Files.write(evidence.resolve("server-run-hook-local.stderr"), observed.stderr)
        Files.writeString(
            evidence.resolve("server-run-hook-local.meta"),
            buildString {
                appendLine("outcome=" + if (failure == null) "PASS" else "FAILURE")
                appendLine("exit=${observed.exitCode}")
                appendLine("startup_ms=${observed.startupMillis}")
                appendLine("signal_to_exit_ms=${observed.shutdownMillis}")
                appendLine("health_status=${observed.healthStatus}")
                appendLine("kill_exit=${observed.signalExitCode}")
                appendLine("runtime_java=${observed.command.firstOrNull() ?: "unknown"}")
                appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
                appendLine("host=${System.getenv("HOSTNAME") ?: "unknown"}")
                appendLine("topology=one LOCAL root; JVM child; CIO; Git disabled; OBJECT absent")
                appendLine("command=")
                observed.command.forEach(::appendLine)
                appendLine("environment=")
                observed.environment.forEach { (key, value) -> appendLine("$key=$value") }
                if (failure != null) appendLine("failure=${failure::class.java.name}:${failure.message}")
            },
        )
    } else {
        Files.writeString(evidence.resolve("server-run-hook-local.meta"), "outcome=FAILURE\nno_child_observation=true\n")
    }
    if (Files.exists(report)) {
        Files.copy(report, evidence.resolve("server-run-hook-local.receipts"), StandardCopyOption.REPLACE_EXISTING)
    }
    Files.writeString(
        evidence.resolve("server-run-hook-local.measurement"),
        measurement.entries.joinToString(separator = "\n", postfix = "\n") { (name, value) -> "$name=$value" } +
            "failure=${failure?.let { it::class.java.name + ":" + it.message } ?: "none"}\n",
    )
    if (availableProbe != null) {
        Files.write(evidence.resolve("server-run-hook-local-probe-available.stdout"), availableProbe.stdout)
        Files.write(evidence.resolve("server-run-hook-local-probe-available.stderr"), availableProbe.stderr)
        Files.writeString(
            evidence.resolve("server-run-hook-local-probe-available.meta"),
            buildString {
                appendLine("exit=${availableProbe.exitCode}")
                appendLine("duration_ms=${availableProbe.durationMillis}")
                appendLine("command=")
                availableProbe.command.forEach(::appendLine)
            },
        )
    }
    Files.list(base).use { entries ->
        entries.filter { it != token && Files.isRegularFile(it) }.forEach { path ->
            Files.copy(path, evidence.resolve("server-run-hook-local-" + path.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun <T> withParentWatchdog(block: (ParentWatchdog) -> T): T {
    var restoreInterrupt = Thread.interrupted()
    val watchdog = ParentWatchdog()
    var primary: Throwable? = null
    var result: T? = null
    try {
        result = block(watchdog)
        watchdog.check()
    } catch (caught: Throwable) {
        if (caught is InterruptedException) {
            restoreInterrupt = true
            Thread.interrupted()
        }
        if (watchdog.hasExpired()) {
            Thread.interrupted()
            primary = TimeoutException("parent watchdog exceeded ${PARENT_WATCHDOG_DEADLINE_MILLIS}ms").also {
                it.addSuppressed(caught)
            }
        } else {
            primary = caught
        }
    } finally {
        val closeObservation = watchdog.close()
        restoreInterrupt = closeObservation.interrupted || restoreInterrupt
        closeObservation.failure?.let { primary = retainFailure(primary, it) }
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
    primary?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
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
    output: OutputStream,
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
