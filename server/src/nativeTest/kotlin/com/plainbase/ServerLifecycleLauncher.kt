package com.plainbase

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.frameworks.cli.systemCommandOutput
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.ktor.HttpCallAdmission
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.lifecycle.ServerRunControl
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Small child-process entry for the runtime-hook tests; production environment loading is intentionally bypassed. */
fun main(args: Array<String>) {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    if (args.firstOrNull() == "--probe-lock") {
        runLockProbe(args)
        return
    }

    val arguments = Arguments(args)
    val output = systemCommandOutput()
    val observation = AtomicReference<HttpCallAdmission.CallJobObservation?>()
    val hook = AtomicReference<Thread?>()
    val helpers = CopyOnWriteArrayList<Thread>()
    val mainThread = Thread.currentThread()
    val factsCaptured = AtomicBoolean(false)
    val helperFailure = AtomicReference<Throwable?>()
    val helperDrainStarted = AtomicBoolean(false)
    val helperDrainFinished = CountDownLatch(1)
    val helperDrainInterrupted = AtomicBoolean(false)

    fun rememberHelperFailure(failure: Throwable) {
        helperFailure.updateAndGet { current ->
            when {
                current == null -> failure
                current !== failure && current.suppressed.none { it === failure } -> current.also { it.addSuppressed(failure) }
                else -> current
            }
        }
    }

    fun drainHelpers(): Throwable? {
        var failure: Throwable? = null
        if (helperDrainStarted.compareAndSet(false, true)) {
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                helpers.forEach { helper ->
                    joinHelper(helper, deadline) {
                        helperDrainInterrupted.set(true)
                        Thread.interrupted()
                    }
                    if (helper.isAlive) {
                        runCatching { helper.interrupt() }
                            .exceptionOrNull()?.let { failure = retainFailure(failure, it) }
                        joinHelper(helper, deadline) {
                            helperDrainInterrupted.set(true)
                            Thread.interrupted()
                        }
                    }
                    if (helper.isAlive) {
                        failure = retainFailure(
                            failure,
                            IllegalStateException("checkpoint05 fixture helper survived shutdown: ${helper.name}"),
                        )
                    }
                }
                helperFailure.get()?.let { failure = retainFailure(failure, it) }
                val survivors = helpers.count { it.isAlive }
                arguments.helperCompleted?.let {
                    runCatching {
                        writeMarker(it, "completed=${survivors == 0}\nhelpers=${helpers.size}\nsurvivors=$survivors\n")
                    }.exceptionOrNull()?.let { markerFailure -> failure = retainFailure(failure, markerFailure) }
                }
                arguments.helperFailure?.let {
                    runCatching {
                        writeMarker(it, "failure=${failure?.let { error -> error::class.java.name + ":" + error.message } ?: "none"}\n")
                    }.exceptionOrNull()?.let { markerFailure -> failure = retainFailure(failure, markerFailure) }
                }
            } finally {
                helperDrainFinished.countDown()
            }
        } else {
            try {
                if (!helperDrainFinished.await(2, TimeUnit.SECONDS)) {
                    failure = retainFailure(failure, IllegalStateException("fixture helper drain did not complete"))
                }
            } catch (_: InterruptedException) {
                helperDrainInterrupted.set(true)
                Thread.interrupted()
                failure = retainFailure(failure, InterruptedException("fixture helper drain was interrupted"))
            }
            helperFailure.get()?.let { failure = retainFailure(failure, it) }
        }
        return failure
    }

    fun closeFacts(): Throwable? {
        val factsPath = arguments.closeFacts ?: return null
        if (!factsCaptured.compareAndSet(false, true)) return null
        return runCatching {
            val call = requireNotNull(observation.get()) { "held PUT admission was not observed before dependent close" }
            val durable = arguments.expected?.let { expected ->
                Files.mismatch(arguments.page, expected) == -1L
            } ?: false
            writeMarker(
                factsPath,
                buildString {
                    appendLine("attribute_installed=${call.attributeInstalled}")
                    appendLine("attribute_identity_same=${call.attributeJob === call.originalJob}")
                    appendLine("original_job_complete_before_close=${call.originalJob.isCompleted}")
                    appendLine("durable_bytes_before_close=$durable")
                },
            )
        }.exceptionOrNull()?.also { failure ->
            runCatching { writeMarker(factsPath, "observation_failure=${failure::class.java.name}:${failure.message}\n") }
        }
    }

    fun closeReceipt(kind: String, close: () -> Unit) {
        var failure: Throwable? = null
        var lockHeld: Boolean? = null
        var observationFailure: Throwable? = null
        runCatching { writeMarker(arguments.report, "close-entry=$kind\n", append = true) }
            .exceptionOrNull()?.let { failure = retainFailure(failure, it) }
        runCatching {
            val acquired = DataDirLock.tryAcquire(arguments.data)
            if (acquired == null) {
                lockHeld = true
            } else {
                acquired.close()
                lockHeld = false
            }
            closeFacts()?.let { throw it }
        }.exceptionOrNull()?.let {
            observationFailure = it
            failure = retainFailure(failure, it)
        }
        val entryObservation = lockHeld?.let { "lockHeld=$it" }
            ?: "observation_failure=${observationFailure?.let { it::class.java.name + ":" + it.message } ?: "unknown"}"
        runCatching { writeMarker(arguments.report, "close-entry=$kind $entryObservation\n", append = true) }
            .exceptionOrNull()?.let { failure = retainFailure(failure, it) }
        runCatching { close() }.exceptionOrNull()?.let { failure = retainFailure(failure, it) }
        val closeObservation = lockHeld?.let { "lockHeld=$it" }
            ?: "observation_failure=${observationFailure?.let { it::class.java.name + ":" + it.message } ?: "unknown"}"
        runCatching { writeMarker(arguments.report, "close=$kind $closeObservation\n", append = true) }
            .exceptionOrNull()?.let { failure = retainFailure(failure, it) }
        if (failure != null) throw failure
    }

    val control = ServerRunControl(
        startServer = { server ->
            if (arguments.naturalReturn) {
                server.start(wait = false)
                waitForFile(arguments.trigger)
            } else {
                server.start(wait = true)
            }
        },
        onHookInstalled = { installed ->
            hook.set(installed)
            arguments.hook?.let { writeMarker(it, "thread=${installed.name}\n") }
        },
        createHttpServer = { config, context ->
            val heldContext = arguments.saveRelease?.let {
                val delegate = context.mutate
                val held = object : MutatingFacade by delegate {
                    override fun save(principal: Principal, request: SaveRequest): SaveResult {
                        writeMarker(requireNotNull(arguments.saveEntered), "entered=true\n")
                        waitForFile(it)
                        return try {
                            delegate.save(principal, request)
                        } finally {
                            writeMarker(requireNotNull(arguments.saveReturned), "returned=true\n")
                        }
                    }
                }
                context.withMutating(held)
            } ?: context
            arguments.token?.let { tokenPath ->
                val token = context.tokens.mint("checkpoint05", AgentMode.COMMIT).plaintext
                writeMarker(tokenPath, token)
            }
            KtorServer(config, heldContext)
        },
        initialRebuild = { builder ->
            val started = System.nanoTime()
            val snapshot = builder.rebuild()
            arguments.initialRebuild?.let {
                writeMarker(
                    it,
                    "duration_ms=${java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}\n" +
                        "page_count=${snapshot.pages.size}\n",
                )
            }
        },
        onHttpAcquired = { server ->
            arguments.armAdmission?.let { armPath ->
                val arm = thread(start = false, isDaemon = false, name = "plainbase-checkpoint05-arm") {
                    try {
                        waitForFile(armPath)
                        server.captureNextAdmissionForTest { call ->
                            observation.set(call)
                            writeMarker(
                                requireNotNull(arguments.admissionObservation),
                                buildString {
                                    appendLine("attribute_installed=${call.attributeInstalled}")
                                    appendLine("attribute_identity_same=${call.attributeJob === call.originalJob}")
                                    appendLine("original_job_complete_at_capture=${call.originalJob.isCompleted}")
                                },
                            )
                            call.originalJob.invokeOnCompletion {
                                writeMarker(requireNotNull(arguments.callCompleted), "completed=true\n")
                            }
                        }
                        writeMarker(requireNotNull(arguments.admissionArmed), "armed=true\n")
                    } catch (_: InterruptedException) {
                        Thread.interrupted()
                    } catch (failure: Throwable) {
                        rememberHelperFailure(failure)
                    }
                }
                helpers += arm
                arm.start()
            }
            if (arguments.shutdownEntered != null && arguments.saveRelease != null) {
                val shutdownPath = requireNotNull(arguments.shutdownEntered)
                val interrupts = thread(start = false, isDaemon = false, name = "plainbase-checkpoint05-waiter-interruptor") {
                    try {
                        waitForFile(shutdownPath)
                        var count = 0
                        while (!Files.exists(requireNotNull(arguments.saveRelease))) {
                            val target = if (arguments.naturalReturn) mainThread else hook.get()
                            if (target != null && target !== Thread.currentThread() && target.isAlive) {
                                target.interrupt()
                                count++
                                arguments.waiterInterruptions?.let { writeMarker(it, "count=$count\n") }
                            }
                            Thread.sleep(25)
                        }
                        arguments.waiterInterruptions?.let { writeMarker(it, "count=$count\n") }
                    } catch (_: InterruptedException) {
                        Thread.interrupted()
                    } catch (failure: Throwable) {
                        rememberHelperFailure(failure)
                    }
                }
                helpers += interrupts
                interrupts.start()
            }
        },
        onWatcherAcquired = { root, _ ->
            arguments.watcherObservation?.let { writeMarker(it, "root=$root\n", append = true) }
        },
        closeHttp = { server ->
            var closeFailure: Throwable? = null
            runCatching { arguments.shutdownEntered?.let { writeMarker(it, "entered=true\n") } }
                .exceptionOrNull()?.let { closeFailure = retainFailure(closeFailure, it) }
            runCatching { server.stop() }
                .exceptionOrNull()?.let { closeFailure = retainFailure(closeFailure, it) }
            drainHelpers()?.let { closeFailure = retainFailure(closeFailure, it) }
            if (closeFailure != null) throw closeFailure
        },
        closeDriver = { driver -> closeReceipt("app", driver::close) },
        closeSearch = { search -> closeReceipt("search", search::close) },
        closeContext = { context -> closeReceipt("context", context::close) },
    )
    val config = PlainbaseConfig(
        contentDir = arguments.content,
        dataDir = arguments.data,
        host = "127.0.0.1",
        port = arguments.port,
        auth = if (arguments.token == null) {
            AuthConfig()
        } else {
            AuthConfig(
                mode = AuthMode.BUILTIN,
                agentDirectCommitGlobs = listOf("docs/**"),
            )
        },
        git = GitConfig(enabled = false),
    )
    var status: Int? = null
    var primaryFailure: Throwable? = null
    try {
        try {
            status = runServer(config, output, control = control)
            check(status == 0) { "server returned a refusal" }
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
    } finally {
        var restoreInterrupt = Thread.interrupted() || primaryFailure is InterruptedException
        try {
            try {
                var cleanupFailure: Throwable? = null
                runCatching {
                    arguments.runFinished?.let {
                        writeMarker(
                            it,
                            "status=${status ?: -1}\n" +
                                "natural_waiter_interrupted=$restoreInterrupt\n",
                        )
                    }
                }.exceptionOrNull()?.let { cleanupFailure = retainFailure(cleanupFailure, it) }
                drainHelpers()?.let { cleanupFailure = retainFailure(cleanupFailure, it) }
                cleanupFailure?.let { failure ->
                    primaryFailure = primaryFailure?.also { retainFailure(it, failure) } ?: failure
                }
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure is InterruptedException) {
                    restoreInterrupt = true
                    Thread.interrupted()
                }
                primaryFailure = primaryFailure?.also { retainFailure(it, cleanupFailure) } ?: cleanupFailure
            }
            if (helperDrainInterrupted.get()) restoreInterrupt = true
            primaryFailure?.let { throw it }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }
}

private fun joinHelper(helper: Thread, deadline: Long, onInterrupt: () -> Unit) {
    while (helper.isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) return
        try {
            helper.join(minOf(100L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)))
        } catch (_: InterruptedException) {
            Thread.interrupted()
            onInterrupt()
        }
    }
}

private fun retainFailure(current: Throwable?, failure: Throwable): Throwable {
    if (current == null) return failure
    if (current !== failure && current.suppressed.none { it === failure }) current.addSuppressed(failure)
    return current
}

private fun runLockProbe(args: Array<String>) {
    require(args.size == 3 && args[1] == "--data") { "probe usage: --probe-lock --data <DATA_DIR>" }
    val data = Path.of(args[2]).toAbsolutePath().normalize()
    val lock = DataDirLock.tryAcquire(data)
    if (lock == null) {
        println("HELD")
    } else {
        lock.close()
        println("AVAILABLE")
    }
}

private class Arguments(args: Array<String>) {
    private val values = args.toList().chunked(2).associate { pair ->
        require(pair.size == 2 && pair[0].startsWith("--")) { "expected --name value arguments" }
        pair[0] to pair[1]
    }

    val content: Path get() = Path.of(required("--content"))
    val data: Path get() = Path.of(required("--data"))
    val report: Path get() = Path.of(required("--report"))
    val port: Int get() = required("--port").toInt()
    val naturalReturn: Boolean get() = values["--mode"] == "natural"
    val trigger: Path get() = Path.of(required("--trigger"))
    val token: Path? get() = optional("--token")
    val saveEntered: Path? get() = optional("--save-entered")
    val saveRelease: Path? get() = optional("--save-release")
    val saveReturned: Path? get() = optional("--save-returned")
    val armAdmission: Path? get() = optional("--arm-admission")
    val admissionArmed: Path? get() = optional("--admission-armed")
    val admissionObservation: Path? get() = optional("--admission-observation")
    val callCompleted: Path? get() = optional("--call-completed")
    val shutdownEntered: Path? get() = optional("--shutdown-entered")
    val closeFacts: Path? get() = optional("--close-facts")
    val expected: Path? get() = optional("--expected")
    val page: Path get() = Path.of(required("--page"))
    val hook: Path? get() = optional("--hook")
    val waiterInterruptions: Path? get() = optional("--waiter-interruptions")
    val runFinished: Path? get() = optional("--run-finished")
    val helperCompleted: Path? get() = optional("--helper-completed")
    val helperFailure: Path? get() = optional("--helper-failure")
    val initialRebuild: Path? get() = optional("--initial-rebuild")
    val watcherObservation: Path? get() = optional("--watcher-observation")

    init {
        Files.createDirectories(requireNotNull(report.parent))
        require(Files.isDirectory(content)) { "content fixture is not a directory: $content" }
        require(Files.isDirectory(data)) { "data fixture is not a directory: $data" }
        if (naturalReturn) require(values.containsKey("--trigger")) { "natural mode requires --trigger" }
    }

    private fun required(name: String): String = requireNotNull(values[name]) { "missing $name" }

    private fun optional(name: String): Path? = values[name]?.let(Path::of)
}

private fun RouteContext.withMutating(mutate: MutatingFacade): RouteContext =
    RouteContext(
        read = read,
        mutate = mutate,
        proposals = proposals,
        registry = registry,
        availability = availability,
        convergence = convergence,
        limbo = limbo,
        tokens = tokens,
        auth = auth,
        trustedProxyCidrs = trustedProxyCidrs,
        idProvider = idProvider,
        maxWriteBodyBytes = maxWriteBodyBytes,
        maxAssetBytes = maxAssetBytes,
        mcpAllowedHosts = mcpAllowedHosts,
        mcpAllowedOrigins = mcpAllowedOrigins,
        builtinAuthEnabled = builtinAuthEnabled,
        proxyAuthEnabled = proxyAuthEnabled,
        proxySecret = proxySecret,
        proxyIdentityHeader = proxyIdentityHeader,
        secureCookie = secureCookie,
        proxyCsrf = proxyCsrf,
        extract = extract,
    )

private fun waitForFile(path: Path) {
    while (!Files.exists(path)) Thread.sleep(25L)
}

private fun writeMarker(path: Path, text: String, append: Boolean = false) {
    Files.createDirectories(requireNotNull(path.parent))
    if (append) {
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return
    }
    val temporary = path.resolveSibling(".${path.fileName}.tmp")
    Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
