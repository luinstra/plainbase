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
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.cli.CommandOutput
import com.plainbase.frameworks.cli.WriteIntent
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectStoreException
import com.plainbase.frameworks.runtime.LocalStoreInputs
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** In-process proof of the serving runtime seam: gates, ownership, cleanup, and primary-failure preservation. */
class ServerRunTest : FunSpec({

    test("config warnings are emitted before a bind refusal and refusal cleanup closes the isolated context") {
        withLocalFixture { content, data ->
            val nestedContent = Files.createDirectory(data.resolve("content"))
            Files.writeString(nestedContent.resolve("readme.md"), "---\ntitle: Readme\n---\n\n# Readme\n")
            val timeline = Collections.synchronizedList(mutableListOf<String>())
            val output = RecordingOutput(timeline)
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = TimelineAppender(timeline).apply { start() }
            root.addAppender(appender)
            val contextCloses = AtomicInteger()
            val opens = AtomicInteger()
            val defaults = ServerOpeners()
            try {
                val config = PlainbaseConfig(
                    contentDir = nestedContent,
                    dataDir = data,
                    host = "0.0.0.0",
                    port = 0,
                    auth = AuthConfig(),
                    git = GitConfig(enabled = false),
                )
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
                val expectedWarning =
                    "warn:roots.docs (${nestedContent.toRealPath()}) is INSIDE DATA_DIR (${data.toRealPath()}). This serves " +
                        "correctly, but DATA_DIR is app-owned state whose contents are routinely wiped and rebuilt " +
                        "(`search.db` and the object mirror are explicitly disposable) - a wipe here takes this root's " +
                        "content with it. Move the root outside DATA_DIR."
                timeline.count { it == expectedWarning } shouldBe 1
                val warningIndex = timeline.indexOf(expectedWarning)
                val errorIndex = timeline.indexOfFirst { it.startsWith("error:serve:") }
                (warningIndex >= 0) shouldBe true
                (errorIndex >= 0) shouldBe true
                (warningIndex < errorIndex) shouldBe true
                timeline.count { it == "context-close-complete" } shouldBe 1
                timeline += "returned"
                (timeline.indexOf("context-close-complete") < timeline.indexOf("returned")) shouldBe true
            } finally {
                root.detachAppender(appender)
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

private class TimelineAppender(
    private val timeline: MutableList<String>,
) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        timeline += "warn:${event.formattedMessage}"
    }
}

private const val START_CONTROL_WAIT_MILLIS = 10_000L
private const val HOOK_OPERATION_WAIT_MILLIS = 15_000L
private const val RUN_COMPLETION_WAIT_MILLIS = 15_000L
private const val NATURAL_RETURN_TEST_DEADLINE_MILLIS = 10_000L
private const val IN_PROCESS_RUN_DEADLINE_MILLIS = 30_000L
private const val IN_PROCESS_CALLER_CLEANUP_MILLIS = 10_000L

private fun localConfig(content: Path, data: Path, port: Int = freePort()): PlainbaseConfig = PlainbaseConfig(
    contentDir = content,
    dataDir = data,
    host = "127.0.0.1",
    port = port,
    git = GitConfig(enabled = false),
)

private fun objectConfigFromEnv(
    content: Path,
    data: Path,
    endpointPort: Int,
    port: Int = freePort(),
    gitEnabled: Boolean = false,
): PlainbaseConfig =
    PlainbaseConfig.fromEnv(
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

    fun register(worker: Thread) {
        active += worker
    }

    fun unregister(worker: Thread) {
        active -= worker
    }

    fun awaitQuiescence(deadline: Long): Boolean {
        while (active.any { it.isAlive } && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        return active.none { it.isAlive }
    }

    fun survivors(): String = active.filter { it.isAlive }.joinToString { "${it.name}(${it.state})" }
}

private val fixtureWorkers = ThreadLocal<FixtureWorkers?>()

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
    fixtureWorkers.set(workers)
    var primary: Throwable? = null
    try {
        block()
    } catch (failure: Throwable) {
        primary = failure
    } finally {
        if (previous == null) fixtureWorkers.remove() else fixtureWorkers.set(previous)
        val quiesced = runCatching {
            workers.awaitQuiescence(System.nanoTime() + IN_PROCESS_CALLER_CLEANUP_MILLIS * 1_000_000)
        }.getOrDefault(false)
        if (quiesced) {
            base.toFile().deleteRecursively()
        } else {
            val cleanupFailure = IllegalStateException(
                "fixture cleanup skipped while test callers remain: ${workers.survivors()}",
            )
            primary = primary?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure
        }
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
