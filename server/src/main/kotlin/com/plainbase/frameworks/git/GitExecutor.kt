package com.plainbase.frameworks.git

import com.plainbase.frameworks.lifecycle.CompletionWait
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The bounded fields used by Linux's `/proc/<pid>/stat` completion observation. */
internal data class LinuxProcessStat(
    val pid: Long,
    val state: Char,
    val numThreads: Long,
    val startTicks: Long,
)

internal enum class GitHandleLiveness {
    LIVE,
    DEAD,
    UNKNOWN,
}

internal data class LinuxProcessObservationDecision(
    val complete: Boolean,
    val firstStartTicks: Long?,
)

private const val MAX_PROC_STAT_BYTES = 4096
private const val STAT_NUM_THREADS_INDEX = 17
private const val STAT_START_TICKS_INDEX = 19

/**
 * Parses a Linux stat record without splitting the command name. The command's final `)` is the delimiter because
 * Linux permits spaces and `)` in that field. Fields after it are one-based stat fields 3 onward.
 */
internal fun parseLinuxProcessStat(raw: String): LinuxProcessStat? {
    val open = raw.indexOf('(')
    val close = raw.lastIndexOf(')')
    if (open <= 0 || close <= open) return null
    val pidText = raw.substring(0, open).trim()
    if (pidText.isEmpty() || pidText.any { !it.isDigit() }) return null
    val pid = pidText.toLongOrNull() ?: return null
    if (pid <= 0L) return null

    val fields = raw.substring(close + 1).trim().split(Regex("\\s+"))
    if (fields.size < STAT_START_TICKS_INDEX + 1) return null
    val stateField = fields[0]
    if (stateField.length != 1) return null
    val numThreads = fields[STAT_NUM_THREADS_INDEX].toLongOrNull() ?: return null
    val startTicks = fields[STAT_START_TICKS_INDEX].toLongOrNull() ?: return null
    if (numThreads < 1L || startTicks < 0L) return null
    return LinuxProcessStat(pid, stateField[0], numThreads, startTicks)
}

/** Conservative decision used by the real observer and its synthetic pure decision cases. */
internal fun linuxStatProvesOriginalSingleThreadZombie(
    stat: LinuxProcessStat,
    expectedPid: Long,
    expectedStartTicks: Long,
): Boolean =
    stat.pid == expectedPid &&
        stat.startTicks == expectedStartTicks &&
        stat.state == 'Z' &&
        stat.numThreads == 1L

/** Modeled identity decision: an unavailable liveness observation never advances raw identity. */
internal fun decideLinuxProcessObservation(
    before: GitHandleLiveness,
    stat: LinuxProcessStat?,
    after: GitHandleLiveness,
    expectedPid: Long,
    firstStartTicks: Long?,
): LinuxProcessObservationDecision {
    if (before == GitHandleLiveness.DEAD || after == GitHandleLiveness.DEAD) {
        return LinuxProcessObservationDecision(complete = true, firstStartTicks = firstStartTicks)
    }
    if (before != GitHandleLiveness.LIVE || after != GitHandleLiveness.LIVE) {
        return LinuxProcessObservationDecision(complete = false, firstStartTicks = firstStartTicks)
    }
    if (stat == null || stat.pid != expectedPid) {
        return LinuxProcessObservationDecision(complete = false, firstStartTicks = firstStartTicks)
    }
    if (firstStartTicks != null && firstStartTicks != stat.startTicks) {
        return LinuxProcessObservationDecision(complete = false, firstStartTicks = firstStartTicks)
    }
    val observedStartTicks = firstStartTicks ?: stat.startTicks
    return LinuxProcessObservationDecision(
        complete = linuxStatProvesOriginalSingleThreadZombie(stat, expectedPid, observedStartTicks),
        firstStartTicks = observedStartTicks,
    )
}

/** A retained original handle plus its first valid Linux identity observation. */
internal class GitProcessObservation(
    val handle: ProcessHandle,
    val role: String,
) {
    var firstStartTicks: Long? = null
}

/**
 * Retains original handles by the runtime's process identity, not by numeric PID. No Linux stat read is needed to
 * retain an observed handle; supplementary identity evidence remains conservative in the completion observer.
 */
internal class GitProcessRetention {
    private val observations = linkedMapOf<ProcessHandle, GitProcessObservation>()

    fun retain(handle: ProcessHandle, role: String): GitProcessObservation? {
        val observation = GitProcessObservation(handle, role)
        return if (observations.putIfAbsent(handle, observation) == null) observation else null
    }

    fun snapshot(): List<GitProcessObservation> = observations.values.toList()

    fun allComplete(isComplete: (GitProcessObservation) -> Boolean): Boolean = snapshot().all(isComplete)
}

internal enum class GitAbnormalCause {
    TIMEOUT,
    INTERRUPTION,
    OUTPUT_OVERFLOW,
    HELPER_FAILURE,
}

/** Atomically preserves the first abnormal invocation cause; later cleanup fallout is secondary. */
internal class GitAbnormalCauseLatch {
    private data class FirstCause(val cause: GitAbnormalCause, val failure: Throwable?)

    private val first = AtomicReference<FirstCause?>(null)

    fun latch(cause: GitAbnormalCause, failure: Throwable? = null): Boolean =
        first.compareAndSet(null, FirstCause(cause, failure))

    fun cause(): GitAbnormalCause? = first.get()?.cause

    fun failure(): Throwable? = first.get()?.failure
}

/**
 * Narrow test seam for confirmation only. The production observer is always [RealGitInvocationCompletionObserver]; a
 * test may hold a modeled process/helper pending without replacing ProcessBuilder, kill, or the real Git path.
 */
internal interface GitInvocationCompletionObserver {
    fun processComplete(observation: GitProcessObservation): Boolean

    fun helperComplete(helper: Thread): Boolean
}

/** Internal observation tap for tests; it reports the real owner and completion decisions without replacing them. */
internal interface GitInvocationObservationListener {
    fun processRetained(observation: GitProcessObservation)

    fun processCompletionObserved(observation: GitProcessObservation, complete: Boolean)

    fun helperCompletionObserved(helper: Thread, complete: Boolean)
}

private object RealGitInvocationCompletionObserver : GitInvocationCompletionObserver {
    override fun processComplete(observation: GitProcessObservation): Boolean {
        val handle = observation.handle
        val before = handleLiveness(handle)
        if (before == GitHandleLiveness.DEAD) return true
        if (before != GitHandleLiveness.LIVE || System.getProperty("os.name") != "Linux") return false
        val expectedPid = runCatching { handle.pid() }.getOrNull() ?: return false
        val stat = readLinuxProcessStat(expectedPid)
        val decision = decideLinuxProcessObservation(
            before = before,
            stat = stat,
            after = handleLiveness(handle),
            expectedPid = expectedPid,
            firstStartTicks = observation.firstStartTicks,
        )
        if (observation.firstStartTicks == null) observation.firstStartTicks = decision.firstStartTicks
        return decision.complete
    }

    override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
}

private fun handleLiveness(handle: ProcessHandle): GitHandleLiveness =
    runCatching { if (handle.isAlive) GitHandleLiveness.LIVE else GitHandleLiveness.DEAD }
        .getOrDefault(GitHandleLiveness.UNKNOWN)

private fun readLinuxProcessStat(pid: Long): LinuxProcessStat? {
    if (pid <= 0L || System.getProperty("os.name") != "Linux") return null
    return runCatching {
        val path = Path.of("/proc", pid.toString(), "stat")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(MAX_PROC_STAT_BYTES)
            var total = 0
            var eof = false
            while (total < bytes.size && !eof) {
                val count = input.read(bytes, total, bytes.size - total)
                when {
                    count < 0 -> eof = true
                    count > 0 -> total += count
                }
            }
            if (total == bytes.size && input.read() >= 0) return@use null
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            parseLinuxProcessStat(decoder.decode(ByteBuffer.wrap(bytes, 0, total)).toString())
        }
    }.getOrNull()
}

/**
 * The single hermetic `git` chokepoint (ADR-0006): EVERY git invocation funnels through [run]. The process is pinned
 * reproducible and isolated — pinned `-c` config on every call, a cleared + nulled environment, hooks disabled, args
 * always a `List<String>` (no shell), one original deadline, and separate concurrent stdout/stderr drains.
 *
 * A successful start creates one invocation-local owner. It retains the direct process, every descendant observed while
 * its ancestry is available, and every helper, and it does not release the caller until all retained work is complete.
 * The original monotonic deadline bounds normal completion; timeout, interruption, overflow, or helper failure enters
 * required termination/confirmation and joins, which may outlive that command budget while the obligation is retained.
 */
class GitExecutor(
    private val workTree: Path,
    private val home: Path,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val gitBinary: String = "git",
    private val maxStdoutBytes: Long = DEFAULT_MAX_STDOUT_BYTES,
    private val maxStderrBytes: Long = DEFAULT_MAX_STDERR_BYTES,
) {
    private var completionObserver: GitInvocationCompletionObserver = RealGitInvocationCompletionObserver

    /** Narrow modeled-completion constructor; production callers use the public constructor above. */
    internal constructor(
        workTree: Path,
        home: Path,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        gitBinary: String = "git",
        maxStdoutBytes: Long = DEFAULT_MAX_STDOUT_BYTES,
        maxStderrBytes: Long = DEFAULT_MAX_STDERR_BYTES,
        completionObserver: GitInvocationCompletionObserver = RealGitInvocationCompletionObserver,
        helperFactory: (String, () -> Unit) -> Thread = { name, block ->
            Thread(block, name).apply { isDaemon = true }
        },
        observationListener: GitInvocationObservationListener? = null,
    ) : this(workTree, home, timeoutSeconds, gitBinary, maxStdoutBytes, maxStderrBytes) {
        this.completionObserver = completionObserver
        this.helperFactory = helperFactory
        this.observationListener = observationListener
    }

    private var helperFactory: (String, () -> Unit) -> Thread = { name, block ->
        Thread(block, name).apply { isDaemon = true }
    }
    private var observationListener: GitInvocationObservationListener? = null

    /** Runs `git [args]` with pinned config, isolated env, separate bounded output, and the invocation owner. */
    fun run(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
        timeoutSecondsOverride: Long? = null,
    ): GitResult =
        runInternal(
            args,
            env,
            stdin,
            includeWorkTree = true,
            timeoutSeconds = timeoutSecondsOverride ?: this.timeoutSeconds,
            operation = "run",
        )

    /** Probes `git --version` without `-C`; it uses the same invocation owner as [run]. */
    fun versionProbe(): GitResult =
        runInternal(
            listOf("--version"),
            emptyMap(),
            null,
            includeWorkTree = false,
            timeoutSeconds = this.timeoutSeconds,
            operation = "versionProbe",
        )

    private fun runInternal(
        args: List<String>,
        env: Map<String, String>,
        stdin: ByteArray?,
        includeWorkTree: Boolean,
        timeoutSeconds: Long,
        operation: String,
    ): GitResult {
        val command = buildList {
            add(gitBinary)
            if (includeWorkTree) {
                add("-C")
                add(workTree.toString())
            }
            addAll(PINNED_CONFIG)
            addAll(args)
        }
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            clear()
            put("HOME", home.toString())
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_CONFIG_SYSTEM", "/dev/null")
            put("LC_ALL", "C")
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_ASKPASS", "true")
            put("GIT_OPTIONAL_LOCKS", "0")
            put("GIT_LITERAL_PATHSPECS", "1")
            putAll(env)
        }

        val process = try {
            builder.start()
        } catch (failure: IOException) {
            logger.warn(failure) { "git $operation could not be started (is git installed?)" }
            return GitResult(exitCode = -1, stdout = ByteArray(0), stderr = failure.message ?: "git could not be started")
        }
        val startedAtNanos = System.nanoTime()
        val deadlineNanos = saturatingAdd(startedAtNanos, TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceAtLeast(0L)))
        val causes = GitAbnormalCauseLatch()
        return CompletionWait.run(
            onInterrupt = { causes.latch(GitAbnormalCause.INTERRUPTION) },
        ) {
            InvocationOwner(process, startedAtNanos, deadlineNanos, operation, causes).run(this, stdin)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inner class InvocationOwner(
        private val process: Process,
        private val startedAtNanos: Long,
        private val deadlineNanos: Long,
        private val operation: String,
        private val causes: GitAbnormalCauseLatch,
    ) {
        private val observations = GitProcessRetention()
        private val helpers = mutableListOf<Thread>()
        private val parentReaped = AtomicBoolean(false)
        private val pendingLogged = AtomicBoolean(false)

        init {
            retainProcess(process.toHandle(), "parent")
        }

        fun run(wait: CompletionWait, stdin: ByteArray?): GitResult {
            try {
                startHelpers(stdin)
            } catch (failure: Throwable) {
                helperFailed(failure)
            }

            wait.captureCurrentInterrupt()
            observeAndTerminateIfNeeded()
            if (causes.cause() == null) {
                val completedWithinDeadline = runCatching { awaitUntilDeadline(wait) }
                    .onFailure(::helperFailed)
                    .getOrDefault(false)
                if (!completedWithinDeadline && causes.cause() == null) {
                    abnormal(GitAbnormalCause.TIMEOUT)
                }
            }

            finishAbnormalWork(wait)
            wait.captureCurrentInterrupt()
            return result()
        }

        private fun finishAbnormalWork(wait: CompletionWait) {
            wait.captureCurrentInterrupt()
            if (causes.cause() == null) return
            observeAndTerminateIfNeeded()
            awaitAfterEscalation(wait)
        }

        private fun startHelpers(stdin: ByteArray?) {
            startHelper("git-stdout-drain") { drainCapped({ process.inputStream }, stdoutBuffer, maxStdoutBytes) }
            startHelper("git-stderr-drain") { drainCapped({ process.errorStream }, stderrBuffer, maxStderrBytes) }
            if (stdin == null) {
                try {
                    process.outputStream.close()
                } catch (failure: Throwable) {
                    helperFailed(failure)
                }
            } else {
                startHelper("git-stdin-writer") {
                    try {
                        process.outputStream.use { it.write(stdin) }
                    } catch (failure: Throwable) {
                        if (isExpectedCleanupIo(failure)) {
                            logger.debug(failure) { "git $operation stdin helper ended during expected cleanup" }
                        } else {
                            helperFailed(failure)
                        }
                    }
                }
            }
        }

        private fun startHelper(name: String, block: () -> Unit) {
            val helper = try {
                helperFactory(name) {
                    try {
                        block()
                    } catch (failure: Throwable) {
                        reportHelperFailure(failure)
                    }
                }
            } catch (failure: Throwable) {
                helperFailed(failure)
                return
            }
            helpers += helper
            try {
                helper.start()
            } catch (failure: Throwable) {
                helperFailed(failure)
            }
        }

        private fun drainCapped(stream: () -> InputStream, buffer: ByteArrayOutputStream, cap: Long) {
            try {
                stream().use { input ->
                    val chunk = ByteArray(DRAIN_CHUNK_BYTES)
                    var total = 0L
                    var draining = true
                    while (draining) {
                        val count = input.read(chunk)
                        when {
                            count < 0 -> draining = false
                            total + count > cap -> {
                                abnormal(GitAbnormalCause.OUTPUT_OVERFLOW)
                                draining = false
                            }

                            count > 0 -> {
                                total += count
                                buffer.write(chunk, 0, count)
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (isExpectedCleanupIo(failure)) {
                    logger.debug(failure) { "git $operation output helper ended during expected cleanup" }
                } else {
                    helperFailed(failure)
                }
            }
        }

        private fun awaitUntilDeadline(wait: CompletionWait): Boolean =
            wait.awaitUntil(
                deadlineNanos = deadlineNanos,
                await = ::awaitOneNanosecondSlice,
                completed = {
                    wait.captureCurrentInterrupt()
                    if (causes.cause() != null) {
                        true
                    } else if (deadlineExpired()) {
                        false
                    } else {
                        val complete = invocationComplete()
                        wait.captureCurrentInterrupt()
                        causes.cause() != null || (!deadlineExpired() && complete)
                    }
                },
                onTick = {
                    wait.captureCurrentInterrupt()
                    if (causes.cause() == null && deadlineExpired()) abnormal(GitAbnormalCause.TIMEOUT)
                    observeAndTerminateIfNeeded()
                },
                waitSliceMillis = PROCESS_OBSERVATION_CADENCE_MILLIS,
            )

        private fun awaitAfterEscalation(wait: CompletionWait) {
            wait.awaitForever(
                await = ::awaitOneMillisecondSlice,
                completed = ::invocationComplete,
                onTick = {
                    wait.captureCurrentInterrupt()
                    observeAndTerminateIfNeeded()
                },
            )
        }

        private fun awaitOneNanosecondSlice(remainingNanos: Long) {
            val slice = minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(PROCESS_OBSERVATION_CADENCE_MILLIS))
            if (!parentReaped.get()) {
                if (process.waitFor(slice, TimeUnit.NANOSECONDS)) {
                    parentReaped.set(true)
                }
                return
            }
            val helper = pendingHelper()
            if (helper != null) {
                joinNanos(helper, slice)
            } else {
                sleepNanos(slice)
            }
        }

        private fun awaitOneMillisecondSlice(millis: Long) {
            if (!parentReaped.get()) {
                if (process.waitFor(millis, TimeUnit.MILLISECONDS)) parentReaped.set(true)
                return
            }
            val helper = pendingHelper()
            if (helper != null) {
                runCatching { helper.join(millis) }
                    .onFailure { failure ->
                        if (failure is InterruptedException) throw failure
                        helperFailed(failure)
                    }
            } else {
                Thread.sleep(millis)
            }
        }

        private fun pendingHelper(): Thread? =
            helperSnapshot().firstOrNull { helper ->
                !helperComplete(helper)
            }

        private fun helperComplete(helper: Thread): Boolean {
            val complete = runCatching { completionObserver.helperComplete(helper) }
                .getOrElse { failure ->
                    helperFailed(failure)
                    false
                }
            observationListener?.helperCompletionObserved(helper, complete)
            return complete
        }

        private fun joinNanos(helper: Thread, nanos: Long) {
            val millis = TimeUnit.NANOSECONDS.toMillis(nanos)
            val nanosRemainder = (nanos - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
            runCatching { helper.join(millis, nanosRemainder) }
                .onFailure { failure ->
                    if (failure is InterruptedException) throw failure
                    helperFailed(failure)
                }
        }

        private fun sleepNanos(nanos: Long) {
            val millis = TimeUnit.NANOSECONDS.toMillis(nanos)
            val nanosRemainder = (nanos - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
            Thread.sleep(millis, nanosRemainder)
        }

        private fun invocationComplete(): Boolean {
            if (!parentReaped.get()) return false
            val processesComplete = observations.allComplete { observation ->
                if (observation.role == "parent") true else processComplete(observation)
            }
            return processesComplete && helperSnapshot().all(::helperComplete)
        }

        private fun observeAndTerminateIfNeeded() {
            observeDescendants()
            if (causes.cause() != null) {
                terminateKnownProcesses()
                if (pendingLogged.compareAndSet(false, true)) logPendingWork()
            }
        }

        private fun observeDescendants() {
            val current = observationSnapshot()
            for (observation in current) {
                if (observation.role != "parent" || !parentReaped.get()) {
                    if (!processComplete(observation)) {
                        val descendants = runCatching { observation.handle.descendants().toList() }
                            .onFailure { failure -> logger.debug(failure) { "git $operation descendant observation was unavailable" } }
                            .getOrDefault(emptyList())
                        descendants.forEach { descendant -> retainProcess(descendant, "descendant") }
                    }
                }
            }
        }

        private fun processComplete(observation: GitProcessObservation): Boolean {
            val complete = runCatching { completionObserver.processComplete(observation) }
                .getOrElse { failure ->
                    helperFailed(failure)
                    false
                }
            observationListener?.processCompletionObserved(observation, complete)
            return complete
        }

        private fun retainProcess(handle: ProcessHandle, role: String) {
            observations.retain(handle, role)?.let { observation ->
                observationListener?.processRetained(observation)
            }
        }

        // Termination deliberately uses each retained original handle; no PID-only replacement can acquire authority.
        private fun terminateKnownProcesses() {
            val candidates = observations.snapshot().filter { observation ->
                observation.role != "parent" || !parentReaped.get()
            }.toList()
            for (observation in candidates) {
                val alive = runCatching { observation.handle.isAlive }.getOrDefault(false)
                if (!alive) continue
                runCatching { observation.handle.destroyForcibly() }
                    .onFailure { failure -> logger.warn(failure) { "git $operation process termination was unavailable" } }
            }
        }

        private fun abnormal(cause: GitAbnormalCause, failure: Throwable? = null) {
            causes.latch(cause, failure)
        }

        private fun helperFailed(failure: Throwable) {
            abnormal(GitAbnormalCause.HELPER_FAILURE, failure)
        }

        private fun reportHelperFailure(failure: Throwable) {
            if (isExpectedCleanupIo(failure)) {
                logger.debug(failure) { "git $operation helper ended during expected cleanup" }
            } else {
                helperFailed(failure)
            }
        }

        private fun isExpectedCleanupIo(failure: Throwable): Boolean =
            causes.cause() != null && failure is IOException

        private fun logPendingWork() {
            val pendingProcesses = observationSnapshot()
                .filter { observation -> observation.role != "parent" || !parentReaped.get() }
                .filterNot(::processComplete)
                .joinToString(",") { observation -> "${observation.role}#${observation.handle.pid()}" }
                .ifEmpty { "none" }
            val pendingHelpers = helperSnapshot()
                .filterNot(::helperComplete)
                .joinToString(",") { helper -> helper.name }
                .ifEmpty { "none" }
            logger.warn {
                "git $operation completion pending after ${elapsedMillis()}ms; " +
                    "processes=$pendingProcesses helpers=$pendingHelpers cause=${causes.cause()}"
            }
        }

        private fun observationSnapshot(): List<GitProcessObservation> = observations.snapshot()

        private fun helperSnapshot(): List<Thread> = helpers.toList()

        private fun elapsedMillis(): Long =
            TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))

        private fun deadlineExpired(): Boolean = System.nanoTime() >= deadlineNanos

        private fun result(): GitResult {
            val stdout = stdoutBuffer.toByteArray()
            val cause = causes.cause()
            if (cause != null) {
                val stderr = when (cause) {
                    GitAbnormalCause.TIMEOUT -> "git $operation timed out and was force-killed"
                    GitAbnormalCause.INTERRUPTION -> "git $operation interrupted and was force-killed"
                    GitAbnormalCause.OUTPUT_OVERFLOW ->
                        "git $operation output exceeded the in-memory read cap " +
                            "(${maxStdoutBytes / (1024 * 1024)} MiB stdout / ${maxStderrBytes / (1024 * 1024)} MiB stderr) " +
                            "and was force-killed — repo history/diff too large for an in-memory read"
                    GitAbnormalCause.HELPER_FAILURE -> "git $operation helper failed while completing the invocation"
                }
                causes.failure()?.let { failure -> logger.error(failure) { stderr } } ?: logger.error { stderr }
                return GitResult(exitCode = -1, stdout = stdout, stderr = stderr)
            }
            return GitResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderrBuffer.toString(Charsets.UTF_8),
            )
        }

        private val stdoutBuffer = ByteArrayOutputStream()
        private val stderrBuffer = ByteArrayOutputStream()
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val DEFAULT_MAX_STDOUT_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_STDERR_BYTES = 1L * 1024 * 1024

        /** Explicit retained-handle observation cadence; it is independent of CompletionWait's scheduler defaults. */
        const val PROCESS_OBSERVATION_CADENCE_MILLIS = 25L
        private const val DRAIN_CHUNK_BYTES = 64 * 1024

        // Security pins disable repository-controlled hooks/config and keep every argv entry outside a shell.
        // Foreground pins keep maintenance owned by this invocation; fsmonitor is disabled for complete observation.
        private val PINNED_CONFIG = listOf(
            "-c", "core.autocrlf=false",
            "-c", "core.eol=lf",
            "-c", "commit.gpgsign=false",
            "-c", "core.hooksPath=/dev/null",
            "-c", "init.defaultBranch=main",
            "-c", "core.quotePath=false",
            "-c", "core.precomposeUnicode=false",
            "-c", "log.showSignature=false",
            "-c", "protocol.ext.allow=never",
            "-c", "maintenance.autoDetach=false",
            "-c", "gc.autoDetach=false",
            "-c", "core.fsmonitor=",
        )

        private val SHA_LINE = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")

        fun parseSha(stdout: ByteArray): String? =
            stdout.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.firstOrNull { SHA_LINE.matches(it) }

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}

/** One git invocation's outcome. stdout and stderr remain distinct; a non-zero exit is a normal result. */
class GitResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val ok: Boolean get() = exitCode == 0

    val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
}
