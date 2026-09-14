package com.plainbase

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.cli.CommandOutput
import com.plainbase.frameworks.cli.WriteIntent
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.FileWatcher
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.BeginImmediateSqliteDriver
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinApplication
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual serve-composition acquisition and partial-startup ownership boundaries for Stage0c O2. */
class ServerRunOwnershipTest : FunSpec({

    test("post-return app SQL failure closes the acquired driver and releases the lock") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("post-return session prune failure")
            val acquired = AtomicReference<SqlDriver?>()
            val connection = AtomicReference<Connection?>()
            val closeCount = AtomicInteger()
            val closeLockHeld = AtomicReference<Boolean?>()
            val contextCloseCount = AtomicInteger()
            val output = OwnershipOutput()
            val defaults = ServerOpeners()
            val driverClose = OnceCloser<SqlDriver>({ it.close() }) { connection.get()?.isClosed == true }
            val captured = OwnedResourceCapture()

            val actual = shouldThrow<Throwable> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data),
                        output,
                        openers = ServerOpeners(
                            openDriver = { path ->
                                DatabaseFactory.createDriver(path).also { driver ->
                                    acquired.set(driver)
                                    connection.set((driver as BeginImmediateSqliteDriver).getConnection())
                                }.let { driver ->
                                    SqlFailureDriver(driver, failure).also { wrapped ->
                                        registerOwnershipHandle("driver", wrapped, { driverClose.invoke(wrapped) }) {
                                            driverClose.completed(wrapped)
                                        }
                                    }
                                }
                            },
                            openLocal = defaults.openLocal,
                        ),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            closeDriver = { driver ->
                                closeLockHeld.set(dataDirLockHeldForOwnership(data))
                                closeCount.incrementAndGet()
                                driverClose.invoke(driver)
                            },
                            closeContext = { app ->
                                contextCloseCount.incrementAndGet()
                                captured.closeContext(app)
                            },
                        ),
                    )
                }
            }

            allCauses(actual).any { it === failure } shouldBe true
            requireNotNull(acquired.get())
            connection.get()?.isClosed shouldBe true
            closeCount.get() shouldBe 1
            contextCloseCount.get() shouldBe 1
            captured.contextCloseCompletedForTest() shouldBe true
            closeLockHeld.get() shouldBe true
            requireLockAvailableForOwnership(data)
        }
    }

    test("route-context assembly failure closes the actual acquired SearchDb") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("route context assembly failure")
            val searchCloseCount = AtomicInteger()
            val searchCloseLockHeld = AtomicReference<Boolean?>()
            val contextCloseCount = AtomicInteger()
            val routeContextSeen = AtomicInteger()
            val runtimeContextSeen = AtomicInteger()
            val output = OwnershipOutput()
            val defaults = ServerOpeners()
            val captured = OwnedResourceCapture(defaults)

            val actual = shouldThrow<Throwable> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            buildRouteContext = {
                                routeContextSeen.incrementAndGet()
                                throw failure
                            },
                            onRuntimeContext = { runtimeContextSeen.incrementAndGet() },
                            closeSearch = { value ->
                                searchCloseLockHeld.set(dataDirLockHeldForOwnership(data))
                                searchCloseCount.incrementAndGet()
                                captured.closeSearch(value)
                            },
                            closeContext = { app ->
                                contextCloseCount.incrementAndGet()
                                captured.closeContext(app)
                            },
                        ),
                    )
                }
            }

            allCauses(actual).any { it === failure } shouldBe true
            requireNotNull(captured.search.get())
            searchCloseCount.get() shouldBe 1
            searchCloseLockHeld.get() shouldBe true
            contextCloseCount.get() shouldBe 1
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            routeContextSeen.get() shouldBe 1
            runtimeContextSeen.get() shouldBe 0
            requireLockAvailableForOwnership(data)
        }
    }

    test("search cleanup observes every retained JDBC connection") {
        withOwnershipFixture { _, data ->
            val captured = OwnedResourceCapture()
            boundedOwnershipRun {
                val search = captured.openers().openSearch(data.resolve("search.db"))

                captured.searchConnectionsClosedForTest() shouldBe false
                captured.closeSearch(search)
                captured.normalSearchConnectionsClosedForTest() shouldBe true
                captured.searchFallbackConnectionsClosedForTest() shouldBe true
                captured.searchConnectionsClosedForTest() shouldBe true
                captured.searchClose.completed(search) shouldBe true
            }
        }
    }

    test("search cleanup preserves a retained-reader omission after safe fallback") {
        val base = Files.createTempDirectory("plainbase-ownership-search-omission")
        val data = Files.createDirectory(base.resolve("data"))
        val fixture = OwnershipFixtureRecord(base)
        val normalObservedBeforeFallback = AtomicReference<Boolean?>()
        val captured = OwnedResourceCapture(
            searchCloseDelegate = { _, connections ->
                connections.first().close()
                normalObservedBeforeFallback.set(connections.all { it.isClosed })
            },
        )
        currentOwnershipFixture.set(fixture)
        try {
            boundedOwnershipRun {
                val search = captured.openers().openSearch(data.resolve("search.db"))

                captured.closeSearch(search)
                normalObservedBeforeFallback.get() shouldBe false
                captured.normalSearchConnectionsClosedForTest() shouldBe false
                captured.searchFallbackConnectionsClosedForTest() shouldBe true
                captured.searchConnectionsClosedForTest() shouldBe true
                captured.searchClose.completed(search) shouldBe false
            }
            requireNotNull(fixture.cleanup()).message shouldContain "owned search did not prove completion"
            fixture.canDelete() shouldBe false
            captured.searchConnectionsClosedForTest() shouldBe true
        } finally {
            currentOwnershipFixture.remove()
            fixture.cleanup()
            captured.searchConnectionsClosedForTest() shouldBe true
            base.toFile().deleteRecursively()
        }
    }

    test("a later watcher registration failure drains the earlier watcher and transferred alarm") {
        withOwnershipFixture { content, data ->
            val extra = Files.createDirectory(content.parent.resolve("extra-root"))
            Files.writeString(extra.resolve("extra.md"), "---\ntitle: Extra\n---\n\n# Extra\n")
            val extraName = RootName.require("extra")
            val config = PlainbaseConfig(
                contentDir = content,
                dataDir = data,
                host = "127.0.0.1",
                port = 0,
                git = GitConfig(enabled = false),
                roots = RootsConfig.of(
                    listOf(
                        Root(RootName.PRIMARY, RootBackend.Local(content), editable = true, history = HistoryMode.OFF),
                        Root(extraName, RootBackend.Local(extra), editable = true, history = HistoryMode.OFF),
                    ),
                    origin = RootsOrigin.EXPLICIT,
                ),
            )
            val registrations = Collections.synchronizedList(mutableListOf<RootName>())
            val acquired = Collections.synchronizedList(mutableListOf<AutoCloseable>())
            val closeEvents = Collections.synchronizedList(mutableListOf<String>())
            val watcherCompletionAtClose = AtomicReference<Boolean?>()
            val watcherCompletionAtSchedulerStart = AtomicReference<Boolean?>()
            val watcherClose = OnceCloser<AutoCloseable>({ it.close() }) {
                (it as? FileWatcher)?.isClosedForTest() == true
            }
            val schedulerLockHeld = AtomicReference<Boolean?>()
            val alarm = RecordingAlarm(closeEvents) {
                schedulerLockHeld.set(dataDirLockHeldForOwnership(data))
                watcherCompletionAtSchedulerStart.set(acquired.firstOrNull()?.let(watcherClose::completed))
            }
            val output = OwnershipOutput()
            val captured = OwnedResourceCapture()
            val actual = shouldThrow<IllegalStateException> {
                boundedOwnershipRun {
                    runServer(
                        config,
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onWatcherRegistration = { root ->
                                registrations += root
                                if (root == extraName) throw IllegalStateException("watcher N registration failed")
                            },
                            onWatcherAcquired = { _, watcher ->
                                acquired += watcher
                                registerOwnershipHandle("watcher", watcher, { watcherClose.invoke(watcher) }) {
                                    watcherClose.completed(watcher)
                                }
                            },
                            createScheduler = { builder ->
                                registerOwnershipHandle("scheduler", alarm, alarm::close, alarm::isClosedForTest)
                                RebuildScheduler(rebuild = { builder.rebuild() }, alarm = alarm)
                            },
                            closeWatcher = { watcher ->
                                try {
                                    watcherClose.invoke(watcher)
                                } finally {
                                    watcherCompletionAtClose.set(watcherClose.completed(watcher))
                                }
                                closeEvents += "watcher"
                            },
                            closeSearch = { search ->
                                closeEvents += "search"
                                captured.closeSearch(search)
                            },
                            closeDriver = { driver ->
                                closeEvents += "driver"
                                captured.closeDriver(driver)
                            },
                            closeContext = { app ->
                                closeEvents += "context"
                                captured.closeContext(app)
                            },
                        ),
                    )
                }
            }

            actual.message shouldContain "watcher N registration failed"
            registrations shouldContainExactly listOf(RootName.PRIMARY, extraName)
            acquired.size shouldBe 1
            watcherClose.invocationCount.get() shouldBe 1
            watcherClose.returnedCount.get() shouldBe 1
            watcherClose.completed(acquired.single()) shouldBe true
            watcherCompletionAtClose.get() shouldBe true
            watcherCompletionAtSchedulerStart.get() shouldBe true
            alarm.closeCount.get() shouldBe 1
            alarm.closeReturned.get() shouldBe 1
            alarm.isClosedForTest() shouldBe true
            requireNotNull(captured.search.get())
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.driverClose.invocationCount.get() shouldBe 1
            captured.driverClose.completed(requireNotNull(captured.driver.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            schedulerLockHeld.get() shouldBe true
            closeEvents shouldContainExactly listOf("watcher", "scheduler", "search", "driver", "context")
            requireLockAvailableForOwnership(data)
        }
    }

    test("first rebuild failure owns the real unstarted HTTP handle and never starts it") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("initial rebuild failure")
            val http = AtomicReference<com.plainbase.frameworks.ktor.KtorServer?>()
            val httpCloseCount = AtomicInteger()
            val httpCloseLockHeld = AtomicReference<Boolean?>()
            val httpClose = OnceCloser<com.plainbase.frameworks.ktor.KtorServer>({ it.stop() })
            val startCount = AtomicInteger()
            val hookCount = AtomicInteger()
            val output = OwnershipOutput()
            val captured = OwnedResourceCapture()

            val actual = shouldThrow<IllegalStateException> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onHttpAcquired = { server ->
                                http.set(server)
                                registerOwnershipHandle("HTTP", server, { httpClose.invoke(server) }) {
                                    httpClose.completed(server)
                                }
                            },
                            initialRebuild = { builder ->
                                builder.rebuild()
                                throw failure
                            },
                            startServer = {
                                startCount.incrementAndGet()
                            },
                            onHookInstalled = { hookCount.incrementAndGet() },
                            closeHttp = { server ->
                                httpCloseLockHeld.set(dataDirLockHeldForOwnership(data))
                                httpCloseCount.incrementAndGet()
                                httpClose.invoke(server)
                            },
                            closeSearch = captured::closeSearch,
                            closeDriver = captured::closeDriver,
                            closeContext = captured::closeContext,
                        ),
                    )
                }
            }

            actual shouldBeSameInstanceAs failure
            requireNotNull(http.get())
            httpCloseCount.get() shouldBe 1
            httpClose.completed(requireNotNull(http.get())) shouldBe true
            httpCloseLockHeld.get() shouldBe true
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.driverClose.invocationCount.get() shouldBe 1
            captured.driverClose.completed(requireNotNull(captured.driver.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            startCount.get() shouldBe 0
            hookCount.get() shouldBe 0
            requireLockAvailableForOwnership(data)
        }
    }

    test("hook installation failure removes the real hook before owned cleanup returns") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("hook installation failure")
            val hook = AtomicReference<Thread?>()
            val http = AtomicReference<com.plainbase.frameworks.ktor.KtorServer?>()
            val httpCloseCount = AtomicInteger()
            val httpCloseLockHeld = AtomicReference<Boolean?>()
            val httpClose = OnceCloser<com.plainbase.frameworks.ktor.KtorServer>({ it.stop() })
            val startCount = AtomicInteger()
            val output = OwnershipOutput()
            val captured = OwnedResourceCapture()

            val actual = shouldThrow<IllegalStateException> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onHttpAcquired = { server ->
                                http.set(server)
                                registerOwnershipHandle("HTTP", server, { httpClose.invoke(server) }) {
                                    httpClose.completed(server)
                                }
                            },
                            onHookInstalled = { installed ->
                                hook.set(installed)
                                throw failure
                            },
                            startServer = { startCount.incrementAndGet() },
                            closeHttp = { server ->
                                httpCloseLockHeld.set(dataDirLockHeldForOwnership(data))
                                httpCloseCount.incrementAndGet()
                                httpClose.invoke(server)
                            },
                            closeSearch = captured::closeSearch,
                            closeDriver = captured::closeDriver,
                            closeContext = captured::closeContext,
                        ),
                    )
                }
            }

            actual shouldBeSameInstanceAs failure
            val installed = requireNotNull(hook.get())
            runCatching { Runtime.getRuntime().removeShutdownHook(installed) }.getOrNull() shouldBe false
            httpCloseCount.get() shouldBe 1
            httpClose.completed(requireNotNull(http.get())) shouldBe true
            httpCloseLockHeld.get() shouldBe true
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.driverClose.invocationCount.get() shouldBe 1
            captured.driverClose.completed(requireNotNull(captured.driver.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            startCount.get() shouldBe 0
            requireLockAvailableForOwnership(data)
        }
    }

    test("actual start failure preserves the sentinel after HTTP and hook acquisition") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("actual start failure")
            val http = AtomicReference<com.plainbase.frameworks.ktor.KtorServer?>()
            val hook = AtomicReference<Thread?>()
            val httpCloseCount = AtomicInteger()
            val httpCloseLockHeld = AtomicReference<Boolean?>()
            val httpClose = OnceCloser<com.plainbase.frameworks.ktor.KtorServer>({ it.stop() })
            val output = OwnershipOutput()
            val captured = OwnedResourceCapture()

            val actual = shouldThrow<IllegalStateException> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onHttpAcquired = { server ->
                                http.set(server)
                                registerOwnershipHandle("HTTP", server, { httpClose.invoke(server) }) {
                                    httpClose.completed(server)
                                }
                            },
                            onHookInstalled = hook::set,
                            startServer = { throw failure },
                            closeHttp = { server ->
                                httpCloseLockHeld.set(dataDirLockHeldForOwnership(data))
                                httpCloseCount.incrementAndGet()
                                httpClose.invoke(server)
                            },
                            closeSearch = captured::closeSearch,
                            closeDriver = captured::closeDriver,
                            closeContext = captured::closeContext,
                        ),
                    )
                }
            }

            actual shouldBeSameInstanceAs failure
            requireNotNull(http.get())
            requireNotNull(hook.get())
            httpCloseCount.get() shouldBe 1
            httpClose.completed(requireNotNull(http.get())) shouldBe true
            httpCloseLockHeld.get() shouldBe true
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.driverClose.invocationCount.get() shouldBe 1
            captured.driverClose.completed(requireNotNull(captured.driver.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            requireLockAvailableForOwnership(data)
        }
    }

    test("occupied loopback bind closes the already constructed HTTP server") {
        val occupied = AtomicReference<ServerSocket?>()
        withOwnershipFixture { content, data ->
            val port = nextOwnershipPort()
            val http = AtomicReference<com.plainbase.frameworks.ktor.KtorServer?>()
            val httpCloseCount = AtomicInteger()
            val httpCloseLockHeld = AtomicReference<Boolean?>()
            val httpClose = OnceCloser<com.plainbase.frameworks.ktor.KtorServer>({ it.stop() })
            val startEntered = AtomicInteger()
            val bindSucceeded = AtomicBoolean()
            val occupiedClose = OnceCloser<ServerSocket>({ it.close() }, ServerSocket::isClosed)
            val output = OwnershipOutput()
            val captured = OwnedResourceCapture()
            val actual = shouldThrow<Throwable> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data, port),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onHttpAcquired = { server ->
                                http.set(server)
                                registerOwnershipHandle("HTTP", server, { httpClose.invoke(server) }) {
                                    httpClose.completed(server)
                                }
                                val socket = ServerSocket()
                                occupied.set(socket)
                                registerOwnershipHandle("occupied port", socket, { occupiedClose.invoke(socket) }) {
                                    occupiedClose.completed(socket)
                                }
                                socket.reuseAddress = false
                                socket.bind(InetSocketAddress("127.0.0.1", port))
                                bindSucceeded.set(true)
                            },
                            startServer = { server ->
                                startEntered.incrementAndGet()
                                server.start(wait = true)
                            },
                            closeHttp = { server ->
                                httpCloseLockHeld.set(dataDirLockHeldForOwnership(data))
                                httpCloseCount.incrementAndGet()
                                httpClose.invoke(server)
                            },
                            closeSearch = captured::closeSearch,
                            closeDriver = captured::closeDriver,
                            closeContext = captured::closeContext,
                        ),
                    )
                }
            }

            allMessages(actual).any {
                it.contains("bind", ignoreCase = true) || it.contains("address", ignoreCase = true)
            } shouldBe true
            bindSucceeded.get() shouldBe true
            startEntered.get() shouldBe 1
            requireNotNull(http.get())
            httpCloseCount.get() shouldBe 1
            httpClose.completed(requireNotNull(http.get())) shouldBe true
            httpCloseLockHeld.get() shouldBe true
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.driverClose.invocationCount.get() shouldBe 1
            captured.driverClose.completed(requireNotNull(captured.driver.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            requireLockAvailableForOwnership(data)
        }
        requireNotNull(occupied.get()).isClosed shouldBe true
    }

    test("actual DR acquisition and arm precede closed-endpoint restore refusal and ordered close") {
        withOwnershipFixture { content, data ->
            val output = OwnershipOutput()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val drAcquired = AtomicInteger()
            val armed = AtomicInteger()
            val dr = AtomicReference<GitBundleDr?>()
            val drClose = OnceCloser<GitBundleDr>({ it.close() }, GitBundleDr::isClosedForTest)
            val objectClosed = AtomicInteger()
            val drLockHeld = AtomicReference<Boolean?>()
            val objectLockHeld = AtomicReference<Boolean?>()
            val driverLockHeld = AtomicReference<Boolean?>()
            val contextLockHeld = AtomicReference<Boolean?>()
            val captured = OwnedResourceCapture()
            val status = boundedOwnershipRun {
                runServer(
                    objectConfigForOwnership(content, data),
                    output,
                    openers = captured.openers(),
                    control = ServerRunControl(
                        onContextAcquired = captured::captureContext,
                        onDrAcquired = { acquired ->
                            dr.set(acquired)
                            drAcquired.incrementAndGet()
                            registerOwnershipHandle("DR", acquired, { drClose.invoke(acquired) }) {
                                drClose.completed(acquired)
                            }
                        },
                        afterDrArm = { armed.incrementAndGet() },
                        closeDr = { acquired ->
                            drLockHeld.set(dataDirLockHeldForOwnership(data))
                            drClose.invoke(acquired)
                            events += "dr"
                        },
                        closeObject = { store ->
                            objectLockHeld.set(dataDirLockHeldForOwnership(data))
                            objectClosed.incrementAndGet()
                            events += "object"
                            captured.closeObject(store)
                        },
                        closeDriver = { driver ->
                            driverLockHeld.set(dataDirLockHeldForOwnership(data))
                            events += "driver"
                            captured.closeDriver(driver)
                        },
                        closeContext = { app ->
                            contextLockHeld.set(dataDirLockHeldForOwnership(data))
                            events += "context"
                            captured.closeContext(app)
                        },
                    ),
                )
            }

            status shouldBe 1
            output.errors.single() shouldContain "object storage endpoint is unreachable"
            drAcquired.get() shouldBe 1
            armed.get() shouldBe 1
            drClose.invocationCount.get() shouldBe 1
            drClose.returnedCount.get() shouldBe 1
            drClose.completed(requireNotNull(dr.get())) shouldBe true
            objectClosed.get() shouldBe 1
            captured.objectClose.invocationCount.get() shouldBe 1
            captured.objectClose.completed(requireNotNull(captured.objectStore.get())) shouldBe true
            captured.search.get() shouldBe null
            captured.searchClose.invocationCount.get() shouldBe 0
            captured.driverClose.invocationCount.get() shouldBe 1
            drLockHeld.get() shouldBe true
            objectLockHeld.get() shouldBe true
            driverLockHeld.get() shouldBe true
            contextLockHeld.get() shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            events shouldContainExactly listOf("dr", "object", "driver", "context")
            requireLockAvailableForOwnership(data)
        }
    }

    test("an acquired DR remains owned when the real arm operation refuses") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("DR arm operation failed")
            val output = OwnershipOutput()
            val entered = AtomicBoolean()
            val dr = AtomicReference<GitBundleDr?>()
            val drLockHeld = AtomicReference<Boolean?>()
            val objectLockHeld = AtomicReference<Boolean?>()
            val driverLockHeld = AtomicReference<Boolean?>()
            val contextLockHeld = AtomicReference<Boolean?>()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val drClose = OnceCloser<GitBundleDr>({ it.close() }, GitBundleDr::isClosedForTest)
            val captured = OwnedResourceCapture()

            val status = boundedOwnershipRun {
                runServer(
                    objectConfigForOwnership(content, data),
                    output,
                    openers = captured.openers(),
                    control = ServerRunControl(
                        onContextAcquired = captured::captureContext,
                        onDrAcquired = { acquired ->
                            dr.set(acquired)
                            registerOwnershipHandle("DR", acquired, { drClose.invoke(acquired) }) {
                                drClose.completed(acquired)
                            }
                        },
                        armObjectHistory = { _, _ ->
                            entered.set(true)
                            throw failure
                        },
                        closeDr = { acquired ->
                            drLockHeld.set(dataDirLockHeldForOwnership(data))
                            drClose.invoke(acquired)
                            events += "dr"
                        },
                        closeObject = { store ->
                            objectLockHeld.set(dataDirLockHeldForOwnership(data))
                            captured.closeObject(store)
                            events += "object"
                        },
                        closeDriver = { driver ->
                            driverLockHeld.set(dataDirLockHeldForOwnership(data))
                            captured.closeDriver(driver)
                            events += "driver"
                        },
                        closeContext = { app ->
                            contextLockHeld.set(dataDirLockHeldForOwnership(data))
                            captured.closeContext(app)
                            events += "context"
                        },
                    ),
                )
            }

            status shouldBe 1
            output.errors.single() shouldContain failure.message.orEmpty()
            entered.get() shouldBe true
            drClose.invocationCount.get() shouldBe 1
            drClose.returnedCount.get() shouldBe 1
            drClose.completed(requireNotNull(dr.get())) shouldBe true
            captured.objectClose.invocationCount.get() shouldBe 1
            captured.objectClose.completed(requireNotNull(captured.objectStore.get())) shouldBe true
            captured.search.get() shouldBe null
            captured.searchClose.invocationCount.get() shouldBe 0
            captured.driverClose.invocationCount.get() shouldBe 1
            drLockHeld.get() shouldBe true
            objectLockHeld.get() shouldBe true
            driverLockHeld.get() shouldBe true
            contextLockHeld.get() shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            events shouldContainExactly listOf("dr", "object", "driver", "context")
            requireLockAvailableForOwnership(data)
        }
    }

    test("an acquired DR remains owned when actual hydrate fails after a typed restore result") {
        withOwnershipFixture { content, data ->
            seedCompleteMirror(data)
            val output = OwnershipOutput()
            val restoreEntered = AtomicBoolean()
            val restored = AtomicReference<GitBundleDr.Restored?>()
            val hydrateStrict = AtomicReference<Boolean?>()
            val dr = AtomicReference<GitBundleDr?>()
            val drLockHeld = AtomicReference<Boolean?>()
            val objectLockHeld = AtomicReference<Boolean?>()
            val driverLockHeld = AtomicReference<Boolean?>()
            val contextLockHeld = AtomicReference<Boolean?>()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val drClose = OnceCloser<GitBundleDr>({ it.close() }, GitBundleDr::isClosedForTest)
            val captured = OwnedResourceCapture()

            val status = boundedOwnershipRun {
                runServer(
                    objectConfigForOwnership(content, data),
                    output,
                    openers = captured.openers(),
                    control = ServerRunControl(
                        onContextAcquired = captured::captureContext,
                        onDrAcquired = { acquired ->
                            dr.set(acquired)
                            registerOwnershipHandle("DR", acquired, { drClose.invoke(acquired) }) {
                                drClose.completed(acquired)
                            }
                        },
                        restoreBundle = { bundleDr ->
                            restoreEntered.set(true)
                            bundleDr.restore().also(restored::set)
                        },
                        hydrateObject = { store, strict ->
                            hydrateStrict.set(strict)
                            store.hydrate(strict)
                        },
                        closeDr = { acquired ->
                            drLockHeld.set(dataDirLockHeldForOwnership(data))
                            drClose.invoke(acquired)
                            events += "dr"
                        },
                        closeObject = { store ->
                            objectLockHeld.set(dataDirLockHeldForOwnership(data))
                            captured.closeObject(store)
                            events += "object"
                        },
                        closeDriver = { driver ->
                            driverLockHeld.set(dataDirLockHeldForOwnership(data))
                            captured.closeDriver(driver)
                            events += "driver"
                        },
                        closeContext = { app ->
                            contextLockHeld.set(dataDirLockHeldForOwnership(data))
                            captured.closeContext(app)
                            events += "context"
                        },
                    ),
                )
            }

            status shouldBe 1
            output.errors.single() shouldContain "object storage endpoint is unreachable"
            restoreEntered.get() shouldBe true
            restored.get() shouldBe GitBundleDr.Restored.NOT_RESTORED
            hydrateStrict.get() shouldBe false
            drClose.invocationCount.get() shouldBe 1
            drClose.returnedCount.get() shouldBe 1
            drClose.completed(requireNotNull(dr.get())) shouldBe true
            captured.objectClose.invocationCount.get() shouldBe 1
            captured.objectClose.completed(requireNotNull(captured.objectStore.get())) shouldBe true
            captured.search.get() shouldBe null
            captured.searchClose.invocationCount.get() shouldBe 0
            captured.driverClose.invocationCount.get() shouldBe 1
            drLockHeld.get() shouldBe true
            objectLockHeld.get() shouldBe true
            driverLockHeld.get() shouldBe true
            contextLockHeld.get() shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            events shouldContainExactly listOf("dr", "object", "driver", "context")
            requireLockAvailableForOwnership(data)
        }
    }

    test("an acquired DR remains owned when the reconcile boundary refuses after typed restore and hydrate") {
        val listRequests = AtomicInteger()
        withEmptyListEndpoint({ listRequests.incrementAndGet() }) { endpoint ->
            withOwnershipFixture { content, data ->
                seedCompleteMirror(data, restorePending = true)
                val expectedTip = GitExecutor(data.resolve("mirror"), data.resolve("git-home"))
                    .run(listOf("rev-parse", "HEAD"))
                    .stdoutText
                    .trim()
                val failure = IllegalStateException("DR reconcile operation failed")
                val output = OwnershipOutput()
                val restoreEntered = AtomicBoolean()
                val hydrateStrict = AtomicReference<Boolean?>()
                val reconcileEntered = AtomicBoolean()
                val restored = AtomicReference<GitBundleDr.Restored?>()
                val dr = AtomicReference<GitBundleDr?>()
                val closeLockHeld = AtomicReference<Boolean?>()
                val objectLockHeld = AtomicReference<Boolean?>()
                val driverLockHeld = AtomicReference<Boolean?>()
                val contextLockHeld = AtomicReference<Boolean?>()
                val events = Collections.synchronizedList(mutableListOf<String>())
                val drClose = OnceCloser<GitBundleDr>({ it.close() }, GitBundleDr::isClosedForTest)
                val captured = OwnedResourceCapture()

                val status = boundedOwnershipRun {
                    runServer(
                        objectConfigForOwnership(content, data, endpoint),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onDrAcquired = { acquired ->
                                dr.set(acquired)
                                registerOwnershipHandle("DR", acquired, { drClose.invoke(acquired) }) {
                                    drClose.completed(acquired)
                                }
                            },
                            restoreBundle = { bundleDr ->
                                restoreEntered.set(true)
                                bundleDr.restore().also(restored::set)
                            },
                            hydrateObject = { store, strict ->
                                hydrateStrict.set(strict)
                                store.hydrate(strict)
                            },
                            reconcileBundle = { _, _ ->
                                reconcileEntered.set(true)
                                throw failure
                            },
                            closeDr = { acquired ->
                                closeLockHeld.set(dataDirLockHeldForOwnership(data))
                                drClose.invoke(acquired)
                                events += "dr"
                            },
                            closeObject = { store ->
                                objectLockHeld.set(dataDirLockHeldForOwnership(data))
                                captured.closeObject(store)
                                events += "object"
                            },
                            closeDriver = { driver ->
                                driverLockHeld.set(dataDirLockHeldForOwnership(data))
                                captured.closeDriver(driver)
                                events += "driver"
                            },
                            closeContext = { app ->
                                contextLockHeld.set(dataDirLockHeldForOwnership(data))
                                captured.closeContext(app)
                                events += "context"
                            },
                        ),
                    )
                }

                status shouldBe 1
                output.errors.single() shouldContain failure.message.orEmpty()
                restoreEntered.get() shouldBe true
                restored.get()?.isRestored shouldBe true
                restored.get()?.tip shouldBe expectedTip
                listRequests.get() shouldBe 2
                hydrateStrict.get() shouldBe true
                reconcileEntered.get() shouldBe true
                drClose.invocationCount.get() shouldBe 1
                drClose.returnedCount.get() shouldBe 1
                drClose.completed(requireNotNull(dr.get())) shouldBe true
                captured.objectClose.invocationCount.get() shouldBe 1
                captured.objectClose.completed(requireNotNull(captured.objectStore.get())) shouldBe true
                captured.search.get() shouldBe null
                captured.searchClose.invocationCount.get() shouldBe 0
                captured.driverClose.invocationCount.get() shouldBe 1
                closeLockHeld.get() shouldBe true
                objectLockHeld.get() shouldBe true
                driverLockHeld.get() shouldBe true
                contextLockHeld.get() shouldBe true
                captured.contextCloseCompletedForTest() shouldBe true
                events shouldContainExactly listOf("dr", "object", "driver", "context")
                requireLockAvailableForOwnership(data)
            }
        }
    }

    test("HTTP construction refusal owns earlier watcher and scheduler services") {
        withOwnershipFixture { content, data ->
            val failure = IllegalStateException("typed HTTP construction failure")
            val output = OwnershipOutput()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val watcher = AtomicReference<AutoCloseable?>()
            val watcherLockHeld = AtomicReference<Boolean?>()
            val watcherCompletionAtClose = AtomicReference<Boolean?>()
            val watcherCompletionAtSchedulerStart = AtomicReference<Boolean?>()
            val schedulerLockHeld = AtomicReference<Boolean?>()
            val searchLockHeld = AtomicReference<Boolean?>()
            val driverLockHeld = AtomicReference<Boolean?>()
            val contextLockHeld = AtomicReference<Boolean?>()
            val watcherClose = OnceCloser<AutoCloseable>({ it.close() }) {
                (it as? FileWatcher)?.isClosedForTest() == true
            }
            val alarm = RecordingAlarm(events) {
                schedulerLockHeld.set(dataDirLockHeldForOwnership(data))
                watcherCompletionAtSchedulerStart.set(watcher.get()?.let(watcherClose::completed))
            }
            val httpConstructed = AtomicInteger()
            val startEntered = AtomicInteger()
            val captured = OwnedResourceCapture()

            val actual = shouldThrow<IllegalStateException> {
                boundedOwnershipRun {
                    runServer(
                        localConfigForOwnership(content, data),
                        output,
                        openers = captured.openers(),
                        control = ServerRunControl(
                            onContextAcquired = captured::captureContext,
                            onWatcherAcquired = { _, acquiredWatcher ->
                                watcher.set(acquiredWatcher)
                                registerOwnershipHandle("watcher", acquiredWatcher, { watcherClose.invoke(acquiredWatcher) }) {
                                    watcherClose.completed(acquiredWatcher)
                                }
                            },
                            createScheduler = { builder ->
                                registerOwnershipHandle("scheduler", alarm, alarm::close, alarm::isClosedForTest)
                                RebuildScheduler(rebuild = { builder.rebuild() }, alarm = alarm)
                            },
                            createHttpServer = { _, _ ->
                                httpConstructed.incrementAndGet()
                                throw failure
                            },
                            closeWatcher = { watcher ->
                                watcherLockHeld.set(dataDirLockHeldForOwnership(data))
                                try {
                                    watcherClose.invoke(watcher)
                                } finally {
                                    watcherCompletionAtClose.set(watcherClose.completed(watcher))
                                }
                                events += "watcher"
                            },
                            closeSearch = { search ->
                                searchLockHeld.set(dataDirLockHeldForOwnership(data))
                                captured.closeSearch(search)
                                events += "search"
                            },
                            closeDriver = { driver ->
                                driverLockHeld.set(dataDirLockHeldForOwnership(data))
                                captured.closeDriver(driver)
                                events += "driver"
                            },
                            closeContext = { app ->
                                contextLockHeld.set(dataDirLockHeldForOwnership(data))
                                captured.closeContext(app)
                                events += "context"
                            },
                            startServer = { startEntered.incrementAndGet() },
                        ),
                    )
                }
            }

            actual shouldBeSameInstanceAs failure
            httpConstructed.get() shouldBe 1
            startEntered.get() shouldBe 0
            watcherClose.invocationCount.get() shouldBe 1
            watcherClose.returnedCount.get() shouldBe 1
            watcherClose.completed(requireNotNull(watcher.get())) shouldBe true
            watcherCompletionAtClose.get() shouldBe true
            watcherCompletionAtSchedulerStart.get() shouldBe true
            alarm.closeCount.get() shouldBe 1
            alarm.closeReturned.get() shouldBe 1
            alarm.isClosedForTest() shouldBe true
            captured.searchClose.invocationCount.get() shouldBe 1
            captured.searchClose.completed(requireNotNull(captured.search.get())) shouldBe true
            captured.driverClose.invocationCount.get() shouldBe 1
            captured.driverClose.completed(requireNotNull(captured.driver.get())) shouldBe true
            captured.contextCloseCompletedForTest() shouldBe true
            watcherLockHeld.get() shouldBe true
            schedulerLockHeld.get() shouldBe true
            searchLockHeld.get() shouldBe true
            driverLockHeld.get() shouldBe true
            contextLockHeld.get() shouldBe true
            events shouldContainExactly listOf("watcher", "scheduler", "search", "driver", "context")
            requireLockAvailableForOwnership(data)
        }
    }

    test("ownership fixture keeps a watchdog timeout when a worker succeeds late") {
        val base = Files.createTempDirectory("plainbase-ownership-late")
        val cancellationReturned = CountDownLatch(1)
        val lateResult = AtomicReference<String?>()
        val fixture = OwnershipFixtureRecord(base, runTimeoutMillis = 30, joinTimeoutMillis = 30)
        try {
            val actual = shouldThrow<IllegalStateException> {
                fixture.run {
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        cancellationReturned.countDown()
                        "late success".also(lateResult::set)
                    }
                }
            }
            actual.message shouldContain "watchdog"
            cancellationReturned.await(1, TimeUnit.SECONDS) shouldBe true
            fixture.completedSuccessfully() shouldBe true
            lateResult.get() shouldBe "late success"
        } finally {
            fixture.cleanup()
            fixture.canDelete() shouldBe true
            base.toFile().deleteRecursively()
        }
    }

    test("ownership fixture retains a path for an unnamed surviving worker") {
        val base = Files.createTempDirectory("plainbase-ownership-survivor")
        val release = AtomicBoolean(false)
        val fixture = OwnershipFixtureRecord(base, runTimeoutMillis = 30, joinTimeoutMillis = 30)
        try {
            shouldThrow<IllegalStateException> {
                fixture.run {
                    while (!release.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            // The outer fixture owns the final release and join.
                        }
                    }
                }
            }
            fixture.cleanup()!!.message shouldContain "survived"
            fixture.canDelete() shouldBe false
        } finally {
            release.set(true)
            val deadline = System.nanoTime() + 1_000_000_000L
            while (!fixture.canDelete() && System.nanoTime() < deadline) {
                fixture.cleanup()
                Thread.yield()
            }
            fixture.canDelete() shouldBe true
            base.toFile().deleteRecursively()
        }
    }

    test("ownership fixture bounds a held fallback and joins it after release") {
        val base = Files.createTempDirectory("plainbase-ownership-fallback")
        val release = CountDownLatch(1)
        val closerStarted = CountDownLatch(1)
        val closed = AtomicBoolean()
        val fixture = OwnershipFixtureRecord(base, runTimeoutMillis = 30, joinTimeoutMillis = 30)
        fixture.run { "ready" }
        fixture.track(
            "held fallback",
            Any(),
            {
                closerStarted.countDown()
                release.await()
                closed.set(true)
            },
            closed::get,
        )
        val outerWatchdog = thread(isDaemon = true, name = "plainbase-ownership-fallback-watchdog") {
            try {
                release.await(1_100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // The test's finally releases the closer on ordinary completion.
            }
            release.countDown()
        }
        try {
            val startedAt = System.nanoTime()
            val failure = fixture.cleanup()
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
            closerStarted.await(1, TimeUnit.SECONDS) shouldBe true
            (elapsedMillis < 1_000L) shouldBe true
            requireNotNull(failure).message shouldContain "cleanup worker"
            fixture.canDelete() shouldBe false
        } finally {
            release.countDown()
            outerWatchdog.interrupt()
            outerWatchdog.join(1_000)
            fixture.cleanup()
            closed.get() shouldBe true
            fixture.canDelete() shouldBe true
            base.toFile().deleteRecursively()
        }
    }

    test("expired parent returns promptly and retains a live-worker fixture path") {
        val base = Files.createTempDirectory("plainbase-ownership-expired-parent")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = OwnershipFixtureRecord(base, parentDeadlineNanos = System.nanoTime() - 1L)
        try {
            val startedAt = System.nanoTime()
            shouldThrow<IllegalStateException> {
                fixture.run {
                    started.countDown()
                    while (true) {
                        try {
                            if (release.await(10, TimeUnit.MILLISECONDS)) break
                        } catch (_: InterruptedException) {
                            // The test releases this worker after the bounded path is observed.
                        }
                    }
                }
            }
            ((System.nanoTime() - startedAt) / 1_000_000L < 500L) shouldBe true
            started.await(1, TimeUnit.SECONDS) shouldBe true
            val cleanup = fixture.cleanup()
            requireNotNull(cleanup)
            fixture.canDelete() shouldBe false
            Files.exists(base) shouldBe true
        } finally {
            release.countDown()
            fixture.joinRunForTest(1_000) shouldBe true
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!fixture.canDelete() && System.nanoTime() < cleanupDeadline) {
                fixture.cleanup()
                Thread.yield()
            }
            fixture.canDelete() shouldBe true
            base.toFile().deleteRecursively()
        }
    }

    test("ownership fixture preserves a throwing fallback identity across outer cleanup") {
        val base = Files.createTempDirectory("plainbase-ownership-failure-identity")
        val original = IllegalStateException("original fallback failure")
        val later = IllegalStateException("later cleanup failure")
        val fixture = OwnershipFixtureRecord(base, runTimeoutMillis = 30, joinTimeoutMillis = 30)
        try {
            fixture.run { "ready" }
            fixture.track("throwing fallback", Any(), { throw original }, { false })

            val cleanupFailure = fixture.cleanup()
            cleanupFailure shouldBeSameInstanceAs original

            val outerFailures = IdentitySafeFailureAccumulator()
            outerFailures.add(cleanupFailure)
            outerFailures.add(original)
            outerFailures.add(later)
            val merged = requireNotNull(outerFailures.failure)
            merged shouldBeSameInstanceAs original
            merged.suppressed.any { it === later } shouldBe true
            merged.suppressed.none { it === merged } shouldBe true
            fixture.canDelete() shouldBe false
        } finally {
            fixture.cleanup()
            base.toFile().deleteRecursively()
        }
    }
})

internal class OwnershipOutput : CommandOutput {
    val errors = Collections.synchronizedList(mutableListOf<String>())

    override fun result(text: String, newline: Boolean) = Unit

    override fun error(text: String) {
        errors += text
    }

    override fun intent(event: WriteIntent) = Unit
}

internal class RecordingAlarm(
    private val events: MutableList<String>? = null,
    private val delegate: ExecutorAlarm = ExecutorAlarm(),
    private val beforeClose: () -> Unit = {},
) : RebuildScheduler.Alarm, AutoCloseable {
    private val closed = AtomicBoolean()
    val closeCount = AtomicInteger()
    val closeReturned = AtomicInteger()
    val closeEntered = CountDownLatch(1)
    val closeStartedAtNanos = AtomicLong()

    override fun after(delayMillis: Long, action: () -> Unit) = delegate.after(delayMillis, action)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount.incrementAndGet()
        closeStartedAtNanos.compareAndSet(0L, System.nanoTime())
        closeEntered.countDown()
        beforeClose()
        delegate.close()
        closeReturned.incrementAndGet()
        events?.add("scheduler")
    }

    fun isClosedForTest(): Boolean = closed.get() && delegate.isTerminatedForTest()
}

internal class OnceCloser<T>(
    private val delegate: (T) -> Unit,
    private val complete: (T) -> Boolean = { true },
) {
    private val invoked = AtomicBoolean()
    val invocationCount = AtomicInteger()
    val returnedCount = AtomicInteger()
    val failure = AtomicReference<Throwable?>()

    fun invoke(value: T) {
        if (!invoked.compareAndSet(false, true)) return
        invocationCount.incrementAndGet()
        try {
            delegate(value)
        } catch (failure: Throwable) {
            this.failure.set(failure)
            throw failure
        }
        returnedCount.incrementAndGet()
    }

    fun completed(value: T): Boolean = returnedCount.get() == 1 && failure.get() == null && complete(value)
}

internal class OwnedResourceCapture(
    private val defaults: ServerOpeners = ServerOpeners(),
    private val searchCloseDelegate: (SearchDb, List<Connection>) -> Unit = { value, _ -> value.close() },
) {
    val driver = AtomicReference<SqlDriver?>()
    val connection = AtomicReference<Connection?>()
    val search = AtomicReference<SearchDb?>()
    private val searchConnections = Collections.synchronizedList(mutableListOf<Connection>())
    private val normalSearchConnectionsClosed = AtomicReference<Boolean?>()
    private val searchFallbackConnectionsClosed = AtomicReference<Boolean?>()
    val objectStore = AtomicReference<ObjectContentStore?>()
    val context = AtomicReference<KoinApplication?>()
    val driverClose = OnceCloser<SqlDriver>({ it.close() }) { connection.get()?.isClosed == true }
    private val contextClose = OnceCloser<KoinApplication>({ it.close() })
    val searchClose = OnceCloser<SearchDb>({ closeSearchAndRemaining(it) }) {
        normalSearchConnectionsClosed.get() == true
    }
    val objectClose = OnceCloser<ObjectContentStore>({ it.close() }) {
        it.isClosedForTest() && it.transportIdleForTest()
    }

    fun openers(): ServerOpeners = ServerOpeners(
        openDriver = { path ->
            defaults.openDriver(path).also { opened ->
                driver.set(opened)
                connection.set((opened as? BeginImmediateSqliteDriver)?.getConnection())
                registerOwnershipHandle("driver", opened, { driverClose.invoke(opened) }) {
                    driverClose.completed(opened)
                }
            }
        },
        openLocal = defaults.openLocal,
        openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
            defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart).also { opened ->
                objectStore.set(opened)
                registerOwnershipHandle("object", opened, { objectClose.invoke(opened) }) {
                    objectClose.completed(opened)
                }
            }
        },
        openSearch = { path ->
            normalSearchConnectionsClosed.set(null)
            searchFallbackConnectionsClosed.set(null)
            SearchDb(path) { url ->
                DriverManager.getConnection(url).also { searchConnections += it }
            }.also { opened ->
                search.set(opened)
                registerOwnershipHandle("search", opened, { searchClose.invoke(opened) }) {
                    searchClose.completed(opened)
                }
            }
        },
    )

    fun closeDriver(value: SqlDriver) = driverClose.invoke(value)

    fun closeObject(value: ObjectContentStore) = objectClose.invoke(value)

    fun closeSearch(value: SearchDb) = searchClose.invoke(value)

    fun captureContext(value: KoinApplication) {
        context.set(value)
        registerOwnershipHandle("context", value, { contextClose.invoke(value) }) {
            contextClose.completed(value)
        }
    }

    fun closeContext(value: KoinApplication) = contextClose.invoke(value)

    fun contextCloseCompletedForTest(): Boolean = context.get()?.let(contextClose::completed) == true

    fun searchConnectionsClosedForTest(): Boolean = synchronized(searchConnections) {
        searchConnections.isNotEmpty() && searchConnections.all { it.isClosed }
    }

    fun normalSearchConnectionsClosedForTest(): Boolean = normalSearchConnectionsClosed.get() == true

    fun searchFallbackConnectionsClosedForTest(): Boolean = searchFallbackConnectionsClosed.get() == true

    private fun closeSearchAndRemaining(value: SearchDb) {
        val connections = synchronized(searchConnections) { searchConnections.toList() }
        val failures = IdentitySafeFailureAccumulator()
        try {
            searchCloseDelegate(value, connections)
        } catch (failure: Throwable) {
            failures.add(failure)
        }
        normalSearchConnectionsClosed.set(connections.isNotEmpty() && connections.all { it.isClosed })
        connections
            .filter { !it.isClosed }
            .forEach { connection ->
                try {
                    connection.close()
                    if (!connection.isClosed) {
                        failures.add(IllegalStateException("retained search.db connection remained open"))
                    }
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
        searchFallbackConnectionsClosed.set(connections.isNotEmpty() && connections.all { it.isClosed })
        failures.failure?.let { throw it }
    }
}

private class SqlFailureDriver(
    private val delegate: SqlDriver,
    private val failure: Throwable,
) : SqlDriver by delegate {
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (normalizeOwnershipSql(sql).startsWith("DELETE FROM SESSIONS")) throw failure
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
}

internal fun localConfigForOwnership(content: Path, data: Path, port: Int = 0): PlainbaseConfig = PlainbaseConfig(
    contentDir = content,
    dataDir = data,
    host = "127.0.0.1",
    port = port,
    git = GitConfig(enabled = false),
)

private fun nextOwnershipPort(): Int = ServerSocket(0).use { it.localPort }

internal fun objectConfigForOwnership(
    content: Path,
    data: Path,
    endpoint: String = "https://127.0.0.1:1",
    gitEnabled: Boolean = true,
): PlainbaseConfig = PlainbaseConfig.fromEnv(
    mapOf(
        "CONTENT_DIR" to content.toString(),
        "DATA_DIR" to data.toString(),
        "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to endpoint,
        "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
        "PLAINBASE_INSECURE_HTTP" to if (endpoint.startsWith("http://")) "1" else "0",
        "PLAINBASE_GIT_ENABLED" to gitEnabled.toString(),
        "PLAINBASE_HOST" to "127.0.0.1",
        "PLAINBASE_PORT" to "0",
    ),
)

private fun seedCompleteMirror(data: Path, restorePending: Boolean = false) {
    val mirror = data.resolve("mirror")
    val home = data.resolve("git-home")
    Files.createDirectories(mirror)
    Files.createDirectories(home)
    val executor = GitExecutor(mirror, home)
    check(executor.run(listOf("init")).ok)
    check(executor.run(listOf("config", "user.name", "Plainbase Test")).ok)
    check(executor.run(listOf("config", "user.email", "plainbase-test@localhost")).ok)
    Files.writeString(mirror.resolve("seed.md"), "seed\n")
    check(executor.run(listOf("add", "--all")).ok)
    check(executor.run(listOf("commit", "-m", "seed")).ok)
    if (restorePending) Files.createFile(data.resolve("restore-pending"))
}

internal fun <T> withEmptyListEndpoint(onRequest: () -> Unit = {}, block: (String) -> T): T {
    val listXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult>
            <IsTruncated>false</IsTruncated>
        </ListBucketResult>
    """.trimIndent()
    val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            route("{path...}") {
                handle {
                    onRequest()
                    call.respondText(listXml, ContentType.Application.Xml)
                }
            }
        }
    }.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    return try {
        block("http://127.0.0.1:$port")
    } finally {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }
}

private val currentOwnershipFixture = ThreadLocal<OwnershipFixtureRecord?>()

internal fun withOwnershipFixture(
    runTimeoutMillis: Long = 45_000,
    joinTimeoutMillis: Long = 10_000,
    parentDeadlineNanos: Long? = null,
    block: (content: Path, data: Path) -> Unit,
) {
    val base = Files.createTempDirectory("plainbase-ownership")
    val content = Files.createDirectory(base.resolve("content"))
    val data = Files.createDirectory(base.resolve("data"))
    val fixture = OwnershipFixtureRecord(base, runTimeoutMillis, joinTimeoutMillis, parentDeadlineNanos)
    Files.writeString(content.resolve("readme.md"), "---\ntitle: Readme\n---\n\n# Readme\n")
    val failures = IdentitySafeFailureAccumulator()
    currentOwnershipFixture.set(fixture)
    try {
        block(content, data)
    } catch (failure: Throwable) {
        failures.add(failure)
    } finally {
        currentOwnershipFixture.remove()
        failures.add(fixture.cleanup())
    }
    val deletable = fixture.canDelete()
    if (!deletable) {
        failures.add(IllegalStateException("ownership fixture retained path because owned work did not complete: $base"))
    } else {
        base.toFile().deleteRecursively()
    }
    failures.failure?.let { throw it }
}

internal fun <T> boundedOwnershipRun(block: () -> T): T {
    val fixture = checkNotNull(currentOwnershipFixture.get()) { "ownership run requires a fixture" }
    return fixture.run(block)
}

internal fun registerOwnershipHandle(label: String, value: Any, close: () -> Unit, complete: () -> Boolean) {
    checkNotNull(currentOwnershipFixture.get()) { "owned handle registered outside a fixture" }
        .track(label, value, close, complete)
}

internal fun currentOwnershipFixtureForTest(): OwnershipFixtureRecord? = currentOwnershipFixture.get()

internal class OwnershipFixtureRecord(
    val base: Path,
    private val runTimeoutMillis: Long = 45_000,
    private val joinTimeoutMillis: Long = 10_000,
    private val parentDeadlineNanos: Long? = null,
) {
    private data class Handle(
        val label: String,
        val value: Any,
        val close: () -> Unit,
        val complete: () -> Boolean,
        val attempted: AtomicBoolean = AtomicBoolean(),
        val failure: AtomicReference<Throwable?> = AtomicReference(),
    )

    private data class CleanupWorker(
        val thread: Thread,
        val result: AtomicReference<Throwable?>,
    )

    private val handles = Collections.synchronizedList(mutableListOf<Handle>())
    private val runThread = AtomicReference<Thread?>()
    private val runResult = AtomicReference<Result<*>?>()
    private val runComplete = AtomicBoolean()
    private val cleanupWorkers = Collections.synchronizedList(mutableListOf<CleanupWorker>())
    private val cleanupLock = Any()
    private val interruptedByCaller = AtomicBoolean()

    fun track(label: String, value: Any, close: () -> Unit, complete: () -> Boolean) {
        handles += Handle(label, value, close, complete)
    }

    fun <T> run(block: () -> T): T {
        val result = AtomicReference<Result<T>?>()
        val worker = thread(start = false, isDaemon = true, name = "plainbase-ownership-run") {
            currentOwnershipFixture.set(this)
            runThread.set(Thread.currentThread())
            try {
                runCatching(block).also {
                    result.set(it)
                    runResult.set(it)
                }
            } finally {
                runComplete.set(true)
                currentOwnershipFixture.remove()
            }
        }
        runThread.set(worker)
        worker.start()
        var primary: Throwable? = null
        var interrupted = false
        try {
            joinBoundedUntil(worker, deadlineAfter(runTimeoutMillis))
        } catch (failure: InterruptedException) {
            primary = failure
            interrupted = true
        }
        if (worker.isAlive) {
            primary = primary ?: IllegalStateException("ownership run watchdog expired")
            worker.interrupt()
            try {
                joinBoundedUntil(worker, deadlineAfter(joinTimeoutMillis))
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (worker.isAlive) primary = primary ?: IllegalStateException("ownership run worker survived its watchdog")
        if (interrupted) interruptedByCaller.set(true)
        if (primary != null) throw primary
        return requireNotNull(result.get()) { "ownership run completed without a result" }.getOrThrow()
    }

    fun completedSuccessfully(): Boolean = runComplete.get() && runResult.get()?.isSuccess == true

    internal fun joinRunForTest(maxMillis: Long): Boolean {
        val worker = runThread.get() ?: return true
        return joinBoundedUntil(worker, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis))
    }

    fun cleanup(): Throwable? {
        var interrupted = Thread.interrupted()
        val failures = IdentitySafeFailureAccumulator()
        startCleanupWorkerIfNeeded()
        val workers = synchronized(cleanupLock) { cleanupWorkers.toList() }
        val deadline = deadlineAfter(joinTimeoutMillis)
        workers.forEach { worker ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) {
                try {
                    joinBoundedUntil(worker.thread, System.nanoTime() + remaining)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (worker.thread.isAlive) {
                val survivor = IllegalStateException("ownership fixture cleanup worker survived its bound")
                failures.add(survivor)
            } else {
                failures.add(worker.result.get())
            }
        }
        if (runThread.get()?.isAlive == true) {
            val survivor = IllegalStateException("ownership fixture worker survived bounded cleanup")
            failures.add(survivor)
        }
        val snapshot = synchronized(handles) { handles.toList() }
        snapshot.forEach { handle ->
            failures.add(handle.failure.get())
            if (!safelyComplete(handle)) {
                val incomplete = IllegalStateException("owned ${handle.label} did not prove completion")
                failures.add(incomplete)
            }
        }
        if (interrupted || interruptedByCaller.get()) {
            Thread.currentThread().interrupt()
            val interruption = InterruptedException("ownership fixture cleanup was interrupted")
            failures.add(interruption)
        }
        return failures.failure
    }

    fun canDelete(): Boolean {
        val snapshot = synchronized(handles) { handles.toList() }
        val workersFinished = synchronized(cleanupLock) { cleanupWorkers.all { !it.thread.isAlive } }
        return runComplete.get() && runThread.get()?.isAlive != true && workersFinished && snapshot.all {
            it.failure.get() == null && safelyComplete(it)
        }
    }

    private fun startCleanupWorkerIfNeeded(): CleanupWorker? {
        synchronized(cleanupLock) {
            cleanupWorkers.firstOrNull { it.thread.isAlive }?.let { return it }
            val snapshot = synchronized(handles) { handles.toList() }
            if (runThread.get()?.isAlive != true && snapshot.all(::safelyComplete)) return null
            val result = AtomicReference<Throwable?>()
            val thread = thread(
                start = false,
                isDaemon = true,
                name = "plainbase-ownership-cleanup-${cleanupWorkers.size + 1}",
            ) {
                result.set(cleanupHandles())
            }
            return CleanupWorker(thread, result).also {
                cleanupWorkers += it
                thread.start()
            }
        }
    }

    private fun cleanupHandles(): Throwable? {
        val failures = IdentitySafeFailureAccumulator()
        val run = runThread.get()
        if (run?.isAlive == true) {
            run.interrupt()
            try {
                joinBoundedUntil(run, deadlineAfter(joinTimeoutMillis))
            } catch (failure: InterruptedException) {
                failures.add(failure)
            }
        }
        if (run?.isAlive == true) {
            val survivor = IllegalStateException("ownership fixture worker survived bounded cleanup")
            failures.add(survivor)
            return failures.failure
        }
        val snapshot = synchronized(handles) { handles.toList().sortedBy { cleanupRank(it.label) } }
        var index = 0
        var stop = false
        while (index < snapshot.size && !stop) {
            val handle = snapshot[index]
            if (safelyComplete(handle)) {
                index++
            } else if (!handle.attempted.compareAndSet(false, true)) {
                val incomplete = IllegalStateException("owned ${handle.label} close did not prove completion")
                failures.add(incomplete)
                stop = true
            } else {
                try {
                    handle.close()
                } catch (failure: Throwable) {
                    handle.failure.compareAndSet(null, failure)
                    failures.add(failure)
                }
                if (!safelyComplete(handle)) {
                    val incomplete = IllegalStateException("owned ${handle.label} did not prove completion")
                    failures.add(incomplete)
                    stop = true
                } else {
                    index++
                }
            }
        }
        return failures.failure
    }

    private fun safelyComplete(handle: Handle): Boolean = runCatching { handle.complete() }.getOrDefault(false)

    internal fun remainingParentMillis(maxMillis: Long = Long.MAX_VALUE): Long {
        val deadline = parentDeadlineNanos ?: return maxMillis
        return minOf(maxMillis, TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0L)))
    }

    private fun deadlineAfter(defaultMillis: Long): Long {
        val local = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(defaultMillis)
        return minOf(local, parentDeadlineNanos ?: Long.MAX_VALUE)
    }

    private fun cleanupRank(label: String): Int = when (label.lowercase()) {
        "http", "occupied port" -> 0
        "watcher", "watchers" -> 1
        "scheduler" -> 2
        "maintenance" -> 3
        "dr" -> 4
        "object" -> 5
        "search" -> 6
        "driver" -> 7
        "context" -> 8
        "lock" -> 9
        else -> 10
    }
}

private fun joinBoundedUntil(thread: Thread, deadlineNanos: Long): Boolean {
    while (thread.isAlive) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return false
        thread.join(minOf(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)))
    }
    return true
}

private fun dataDirLockHeldForOwnership(data: Path): Boolean {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt == null) return true
    attempt.close()
    return false
}

private fun requireLockAvailableForOwnership(data: Path) {
    val attempt = DataDirLock.tryAcquire(data) ?: error("DATA_DIR lock was not released")
    attempt.close()
}

private fun normalizeOwnershipSql(sql: String): String = sql.replace(Regex("\\s+"), " ").trim().uppercase()

private fun allMessages(failure: Throwable): Sequence<String> = sequence {
    var current: Throwable? = failure
    while (current != null) {
        yield(current.message.orEmpty())
        current = current.cause
    }
}

private fun allCauses(failure: Throwable): Sequence<Throwable> = sequence {
    var current: Throwable? = failure
    while (current != null) {
        yield(current)
        current = current.cause
    }
}
