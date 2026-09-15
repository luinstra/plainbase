package com.plainbase

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.frameworks.cli.CommandOutput
import com.plainbase.frameworks.cli.WriteIntent
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.ManagedRootsFile
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.ktor.HttpCallAdmission
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectStoreException
import com.plainbase.frameworks.runtime.LocalStoreInputs
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
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
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO

/** In-process proof of the serving runtime seam: gates, ownership, cleanup, and primary-failure preservation. */
class ServerRunTest : FunSpec({

    listOf(false, true).forEach { explicit ->
        val warningCase = "config warnings are emitted before a bind refusal and refusal cleanup closes the isolated context"
        test(warningCase + if (explicit) " with explicit roots" else "") {
            withLocalFixture { content, data ->
                val nestedContent = Files.createDirectory(data.resolve("content"))
                Files.writeString(nestedContent.resolve("readme.md"), "---\ntitle: Readme\n---\n\n# Readme\n")
                val zeta = data.resolve("zeta")
                val alpha = data.resolve("alpha")
                val rootsFile = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
                val backup = ManagedRootsFile.backupPath(rootsFile)
                Files.writeString(rootsFile, "roots {}")
                Files.copy(rootsFile, backup)
                val explicitSettings = if (explicit) {
                    """
                    roots {
                      zeta { path = "$zeta", editable = false }
                      docs { path = "$nestedContent", editable = false }
                      alpha { path = "$alpha", editable = false }
                    }
                    auth.agentDirectCommit.roots {
                      zeta = ["**"]
                      docs = ["**"]
                      alpha = ["**"]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
                Files.writeString(data.resolve("plainbase.conf"), "storage.object.bucket=docs\n$explicitSettings")
                val timeline = Collections.synchronizedList(mutableListOf<String>())
                val output = RecordingOutput(timeline)
                val applicationLogger = LoggerFactory.getLogger("com.plainbase.Application") as Logger
                val appender = LogTimelineAppender(timeline).apply { start() }
                applicationLogger.addAppender(appender)
                val contextCloses = AtomicInteger()
                val opens = AtomicInteger()
                val defaults = ServerOpeners()
                try {
                    val config = ConfigLoader.fromEnvAndFile(
                        mapOf(
                            "CONTENT_DIR" to (if (explicit) content else nestedContent).toString(),
                            "DATA_DIR" to data.toString(),
                            "PLAINBASE_HOST" to "0.0.0.0",
                            "PLAINBASE_GIT_ENABLED" to "false",
                        ),
                    )
                    config.roots.origin shouldBe if (explicit) RootsOrigin.EXPLICIT else RootsOrigin.SYNTHESIZED
                    val status = runServerBounded {
                        runServer(
                            config,
                            output,
                            openers = ServerOpeners(
                                openDriver = { path ->
                                    opens.incrementAndGet()
                                    defaults.openDriver(path)
                                },
                                openLocal = { inputs ->
                                    opens.incrementAndGet()
                                    defaults.openLocal(inputs)
                                },
                                openObject = { objectConfig, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                                    opens.incrementAndGet()
                                    defaults.openObject(objectConfig, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
                                },
                                openSearch = { path ->
                                    opens.incrementAndGet()
                                    defaults.openSearch(path)
                                },
                            ),
                            control = ServerRunControl(closeContext = { app ->
                                app.close()
                                contextCloses.incrementAndGet()
                                timeline += "context-close-complete"
                            }),
                        )
                    }

                    status shouldBe 1
                    output.errors.shouldContainExactly(
                        "serve: binds 0.0.0.0 with auth.mode=off but no TLS/trusted-proxy and no insecure override. " +
                            "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
                            "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
                            "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext.",
                    )
                    contextCloses.get() shouldBe 1
                    opens.get() shouldBe 0
                    val expectedStorageWarning =
                        "warn:storage.backend=local ignores the configured object-storage key(s): storage.object.bucket " +
                            "(set storage.backend=object to use them)"
                    fun containment(name: String, path: Path): String =
                        "warn:roots.$name ($path) is INSIDE DATA_DIR (${data.toRealPath()}). This serves " +
                            "correctly, but DATA_DIR is app-owned state whose contents are routinely wiped and rebuilt " +
                            "(`search.db` and the object mirror are explicitly disposable) - a wipe here takes this root's " +
                            "content with it. Move the root outside DATA_DIR."
                    fun unavailable(name: String, path: Path): String =
                        "warn:roots.$name.path does not exist or is not a readable/searchable directory: $path - the root will " +
                            "serve 503 for every request until the path is restored AND the server is restarted (its pages, " +
                            "aliases and checkpoints are left untouched in the meantime)"
                    fun glob(name: String): String =
                        "warn:auth.agentDirectCommit declares direct-commit globs for root '$name', but roots.$name is " +
                            "editable = false - the globs can never authorize anything there, because the root refuses page " +
                            "writes outright. Set editable = true, or drop the globs."
                    val backupWarning =
                        "warn:$backup is left over from an interrupted `plainbase root` promote. $rootsFile itself is intact and is " +
                            "the topology being served; remove the backup once you have satisfied yourself that is the topology you want."
                    val expectedRootsWarnings = if (explicit) {
                        listOf(
                            containment("zeta", data.toRealPath().resolve("zeta")),
                            containment("docs", nestedContent.toRealPath()),
                            containment("alpha", data.toRealPath().resolve("alpha")),
                            backupWarning,
                            "warn:roots {} is configured: the explicitly set CONTENT_DIR/contentDir (via env) is ignored - " +
                                "primary's path comes from roots.docs.path",
                            unavailable("zeta", zeta),
                            unavailable("alpha", alpha),
                            glob("zeta"),
                            glob("docs"),
                            glob("alpha"),
                        )
                    } else {
                        listOf(containment("docs", nestedContent.toRealPath()), backupWarning)
                    }
                    val expectedBindError =
                        "error:serve: binds 0.0.0.0 with auth.mode=off but no TLS/trusted-proxy and no insecure override. " +
                            "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
                            "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
                            "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext."
                    timeline.filter { it.startsWith("warn:") || it.startsWith("error:") } shouldContainExactly
                        (listOf(expectedStorageWarning) + expectedRootsWarnings + expectedBindError)
                    timeline.count { it == "context-close-complete" } shouldBe 1
                    timeline += "returned"
                    (timeline.indexOf("context-close-complete") < timeline.indexOf("returned")) shouldBe true
                } finally {
                    applicationLogger.detachAppender(appender)
                }
            }
        }
    }

    test("a local run returns naturally, observes the real hook thread, and closes each resource once") {
        withLocalFixture { content, data ->
            requireNoGlobalContext()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val bootAvailability = AtomicReference<RootAvailability>()
            val runtimeContext = AtomicReference<RouteContext>()
            val watcherRoots = Collections.synchronizedList(mutableListOf<RootName>())
            val port = freePort()
            var hook: Thread? = null
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                val startedAt = System.nanoTime()
                val status = runServerBounded(timeoutMillis = NATURAL_RETURN_TEST_DEADLINE_MILLIS) {
                    runServer(
                        localConfig(content, data, port = port),
                        RecordingOutput(events),
                        control = ServerRunControl(
                            startServer = {},
                            onHookInstalled = { installed ->
                                hook = installed
                                events += "hook:${installed.name}"
                            },
                            onBootAvailability = bootAvailability::set,
                            onRuntimeContext = runtimeContext::set,
                            onWatcherRegistration = watcherRoots::add,
                            closeDriver = { driver ->
                                requireLockHeld(data)
                                driver.close()
                                events += "driver"
                            },
                            closeSearch = { search ->
                                requireLockHeld(data)
                                search.close()
                                events += "search"
                            },
                            closeContext = { app ->
                                requireLockHeld(data)
                                app.close()
                                events += "context"
                            },
                        ),
                    )
                }
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                status shouldBe 0
                requireNotNull(bootAvailability.get()) shouldBeSameInstanceAs requireNotNull(runtimeContext.get()).availability
                watcherRoots shouldContainExactly listOf(RootName.PRIMARY)
                hook shouldNotBe null
                events.count { it == "driver" } shouldBe 1
                events.count { it == "search" } shouldBe 1
                events.count { it == "context" } shouldBe 1
                events += "stop-port-bound=${isPortBound(port)}"
                events.last() shouldBe "stop-port-bound=false"
                (elapsedMillis <= NATURAL_RETURN_TEST_DEADLINE_MILLIS) shouldBe true
                appender.list.none {
                    it.formattedMessage.contains("shutdown step '") &&
                        it.formattedMessage.contains(" failed; continuing with the remaining steps")
                } shouldBe true
                requireLockAvailable(data)
                requireNoGlobalContext()
            } finally {
                root.detachAppender(appender)
            }
        }
    }

    test("HTTP stop worker bounds launch diagnostics before starting the real worker") {
        withLocalFixture { content, data ->
            val firstLaunchFailure = IllegalStateException("launch failure 0")
            val additionalLaunchFailures = List(4) { index -> IllegalStateException("launch failure ${index + 1}") }
            val finalWorkerFailure = IllegalStateException("final worker failure")
            val launchAttempts = AtomicInteger()
            val actualWorker = AtomicReference<Thread>()
            val observedFailure = AtomicReference<Throwable>()

            runServerBounded {
                runServer(
                    localConfig(content, data),
                    RecordingOutput(),
                    control = ServerRunControl(
                        onHttpAcquired = { server ->
                            server.configureStopWorkerForTest(
                                factory = { task ->
                                    when (val attempt = launchAttempts.incrementAndGet()) {
                                        1 -> throw firstLaunchFailure
                                        in 2..5 -> throw additionalLaunchFailures[attempt - 2]
                                        else -> Thread(task, "plainbase-http-stop-cap")
                                    }
                                },
                                starter = { worker ->
                                    actualWorker.set(worker)
                                    worker.start()
                                },
                            )
                            server.failAfterEngineStopForTest(finalWorkerFailure)
                        },
                        startServer = { server -> server.start(wait = false) },
                        closeHttp = { server ->
                            runCatching { server.stop() }.onFailure(observedFailure::set)
                        },
                    ),
                ) shouldBe 0
            }

            launchAttempts.get() shouldBe 6
            val retainedFailure = requireNotNull(observedFailure.get())
            retainedFailure shouldBeSameInstanceAs firstLaunchFailure
            retainedFailure.suppressed.toList() shouldContainExactly additionalLaunchFailures.take(3) + finalWorkerFailure
            retainedFailure.suppressed.none { it === additionalLaunchFailures[3] }.shouldBeTrue()
            requireNotNull(actualWorker.get()).isAlive.shouldBeFalse()
        }
    }

    test("H1 real REST PUT remains admitted through engine stop and final call completion") {
        withLocalFixture { content, data ->
            val pageId = "0199aaaa-bbbb-7ccc-8ddd-0000000000a1"
            val original = "---\nid: $pageId\ntitle: Held\n---\n\n# Held\n\noriginal.\n"
            val updated = "---\nid: $pageId\ntitle: Held\n---\n\n# Held\n\nupdated after shutdown.\n"
            Files.createDirectories(content.resolve("docs"))
            Files.writeString(content.resolve("docs/held.md"), original)

            val h1DeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            val serverReady = CountDownLatch(1)
            val startRelease = CountDownLatch(1)
            val saveEntered = CountDownLatch(1)
            val saveRelease = CountDownLatch(1)
            val saveReturned = CountDownLatch(1)
            val receiveEntered = CountDownLatch(1)
            val receiveCallAttributeJob = AtomicReference<Job>()
            val incompleteCallCompleted = CountDownLatch(1)
            val putCallCompleted = CountDownLatch(1)
            val structuredChildStarted = CountDownLatch(1)
            val structuredChildCompleted = CountDownLatch(1)
            val structuredChildRelease = CountDownLatch(1)
            val stopGapEntered = CountDownLatch(1)
            val stopGapRelease = CountDownLatch(1)
            val runtimeContext = AtomicReference<RouteContext>()
            val serverRef = AtomicReference<com.plainbase.frameworks.ktor.KtorServer>()
            val incompleteObservation = AtomicReference<com.plainbase.frameworks.ktor.HttpCallAdmission.CallJobObservation>()
            val putObservation = AtomicReference<com.plainbase.frameworks.ktor.HttpCallAdmission.CallJobObservation>()
            val driverRef = AtomicReference<SqlDriver>()
            val searchRef = AtomicReference<SearchDb>()
            val driverCloseEntry = CountDownLatch(1)
            val searchCloseEntry = CountDownLatch(1)
            val runResult = AtomicReference<Result<Int>>()
            val contextCloseSawFinalCall = AtomicBoolean(false)
            val contextCloseSawDurableBytes = AtomicBoolean(false)
            val sameStopFailure = IllegalStateException("H1 same-stop sentinel")
            val launchConstructionFailure = IllegalStateException("H1 stop-worker construction sentinel")
            val launchStartFailure = IllegalStateException("H1 stop-worker start sentinel")
            val launchAfterStartFailure = IllegalStateException("H1 stop-worker post-start sentinel")
            val launchFailureObserved = CountDownLatch(1)
            val launchAttempts = AtomicInteger()
            val observedEngineStopFailure = AtomicReference<Throwable>()
            val port = freePort()
            val config = localConfig(content, data, port).copy(
                auth = AuthConfig(
                    mode = AuthMode.BUILTIN,
                    agentDirectCommitGlobs = listOf("docs/**"),
                ),
            )
            val defaults = ServerOpeners()
            var client: HttpClient? = null
            val clientCloseFinished = AtomicBoolean(false)
            val putCloseFactsCaptured = AtomicBoolean(false)
            val searchCloseFactsCaptured = AtomicBoolean(false)
            val putCloseObservedJobComplete = AtomicBoolean(false)
            val searchCloseObservedJobComplete = AtomicBoolean(false)
            val putCloseObservedDurableBytes = AtomicBoolean(false)
            val searchCloseObservedDurableBytes = AtomicBoolean(false)
            val driverCloseEntries = AtomicInteger()
            val searchCloseEntries = AtomicInteger()
            val driverCloseAttempts = AtomicInteger()
            val searchCloseAttempts = AtomicInteger()
            val driverCloseInvoked = AtomicBoolean(false)
            val searchCloseInvoked = AtomicBoolean(false)
            val driverCloseReturned = AtomicBoolean(false)
            val searchCloseReturned = AtomicBoolean(false)
            val driverCloseFailure = AtomicReference<Throwable>()
            val searchCloseFailure = AtomicReference<Throwable>()
            fun recordDriverCloseFacts() {
                if (putCloseFactsCaptured.compareAndSet(false, true)) {
                    putCloseObservedJobComplete.set(putObservation.get()?.originalJob?.isCompleted == true)
                    putCloseObservedDurableBytes.set(
                        runCatching {
                            Files.readAllBytes(content.resolve("docs/held.md"))
                                .contentEquals(updated.toByteArray())
                        }.getOrDefault(false),
                    )
                }
                driverCloseEntry.countDown()
            }
            fun recordSearchCloseFacts() {
                if (searchCloseFactsCaptured.compareAndSet(false, true)) {
                    searchCloseObservedJobComplete.set(putObservation.get()?.originalJob?.isCompleted == true)
                    searchCloseObservedDurableBytes.set(
                        runCatching {
                            Files.readAllBytes(content.resolve("docs/held.md"))
                                .contentEquals(updated.toByteArray())
                        }.getOrDefault(false),
                    )
                }
                searchCloseEntry.countDown()
            }
            fun closeDriverOnce(driver: SqlDriver) {
                if (driverCloseInvoked.compareAndSet(false, true)) {
                    driverCloseAttempts.incrementAndGet()
                    try {
                        driver.close()
                    } catch (failure: Throwable) {
                        driverCloseFailure.set(failure)
                        throw failure
                    } finally {
                        driverCloseReturned.set(true)
                    }
                } else {
                    driverCloseFailure.get()?.let { throw it }
                }
            }
            fun closeSearchOnce(search: SearchDb) {
                if (searchCloseInvoked.compareAndSet(false, true)) {
                    searchCloseAttempts.incrementAndGet()
                    try {
                        search.close()
                    } catch (failure: Throwable) {
                        searchCloseFailure.set(failure)
                        throw failure
                    } finally {
                        searchCloseReturned.set(true)
                    }
                } else {
                    searchCloseFailure.get()?.let { throw it }
                }
            }
            val runner = startFixtureWorker("plainbase-h1-production-run") {
                runResult.set(
                    runCatching {
                        runServer(
                            config,
                            RecordingOutput(),
                            openers = ServerOpeners(
                                openDriver = { path -> defaults.openDriver(path).also(driverRef::set) },
                                openSearch = { path -> defaults.openSearch(path).also(searchRef::set) },
                            ),
                            control = ServerRunControl(
                                onRuntimeContext = runtimeContext::set,
                                buildRouteContext = { build ->
                                    val base = build()
                                    val delegate = base.mutate
                                    val held = object : MutatingFacade by delegate {
                                        override fun save(
                                            principal: com.plainbase.domain.principal.Principal,
                                            request: SaveRequest,
                                        ): SaveResult {
                                            saveEntered.countDown()
                                            check(saveRelease.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                                                "H1 save release timed out"
                                            }
                                            return try {
                                                delegate.save(principal, request)
                                            } finally {
                                                saveReturned.countDown()
                                            }
                                        }
                                    }
                                    base.withMutating(held)
                                },
                                onHttpAcquired = { server ->
                                    serverRef.set(server)
                                    server.configureStopWorkerForTest(
                                        factory = { task ->
                                            when (launchAttempts.incrementAndGet()) {
                                                1 -> {
                                                    launchFailureObserved.countDown()
                                                    throw launchConstructionFailure
                                                }
                                                else -> Thread(task, "plainbase-http-stop")
                                            }
                                        },
                                        starter = { worker ->
                                            when (launchAttempts.get()) {
                                                2 -> throw launchStartFailure
                                                3 -> {
                                                    worker.start()
                                                    throw launchAfterStartFailure
                                                }
                                                else -> error("unexpected duplicate H1 stop-worker launch")
                                            }
                                        },
                                    )
                                    server.beforeEngineStopForTest {
                                        stopGapEntered.countDown()
                                        check(stopGapRelease.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                                            "H1 stop-gap release timed out"
                                        }
                                        throw sameStopFailure
                                    }
                                    server.failAfterEngineStopForTest(sameStopFailure)
                                },
                                startServer = { server ->
                                    server.start(wait = false)
                                    serverReady.countDown()
                                    check(startRelease.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                                        "H1 start release timed out"
                                    }
                                },
                                closeDriver = { driver ->
                                    driverCloseEntries.incrementAndGet()
                                    recordDriverCloseFacts()
                                    closeDriverOnce(driver)
                                },
                                closeSearch = { search ->
                                    searchCloseEntries.incrementAndGet()
                                    recordSearchCloseFacts()
                                    closeSearchOnce(search)
                                },
                                closeContext = { app ->
                                    contextCloseSawFinalCall.set(
                                        (putObservation.get()?.originalJob?.isCompleted == true) &&
                                            saveReturned.count == 0L && serverRef.get()?.admittedCallsForTest() == 0,
                                    )
                                    contextCloseSawDurableBytes.set(
                                        runCatching {
                                            Files.readAllBytes(content.resolve("docs/held.md"))
                                                .contentEquals(updated.toByteArray())
                                        }.getOrDefault(false),
                                    )
                                    app.close()
                                },
                                closeHttp = { server ->
                                    try {
                                        server.stop()
                                    } catch (failure: Throwable) {
                                        observedEngineStopFailure.set(failure)
                                        throw failure
                                    }
                                },
                            ),
                        )
                    },
                )
            }
            fun runBoundedFallbackClose(
                name: String,
                close: () -> Unit,
                deadlineNanos: Long,
                onFailure: (Throwable) -> Unit,
            ) {
                val result = AtomicReference<Result<Unit>>()
                val worker = startFixtureWorker(name) {
                    result.set(runCatching(close))
                }
                try {
                    worker.start()
                    if (!joinUntilCleanup(worker, deadlineNanos, ::rememberFixtureInterrupt)) {
                        worker.interrupt()
                        if (!joinUntilCleanup(worker, deadlineNanos, ::rememberFixtureInterrupt)) {
                            onFailure(TimeoutException("$name did not complete before fixture cleanup deadline"))
                        }
                    }
                    if (!worker.isAlive) result.get()?.exceptionOrNull()?.let(onFailure)
                } catch (failure: Throwable) {
                    onFailure(failure)
                }
            }
            fun closeDeferredResourcesIfSafe(deadlineNanos: Long, onFailure: (Throwable) -> Unit) {
                val runnerQuiescent = !runner.isAlive
                val incompleteCallQuiescent = incompleteObservation.get() == null || incompleteCallCompleted.count == 0L
                val putCallQuiescent = putObservation.get() == null || putCallCompleted.count == 0L
                val structuredChildQuiescent = structuredChildStarted.count != 0L || structuredChildCompleted.count == 0L
                if (!runnerQuiescent || !incompleteCallQuiescent || !putCallQuiescent || !structuredChildQuiescent) return
                driverRef.get()?.let { driver ->
                    recordDriverCloseFacts()
                    if (driverCloseReturned.get()) {
                        driverCloseFailure.get()?.let(onFailure)
                    } else {
                        runBoundedFallbackClose(
                            "plainbase-h1-driver-close-fallback",
                            { closeDriverOnce(driver) },
                            deadlineNanos,
                            onFailure,
                        )
                    }
                }
                searchRef.get()?.let { search ->
                    recordSearchCloseFacts()
                    if (searchCloseReturned.get()) {
                        searchCloseFailure.get()?.let(onFailure)
                    } else {
                        runBoundedFallbackClose(
                            "plainbase-h1-search-close-fallback",
                            { closeSearchOnce(search) },
                            deadlineNanos,
                            onFailure,
                        )
                    }
                }
            }
            var put: java.util.concurrent.CompletableFuture<HttpResponse<String>>? = null
            registerFixtureSurvivor("H1 incomplete receive") {
                incompleteObservation.get()?.originalJob?.isCompleted == false
            }
            registerFixtureSurvivor("H1 admitted PUT") {
                putObservation.get()?.originalJob?.isCompleted == false
            }
            registerFixtureSurvivor("H1 structured child") {
                structuredChildStarted.count == 0L && structuredChildCompleted.count != 0L
            }
            registerFixtureSurvivor("H1 HTTP client close") {
                client != null && !clientCloseFinished.get()
            }
            registerFixtureSurvivor("H1 driver close") {
                driverRef.get() != null && !driverCloseReturned.get()
            }
            registerFixtureSurvivor("H1 search close") {
                searchRef.get() != null && !searchCloseReturned.get()
            }
            var fallbackFailure: Throwable? = null
            fun retainFallback(failure: Throwable) {
                if (fallbackFailure == null) {
                    fallbackFailure = failure
                } else if (failure !== fallbackFailure && requireNotNull(fallbackFailure).suppressed.none { it === failure }) {
                    requireNotNull(fallbackFailure).addSuppressed(failure)
                }
            }
            var primaryFailure: Throwable? = null
            try {
                runner.start()
                check(serverReady.await(H1_PARENT_CEILING_MILLIS, TimeUnit.MILLISECONDS)) { "H1 server did not start" }
                val server = requireNotNull(serverRef.get())
                val context = requireNotNull(runtimeContext.get())
                val token = context.tokens.mint("h1", AgentMode.COMMIT).plaintext
                client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                val uri = URI("http://127.0.0.1:$port/api/v1/pages/$pageId")
                val get = client.send(
                    HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(remainingMillis(h1DeadlineNanos)))
                        .header("Authorization", "Bearer $token")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                get.statusCode() shouldBe 200
                val etag = get.headers().firstValue("ETag").orElseThrow()
                requireNotNull(driverRef.get())
                requireNotNull(searchRef.get())

                val established = openRawHttpSocket(port)
                try {
                    rawHttpGet(established).statusCode shouldBe 200
                    val incomplete = openRawHttpSocket(port)
                    try {
                        server.captureNextAdmissionForTest { observation ->
                            incompleteObservation.set(observation)
                            observation.originalJob.invokeOnCompletion { incompleteCallCompleted.countDown() }
                        }
                        server.captureNextReceiveForTest { call ->
                            receiveCallAttributeJob.set(
                                call.attributes.getOrNull(HttpCallAdmission.ORIGINAL_CALL_JOB_KEY),
                            )
                            receiveEntered.countDown()
                        }
                        writeIncompletePut(incomplete, pageId, token, etag)
                        check(receiveEntered.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                            "H1 incomplete PUT did not enter production body receive"
                        }
                        incomplete.setSoLinger(true, 0)
                    } finally {
                        incomplete.close()
                    }
                    check(incompleteCallCompleted.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                        "H1 incomplete PUT original call did not complete after disconnect"
                    }
                    val incompleteCall = requireNotNull(incompleteObservation.get())
                    incompleteCall.attributeInstalled shouldBe true
                    incompleteCall.attributeJob shouldBeSameInstanceAs incompleteCall.originalJob
                    receiveCallAttributeJob.get() shouldBeSameInstanceAs incompleteCall.originalJob
                    incompleteCall.originalJob.isCompleted shouldBe true
                    saveEntered.count shouldBe 1L
                    Files.readAllBytes(content.resolve("docs/held.md")).contentEquals(original.toByteArray()) shouldBe true

                    server.captureNextAdmissionForTest { observation ->
                        putObservation.set(observation)
                        observation.originalJob.invokeOnCompletion { putCallCompleted.countDown() }
                    }
                    server.launchNextStructuredChildForTest { scope ->
                        scope.launch {
                            structuredChildStarted.countDown()
                            check(
                                structuredChildRelease.await(
                                    remainingMillis(h1DeadlineNanos),
                                    TimeUnit.MILLISECONDS,
                                ),
                            ) { "H1 structured child release timed out" }
                        }.also { child ->
                            child.invokeOnCompletion { structuredChildCompleted.countDown() }
                        }
                    }
                    put = client.sendAsync(
                        HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofMillis(remainingMillis(h1DeadlineNanos)))
                            .header("Authorization", "Bearer $token")
                            .header("If-Match", etag)
                            .header("Content-Type", "text/markdown")
                            .PUT(HttpRequest.BodyPublishers.ofString(updated))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    check(saveEntered.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) { "H1 save was not reached" }
                    check(structuredChildStarted.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                        "H1 real structured child was not started"
                    }
                    val putCall = requireNotNull(putObservation.get())
                    putCall.attributeInstalled shouldBe true
                    putCall.attributeJob shouldBeSameInstanceAs putCall.originalJob
                    putCall.originalJob.isCompleted shouldBe false
                    server.admittedCallsForTest() shouldBe 1
                    server.admissionOpenForTest() shouldBe true
                    dataDirLockHeld(data) shouldBe true
                    Files.exists(data.resolve("plainbase.db")) shouldBe true
                    driverCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    searchCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false

                    startRelease.countDown()
                    check(launchFailureObserved.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                        "H1 stop-worker launch failure was not observed"
                    }
                    server.admissionOpenForTest() shouldBe false
                    runner.isAlive shouldBe true
                    dataDirLockHeld(data) shouldBe true
                    driverCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    searchCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    check(stopGapEntered.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) { "H1 stop gap was not reached" }
                    server.admissionOpenForTest() shouldBe false
                    val closed = rawHttpGet(established)
                    closed.statusCode shouldBe 503
                    closed.headers["connection"]?.lowercase() shouldBe "close"
                    closed.headers["content-type"]?.startsWith("application/json") shouldBe true
                    closed.body shouldBe "{\"error\":{\"code\":\"server_shutting_down\",\"message\":\"Server is shutting down\"}}"
                } finally {
                    established.close()
                }

                stopGapRelease.countDown()
                val holdStarted = System.nanoTime()
                while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdStarted) < H1_WARNING_OVERRUN_MILLIS) {
                    dataDirLockHeld(data) shouldBe true
                    Files.exists(data.resolve("plainbase.db")) shouldBe true
                    requireNotNull(driverRef.get())
                    requireNotNull(searchRef.get())
                    driverCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    searchCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    Thread.sleep(H1_POLL_MILLIS)
                }
                saveRelease.countDown()
                val putResult = requireNotNull(put).handle { response, failure -> response to failure }.get(
                    remainingMillis(h1DeadlineNanos),
                    TimeUnit.MILLISECONDS,
                )
                if (putResult.second == null) {
                    val putResponse = requireNotNull(putResult.first)
                    check(putResponse.statusCode() == 200) {
                        "H1 direct PUT returned ${putResponse.statusCode()}: ${putResponse.body()}"
                    }
                }
                check(saveReturned.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) { "H1 save did not return" }
                val putCall = requireNotNull(putObservation.get())
                putCall.originalJob.isCompleted shouldBe false
                server.admittedCallsForTest() shouldBe 1
                structuredChildRelease.countDown()
                check(putCallCompleted.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H1 original PUT call did not complete after its structured child"
                }
                putCall.originalJob.isCompleted shouldBe true
                joinUntil(runner, h1DeadlineNanos)
                runner.isAlive shouldBe false
                check(driverCloseEntry.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H1 driver close entry was not observed"
                }
                check(searchCloseEntry.await(remainingMillis(h1DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H1 search close entry was not observed"
                }
                closeDeferredResourcesIfSafe(h1DeadlineNanos, ::retainFallback)
                fallbackFailure?.let { throw it }
                requireNotNull(runResult.get()).getOrThrow() shouldBe 0
                launchAttempts.get() shouldBe 3
                val stopWorker = requireNotNull(server.stopWorkerForTest())
                stopWorker.isDaemon shouldBe false
                stopWorker.isAlive shouldBe false
                launchConstructionFailure shouldBeSameInstanceAs requireNotNull(observedEngineStopFailure.get())
                requireNotNull(observedEngineStopFailure.get()).suppressed.toList() shouldContainExactly listOf(
                    launchStartFailure,
                    launchAfterStartFailure,
                    sameStopFailure,
                )
                putCloseObservedJobComplete.get() shouldBe true
                searchCloseObservedJobComplete.get() shouldBe true
                putCloseObservedDurableBytes.get() shouldBe true
                searchCloseObservedDurableBytes.get() shouldBe true
                contextCloseSawFinalCall.get() shouldBe true
                contextCloseSawDurableBytes.get() shouldBe true
                driverCloseEntries.get() shouldBe 1
                searchCloseEntries.get() shouldBe 1
                driverCloseAttempts.get() shouldBe 1
                searchCloseAttempts.get() shouldBe 1
                driverCloseInvoked.get() shouldBe true
                searchCloseInvoked.get() shouldBe true
                driverCloseReturned.get() shouldBe true
                searchCloseReturned.get() shouldBe true
                Files.readAllBytes(content.resolve("docs/held.md")).contentEquals(updated.toByteArray()) shouldBe true
                requireLockAvailable(data)
            } catch (failure: Throwable) {
                if (failure is InterruptedException) rememberFixtureInterrupt()
                primaryFailure = failure
            } finally {
                saveRelease.countDown()
                stopGapRelease.countDown()
                structuredChildRelease.countDown()
                startRelease.countDown()
                val cleanupDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(H1_CLEANUP_MILLIS)
                var cleanupFailure: Throwable? = null
                fun retainCleanup(failure: Throwable) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure
                    } else if (failure !== cleanupFailure && requireNotNull(cleanupFailure).suppressed.none { it === failure }) {
                        requireNotNull(cleanupFailure).addSuppressed(failure)
                    }
                }
                try {
                    put?.cancel(true)
                    check(put?.isDone != false) { "H1 PUT future survived cancellation" }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    val clientToClose = client
                    if (clientToClose != null) {
                        val closeResult = AtomicReference<Result<Unit>>()
                        val closeWorker = startFixtureWorker("plainbase-h1-client-close") {
                            try {
                                closeResult.set(runCatching { clientToClose.close() })
                            } finally {
                                clientCloseFinished.set(true)
                            }
                        }
                        try {
                            closeWorker.start()
                            var closeCompleted = joinUntilCleanup(closeWorker, cleanupDeadlineNanos, ::rememberFixtureInterrupt)
                            if (!closeCompleted && closeWorker.isAlive) {
                                closeWorker.interrupt()
                                closeCompleted = joinUntilCleanup(closeWorker, cleanupDeadlineNanos, ::rememberFixtureInterrupt)
                            }
                            if (!closeCompleted) {
                                retainCleanup(
                                    TimeoutException("H1 HTTP client close did not complete before fixture cleanup deadline"),
                                )
                            } else {
                                val result = closeResult.get()
                                if (result == null) {
                                    retainCleanup(IllegalStateException("H1 HTTP client close completed without a result"))
                                } else {
                                    result.exceptionOrNull()?.let(::retainCleanup)
                                }
                            }
                        } catch (failure: Throwable) {
                            retainCleanup(failure)
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (incompleteObservation.get() != null &&
                        !awaitCleanup(incompleteCallCompleted, cleanupDeadlineNanos, ::rememberFixtureInterrupt)
                    ) {
                        retainCleanup(TimeoutException("H1 incomplete receive call survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (putObservation.get() != null) {
                        if (!awaitCleanup(putCallCompleted, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            retainCleanup(TimeoutException("H1 original PUT call survived cleanup"))
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (structuredChildStarted.count == 0L) {
                        if (!awaitCleanup(structuredChildCompleted, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            retainCleanup(TimeoutException("H1 structured child survived cleanup"))
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (runner.isAlive) runner.interrupt()
                    if (!joinUntilCleanup(runner, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                        retainCleanup(TimeoutException("H1 server runner survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    closeDeferredResourcesIfSafe(cleanupDeadlineNanos, ::retainCleanup)
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                cleanupFailure?.let { failure ->
                    if (primaryFailure == null) primaryFailure = failure else requireNotNull(primaryFailure).addSuppressed(failure)
                }
            }
            primaryFailure?.let { throw it }
        }
    }

    test("H2 real MCP proposal keeps the SSE GET and POST ordered through engine cancellation") {
        withLocalFixture { content, data ->
            val pageId = PageId.require("0199aaaa-bbbb-7ccc-8ddd-0000000000b2")
            val targetPath = TreePath.require("docs/h2.md")
            val original = "---\nid: ${pageId.value}\ntitle: H2\n---\n\n# H2\n\noriginal.\n"
            val proposed = "---\nid: ${pageId.value}\ntitle: H2\n---\n\n# H2\n\nproposed during shutdown.\n"
            val originalBytes = original.toByteArray(StandardCharsets.UTF_8)
            val proposedBytes = proposed.toByteArray(StandardCharsets.UTF_8)
            Files.createDirectories(content.resolve("docs"))
            Files.write(content.resolve(targetPath.value), originalBytes)

            val h2DeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(H2_PARENT_CEILING_MILLIS)
            val serverReady = CountDownLatch(1)
            val startRelease = CountDownLatch(1)
            val clientConnected = CountDownLatch(1)
            val clientFailure = AtomicReference<Throwable>()
            val clientRelease = CountDownLatch(1)
            val proposalRelease = CountDownLatch(1)
            val getCompleted = CountDownLatch(1)
            val postCompleted = CountDownLatch(1)
            val proposalEntered = CountDownLatch(1)
            val proposalReturned = CountDownLatch(1)
            val runtimeContext = AtomicReference<RouteContext>()
            val isolatedKoin = AtomicReference<KoinApplication>()
            val proposalRepository = AtomicReference<ProposalRepository>()
            val serverRef = AtomicReference<com.plainbase.frameworks.ktor.KtorServer>()
            val getObservation = AtomicReference<HttpCallAdmission.CallJobObservation>()
            val postObservation = AtomicReference<HttpCallAdmission.CallJobObservation>()
            val proposalPrincipal = AtomicReference<Principal>()
            val proposalCommand = AtomicReference<ProposeCommand>()
            val proposalOutcome = AtomicReference<ProposeOutcome>()
            val proposalClientResult = AtomicReference<Result<CallToolResult>>()
            val proposalRowAtDriverClose = AtomicReference<ProposalRow>()
            val proposalRowAtSearchClose = AtomicReference<ProposalRow>()
            val proposalSnapshot = AtomicReference<ProposalRow>()
            val closeObservationFailure = AtomicReference<Throwable>()
            val closeFactsCaptured = AtomicBoolean(false)
            val driverCloseEntry = CountDownLatch(1)
            val searchCloseEntry = CountDownLatch(1)
            val driverCloseInvoked = AtomicBoolean(false)
            val searchCloseInvoked = AtomicBoolean(false)
            val driverCloseFailure = AtomicReference<Throwable>()
            val searchCloseFailure = AtomicReference<Throwable>()
            val driverCloseReturned = AtomicBoolean(false)
            val searchCloseReturned = AtomicBoolean(false)
            val closeSawPostCompleted = AtomicBoolean(false)
            val closeSawProposedBytes = AtomicBoolean(false)
            val contextCloseSawPostCompleted = AtomicBoolean(false)
            val contextCloseSawProposedBytes = AtomicBoolean(false)
            val routeContextBuilds = AtomicInteger()
            val driverRef = AtomicReference<SqlDriver>()
            val searchRef = AtomicReference<SearchDb>()
            val runResult = AtomicReference<Result<Int>>()
            val port = freePort()
            val config = localConfig(content, data, port).copy(
                auth = AuthConfig(mode = AuthMode.BUILTIN),
            )
            val defaults = ServerOpeners()
            var client: Client? = null
            var http: KtorHttpClient? = null
            var getWorker: Thread? = null
            var proposalWorker: Thread? = null
            val clientCloseFinished = AtomicBoolean(false)
            var clientCloseWorker: Thread? = null
            val httpClientJob = AtomicReference<Job>()

            fun proposalRow(): ProposalRow? {
                val outcome = proposalOutcome.get() as? ProposeOutcome.Created
                return outcome?.id?.let { proposalRepository.get()?.findById(it) }
            }
            fun immutableProposalRow(row: ProposalRow): ProposalRow = ProposalRow(
                id = row.id,
                operation = row.operation,
                pageId = row.pageId,
                root = row.root,
                baseHash = row.baseHash,
                targetPath = row.targetPath,
                proposedContent = row.proposedContent.copyOf(),
                rationale = row.rationale,
                diffArtifact = row.diffArtifact,
                status = row.status,
                authorIssuer = row.authorIssuer,
                authorExternalId = row.authorExternalId,
                authorLabel = row.authorLabel,
                approverIssuer = row.approverIssuer,
                approverExternalId = row.approverExternalId,
                decisionComment = row.decisionComment,
                createdAt = row.createdAt,
                decidedAt = row.decidedAt,
                appliedCommit = row.appliedCommit,
                statusReason = row.statusReason,
            )
            fun retainCloseObservation(failure: Throwable) {
                while (true) {
                    val retained = closeObservationFailure.get()
                    if (retained == null) {
                        if (closeObservationFailure.compareAndSet(null, failure)) return
                    } else {
                        synchronized(retained) {
                            if (retained !== failure && retained.suppressed.none { it === failure }) {
                                retained.addSuppressed(failure)
                            }
                        }
                        return
                    }
                }
            }
            fun captureProposalSnapshot() {
                if (proposalSnapshot.get() != null) return
                try {
                    proposalRow()?.let { row -> proposalSnapshot.compareAndSet(null, immutableProposalRow(row)) }
                } catch (failure: Throwable) {
                    retainCloseObservation(failure)
                }
            }
            fun captureCloseFacts(rowAtClose: AtomicReference<ProposalRow>, closeEntry: CountDownLatch) {
                try {
                    if (closeFactsCaptured.compareAndSet(false, true)) captureProposalSnapshot()
                    val row = proposalSnapshot.get()
                    rowAtClose.set(row)
                    closeSawPostCompleted.set(postObservation.get()?.originalJob?.isCompleted == true)
                    closeSawProposedBytes.set(row?.proposedContent?.contentEquals(proposedBytes) == true)
                } catch (failure: Throwable) {
                    retainCloseObservation(failure)
                } finally {
                    closeEntry.countDown()
                }
            }
            fun closeDriver(driver: SqlDriver) {
                if (!driverCloseInvoked.compareAndSet(false, true)) {
                    driverCloseFailure.get()?.let { throw it }
                    return
                }
                try {
                    driver.close()
                } catch (failure: Throwable) {
                    driverCloseFailure.set(failure)
                    throw failure
                } finally {
                    driverCloseReturned.set(true)
                }
            }
            fun closeSearch(search: SearchDb) {
                if (!searchCloseInvoked.compareAndSet(false, true)) {
                    searchCloseFailure.get()?.let { throw it }
                    return
                }
                try {
                    search.close()
                } catch (failure: Throwable) {
                    searchCloseFailure.set(failure)
                    throw failure
                } finally {
                    searchCloseReturned.set(true)
                }
            }
            val runner = startFixtureWorker("plainbase-h2-production-run") {
                runResult.set(
                    runCatching {
                        runServer(
                            config,
                            RecordingOutput(),
                            openers = ServerOpeners(
                                openDriver = { path -> defaults.openDriver(path).also(driverRef::set) },
                                openSearch = { path -> defaults.openSearch(path).also(searchRef::set) },
                            ),
                            control = ServerRunControl(
                                onContextAcquired = isolatedKoin::set,
                                onRuntimeContext = runtimeContext::set,
                                buildRouteContext = { build ->
                                    routeContextBuilds.incrementAndGet()
                                    val base = build()
                                    val delegate = base.proposals
                                    val held = object : ProposalFacade by delegate {
                                        override fun propose(principal: Principal, command: ProposeCommand): ProposeOutcome {
                                            proposalPrincipal.set(principal)
                                            proposalCommand.set(command)
                                            proposalEntered.countDown()
                                            return try {
                                                check(
                                                    proposalRelease.await(
                                                        remainingMillis(h2DeadlineNanos),
                                                        TimeUnit.MILLISECONDS,
                                                    ),
                                                ) { "H2 proposal release timed out" }
                                                delegate.propose(principal, command).also(proposalOutcome::set)
                                            } finally {
                                                proposalReturned.countDown()
                                            }
                                        }
                                    }
                                    base.withProposals(held)
                                },
                                onHttpAcquired = serverRef::set,
                                startServer = { server ->
                                    proposalRepository.set(
                                        requireNotNull(isolatedKoin.get()).koin.get<ProposalRepository>(),
                                    )
                                    server.start(wait = false)
                                    serverReady.countDown()
                                    check(startRelease.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                                        "H2 start release timed out"
                                    }
                                },
                                closeDriver = { driver ->
                                    captureCloseFacts(proposalRowAtDriverClose, driverCloseEntry)
                                    closeDriver(driver)
                                },
                                closeSearch = { search ->
                                    captureCloseFacts(proposalRowAtSearchClose, searchCloseEntry)
                                    closeSearch(search)
                                },
                                closeContext = { app ->
                                    try {
                                        contextCloseSawPostCompleted.set(postObservation.get()?.originalJob?.isCompleted == true)
                                        contextCloseSawProposedBytes.set(
                                            proposalSnapshot.get()?.proposedContent?.contentEquals(proposedBytes) == true,
                                        )
                                    } catch (failure: Throwable) {
                                        retainCloseObservation(failure)
                                    } finally {
                                        app.close()
                                    }
                                },
                            ),
                        )
                    },
                )
            }
            registerFixtureSurvivor("H2 SSE GET") {
                getObservation.get()?.originalJob?.isCompleted == false
            }
            registerFixtureSurvivor("H2 proposal POST") {
                postObservation.get()?.originalJob?.isCompleted == false
            }
            registerFixtureSurvivor("H2 proposal worker") {
                proposalWorker?.isAlive == true
            }
            registerFixtureSurvivor("H2 client close worker") {
                (client != null || http != null) && (!clientCloseFinished.get() || clientCloseWorker?.isAlive == true)
            }
            registerFixtureSurvivor("H2 HTTP client job") {
                httpClientJob.get()?.isCompleted == false
            }
            registerFixtureSurvivor("H2 server runner") {
                runner.isAlive
            }
            registerFixtureSurvivor("H2 driver close") {
                driverRef.get() != null && !driverCloseReturned.get()
            }
            registerFixtureSurvivor("H2 search close") {
                searchRef.get() != null && !searchCloseReturned.get()
            }
            var primaryFailure: Throwable? = null
            try {
                runner.start()
                check(serverReady.await(H2_PARENT_CEILING_MILLIS, TimeUnit.MILLISECONDS)) { "H2 server did not start" }
                val context = requireNotNull(runtimeContext.get())
                val minted = context.tokens.mint(label = "h2", mode = AgentMode.PROPOSE)
                val baseHash = CitationFactory().contentHash(originalBytes)
                val mcpClient = Client(Implementation(name = "plainbase-h2", version = "0.0.1"))
                val httpClient = KtorHttpClient(ClientCIO) { install(SSE) }
                client = mcpClient
                http = httpClient
                httpClientJob.set(httpClient.coroutineContext[Job])
                val transport = httpClient.mcpSseTransport("http://127.0.0.1:$port/api/v1/mcp") {
                    header(HttpHeaders.Authorization, "Bearer ${minted.plaintext}")
                }
                serverRef.get()?.captureNextAdmissionForTest { observation ->
                    getObservation.set(observation)
                    observation.originalJob.invokeOnCompletion { getCompleted.countDown() }
                }
                val sseWorker = startFixtureWorker("plainbase-h2-sse-client") {
                    try {
                        runBlocking {
                            mcpClient.connect(transport)
                            clientConnected.countDown()
                            check(clientRelease.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                                "H2 client release timed out"
                            }
                        }
                    } catch (failure: Throwable) {
                        clientFailure.set(failure)
                        clientConnected.countDown()
                    }
                }
                getWorker = sseWorker
                sseWorker.start()
                check(clientConnected.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H2 MCP client did not initialize"
                }
                clientFailure.get()?.let { throw it }
                requireNotNull(getObservation.get())
                serverRef.get()?.captureNextAdmissionForTest { observation ->
                    postObservation.set(observation)
                    observation.originalJob.invokeOnCompletion { postCompleted.countDown() }
                }
                val arguments = mapOf(
                    "operation" to "edit",
                    "page_id" to pageId.value,
                    "base_hash" to baseHash,
                    "proposed_content" to proposed,
                    "rationale" to "H2 shutdown proposal",
                )
                val proposalCallWorker = startFixtureWorker("plainbase-h2-proposal-client") {
                    proposalClientResult.set(runCatching { runBlocking { mcpClient.callTool("propose_change", arguments) } })
                }
                proposalWorker = proposalCallWorker
                proposalCallWorker.start()
                check(proposalEntered.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H2 proposal delegate was not reached"
                }
                val actualPrincipal = requireNotNull(proposalPrincipal.get())
                actualPrincipal shouldBe Principal.Agent(minted.id)
                val actualCommand = requireNotNull(proposalCommand.get()) as? ProposeCommand.Edit
                requireNotNull(actualCommand)
                actualCommand.pageId shouldBe pageId
                actualCommand.baseHash shouldBe baseHash
                actualCommand.clientTargetPath shouldBe null
                actualCommand.root shouldBe null
                actualCommand.proposedContent.contentEquals(proposedBytes) shouldBe true
                actualCommand.rationale shouldBe "H2 shutdown proposal"
                routeContextBuilds.get() shouldBe 1
                requireNotNull(proposalRepository.get()).all() shouldBe emptyList()
                dataDirLockHeld(data) shouldBe true
                Files.readAllBytes(content.resolve(targetPath.value)).contentEquals(originalBytes) shouldBe true

                startRelease.countDown()
                check(getCompleted.await(H2_ENGINE_CANCEL_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "H2 idle SSE GET did not complete after engine cancellation"
                }
                val postCall = requireNotNull(postObservation.get())
                postCall.originalJob.isCompleted shouldBe false
                dataDirLockHeld(data) shouldBe true
                driverCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                searchCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                val holdStarted = System.nanoTime()
                while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdStarted) < H2_WARNING_OVERRUN_MILLIS) {
                    postCall.originalJob.isCompleted shouldBe false
                    dataDirLockHeld(data) shouldBe true
                    driverCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    searchCloseEntry.await(0, TimeUnit.MILLISECONDS) shouldBe false
                    Thread.sleep(H2_POLL_MILLIS)
                }

                proposalRelease.countDown()
                check(proposalReturned.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H2 proposal delegate did not return"
                }
                check(postCompleted.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H2 proposal POST did not complete"
                }
                closeObservationFailure.get()?.let { throw it }
                clientRelease.countDown()
                joinUntil(runner, h2DeadlineNanos)
                requireNotNull(runResult.get()).getOrThrow() shouldBe 0
                check(driverCloseEntry.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H2 driver close was not observed"
                }
                check(searchCloseEntry.await(remainingMillis(h2DeadlineNanos), TimeUnit.MILLISECONDS)) {
                    "H2 search close was not observed"
                }
                getCompleted.count shouldBe 0L
                postCall.originalJob.isCompleted shouldBe true
                driverCloseReturned.get() shouldBe true
                searchCloseReturned.get() shouldBe true
                closeSawPostCompleted.get() shouldBe true
                closeSawProposedBytes.get() shouldBe true
                contextCloseSawPostCompleted.get() shouldBe true
                contextCloseSawProposedBytes.get() shouldBe true
                val row = requireNotNull(proposalRowAtDriverClose.get())
                val searchRow = requireNotNull(proposalRowAtSearchClose.get())
                searchRow.id shouldBe row.id
                searchRow.proposedContent.contentEquals(row.proposedContent) shouldBe true
                row.id shouldBe (proposalOutcome.get() as ProposeOutcome.Created).id
                row.operation shouldBe ProposalOperation.EDIT
                row.pageId shouldBe pageId
                row.root shouldBe context.registry.primary.name
                row.baseHash shouldBe baseHash
                row.targetPath shouldBe targetPath
                row.proposedContent.contentEquals(proposedBytes) shouldBe true
                row.rationale shouldBe "H2 shutdown proposal"
                row.status shouldBe ProposalStatus.PENDING
                row.authorIssuer shouldBe "agent"
                row.authorExternalId shouldBe minted.id
                row.authorLabel shouldBe "h2"
                Files.readAllBytes(content.resolve(targetPath.value)).contentEquals(originalBytes) shouldBe true
                requireLockAvailable(data)
            } catch (failure: Throwable) {
                if (failure is InterruptedException) rememberFixtureInterrupt()
                primaryFailure = failure
            } finally {
                startRelease.countDown()
                proposalRelease.countDown()
                clientRelease.countDown()
                val cleanupDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(H2_CLEANUP_MILLIS)
                var cleanupFailure: Throwable? = null
                fun retainCleanup(failure: Throwable) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure
                    } else if (failure !== cleanupFailure && requireNotNull(cleanupFailure).suppressed.none { it === failure }) {
                        requireNotNull(cleanupFailure).addSuppressed(failure)
                    }
                }
                fun runBoundedFallbackClose(name: String, close: () -> Unit) {
                    val result = AtomicReference<Result<Unit>>()
                    val worker = startFixtureWorker(name) { result.set(runCatching(close)) }
                    try {
                        worker.start()
                        if (!joinUntilCleanup(worker, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            worker.interrupt()
                            if (!joinUntilCleanup(worker, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                                retainCleanup(TimeoutException("$name did not complete before fixture cleanup deadline"))
                            }
                        }
                        if (!worker.isAlive) result.get()?.exceptionOrNull()?.let(::retainCleanup)
                    } catch (failure: Throwable) {
                        retainCleanup(failure)
                    }
                }
                fun awaitJobCleanup(job: Job): Boolean {
                    while (!job.isCompleted && System.nanoTime() < cleanupDeadlineNanos) {
                        try {
                            val remaining = cleanupDeadlineNanos - System.nanoTime()
                            Thread.sleep(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                        } catch (_: InterruptedException) {
                            rememberFixtureInterrupt()
                        }
                    }
                    return job.isCompleted
                }
                fun h2JobsQuiescent(): Boolean {
                    val getCallQuiescent = getObservation.get() == null || getCompleted.count == 0L
                    val postCallQuiescent = postObservation.get() == null || postCompleted.count == 0L
                    val proposalWorkerQuiescent = proposalWorker?.isAlive != true
                    val getWorkerQuiescent = getWorker?.isAlive != true
                    val workersQuiescent = proposalWorkerQuiescent && getWorkerQuiescent
                    val httpClientQuiescent = httpClientJob.get()?.isCompleted != false
                    return getCallQuiescent && postCallQuiescent && workersQuiescent && httpClientQuiescent
                }
                fun closeDeferredResourcesIfSafe() {
                    if (runner.isAlive || !h2JobsQuiescent()) return
                    driverRef.get()?.let { driver ->
                        if (driverCloseReturned.get()) {
                            driverCloseFailure.get()?.let(::retainCleanup)
                        } else if (!driverCloseInvoked.get()) {
                            captureCloseFacts(proposalRowAtDriverClose, driverCloseEntry)
                            runBoundedFallbackClose("plainbase-h2-driver-close-fallback") { closeDriver(driver) }
                        } else {
                            retainCleanup(TimeoutException("H2 driver close did not return"))
                        }
                    }
                    searchRef.get()?.let { search ->
                        if (searchCloseReturned.get()) {
                            searchCloseFailure.get()?.let(::retainCleanup)
                        } else if (!searchCloseInvoked.get()) {
                            captureCloseFacts(proposalRowAtSearchClose, searchCloseEntry)
                            runBoundedFallbackClose("plainbase-h2-search-close-fallback") { closeSearch(search) }
                        } else {
                            retainCleanup(TimeoutException("H2 search close did not return"))
                        }
                    }
                }
                try {
                    val clientToClose = client
                    val httpToClose = http
                    if (clientToClose != null || httpToClose != null) {
                        val sdkCloseResult = AtomicReference<Result<Unit>>()
                        val httpCloseResult = AtomicReference<Result<Unit>>()
                        val closeWorker = startFixtureWorker("plainbase-h2-client-close") {
                            try {
                                sdkCloseResult.set(
                                    runCatching {
                                        clientToClose?.let { runBlocking { it.close() } }
                                        Unit
                                    },
                                )
                                httpCloseResult.set(
                                    runCatching {
                                        httpToClose?.close()
                                        Unit
                                    },
                                )
                            } finally {
                                clientCloseFinished.set(true)
                            }
                        }
                        clientCloseWorker = closeWorker
                        try {
                            closeWorker.start()
                            if (!joinUntilCleanup(closeWorker, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                                closeWorker.interrupt()
                                if (!joinUntilCleanup(closeWorker, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                                    retainCleanup(TimeoutException("H2 client close did not complete before fixture cleanup deadline"))
                                }
                            }
                            if (!closeWorker.isAlive) {
                                sdkCloseResult.get()?.exceptionOrNull()?.let(::retainCleanup)
                                httpCloseResult.get()?.exceptionOrNull()?.let(::retainCleanup)
                            }
                        } catch (failure: Throwable) {
                            retainCleanup(failure)
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    httpClientJob.get()?.let { job ->
                        if (!awaitJobCleanup(job)) retainCleanup(TimeoutException("H2 HTTP client job survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (!awaitCleanup(getCompleted, cleanupDeadlineNanos, ::rememberFixtureInterrupt) && getObservation.get() != null) {
                        retainCleanup(TimeoutException("H2 SSE GET survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (!awaitCleanup(postCompleted, cleanupDeadlineNanos, ::rememberFixtureInterrupt) && postObservation.get() != null) {
                        retainCleanup(TimeoutException("H2 proposal POST survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    proposalWorker?.let {
                        if (!joinUntilCleanup(it, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            retainCleanup(TimeoutException("H2 proposal worker survived cleanup"))
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    getWorker?.let {
                        if (!joinUntilCleanup(it, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            retainCleanup(TimeoutException("H2 SSE client worker survived cleanup"))
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (runner.isAlive) runner.interrupt()
                    if (!joinUntilCleanup(runner, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                        retainCleanup(TimeoutException("H2 server runner survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    closeDeferredResourcesIfSafe()
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                closeObservationFailure.get()?.let(::retainCleanup)
                driverCloseFailure.get()?.let(::retainCleanup)
                searchCloseFailure.get()?.let(::retainCleanup)
                cleanupFailure?.let { failure ->
                    if (primaryFailure == null) {
                        primaryFailure = failure
                    } else if (failure !== primaryFailure && requireNotNull(primaryFailure).suppressed.none { it === failure }) {
                        requireNotNull(primaryFailure).addSuppressed(failure)
                    }
                }
            }
            primaryFailure?.let { throw it }
        }
    }

    test("first and waiting stop callers preserve interruption through the shared completion barrier") {
        withLocalFixture { content, data ->
            val serverReady = CountDownLatch(1)
            val startRelease = CountDownLatch(1)
            val stopGapEntered = CountDownLatch(1)
            val stopGapRelease = CountDownLatch(1)
            val waiterEntered = CountDownLatch(1)
            val serverRef = AtomicReference<com.plainbase.frameworks.ktor.KtorServer>()
            val runResult = AtomicReference<Result<Int>>()
            val firstInterrupted = AtomicReference<Boolean>()
            val waiterInterrupted = AtomicReference<Boolean>()
            val firstFailure = AtomicReference<Throwable>()
            val waiterFailure = AtomicReference<Throwable>()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            val runner = startFixtureWorker("plainbase-stop-interruption-run") {
                runResult.set(
                    runCatching {
                        runServer(
                            localConfig(content, data, port = freePort()),
                            RecordingOutput(),
                            control = ServerRunControl(
                                onHttpAcquired = { server ->
                                    serverRef.set(server)
                                    server.beforeEngineStopForTest {
                                        stopGapEntered.countDown()
                                        check(
                                            stopGapRelease.await(
                                                remainingMillis(deadlineNanos),
                                                TimeUnit.MILLISECONDS,
                                            ),
                                        ) { "stop interruption control release timed out" }
                                    }
                                },
                                startServer = { server ->
                                    server.start(wait = false)
                                    serverReady.countDown()
                                    check(
                                        startRelease.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS),
                                    ) { "stop interruption start release timed out" }
                                },
                            ),
                        )
                    },
                )
            }
            var first: Thread? = null
            var waiter: Thread? = null
            var primaryFailure: Throwable? = null
            try {
                runner.start()
                check(serverReady.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                    "stop interruption server did not start"
                }
                val server = requireNotNull(serverRef.get())
                first = startFixtureWorker("plainbase-stop-first-caller") {
                    try {
                        server.stop()
                    } catch (failure: Throwable) {
                        firstFailure.set(failure)
                    } finally {
                        firstInterrupted.set(Thread.interrupted())
                    }
                }
                first.start()
                check(stopGapEntered.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                    "first stop caller did not reach engine stop"
                }
                waiter = startFixtureWorker("plainbase-stop-waiting-caller") {
                    waiterEntered.countDown()
                    try {
                        server.stop()
                    } catch (failure: Throwable) {
                        waiterFailure.set(failure)
                    } finally {
                        waiterInterrupted.set(Thread.interrupted())
                    }
                }
                waiter.start()
                check(waiterEntered.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                    "waiting stop caller did not enter"
                }
                first.interrupt()
                waiter.interrupt()
                stopGapRelease.countDown()
                joinUntil(first, deadlineNanos)
                joinUntil(waiter, deadlineNanos)
                first.isAlive shouldBe false
                waiter.isAlive shouldBe false
                firstFailure.get() shouldBe null
                waiterFailure.get() shouldBe null
                firstInterrupted.get() shouldBe true
                waiterInterrupted.get() shouldBe true
                startRelease.countDown()
                joinUntil(runner, deadlineNanos)
                runner.isAlive shouldBe false
                requireNotNull(runResult.get()).getOrThrow() shouldBe 0
            } catch (failure: Throwable) {
                if (failure is InterruptedException) rememberFixtureInterrupt()
                primaryFailure = failure
            } finally {
                stopGapRelease.countDown()
                startRelease.countDown()
                val cleanupDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var cleanupFailure: Throwable? = null
                fun retainCleanup(failure: Throwable) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure
                    } else if (failure !== cleanupFailure && requireNotNull(cleanupFailure).suppressed.none { it === failure }) {
                        requireNotNull(cleanupFailure).addSuppressed(failure)
                    }
                }
                try {
                    first?.let {
                        if (it.isAlive) it.interrupt()
                        if (!joinUntilCleanup(it, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            retainCleanup(TimeoutException("first stop caller survived cleanup"))
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    waiter?.let {
                        if (it.isAlive) it.interrupt()
                        if (!joinUntilCleanup(it, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                            retainCleanup(TimeoutException("waiting stop caller survived cleanup"))
                        }
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                try {
                    if (runner.isAlive) runner.interrupt()
                    if (!joinUntilCleanup(runner, cleanupDeadlineNanos, ::rememberFixtureInterrupt)) {
                        retainCleanup(TimeoutException("stop interruption server runner survived cleanup"))
                    }
                } catch (failure: Throwable) {
                    retainCleanup(failure)
                }
                cleanupFailure?.let { failure ->
                    if (primaryFailure == null) {
                        primaryFailure = failure
                    } else if (failure !== primaryFailure && requireNotNull(primaryFailure).suppressed.none { it === failure }) {
                        requireNotNull(primaryFailure).addSuppressed(failure)
                    }
                }
            }
            primaryFailure?.let { throw it }
        }
    }

    test("two sequential local runs own distinct contexts, stores, databases, and search instances") {
        val objectBefore = ObjectContentStore.constructions.get()
        val defaultOpeners = ServerOpeners()
        var firstDriver: Any? = null
        var secondDriver: Any? = null
        var firstSearch: Any? = null
        var secondSearch: Any? = null
        var firstStore: Any? = null
        var secondStore: Any? = null
        var firstContext: Any? = null
        var secondContext: Any? = null
        val firstCleanupFacts = Collections.synchronizedList(mutableListOf<Pair<String, Any>>())
        val secondCleanupFacts = Collections.synchronizedList(mutableListOf<Pair<String, Any>>())

        withLocalFixture { firstContent, firstData ->
            requireNoGlobalContext()
            runServerBounded {
                runServer(
                    localConfig(firstContent, firstData, port = freePort()),
                    RecordingOutput(),
                    openers = ServerOpeners(
                        openDriver = { path ->
                            requireNoGlobalContext()
                            defaultOpeners.openDriver(path).also { firstDriver = it }
                        },
                        openLocal = { inputs ->
                            requireNoGlobalContext()
                            defaultOpeners.openLocal(inputs).also { firstStore = it }
                        },
                        openSearch = { path ->
                            requireNoGlobalContext()
                            defaultOpeners.openSearch(path).also { firstSearch = it }
                        },
                    ),
                    control = observingControl(firstData) { kind, value ->
                        firstCleanupFacts += kind to value
                        if (kind == "context") firstContext = value
                    },
                )
            } shouldBe 0
            firstCleanupFacts.count { it.first.endsWith(".lockHeld") } shouldBe 3
            firstCleanupFacts.filter { it.first.endsWith(".lockHeld") }.all { it.second == true } shouldBe true
            firstCleanupFacts.count { it.first.contains("globalContext") } shouldBe 7
            firstCleanupFacts.filter { it.first.contains("globalContext") }.all { it.second == true } shouldBe true
            requireLockAvailable(firstData)
            requireNoGlobalContext()
        }
        GlobalContext.getOrNull() shouldBe null

        withLocalFixture { secondContent, secondData ->
            requireNoGlobalContext()
            runServerBounded {
                runServer(
                    localConfig(secondContent, secondData, port = freePort()),
                    RecordingOutput(),
                    openers = ServerOpeners(
                        openDriver = { path ->
                            requireNoGlobalContext()
                            defaultOpeners.openDriver(path).also { secondDriver = it }
                        },
                        openLocal = { inputs ->
                            requireNoGlobalContext()
                            defaultOpeners.openLocal(inputs).also { secondStore = it }
                        },
                        openSearch = { path ->
                            requireNoGlobalContext()
                            defaultOpeners.openSearch(path).also { secondSearch = it }
                        },
                    ),
                    control = observingControl(secondData) { kind, value ->
                        secondCleanupFacts += kind to value
                        if (kind == "context") secondContext = value
                    },
                )
            } shouldBe 0
            secondCleanupFacts.count { it.first.endsWith(".lockHeld") } shouldBe 3
            secondCleanupFacts.filter { it.first.endsWith(".lockHeld") }.all { it.second == true } shouldBe true
            secondCleanupFacts.count { it.first.contains("globalContext") } shouldBe 7
            secondCleanupFacts.filter { it.first.contains("globalContext") }.all { it.second == true } shouldBe true
            requireLockAvailable(secondData)
            requireNoGlobalContext()
        }

        firstDriver shouldNotBeSameInstanceAs secondDriver
        firstSearch shouldNotBeSameInstanceAs secondSearch
        firstStore shouldNotBeSameInstanceAs secondStore
        firstContext shouldNotBeSameInstanceAs secondContext
        GlobalContext.getOrNull() shouldBe null
        ObjectContentStore.constructions.get() shouldBe objectBefore
    }

    test("the observed cleanup Thread converges with normal return and removes its hook") {
        withLocalFixture { content, data ->
            val events = Collections.synchronizedList(mutableListOf<String>())
            val port = freePort()
            val startEntered = CountDownLatch(1)
            val allowReturn = CountDownLatch(1)
            val observedHook = AtomicReference<Thread>()
            val failure = AtomicReference<Throwable?>()
            var runner: Thread? = null
            var hookRunner: Thread? = null
            var primary: Throwable? = null
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                val serverRunner = startFixtureWorker("plainbase-server-runner") {
                    runCatching {
                        runServer(
                            localConfig(content, data, port = port),
                            RecordingOutput(events),
                            control = ServerRunControl(
                                startServer = {
                                    startEntered.countDown()
                                    check(allowReturn.await(START_CONTROL_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                                        "start release timed out"
                                    }
                                },
                                onHookInstalled = {
                                    observedHook.set(it)
                                    events += "hook"
                                },
                                closeDriver = { driver ->
                                    requireLockHeld(data)
                                    driver.close()
                                    events += "driver"
                                },
                                closeSearch = { search ->
                                    requireLockHeld(data)
                                    search.close()
                                    events += "search"
                                },
                                closeContext = { app ->
                                    requireLockHeld(data)
                                    app.close()
                                    events += "context"
                                },
                            ),
                        ).also { if (it != 0) error("unexpected status $it") }
                    }.onFailure(failure::set)
                }
                runner = serverRunner
                serverRunner.start()
                check(startEntered.await(START_CONTROL_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "start control was not entered"
                }
                val hook = requireNotNull(observedHook.get())
                val cleanupRunner = startFixtureWorker("plainbase-server-hook-runner") { hook.run() }
                hookRunner = cleanupRunner
                cleanupRunner.start()
                cleanupRunner.join(HOOK_OPERATION_WAIT_MILLIS)
                check(!cleanupRunner.isAlive) { "hook runner did not finish within ${HOOK_OPERATION_WAIT_MILLIS}ms" }
                allowReturn.countDown()
                serverRunner.join(RUN_COMPLETION_WAIT_MILLIS)
                check(!serverRunner.isAlive) { "server runner did not finish within ${RUN_COMPLETION_WAIT_MILLIS}ms" }
                failure.get() shouldBe null
                events.count { it == "driver" } shouldBe 1
                events.count { it == "search" } shouldBe 1
                events.count { it == "context" } shouldBe 1
                events += "stop-port-bound=${isPortBound(port)}"
                events.last() shouldBe "stop-port-bound=false"
                val messages = appender.list.map { it.formattedMessage }
                messages.none { it.contains("failed; continuing with the remaining steps") } shouldBe true
                messages.any { it.startsWith("shutdown complete in ") } shouldBe true
                Runtime.getRuntime().removeShutdownHook(hook) shouldBe false
                requireLockAvailable(data)
            } catch (caught: Throwable) {
                primary = caught
            } finally {
                allowReturn.countDown()
                val cleanupFailure = runCatching {
                    joinFixtureWorkers(
                        listOfNotNull(hookRunner, runner),
                        System.nanoTime() + IN_PROCESS_CALLER_CLEANUP_MILLIS * 1_000_000,
                    )
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    primary = primary?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure
                }
                root.detachAppender(appender)
            }
            primary?.let { throw it }
        }
    }

    test("a detached-root refusal closes acquired resources under the lock and then releases it") {
        withLocalFixture { content, data ->
            DatabaseFactory.createDriver(data.resolve("plainbase.db")).use { driver ->
                SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)).bind(
                    RootedPath(RootName.require("ghost"), TreePath.require("lost.md")),
                    PageId.require("01900000-0000-7000-8000-0000000000d1"),
                    materialized = true,
                )
            }
            val events = Collections.synchronizedList(mutableListOf<String>())
            val output = RecordingOutput(events)
            val searchOpens = AtomicInteger()
            val objectOpens = AtomicInteger()
            val searchCloses = AtomicInteger()
            val objectCloses = AtomicInteger()
            val defaults = ServerOpeners()
            val expectedDiagnostic =
                "serve: REFUSING TO SERVE: every page binding in this DATA_DIR belongs to root(s) absent from the " +
                    "configuration (bound: ghost; configured: docs). This DATA_DIR likely belongs to a different " +
                    "deployment, or the roots{} block was rewritten wholesale. Remedies, in order: (1) fix roots{} " +
                    "so the bound name(s) above are declared again (root names are permanent identifiers), or point " +
                    "DATA_DIR at the right directory; (2) if the removal is intentional and losing those roots' " +
                    "permalinks and old-URL redirects is accepted: back up DATA_DIR first, then delete only the " +
                    "detached rows, per root name, from the root-bearing tables - e.g. sqlite3 DATA_DIR/plainbase.db " +
                    "\"DELETE FROM id_map WHERE root='<name>'\" (repeat for retired_binding, url_alias, identity_issue, " +
                    "page_checkpoint, dirty_page, proposals, git_checkpoint, root_observation, and root_topology). " +
                    "Do NOT delete plainbase.db itself: it also holds users, sessions, API tokens, roles, proposals, " +
                    "and the audit log. NOTE: a detached root's PENDING/APPLYING proposals stay exactly as they are - " +
                    "they are never applied, never terminally failed, and never deleted - and they revive if the root's " +
                    "name returns to roots{}."
            val status = runServerBounded {
                runServer(
                    localConfig(content, data),
                    output,
                    openers = ServerOpeners(
                        openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                            objectOpens.incrementAndGet()
                            defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
                        },
                        openSearch = { path ->
                            searchOpens.incrementAndGet()
                            defaults.openSearch(path)
                        },
                    ),
                    control = ServerRunControl(
                        closeDriver = { driver ->
                            requireLockHeld(data)
                            driver.close()
                            events += "driver"
                        },
                        closeObject = { store ->
                            requireLockHeld(data)
                            store.close()
                            objectCloses.incrementAndGet()
                        },
                        closeSearch = { search ->
                            requireLockHeld(data)
                            search.close()
                            searchCloses.incrementAndGet()
                        },
                        closeContext = { app ->
                            requireLockHeld(data)
                            app.close()
                            events += "context"
                        },
                    ),
                )
            }

            status shouldBe 1
            output.errors.single() shouldBe expectedDiagnostic
            events.filter { it == "driver" || it == "context" }.shouldContainExactly("driver", "context")
            searchOpens.get() shouldBe 0
            objectOpens.get() shouldBe 0
            searchCloses.get() shouldBe 0
            objectCloses.get() shouldBe 0
            requireLockAvailable(data)
        }
    }

    test("object hydrate refusal maps the closed endpoint to the stable boot diagnostic") {
        withLocalFixture { content, data ->
            val port = freePort()
            val config = objectConfigFromEnv(content, data, endpointPort = port)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val closeLockFacts = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
            val output = RecordingOutput(events)
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                val status = runServerBounded {
                    runServer(
                        config,
                        output,
                        control = ServerRunControl(
                            closeDriver = { driver ->
                                closeLockFacts += "driver" to dataDirLockHeld(data)
                                try {
                                    driver.close()
                                } finally {
                                    events += "driver"
                                }
                            },
                            closeObject = { store ->
                                closeLockFacts += "object" to dataDirLockHeld(data)
                                try {
                                    store.close()
                                } finally {
                                    events += "object"
                                }
                            },
                            closeContext = { app ->
                                closeLockFacts += "context" to dataDirLockHeld(data)
                                try {
                                    app.close()
                                } finally {
                                    events += "context"
                                }
                            },
                        ),
                    )
                }
                status shouldBe 1
                output.errors.single() shouldContain "object storage endpoint is unreachable"
                events.count { it == "driver" } shouldBe 1
                events.count { it == "object" } shouldBe 1
                events.count { it == "context" } shouldBe 1
                closeLockFacts.map { it.first }.shouldContainExactly("object", "driver", "context")
                closeLockFacts.all { it.second } shouldBe true
                appender.list.any { it.formattedMessage == "serve object hydrate or bundle restore failed" } shouldBe true
                appender.list.any {
                    it.throwableProxy?.className == ObjectStoreException::class.java.name &&
                        it.throwableProxy?.message?.contains("object storage endpoint is unreachable") == true
                } shouldBe true
                requireLockAvailable(data)
            } finally {
                root.detachAppender(appender)
            }
        }
    }

    test("prepare refusal preserves its diagnostic and closes the lock-owned resources") {
        withLocalFixture { content, data ->
            Files.writeString(data.resolve("git-home"), "not a directory")
            val events = Collections.synchronizedList(mutableListOf<String>())
            val driverCloses = AtomicInteger()
            val contextCloses = AtomicInteger()
            val searchOpens = AtomicInteger()
            val objectOpens = AtomicInteger()
            val searchCloses = AtomicInteger()
            val objectCloses = AtomicInteger()
            val defaults = ServerOpeners()
            val output = RecordingOutput(events)
            val closeLockFacts = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                val status = runServerBounded {
                    runServer(
                        localConfig(content, data).copy(git = GitConfig(enabled = true)),
                        output,
                        openers = ServerOpeners(
                            openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                                objectOpens.incrementAndGet()
                                defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
                            },
                            openSearch = { path ->
                                searchOpens.incrementAndGet()
                                defaults.openSearch(path)
                            },
                        ),
                        control = ServerRunControl(
                            closeDriver = { driver ->
                                closeLockFacts += "driver" to dataDirLockHeld(data)
                                try {
                                    driver.close()
                                } finally {
                                    events += "driver"
                                }
                                driverCloses.incrementAndGet()
                            },
                            closeObject = { store ->
                                closeLockFacts += "object" to dataDirLockHeld(data)
                                try {
                                    store.close()
                                } finally {
                                    events += "object"
                                }
                                objectCloses.incrementAndGet()
                            },
                            closeSearch = { search ->
                                closeLockFacts += "search" to dataDirLockHeld(data)
                                try {
                                    search.close()
                                } finally {
                                    events += "search"
                                }
                                searchCloses.incrementAndGet()
                            },
                            closeContext = { app ->
                                closeLockFacts += "context" to dataDirLockHeld(data)
                                try {
                                    app.close()
                                } finally {
                                    events += "context"
                                }
                                contextCloses.incrementAndGet()
                            },
                        ),
                    )
                }
                status shouldBe 1
                output.errors.single() shouldContain "git-home"
                appender.list.any { it.formattedMessage == "serve history preparation failed" } shouldBe true
                driverCloses.get() shouldBe 1
                contextCloses.get() shouldBe 1
                closeLockFacts.map { it.first }.shouldContainExactly("driver", "context")
                closeLockFacts.all { it.second } shouldBe true
                searchOpens.get() shouldBe 0
                objectOpens.get() shouldBe 0
                searchCloses.get() shouldBe 0
                objectCloses.get() shouldBe 0
                events += "returned"
                (events.indexOf("driver") < events.indexOf("returned")) shouldBe true
                (events.indexOf("context") < events.indexOf("returned")) shouldBe true
                requireLockAvailable(data)
            } finally {
                root.detachAppender(appender)
            }
        }
    }

    test("a migration refusal escapes as the original failure and does not become an entry-owned close") {
        withLocalFixture { content, data ->
            val database = data.resolve("plainbase.db")
            DatabaseFactory.createDriver(database).use { }
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement -> statement.execute("PRAGMA user_version = 999") }
            }
            val migrationFailure = AtomicReference<Throwable?>()
            val driverCloses = AtomicInteger()
                val failure = shouldThrow<IllegalStateException> {
                    runServerBounded {
                        runServer(
                            localConfig(content, data, port = freePort()),
                            RecordingOutput(),
                            openers = ServerOpeners(
                                openDriver = { path ->
                                    try {
                                        DatabaseFactory.createDriver(path)
                                    } catch (thrown: Throwable) {
                                        migrationFailure.set(thrown)
                                        throw thrown
                                    }
                                },
                            ),
                            control = ServerRunControl(closeDriver = { driver ->
                                driverCloses.incrementAndGet()
                                driver.close()
                            }),
                        )
                    }
                }
            requireNotNull(migrationFailure.get()) shouldBeSameInstanceAs failure
            failure.message shouldContain "NEWER than this binary understands"
            driverCloses.get() shouldBe 0
            requireLockAvailable(data)
        }
    }

    test("an OBJECT run defers resource construction and refuses a held DATA_DIR lock without hydration") {
        val base = Files.createTempDirectory("plainbase-server-run-object")
        withFixtureScope(base) {
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("unused-content")
            val mirror = Files.createDirectory(data.resolve("mirror"))
            Files.writeString(mirror.resolve("sentinel.md"), "must remain untouched")
            val mirrorBefore = treeFingerprint(mirror)
            val endpointPort = freePort()
            val config = objectConfigFromEnv(content, data, endpointPort)
            val before = ObjectContentStore.constructions.get()
            val driverOpens = AtomicInteger()
            val driverCloses = AtomicInteger()
            val objectOpens = AtomicInteger()
            val objectCloses = AtomicInteger()
            val searchOpens = AtomicInteger()
            val searchCloses = AtomicInteger()
            val contextCloses = AtomicInteger()
            val defaults = ServerOpeners()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = runServerBounded {
                    runServer(
                        config,
                        RecordingOutput(),
                        openers = ServerOpeners(
                            openDriver = { path ->
                                driverOpens.incrementAndGet()
                                defaults.openDriver(path)
                            },
                            openObject = { cfg, ignore, dirty, isDirty, rows ->
                                objectOpens.incrementAndGet()
                                defaults.openObject(cfg, ignore, dirty, isDirty, rows)
                            },
                            openSearch = { path ->
                                searchOpens.incrementAndGet()
                                defaults.openSearch(path)
                            },
                        ),
                        control = ServerRunControl(
                            closeDriver = { driver ->
                                requireLockHeld(data)
                                driverCloses.incrementAndGet()
                                driver.close()
                            },
                            closeObject = { store ->
                                requireLockHeld(data)
                                objectCloses.incrementAndGet()
                                store.close()
                            },
                            closeSearch = { search ->
                                requireLockHeld(data)
                                searchCloses.incrementAndGet()
                                search.close()
                            },
                            closeContext = { app ->
                                requireLockHeld(data)
                                contextCloses.incrementAndGet()
                                app.close()
                            },
                        ),
                    )
                }
                status shouldBe 1
                driverOpens.get() shouldBe 0
                driverCloses.get() shouldBe 0
                objectOpens.get() shouldBe 0
                objectCloses.get() shouldBe 0
                searchOpens.get() shouldBe 0
                searchCloses.get() shouldBe 0
                contextCloses.get() shouldBe 1
                ObjectContentStore.constructions.get() shouldBe before
                Files.exists(data.resolve("plainbase.db")) shouldBe false
                treeFingerprint(mirror) shouldBe mirrorBefore
            } finally {
                lock.close()
            }
            requireLockAvailable(data)
        }
    }

    test("an opener exception crosses Koin without losing its original identity") {
        withLocalFixture { content, data ->
            val openerFailure = IllegalStateException("sentinel driver opener failure")
            val actual = shouldThrow<IllegalStateException> {
                runServerBounded {
                    runServer(
                        localConfig(content, data),
                        RecordingOutput(),
                        openers = ServerOpeners(openDriver = { throw openerFailure }),
                    )
                }
            }
            openerFailure shouldBeSameInstanceAs actual
            requireLockAvailable(data)
        }
    }

    test("a server-start failure remains primary while shared cleanup still runs") {
        withLocalFixture { content, data ->
            val startFailure = IllegalStateException("sentinel server start failure")
            val closeFailure = IllegalStateException("sentinel search close failure")
            val events = Collections.synchronizedList(mutableListOf<String>())
            val searchCloseActions = AtomicInteger()
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                val actual = shouldThrow<IllegalStateException> {
                    runServerBounded {
                        runServer(
                            localConfig(content, data),
                            RecordingOutput(events),
                            control = ServerRunControl(
                                startServer = { throw startFailure },
                                closeDriver = { driver ->
                                    requireLockHeld(data)
                                    events += "driver"
                                    driver.close()
                                },
                                closeSearch = { search ->
                                    requireLockHeld(data)
                                    search.close()
                                    searchCloseActions.incrementAndGet()
                                    events += "search"
                                    throw closeFailure
                                },
                                closeContext = { app ->
                                    requireLockHeld(data)
                                    events += "context"
                                    app.close()
                                },
                            ),
                        )
                    }
                }
                startFailure shouldBeSameInstanceAs actual
                events.count { it == "driver" } shouldBe 1
                events.count { it == "search" } shouldBe 1
                events.count { it == "context" } shouldBe 1
                searchCloseActions.get() shouldBe 1
                val loggedCloseFailure = appender.list
                    .firstOrNull { it.formattedMessage == "closing search database failed" }
                    ?.throwableProxy
                    ?.let { it as? ThrowableProxy }
                    ?.throwable
                closeFailure shouldBeSameInstanceAs loggedCloseFailure
                requireLockAvailable(data)
            } finally {
                root.detachAppender(appender)
            }
        }
    }

    test("a bounded run timeout stays primary when interruption lets its worker return") {
        withLocalFixture { _, _ ->
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val observedWorker = AtomicReference<Thread?>()
            val timeout = shouldThrow<TimeoutException> {
                runServerBounded(timeoutMillis = 100) {
                    observedWorker.set(Thread.currentThread())
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                        error("controlled bounded worker returned before interruption")
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                        "late success"
                    }
                }
            }

            entered.await(1, TimeUnit.SECONDS) shouldBe true
            interrupted.await(1, TimeUnit.SECONDS) shouldBe true
            timeout.message shouldContain "100ms"
            requireNotNull(observedWorker.get()).isAlive shouldBe false
        }
    }

    test("localSignalsDrainBeforeDetachedGuard") {
        withLocalFixture { content, data ->
            val database = data.resolve("plainbase.db")
            seedCurrentDatabase(database)
            seedObservation(database, 100L)
            val replacement = Files.createDirectory(content.parent.resolve("replacement"))
            Files.writeString(replacement.resolve("index.md"), "# Replacement\n")
            val retainedKey = requireNotNull(
                Files.readAttributes(
                    content,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey(),
            )
            val replacementKey = AtomicReference<Any?>()
            val localInputs = AtomicReference<LocalStoreInputs>()
            val observations = Collections.synchronizedList(mutableListOf<RootObservationWrite>())
            val driverRef = AtomicReference<ProductionOracleDriver>()
            val defaults = ServerOpeners()
            val runOpeners = ServerOpeners(
                openDriver = { path ->
                    val real = defaults.openDriver(path)
                    ProductionOracleDriver(real, RootName.PRIMARY, observations).also(driverRef::set)
                },
                openLocal = { inputs ->
                    localInputs.set(inputs)
                    val store = defaults.openLocal(inputs)
                    Files.move(inputs.root, content.parent.resolve("original-retained"))
                    Files.move(replacement, inputs.root)
                    replacementKey.set(
                        Files.readAttributes(inputs.root, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey(),
                    )
                    store
                },
            )
            val bootAvailability = AtomicReference<RootAvailability>()
            val runtimeContext = AtomicReference<RouteContext>()
            val status = runServerBounded {
                runServer(
                    localConfig(content, data),
                    RecordingOutput(),
                    runOpeners,
                    ServerRunControl(
                        onBootAvailability = bootAvailability::set,
                        onRuntimeContext = runtimeContext::set,
                        startServer = { server ->
                            val driver = requireNotNull(driverRef.get())
                            driver.detachedRootQueries.get() shouldBe 1
                            synchronized(driver.detachedObservationTokens) {
                                driver.detachedObservationTokens.toList()
                            } shouldContainExactly listOf(101L)
                            retainedKey shouldNotBe replacementKey.get()
                            val availability = requireNotNull(bootAvailability.get())
                            availability shouldBeSameInstanceAs requireNotNull(runtimeContext.get()).availability
                            availability.current().isAvailable(RootName.PRIMARY) shouldBe true

                            val before = synchronized(observations) { observations.size }
                            val invokingThread = Thread.currentThread()
                            requireNotNull(localInputs.get()).onIdentityRebind()
                            val writes = synchronized(observations) { observations.toList().drop(before) }
                            val matchingWrites = writes.filter { write ->
                                write.root == RootName.PRIMARY && write.thread === invokingThread
                            }
                            matchingWrites.size shouldBe 1
                            matchingWrites.single().root shouldBe RootName.PRIMARY
                            matchingWrites.single().thread shouldBeSameInstanceAs invokingThread
                            availability.current().isAvailable(RootName.PRIMARY) shouldBe true
                            server.start(wait = false)
                            server.stop()
                        },
                        closeDriver = { driver ->
                            requireLockHeld(data)
                            driver.close()
                        },
                        closeSearch = { search ->
                            requireLockHeld(data)
                            search.close()
                        },
                        closeContext = { app ->
                            requireLockHeld(data)
                            app.close()
                        },
                    ),
                )
            }

            status shouldBe 0
            driverRef.get()?.detachedRootQueries?.get() shouldBe 1
        }
    }

    test("missingExtraRetainsAvailabilityAcrossHandoff") {
        withLocalFixture { content, data ->
            val extra = content.parent.resolve("missing-extra")
            val extraName = RootName.require("extra")
            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5c")
            seedCurrentDatabase(data.resolve("plainbase.db"))
            seedBinding(data.resolve("plainbase.db"), extraName, TreePath.require("notes/rollback.md"), pageId)
            val roots = RootsConfig.of(
                listOf(
                    Root(RootName.PRIMARY, RootBackend.Local(content), editable = true, history = HistoryMode.OFF),
                    Root(extraName, RootBackend.Local(extra), editable = true, history = HistoryMode.OFF),
                ),
                origin = RootsOrigin.EXPLICIT,
            )
            val config = PlainbaseConfig(
                contentDir = content,
                dataDir = data,
                host = "127.0.0.1",
                port = freePort(),
                auth = AuthConfig(mode = AuthMode.BUILTIN),
                git = GitConfig(enabled = false),
                roots = roots,
            )
            val inputsByRoot = ConcurrentHashMap<RootName, LocalStoreInputs>()
            val defaults = ServerOpeners()
            val bootAvailability = AtomicReference<RootAvailability>()
            val runtimeContext = AtomicReference<RouteContext>()
            val watchedRoots = Collections.synchronizedList(mutableListOf<RootName>())
            val status = runServerBounded {
                runServer(
                    config,
                    RecordingOutput(),
                    openers = ServerOpeners(
                        openLocal = { inputs ->
                            inputsByRoot[inputs.rootName] = inputs
                            defaults.openLocal(inputs)
                        },
                    ),
                    control = ServerRunControl(
                        onBootAvailability = bootAvailability::set,
                        onRuntimeContext = runtimeContext::set,
                        onWatcherRegistration = watchedRoots::add,
                        startServer = { server ->
                            val boot = requireNotNull(bootAvailability.get())
                            val context = requireNotNull(runtimeContext.get())
                            boot shouldBeSameInstanceAs context.availability
                            boot.current().unavailable.getValue(extraName).cause shouldBe
                                com.plainbase.domain.root.UnavailableCause.MISSING_AT_BOOT
                            context.availability.current().unavailable.getValue(extraName).cause shouldBe
                                com.plainbase.domain.root.UnavailableCause.MISSING_AT_BOOT
                            watchedRoots shouldContainExactly listOf(RootName.PRIMARY)

                            server.start(wait = false)
                            try {
                                val minted = context.tokens.mint("missing-extra", AgentMode.READ_ONLY)
                                val response = HttpClient.newHttpClient().send(
                                    HttpRequest.newBuilder(
                                        URI("http://127.0.0.1:${config.port}/api/v1/pages/${pageId.value}"),
                                    ).header("Authorization", "Bearer ${minted.plaintext}").GET().build(),
                                    HttpResponse.BodyHandlers.ofString(),
                                )
                                response.statusCode() shouldBe 503
                                response.body() shouldContain "root_unavailable"

                                requireNotNull(inputsByRoot[RootName.PRIMARY]).onRootUnavailable()
                                boot.current().unavailable.getValue(RootName.PRIMARY).cause shouldBe
                                    com.plainbase.domain.root.UnavailableCause.VANISHED
                                requireNotNull(inputsByRoot[extraName]).onRootUnavailable()
                                boot.current().unavailable.getValue(extraName).cause shouldBe
                                    com.plainbase.domain.root.UnavailableCause.MISSING_AT_BOOT
                                context.availability shouldBeSameInstanceAs boot
                            } finally {
                                server.stop()
                            }
                        },
                        closeDriver = { driver ->
                            requireLockHeld(data)
                            driver.close()
                        },
                        closeSearch = { search ->
                            requireLockHeld(data)
                            search.close()
                        },
                        closeContext = { app ->
                            requireLockHeld(data)
                            app.close()
                        },
                    ),
                )
            }
            status shouldBe 0
        }
    }

    test("objectHistoryCallbacksReadyBeforeRestore") {
        withLocalFixture { content, data ->
            seedCurrentDatabase(data.resolve("plainbase.db"))
            val mirror = Files.createDirectories(data.resolve("mirror"))
            Files.exists(mirror.resolve(".git")) shouldBe false
            val beforeWorkers = liveDrWorkers()
            val output = RecordingOutput()
            val driverOpens = AtomicInteger()
            val objectOpens = AtomicInteger()
            val objectCloses = AtomicInteger()
            val driverCloses = AtomicInteger()
            val contextCloses = AtomicInteger()
            val driverAcquiredWithLock = AtomicReference<Boolean?>()
            val objectAcquiredWithLock = AtomicReference<Boolean?>()
            val defaults = ServerOpeners()
            val status = runServerBounded {
                runServer(
                    objectConfigFromEnv(content, data, endpointPort = freePort(), gitEnabled = true),
                    output,
                    openers = ServerOpeners(
                        openDriver = { path ->
                            driverOpens.incrementAndGet()
                            driverAcquiredWithLock.set(dataDirLockHeld(data))
                            defaults.openDriver(path)
                        },
                        openObject = { config, ignore, dirty, isDirty, rows ->
                            objectOpens.incrementAndGet()
                            objectAcquiredWithLock.set(dataDirLockHeld(data))
                            defaults.openObject(config, ignore, dirty, isDirty, rows)
                        },
                    ),
                    control = ServerRunControl(
                        closeDriver = { driver ->
                            requireLockHeld(data)
                            driverCloses.incrementAndGet()
                            driver.close()
                        },
                        closeObject = { store ->
                            requireLockHeld(data)
                            objectCloses.incrementAndGet()
                            store.close()
                        },
                        closeContext = { app ->
                            requireLockHeld(data)
                            contextCloses.incrementAndGet()
                            app.close()
                        },
                    ),
                )
            }
            val survivingWorkers = liveDrWorkers() - beforeWorkers
            val diagnostics = synchronized(output.errors) { output.errors.toList() }
            status shouldBe 1
            survivingWorkers shouldBe emptySet()
            driverOpens.get() shouldBe 1
            objectOpens.get() shouldBe 1
            driverAcquiredWithLock.get() shouldBe true
            objectAcquiredWithLock.get() shouldBe true
            driverCloses.get() shouldBe 1
            objectCloses.get() shouldBe 1
            contextCloses.get() shouldBe 1
            diagnostics.single() shouldContain "object storage endpoint is unreachable"
            diagnostics.single().contains("object history callbacks are not armed") shouldBe false
        }
    }
})

private class RecordingOutput(
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf()),
) : CommandOutput {
    val errors = Collections.synchronizedList(mutableListOf<String>())

    override fun result(text: String, newline: Boolean) = Unit

    override fun error(text: String) {
        errors += text
        timeline += "error:$text"
    }

    override fun intent(event: WriteIntent) = Unit
}

private class LogTimelineAppender(
    private val timeline: MutableList<String>,
) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        timeline += "${event.level.levelStr.lowercase()}:${event.formattedMessage}"
    }
}

private const val START_CONTROL_WAIT_MILLIS = 10_000L
private const val HOOK_OPERATION_WAIT_MILLIS = 15_000L
private const val RUN_COMPLETION_WAIT_MILLIS = 15_000L
private const val NATURAL_RETURN_TEST_DEADLINE_MILLIS = 10_000L
private const val IN_PROCESS_RUN_DEADLINE_MILLIS = 30_000L
private const val IN_PROCESS_CALLER_CLEANUP_MILLIS = 10_000L
private const val H1_PARENT_CEILING_MILLIS = 60_000L
private const val H1_WARNING_OVERRUN_MILLIS = 8_500L
private const val H1_POLL_MILLIS = 100L
private const val H1_CLEANUP_MILLIS = 10_000L
private const val H2_PARENT_CEILING_MILLIS = 60_000L
private const val H2_ENGINE_CANCEL_WAIT_MILLIS = 5_000L
private const val H2_WARNING_OVERRUN_MILLIS = 8_500L
private const val H2_POLL_MILLIS = 100L
private const val H2_CLEANUP_MILLIS = 10_000L

private fun remainingMillis(deadlineNanos: Long): Long {
    val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
    check(remaining > 0L) { "bounded H1 parent deadline expired" }
    return remaining
}

private fun localConfig(content: Path, data: Path, port: Int = freePort()): PlainbaseConfig = PlainbaseConfig(
    contentDir = content,
    dataDir = data,
    host = "127.0.0.1",
    port = port,
    git = GitConfig(enabled = false),
)

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

private fun RouteContext.withProposals(proposals: ProposalFacade): RouteContext =
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

private data class RawHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
)

private fun openRawHttpSocket(port: Int): Socket = Socket().also {
    it.soTimeout = 5_000
    it.connect(InetSocketAddress("127.0.0.1", port), 2_000)
}

private fun rawHttpGet(socket: Socket): RawHttpResponse {
    socket.getOutputStream().write(
        "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
    )
    socket.getOutputStream().flush()
    return readRawHttpResponse(socket)
}

private fun writeIncompletePut(socket: Socket, pageId: String, token: String, etag: String) {
    val prefix = "---\nid: $pageId\ntitle: Held\n---\n\n# Held\n".toByteArray(StandardCharsets.UTF_8)
    socket.getOutputStream().write(
        (
            "PUT /api/v1/pages/$pageId HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Authorization: Bearer $token\r\n" +
                "If-Match: $etag\r\n" +
                "Content-Type: text/markdown\r\n" +
                "Content-Length: 4096\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII),
    )
    socket.getOutputStream().write(prefix)
    socket.getOutputStream().flush()
}

private fun readRawHttpResponse(socket: Socket): RawHttpResponse {
    val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
    val status = reader.readLine() ?: error("raw HTTP response ended before status line")
    val headers = buildMap {
        while (true) {
            val line = reader.readLine() ?: error("raw HTTP response ended before headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            require(separator > 0) { "malformed raw HTTP header: $line" }
            put(line.substring(0, separator).lowercase(), line.substring(separator + 1).trim())
        }
    }
    val length = headers["content-length"]?.toIntOrNull() ?: error("raw HTTP response has no Content-Length")
    val body = CharArray(length)
    var offset = 0
    while (offset < length) {
        val read = reader.read(body, offset, length - offset)
        if (read < 0) error("raw HTTP response ended before its Content-Length")
        offset += read
    }
    return RawHttpResponse(status.substringAfter(' ').substringBefore(' ').toInt(), headers, String(body))
}

private fun objectConfigFromEnv(
    content: Path,
    data: Path,
    endpointPort: Int,
    port: Int = freePort(),
    gitEnabled: Boolean = false,
): PlainbaseConfig =
    ConfigLoader.fromEnv(
        mapOf(
            "CONTENT_DIR" to content.toString(),
            "DATA_DIR" to data.toString(),
            "PLAINBASE_STORAGE_BACKEND" to "object",
            "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:$endpointPort",
            "PLAINBASE_S3_BUCKET" to "docs",
            "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
            "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
            "PLAINBASE_GIT_ENABLED" to gitEnabled.toString(),
            "PLAINBASE_HOST" to "127.0.0.1",
            "PLAINBASE_PORT" to port.toString(),
        ),
    )

private class FixtureWorkers {
    private val active = ConcurrentHashMap.newKeySet<Thread>()
    private val observed = ConcurrentHashMap<String, () -> Boolean>()

    fun register(worker: Thread) {
        active += worker
    }

    fun unregister(worker: Thread) {
        active -= worker
    }

    fun registerSurvivor(name: String, predicate: () -> Boolean) {
        observed[name] = predicate
    }

    fun awaitQuiescence(deadline: Long, onInterrupt: () -> Unit = {}): Boolean {
        while (hasSurvivors() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                onInterrupt()
            }
        }
        return !hasSurvivors()
    }

    fun survivors(): String = buildList {
        addAll(active.filter { it.isAlive }.map { "${it.name}(${it.state})" })
        observed.forEach { (name, predicate) ->
            try {
                if (predicate()) add(name)
            } catch (failure: Throwable) {
                add("$name(observation failed: ${failure.message})")
            }
        }
    }.joinToString()

    private fun hasSurvivors(): Boolean = active.any { it.isAlive } || observed.values.any { predicate ->
        runCatching { predicate() }.getOrDefault(true)
    }
}

private val fixtureWorkers = ThreadLocal<FixtureWorkers?>()
private val fixtureInterruptHandler = ThreadLocal<(() -> Unit)?>()

private fun registerFixtureSurvivor(name: String, predicate: () -> Boolean) {
    fixtureWorkers.get()?.registerSurvivor(name, predicate)
}

private fun rememberFixtureInterrupt() {
    fixtureInterruptHandler.get()?.invoke()
}

private fun startFixtureWorker(name: String, block: () -> Unit): Thread {
    val workers = fixtureWorkers.get()
    val worker = thread(start = false, name = name) {
        try {
            block()
        } finally {
            workers?.unregister(Thread.currentThread())
        }
    }
    workers?.register(worker)
    return worker
}

private fun <T> runServerBounded(
    timeoutMillis: Long = IN_PROCESS_RUN_DEADLINE_MILLIS,
    block: () -> T,
): T {
    val workers = fixtureWorkers.get()
    val result = AtomicReference<Result<T>?>(null)
    val worker = startFixtureWorker("plainbase-server-bounded") {
        result.set(runCatching(block))
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    try {
        worker.start()
        joinUntil(worker, deadline)
        val timedOut = worker.isAlive
        if (timedOut) {
            worker.interrupt()
            joinUntil(worker, System.nanoTime() + IN_PROCESS_CALLER_CLEANUP_MILLIS * 1_000_000)
        }
        if (timedOut) {
            val timeout = TimeoutException("bounded server run exceeded ${timeoutMillis}ms")
            result.get()?.exceptionOrNull()?.let(timeout::addSuppressed)
            if (worker.isAlive) {
                timeout.addSuppressed(
                    IllegalStateException("bounded server run left a surviving caller: ${worker.name}(${worker.state})"),
                )
            }
            throw timeout
        }
        return requireNotNull(result.get()) { "bounded server run completed without a result" }.getOrThrow()
    } finally {
        if (!worker.isAlive) workers?.unregister(worker)
    }
}

private fun joinFixtureWorkers(callers: List<Thread>, deadline: Long) {
    callers.forEach { joinUntil(it, deadline) }
    val survivors = callers.filter { it.isAlive }.joinToString { "${it.name}(${it.state})" }
    check(survivors.isEmpty()) { "surviving in-process caller(s) before fixture cleanup: $survivors" }
}

private fun joinUntil(worker: Thread, deadline: Long) {
    while (worker.isAlive && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
    }
}

private fun joinUntilCleanup(worker: Thread, deadline: Long, onInterrupt: () -> Unit): Boolean {
    while (worker.isAlive && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        try {
            worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
        } catch (_: InterruptedException) {
            onInterrupt()
        }
    }
    return !worker.isAlive
}

private fun awaitCleanup(latch: CountDownLatch, deadline: Long, onInterrupt: () -> Unit): Boolean {
    while (latch.count != 0L && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        try {
            latch.await(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            onInterrupt()
        }
    }
    return latch.count == 0L
}

private fun withLocalFixture(block: (content: Path, data: Path) -> Unit) {
    val base = Files.createTempDirectory("plainbase-server-run")
    withFixtureScope(base) {
        val content = Files.createDirectory(base.resolve("content"))
        val data = Files.createDirectory(base.resolve("data"))
        Files.writeString(content.resolve("readme.md"), "---\ntitle: Readme\n---\n\n# Readme\n")
        block(content, data)
    }
}

private fun withFixtureScope(base: Path, block: () -> Unit) {
    val workers = FixtureWorkers()
    val previous = fixtureWorkers.get()
    val previousInterruptHandler = fixtureInterruptHandler.get()
    var interrupted = false
    fixtureWorkers.set(workers)
    fixtureInterruptHandler.set { interrupted = true }
    var primary: Throwable? = null
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is InterruptedException) interrupted = true
        primary = failure
    } finally {
        val quiesced = runCatching {
            workers.awaitQuiescence(
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IN_PROCESS_CALLER_CLEANUP_MILLIS),
            ) { interrupted = true }
        }.getOrDefault(false)
        if (quiesced) {
            base.toFile().deleteRecursively()
        } else {
            val cleanupFailure = IllegalStateException(
                "fixture cleanup skipped while test callers remain: ${workers.survivors()}",
            )
            primary = primary?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure
        }
        if (previous == null) fixtureWorkers.remove() else fixtureWorkers.set(previous)
        if (previousInterruptHandler == null) fixtureInterruptHandler.remove() else fixtureInterruptHandler.set(previousInterruptHandler)
        if (interrupted) Thread.currentThread().interrupt()
    }
    primary?.let { throw it }
}

private fun observingControl(data: Path, observer: (kind: String, value: Any) -> Unit): ServerRunControl =
    ServerRunControl(
        startServer = { observer("start.globalContext", GlobalContext.getOrNull() == null) },
        closeDriver = { driver ->
            observer("driver.lockHeld", dataDirLockHeld(data))
            observer("driver.globalContext.before", GlobalContext.getOrNull() == null)
            try {
                driver.close()
            } finally {
                observer("driver", driver)
                observer("driver.globalContext.after", GlobalContext.getOrNull() == null)
            }
        },
        closeSearch = { search ->
            observer("search.lockHeld", dataDirLockHeld(data))
            observer("search.globalContext.before", GlobalContext.getOrNull() == null)
            try {
                search.close()
            } finally {
                observer("search", search)
                observer("search.globalContext.after", GlobalContext.getOrNull() == null)
            }
        },
        closeObject = { store ->
            observer("object.lockHeld", dataDirLockHeld(data))
            observer("object.globalContext.before", GlobalContext.getOrNull() == null)
            try {
                store.close()
            } finally {
                observer("object", store)
                observer("object.globalContext.after", GlobalContext.getOrNull() == null)
            }
        },
        closeContext = { app ->
            observer("context.lockHeld", dataDirLockHeld(data))
            observer("context.globalContext.before", GlobalContext.getOrNull() == null)
            try {
                app.close()
            } finally {
                observer("context", app)
                observer("context.globalContext.after", GlobalContext.getOrNull() == null)
            }
        },
    )

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun isPortBound(port: Int): Boolean =
    runCatching { ServerSocket(port).use { } }.isFailure

private fun requireNoGlobalContext() {
    GlobalContext.getOrNull() shouldBe null
}

private fun requireLockHeld(data: Path) {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt != null) {
        attempt.close()
        error("DATA_DIR lock was released before resource cleanup")
    }
}

private fun dataDirLockHeld(data: Path): Boolean {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt == null) return true
    attempt.close()
    return false
}

private fun requireLockAvailable(data: Path) {
    val attempt = DataDirLock.tryAcquire(data) ?: error("DATA_DIR lock was not released")
    attempt.close()
}

private fun treeFingerprint(root: Path): String? {
    if (!Files.exists(root)) return null
    val digest = MessageDigest.getInstance("SHA-256")
    Files.walk(root).use { paths ->
        paths.filter { it != root }.sorted().forEach { path ->
            digest.update(root.relativize(path).toString().toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(if (Files.isDirectory(path)) 0.toByte() else 1.toByte()))
            if (Files.isRegularFile(path)) digest.update(Files.readAllBytes(path))
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun seedCurrentDatabase(path: Path) {
    DatabaseFactory.createDriver(path).use { driver ->
        val database = DatabaseFactory.createDatabase(driver)
        database.rootObservationQueries.upsertObservation(root = RootName.PRIMARY, observationId = 100L)
    }
}

private fun seedObservation(path: Path, observationId: Long) {
    DatabaseFactory.createDriver(path).use { driver ->
        DatabaseFactory.createDatabase(driver).rootObservationQueries.upsertObservation(
            root = RootName.PRIMARY,
            observationId = observationId,
        )
    }
}

private fun seedBinding(path: Path, root: RootName, pathInRoot: TreePath, id: PageId) {
    DatabaseFactory.createDriver(path).use { driver ->
        SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)).bind(
            RootedPath(root, pathInRoot),
            id,
            materialized = false,
        )
    }
}

private data class RootObservationWrite(val root: RootName, val observationId: Long?, val thread: Thread)

private class ProductionOracleDriver(
    private val delegateDriver: SqlDriver,
    private val detachedRoot: RootName,
    val observationWrites: MutableList<RootObservationWrite>,
) : SqlDriver by delegateDriver {
    val detachedRootQueries = AtomicInteger()
    val detachedObservationTokens = Collections.synchronizedList(mutableListOf<Long?>())

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val normalized = normalizeSql(sql)
        val boundRoot = AtomicReference<String?>()
        val observingWrite = normalized.startsWith("INSERT INTO ROOT_OBSERVATION")
        val wrappedBinders = if (observingWrite && binders != null) {
            {
                val delegateStatement = this
                val observingStatement = object : SqlPreparedStatement by delegateStatement {
                    override fun bindString(index: Int, string: String?) {
                        if (index == 0) boundRoot.set(string)
                        delegateStatement.bindString(index, string)
                    }
                }
                binders.invoke(observingStatement)
            }
        } else {
            binders
        }
        val result = delegateDriver.execute(identifier, sql, parameters, wrappedBinders)
        if (observingWrite) {
            val root = RootName.require(requireNotNull(boundRoot.get()) { "root_observation write did not bind root" })
            val observation = readObservation(delegateDriver, root)
            synchronized(observationWrites) {
                observationWrites += RootObservationWrite(root, observation, Thread.currentThread())
            }
        }
        return result
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (normalizeSql(sql) == "SELECT DISTINCT ROOT FROM ID_MAP") {
            detachedRootQueries.incrementAndGet()
            synchronized(detachedObservationTokens) {
                detachedObservationTokens += readObservation(delegateDriver, detachedRoot)
            }
        }
        return delegateDriver.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

private fun readObservation(driver: SqlDriver, root: RootName): Long? = driver.executeQuery(
    identifier = null,
    sql = "SELECT observation_id FROM root_observation WHERE root = ?",
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
    parameters = 1,
    binders = { bindString(0, root.value) },
).value

private fun normalizeSql(sql: String): String = sql.replace(Regex("\\s+"), " ").trim().uppercase()

private fun liveDrWorkers(): Set<Thread> = Thread.getAllStackTraces().keys
    .filter { it.isAlive && it.name in setOf("plainbase-bundle-dr-ship", "plainbase-bundle-dr-cadence") }
    .toSet()
