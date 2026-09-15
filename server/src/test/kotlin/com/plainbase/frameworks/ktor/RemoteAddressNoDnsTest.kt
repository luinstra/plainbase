package com.plainbase.frameworks.ktor

import com.plainbase.mainSourceRoot
import com.plainbase.referencesToken
import com.plainbase.stripComments
import io.kotest.core.spec.style.FunSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val CHILD_RUNTIME_CLASSPATH_PROPERTY = "plainbase.test.childRuntimeClasspath"
private const val LAUNCHER_CLASS = "com.plainbase.frameworks.ktor.RemoteAddressNoDnsLauncherKt"
private const val MAX_CAPTURED_OUTPUT_BYTES = 64 * 1024
private const val OUTPUT_OVERFLOW_MESSAGE = "child output exceeded the capture limit"
private const val STREAM_DEADLINE_MILLIS = 2_000L
private const val CLEANUP_GRACE_MILLIS = 500L
private const val CLEANUP_REAP_MILLIS = 1_500L

class RemoteAddressNoDnsTest : FunSpec({
    test("the production helper has one literal parser and no resolver operations") {
        val sourceFile = mainSourceRoot().resolve("frameworks/ktor/RemoteAddress.kt")
        val code = stripComments(Files.readString(sourceFile))
        val forbidden = listOf(
            "getByName",
            "getAllByName",
            "getLocalHost",
            "getHostName",
            "getCanonicalHostName",
            "hostName",
            "canonicalHostName",
            "InetAddressResolver",
            "ResolverProvider",
            "NameService",
            "InetSocketAddress",
        )
        check(forbidden.isNotEmpty()) { "source guard has no known prohibited operations" }
        check(code.contains("parseNumericLiteral")) { "source guard did not find the parser declaration" }
        val violations = forbidden.filter { referencesToken(code, it) }
        check(code.contains("InetAddress.ofLiteral") && violations.isEmpty()) {
            "RemoteAddress source guard failed: literal parser present=${code.contains("InetAddress.ofLiteral")}, " +
                "prohibited operations=$violations"
        }
    }

    test("six isolated JVM lanes reject hostname resolution and preserve address policy") {
        val failures = RemoteAddressNoDnsCases.lanes.flatMap { lane ->
            validateLane(lane, runChild(lane))
        }
        if (failures.isNotEmpty()) error(failures.joinToString("\n\n"))
    }

    test("the bounded runner reaps an intentional stall") {
        val result = runChild("stall", deadlineMillis = 500L)
        check(result.failure is TimeoutException) { "stall did not hit its bounded deadline: ${result.failure}" }
        check(!result.processAlive) { "stall process survived forced cleanup" }
        check(!result.stdoutDrainAlive && !result.stderrDrainAlive) { "stall stream drain survived forced cleanup" }
    }

    test("retains output overflow when timeout is the primary failure") {
        val result = runChild("overflow", deadlineMillis = 500L, beforeAwait = { output ->
            check(output.awaitOverflow()) { "child did not overflow output before the startup deadline" }
        })
        val failure = result.failure
        check(failure is TimeoutException) { "overflow did not hit its bounded deadline: $failure" }
        check(failure.suppressed.count { it.message == OUTPUT_OVERFLOW_MESSAGE } == 1) {
            "output overflow was not retained exactly once alongside timeout"
        }
        check(!result.processAlive) { "overflow process survived forced cleanup" }
        check(!result.stdoutDrainAlive && !result.stderrDrainAlive) {
            "overflow stream drain survived forced cleanup"
        }
    }

    test("interrupting an owned wait completes cleanup and restores interruption") {
        val childReady = CountDownLatch(1)
        val result = AtomicReference<ChildResult?>()
        val owner = thread(start = true, isDaemon = true, name = "plainbase-no-dns-interrupted-owner") {
            result.set(runChild("stall", beforeAwait = { childReady.countDown() }))
        }
        check(childReady.await(5, TimeUnit.SECONDS)) { "interruption child was not established" }
        owner.interrupt()
        owner.join(5_000L)
        check(!owner.isAlive) { "interrupted runner did not return" }
        check(owner.isInterrupted) { "interrupted runner did not restore interruption" }
        val childResult = requireNotNull(result.get()) { "interrupted runner produced no result" }
        check(childResult.failure is InterruptedException) { "original interruption was not preserved" }
        check(!childResult.processAlive) { "interrupted child process survived cleanup" }
        check(!childResult.stdoutDrainAlive && !childResult.stderrDrainAlive) {
            "interrupted child stream drain survived cleanup"
        }
    }
})

private data class ChildResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val failure: Throwable?,
    val processAlive: Boolean,
    val stdoutDrainAlive: Boolean,
    val stderrDrainAlive: Boolean,
)

private fun validateLane(lane: String, result: ChildResult): List<String> {
    val lines = result.stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
    val rows = lines.mapNotNull(RemoteAddressNoDnsProtocol::parseRow)
    val malformed = lines.filter {
        it.startsWith(RemoteAddressNoDnsProtocol.ROW_PREFIX) && RemoteAddressNoDnsProtocol.parseRow(it) == null
    }
    val completions = lines.mapNotNull(RemoteAddressNoDnsProtocol::parseCompletion)
    val expected = RemoteAddressNoDnsCases.cases(lane)
    val failures = buildList {
        if (result.exitCode != 0) add("exit=${result.exitCode}")
        result.failure?.let { add("runner failure=${it.message ?: it::class.simpleName}") }
        if (result.processAlive) add("owned process is still alive")
        if (result.stdoutDrainAlive || result.stderrDrainAlive) add("owned stream drain is still alive")
        if (malformed.isNotEmpty()) add("malformed rows=$malformed")
        if (completions != listOf(lane to expected.size)) add("completion=$completions")
        if (rows.map { it.id } != expected.map { it.id }) add("row IDs=${rows.map { it.id }}")
        rows.zip(expected).forEach { (actual, required) ->
            if (actual.verdict != required.expected) add("${actual.id}: expected=${required.expected}, actual=${actual.verdict}")
            if (actual.attempts != 0) add("${actual.id}: resolver-attempts=${actual.attempts}")
        }
    }
    if (failures.isEmpty()) return emptyList()
    return listOf(
        "lane $lane failed: ${failures.joinToString("; ")}\n" +
            "stdout:\n${result.stdout}\n" +
            "stderr:\n${result.stderr}",
    )
}

private fun runChild(
    lane: String,
    deadlineMillis: Long = 30_000L,
    beforeAwait: ((BoundedOutputStream) -> Unit)? = null,
): ChildResult {
    val root = Files.createTempDirectory("plainbase-no-dns-child")
    var process: Process? = null
    var stdoutDrain: Thread? = null
    var stderrDrain: Thread? = null
    val stdout = BoundedOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
    val stderr = BoundedOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
    val drainFailure = AtomicReference<Throwable?>()
    var primary: Throwable? = null
    var restoreInterrupt = false
    var exitCode: Int? = null
    try {
        val classpath = requireNotNull(System.getProperty(CHILD_RUNTIME_CLASSPATH_PROPERTY)) {
            "$CHILD_RUNTIME_CLASSPATH_PROPERTY is required; launch ./gradlew :server:test"
        }.also { require(it.isNotBlank()) { "$CHILD_RUNTIME_CLASSPATH_PROPERTY must be nonblank" } }
        val descriptor = root.resolve("META-INF/services/java.net.spi.InetAddressResolverProvider")
        Files.createDirectories(descriptor.parent)
        Files.writeString(descriptor, "${RemoteAddressResolverProvider::class.qualifiedName}\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val childClasspath = root.toString() + File.pathSeparator + classpath
        val command = listOf(java.toString(), "-cp", childClasspath, LAUNCHER_CLASS, lane)
        val builder = ProcessBuilder(command).directory(root.toFile())
        builder.environment().clear()
        builder.environment()["LANG"] = "C.UTF-8"
        builder.environment()["LC_ALL"] = "C.UTF-8"
        builder.environment()["PLAINBASE_LOG_LEVEL"] = "ERROR"
        val started = builder.start()
        process = started
        started.outputStream.close()
        stdoutDrain = drain(started.inputStream, stdout, "$lane-stdout", drainFailure)
        stderrDrain = drain(started.errorStream, stderr, "$lane-stderr", drainFailure)

        beforeAwait?.invoke(stdout)
        awaitExit(started, deadlineMillis)
        exitCode = started.exitValue()
        val drainResult = finishDrains(started, stdoutDrain, stderrDrain)
        restoreInterrupt = restoreInterrupt || drainResult.interrupted
        drainResult.failure?.let { throw it }
        drainFailure.get()?.let { throw IllegalStateException("child output drain failed", it) }
    } catch (failure: Throwable) {
        primary = failure
        restoreInterrupt = restoreInterrupt || failure is InterruptedException
    } finally {
        val cleanupResult = cleanup(process, stdoutDrain, stderrDrain, stdout, stderr, drainFailure)
        restoreInterrupt = restoreInterrupt || cleanupResult.interrupted
        cleanupResult.failure?.let { cleanupFailure ->
            val current = primary
            if (current == null) primary = cleanupFailure else current.addSuppressed(cleanupFailure)
        }
    }
    val liveProcess = process?.isAlive == true
    val finishedProcess = process
    if (exitCode == null && finishedProcess != null && !liveProcess) {
        exitCode = runCatching { finishedProcess.exitValue() }.getOrNull()
    }
    val result = ChildResult(
        stdout = stdout.text(),
        stderr = stderr.text(),
        exitCode = exitCode,
        failure = primary,
        processAlive = liveProcess,
        stdoutDrainAlive = stdoutDrain?.isAlive == true,
        stderrDrainAlive = stderrDrain?.isAlive == true,
    )
    try {
        root.toFile().deleteRecursively()
    } finally {
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
    return result
}

private fun awaitExit(process: Process, deadlineMillis: Long) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
    while (process.isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw TimeoutException("child exceeded ${deadlineMillis}ms")
        process.waitFor(
            minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L), 100L),
            TimeUnit.MILLISECONDS,
        )
    }
}

private data class CleanupResult(val failure: Throwable?, val interrupted: Boolean)

private data class WaitResult(val exited: Boolean, val interrupted: Boolean)

private data class JoinResult(val interrupted: Boolean)

private fun finishDrains(process: Process, stdout: Thread?, stderr: Thread?): CleanupResult {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STREAM_DEADLINE_MILLIS)
    var interrupted = false
    var failure: Throwable? = null
    listOfNotNull(stdout, stderr).forEach { interrupted = joinUntil(it, deadline).interrupted || interrupted }
    if (stdout?.isAlive == true || stderr?.isAlive == true) {
        try {
            process.inputStream.close()
        } catch (closeFailure: Throwable) {
            failure = retain(failure, closeFailure)
        }
        try {
            process.errorStream.close()
        } catch (closeFailure: Throwable) {
            failure = retain(failure, closeFailure)
        }
        val postCloseDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STREAM_DEADLINE_MILLIS)
        listOfNotNull(stdout, stderr).forEach {
            interrupted = joinUntil(it, postCloseDeadline).interrupted || interrupted
        }
    }
    if (stdout?.isAlive == true || stderr?.isAlive == true) {
        failure = retain(failure, IllegalStateException("child stream drain exceeded its deadline"))
    }
    return CleanupResult(failure, interrupted)
}

private fun cleanup(
    process: Process?,
    stdout: Thread?,
    stderr: Thread?,
    stdoutOutput: BoundedOutputStream,
    stderrOutput: BoundedOutputStream,
    drainFailure: AtomicReference<Throwable?>,
): CleanupResult {
    if (process == null) return CleanupResult(null, false)
    var failure: Throwable? = null
    var interrupted = false
    try {
        if (process.isAlive) {
            process.destroy()
            val graceful = waitForReap(process, CLEANUP_GRACE_MILLIS)
            interrupted = interrupted || graceful.interrupted
            if (!graceful.exited && process.isAlive) {
                process.destroyForcibly()
                val forced = waitForReap(process, CLEANUP_REAP_MILLIS)
                interrupted = interrupted || forced.interrupted
                if (!forced.exited && process.isAlive) {
                    error("child survived forced cleanup")
                }
            }
        }
    } catch (cleanupFailure: Throwable) {
        failure = cleanupFailure
    }
    if (stdout?.isAlive == true || stderr?.isAlive == true) {
        runCatching { process.inputStream.close() }.exceptionOrNull()?.let { failure = retain(failure, it) }
        runCatching { process.errorStream.close() }.exceptionOrNull()?.let { failure = retain(failure, it) }
    }
    val drains = finishDrains(process, stdout, stderr)
    interrupted = interrupted || drains.interrupted
    if (stdoutOutput.truncated || stderrOutput.truncated) {
        failure = retain(failure, IllegalStateException(OUTPUT_OVERFLOW_MESSAGE))
    }
    drains.failure?.let { failure = retain(failure, it) }
    drainFailure.get()?.let { failure = retain(failure, IllegalStateException("child output drain failed", it)) }
    if (process.isAlive) failure = retain(failure, IllegalStateException("child process survived cleanup"))
    return CleanupResult(failure, interrupted)
}

private fun waitForReap(process: Process, timeoutMillis: Long): WaitResult {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    var interrupted = false
    while (process.isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return WaitResult(false, interrupted)
        try {
            process.waitFor(
                minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L), 100L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    return WaitResult(true, interrupted)
}

private fun joinUntil(thread: Thread, deadline: Long): JoinResult {
    var interrupted = false
    while (thread.isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return JoinResult(interrupted)
        try {
            thread.join(minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L), 100L))
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    return JoinResult(interrupted)
}

private fun retain(first: Throwable?, second: Throwable): Throwable = first?.also { it.addSuppressed(second) } ?: second

private fun drain(
    input: InputStream,
    output: BoundedOutputStream,
    name: String,
    failure: AtomicReference<Throwable?>,
): Thread = thread(start = true, isDaemon = true, name = name) {
    try {
        input.use { it.copyTo(output) }
    } catch (drainFailure: Throwable) {
        failure.compareAndSet(null, drainFailure)
    }
}

private class BoundedOutputStream(private val limit: Int) : OutputStream() {
    private val delegate = ByteArrayOutputStream(limit)
    private val overflow = CountDownLatch(1)
    var truncated: Boolean = false
        private set(value) {
            field = value
            if (value) overflow.countDown()
        }

    fun awaitOverflow(): Boolean = overflow.await(10, TimeUnit.SECONDS)

    override fun write(value: Int) {
        if (delegate.size() < limit) delegate.write(value) else truncated = true
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "invalid output slice" }
        val remaining = limit - delegate.size()
        if (remaining > 0) delegate.write(bytes, offset, minOf(length, remaining))
        if (length > remaining) truncated = true
    }

    fun text(): String = delegate.toByteArray().toString(StandardCharsets.UTF_8)
}
