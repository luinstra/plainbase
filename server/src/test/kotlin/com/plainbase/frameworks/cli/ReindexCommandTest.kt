package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.SearchQuery
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.lifecycle.Stage0cParentDeadline
import com.plainbase.frameworks.lifecycle.probeDataDirLock
import com.plainbase.frameworks.runtime.OfflineStoreOperations
import com.plainbase.frameworks.runtime.RootStoreFactory
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `plainbase reindex` CLI contract (S8 Resolution 2 / criteria 6-8 JVM half + criterion 14).
 * A temp content tree + temp DATA_DIR drives the real `ReindexCommand.run`; the resulting
 * search.db is reopened independently and queried to prove the rebuild took. The cross-process
 * lock leg holds the DATA_DIR lock and asserts the exact refusal message + exit 1.
 */
class ReindexCommandTest : FunSpec({

    test("reindex exits 0, prints the exact summary line, and the rebuilt search.db answers a known term") {
        withReindexTree { config ->
            // The println summary is the output contract. Logback also writes INFO diagnostics to
            // the test's stdout, so assert the exact summary LINE is present (not the whole buffer).
            val out = captureStdout { runReindex(emptyList(), config) shouldBe 0 }
            out.lineSequence().toList() shouldContain "reindex: rebuilt the search index for 2 page(s) under ${config.contentDir}"

            // Reopen the engine independently of the CLI's lifetime and confirm the term is indexed.
            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.search(SearchQuery(text = "capacitor", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
                provider.indexedState().keys shouldBe
                    setOf(
                        RootedPageId(RootName.PRIMARY, ALPHA_ID),
                        RootedPageId(RootName.PRIMARY, BETA_ID),
                    )
                provider.indexedState().size shouldBe 2
            }
        }
    }

    test("reindex displaces a fixed-id page without carrying the stale raw engine row") {
        withReindexTree { config ->
            captureStdout { runReindex(emptyList(), config) shouldBe 0 }

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val database = DatabaseFactory.createDatabase(driver)
                val idMap = SqlDelightIdMapRepository(database)
                idMap.bindingInRoot(RootName.PRIMARY, ALPHA_ID)?.path?.path?.value shouldBe "alpha.md"
            }

            Files.writeString(
                config.contentDir.resolve("alpha.md"),
                "---\nid: ${REPLACEMENT_ALPHA_ID.value}\ntitle: Replacement Alpha\n---\n\n# Replacement Alpha\n\nfind the flux capacitor here.\n",
            )
            val out = captureStdout { runReindex(emptyList(), config) shouldBe 0 }
            out.lineSequence().toList() shouldContain "reindex: rebuilt the search index for 2 page(s) under ${config.contentDir}"

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val database = DatabaseFactory.createDatabase(driver)
                val idMap = SqlDelightIdMapRepository(database)
                idMap.retiredAt(RootName.PRIMARY, ALPHA_ID)?.path?.path?.value shouldBe "alpha.md"
                idMap.bindingInRoot(RootName.PRIMARY, ALPHA_ID) shouldBe null
                idMap.bindingInRoot(RootName.PRIMARY, REPLACEMENT_ALPHA_ID)?.path?.path?.value shouldBe "alpha.md"
            }
            SearchDb(config.searchDatabasePath).use { db ->
                Fts5SearchProvider(db).indexedState().keys shouldBe
                    setOf(
                        RootedPageId(RootName.PRIMARY, REPLACEMENT_ALPHA_ID),
                        RootedPageId(RootName.PRIMARY, BETA_ID),
                    )
            }
        }
    }

    test("extra arguments are a usage error (exit 2)") {
        withReindexTree { config ->
            runReindex(listOf("--bogus"), config) shouldBe 2
        }
    }

    test(
        "storage.backend=object with an incomplete Q9 matrix fails fast at requireContentDir (exit 1), " +
            "taking no lock and writing nothing - C4 replaced the outright refusal with real object wiring, " +
            "but requireContentDir()'s pre-lock Q9 gate still refuses a config missing its required keys",
    ) {
        withReindexTree { config ->
            val objectConfig = config.copy(storage = StorageConfig(backend = StorageBackend.OBJECT))
            val err = captureStderr { runReindex(emptyList(), objectConfig) shouldBe 1 }
            err shouldNotContain "storage.backend=object is configured but the object backend is not available"
            // The gate precedes the lock and any driver open: no db/search.db, and the lock is free.
            Files.exists(objectConfig.appDatabasePath) shouldBe false
            Files.exists(objectConfig.searchDatabasePath) shouldBe false
            DataDirLock.tryAcquire(objectConfig.dataDir)!!.use { }
        }
    }

    test(
        "storage.backend=object, fully configured but unreachable: reindex hydrates first, fails fast and " +
            "actionably (exit 1) rather than silently reindexing an empty/stale mirror",
    ) {
        withReindexTree { config ->
            withListRefusalEndpoint { endpoint, requests ->
                val objectConfig = objectConfig(config, endpoint)
                // The failure is logged via the facade (logger.error), not println - the exit code is the contract.
                captureStderr { runReindex(emptyList(), objectConfig) shouldBe 1 }
                (requests.get() > 0) shouldBe true
            }
        }
    }

    test("a multi-root reindex covers EVERY configured root: both roots' documents survive the swap, and the count is the whole corpus") {
        withTwoRootTree { config, _ ->
            val out = captureStdout { runReindex(emptyList(), config) shouldBe 0 }
            out.lineSequence().toList() shouldContain
                "reindex: rebuilt the search index for 3 page(s) across 2 roots: docs (2), handbook (1)"

            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                // The regression this pins: a main-only source list would leave the engine holding `main` alone,
                // because the rebuild is a GENERATION SWAP. Here the fresh complete scan sees both roots, so its
                // accepted input and engine keys are exactly the known three pages.
                provider.indexedState().keys.map { it.root }.toSet() shouldBe setOf(RootName.PRIMARY, HANDBOOK)
                provider.indexedState().keys shouldBe
                    setOf(
                        RootedPageId(RootName.PRIMARY, ALPHA_ID),
                        RootedPageId(RootName.PRIMARY, BETA_ID),
                        RootedPageId(HANDBOOK, HANDBOOK_PAGE_ID),
                    )
                provider.indexedState().size shouldBe 3
                provider.search(SearchQuery(text = "capacitor", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
                provider.search(SearchQuery(text = "onboarding", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            }
        }
    }

    test("LOCAL reindex keeps the declared root order and excludes DATA_DIR nested in an extra") {
        withTwoRootTree { config, handbook ->
            val nestedData = Files.createDirectories(handbook.resolve("plainbase-data"))
            Files.writeString(nestedData.resolve("secret.md"), "---\ntitle: Hidden\n---\nnot corpus\n")
            val nestedConfig = config.copy(dataDir = nestedData)

            val out = captureStdout { runReindex(emptyList(), nestedConfig) shouldBe 0 }

            out.lineSequence().toList() shouldContain
                "reindex: rebuilt the search index for 3 page(s) across 2 roots: docs (2), handbook (1)"
            SearchDb(nestedConfig.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.indexedState().size shouldBe 3
                provider.search(SearchQuery(text = "not corpus", limit = 20, offset = 0)).total shouldBe 0L
            }
        }
    }

    test("LOCAL reindex constructs primary first while indexing the declared root order") {
        withTwoRootTree { config, handbook ->
            val nestedData = Files.createDirectories(handbook.resolve("plainbase-data"))
            Files.writeString(nestedData.resolve("secret.md"), "---\ntitle: Hidden\n---\nnot corpus\n")
            val reordered = config.copy(
                dataDir = nestedData,
                roots = RootsConfig.of(
                    listOf(config.roots.list[1], config.roots.list[0]),
                    origin = RootsOrigin.EXPLICIT,
                ),
            )
            val localOpens = mutableListOf<RootName>()
            val decorated = mutableListOf<RootName>()
            val fixture = OfflineStoreFixture()
            val tracked = fixture.operations()
            val operations = OfflineStoreOperations(
                openDriver = tracked.openDriver,
                openReadOnlyDriver = tracked.openReadOnlyDriver,
                openSearch = tracked.openSearch,
                openLocal = { inputs ->
                    localOpens += inputs.rootName
                    tracked.openLocal(inputs)
                },
                openObject = { _, _, _, _, _ -> error("OBJECT must not open in LOCAL mode") },
                hydrateObject = tracked.hydrateObject,
                closeObject = tracked.closeObject,
            )
            fixture.use {
                val out = captureStdout {
                    ReindexCommand.run(
                        emptyList(),
                        reordered,
                        { name, store ->
                            decorated += name
                            object : ContentStore by store {}
                        },
                        CommandOutputCapture.current,
                        operations,
                    ) shouldBe 0
                }

                localOpens shouldBe listOf(RootName.PRIMARY, HANDBOOK)
                decorated shouldBe listOf(RootName.PRIMARY, HANDBOOK)
                out.lineSequence().toList() shouldContain
                    "reindex: rebuilt the search index for 3 page(s) across 2 roots: handbook (1), docs (2)"
                SearchDb(reordered.searchDatabasePath).use { db ->
                    Fts5SearchProvider(db).search(SearchQuery(text = "not corpus", limit = 20, offset = 0)).total shouldBe 0L
                }
            }
        }
    }

    test("OBJECT reindex uses the default high-level store, ignores CONTENT_DIR, and closes the raw store") {
        withReindexTree { config ->
            withEmptyListEndpoint { endpoint ->
                val fixture = OfflineStoreFixture()
                val localOpens = mutableListOf<RootName>()
                val objectOpens = AtomicInteger()
                var closeCount = 0
                val base = fixture.productionOperations(
                    openLocal = { inputs ->
                        localOpens += inputs.rootName
                        RootStoreFactory.local(inputs)
                    },
                    closeObject = { store ->
                        closeCount++
                        store.close()
                    },
                )
                val operations = OfflineStoreOperations(
                    openDriver = base.openDriver,
                    openReadOnlyDriver = base.openReadOnlyDriver,
                    openSearch = base.openSearch,
                    openLocal = base.openLocal,
                    openObject = { objectConfig, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                        objectOpens.incrementAndGet()
                        base.openObject(objectConfig, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
                    },
                    hydrateObject = base.hydrateObject,
                    closeObject = base.closeObject,
                )
                val objectConfig = objectConfig(config, endpoint).copy(contentDir = config.dataDir.resolve("ignored-content"))
                fixture.use {
                    val out = captureStdout {
                        ReindexCommand.run(
                            emptyList(),
                            objectConfig,
                            { _, store -> store },
                            CommandOutputCapture.current,
                            operations,
                        ) shouldBe 0
                    }

                    out.lineSequence().toList() shouldContain
                        "reindex: rebuilt the search index for 0 page(s) under ${objectConfig.dataDir.resolve("mirror")}"
                    objectOpens.get() shouldBe 1
                    localOpens shouldBe emptyList()
                    fixture.stores.single().isClosedForTest() shouldBe true
                    fixture.stores.single().transportIdleForTest() shouldBe true
                    closeCount shouldBe 1
                    fixture.connections.isNotEmpty() shouldBe true
                    fixture.connections.all { it.isClosed } shouldBe true
                    fixture.drivers.single().closeCount shouldBe 1
                }
                closeCount shouldBe 1
                fixture.drivers.single().closeCount shouldBe 1
            }
        }
    }

    test("reindex closes a raw object store when primary decoration fails") {
        withReindexTree { config ->
            withEmptyListEndpoint { endpoint ->
                val objectConfig = objectConfig(config, endpoint)
                val decorationFailure = AssertionError("decoration failed")
                val closeFailure = IllegalStateException("raw close failed")
                val parent = Stage0cParentDeadline(30_000)
                val fixture = OfflineStoreFixture()
                val clientLocks = mutableListOf<String>()
                val driverLocks = mutableListOf<String>()
                val searchLocks = mutableListOf<String>()
                fixture.onClientClose = { clientLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
                fixture.onDriverClose = { driverLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
                fixture.onConnectionClose = { searchLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
                val operations = fixture.operations(
                    closeObject = { store ->
                        store.close()
                        throw closeFailure
                    },
                )
                fixture.use {
                    val actual = shouldThrow<AssertionError> {
                        ReindexCommand.run(
                            emptyList(),
                            objectConfig,
                            { _, _ -> throw decorationFailure },
                            CommandOutputCapture.current,
                            operations,
                        )
                    }

                    actual shouldBeSameInstanceAs decorationFailure
                    actual.suppressed.single() shouldBeSameInstanceAs closeFailure
                    fixture.clients.single().closeCount shouldBe 1
                    fixture.clients.single().transportActive shouldBe false
                    fixture.connections.isNotEmpty() shouldBe true
                    fixture.connections.all { it.isClosed } shouldBe true
                    fixture.connections.all { it.closeCount == 1 } shouldBe true
                    fixture.drivers.single().closeCount shouldBe 1
                    clientLocks shouldBe listOf("HELD")
                    driverLocks shouldBe listOf("HELD")
                    searchLocks shouldBe List(fixture.connections.size) { "HELD" }
                    probeDataDirLock(config.dataDir, parent, fixture = null) shouldBe "AVAILABLE"
                }
                fixture.clients.single().closeCount shouldBe 1
                fixture.clients.single().transportActive shouldBe false
                fixture.drivers.single().closeCount shouldBe 1
                fixture.connections.all { it.closeCount == 1 } shouldBe true
            }
        }
    }

    test("reindex closes a partially assembled object store before returning the construction failure") {
        withReindexTree { config ->
            val fixture = OfflineStoreFixture()
            val partialFailure = IllegalStateException("store assembly failed")
            val parent = Stage0cParentDeadline(30_000)
            val clientLocks = mutableListOf<String>()
            val driverLocks = mutableListOf<String>()
            val searchLocks = mutableListOf<String>()
            fixture.onClientClose = { clientLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
            fixture.onDriverClose = { driverLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
            fixture.onConnectionClose = { searchLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
            val logger = LoggerFactory.getLogger(ReindexCommand::class.java) as Logger
            val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            fixture.use {
                try {
                    captureStderr {
                        ReindexCommand.run(
                            emptyList(),
                            objectConfig(config, "http://127.0.0.1:1"),
                            { _, store -> store },
                            CommandOutputCapture.current,
                            fixture.operations(
                                openObject = { objectConfigArg, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                                    fixture.openObjectThenFail(
                                        objectConfigArg,
                                        ignoreRules,
                                        dirtyPaths,
                                        isDirty,
                                        rowsAtStart,
                                        partialFailure,
                                    )
                                },
                            ),
                        ) shouldBe 1
                    }

                    val event = appender.list.first { it.formattedMessage == "reindex failed" }
                    (event.throwableProxy as? ThrowableProxy)?.throwable shouldBeSameInstanceAs partialFailure
                    fixture.clients.single().closeCount shouldBe 1
                    fixture.clients.single().transportActive shouldBe false
                    fixture.connections.isNotEmpty() shouldBe true
                    fixture.connections.all { it.isClosed } shouldBe true
                    fixture.drivers.single().closeCount shouldBe 1
                    clientLocks shouldBe listOf("HELD")
                    driverLocks shouldBe listOf("HELD")
                    searchLocks shouldBe List(fixture.connections.size) { "HELD" }
                    probeDataDirLock(config.dataDir, parent, fixture = null) shouldBe "AVAILABLE"
                } finally {
                    logger.detachAppender(appender)
                }
            }
            fixture.clients.single().closeCount shouldBe 1
            fixture.clients.single().transportActive shouldBe false
            fixture.drivers.single().closeCount shouldBe 1
        }
    }

    test("reindex closes a raw store behind a non-closeable decorated view before its success result") {
        withReindexTree { config ->
            withEmptyListEndpoint { endpoint ->
                val fixture = OfflineStoreFixture()
                fixture.use {
                    val out = captureStdout {
                        ReindexCommand.run(
                            emptyList(),
                            objectConfig(config, endpoint),
                            { _, store -> object : ContentStore by store {} },
                            CommandOutputCapture.current,
                            fixture.operations(),
                        ) shouldBe 0
                    }

                    out shouldContain "reindex: rebuilt the search index for 0 page(s)"
                    fixture.clients.single().closeCount shouldBe 1
                    fixture.clients.single().transportActive shouldBe false
                    fixture.connections.isNotEmpty() shouldBe true
                    fixture.connections.all { it.isClosed } shouldBe true
                    fixture.drivers.single().closeCount shouldBe 1
                }
                fixture.clients.single().closeCount shouldBe 1
                fixture.clients.single().transportActive shouldBe false
                fixture.drivers.single().closeCount shouldBe 1
            }
        }
    }

    test("reindex retains the DATA_DIR lock through raw object, search, and driver close") {
        withReindexTree { config ->
            withEmptyListEndpoint { endpoint ->
                val parent = Stage0cParentDeadline(30_000)
                val fixture = OfflineStoreFixture()
                val base = fixture.operations()
                val events = mutableListOf<String>()
                val lockStates = linkedMapOf<String, String>()
                var observedSearchClose = false
                fixture.onConnectionClose = {
                    if (!observedSearchClose) {
                        observedSearchClose = true
                        events += "search-close"
                        lockStates["search"] = probeDataDirLock(config.dataDir, parent, fixture = null)
                    }
                }
                val operations = OfflineStoreOperations(
                    openDriver = { path ->
                        val driver = base.openDriver(path)
                        object : SqlDriver by driver {
                            override fun close() {
                                events += "driver-close"
                                fixture.observe {
                                    lockStates["driver"] = probeDataDirLock(config.dataDir, parent, fixture = null)
                                }
                                driver.close()
                            }
                        }
                    },
                    openReadOnlyDriver = base.openReadOnlyDriver,
                    openSearch = base.openSearch,
                    openLocal = base.openLocal,
                    openObject = base.openObject,
                    hydrateObject = base.hydrateObject,
                    closeObject = { store ->
                        events += "object-close"
                        fixture.observe {
                            lockStates["object"] = probeDataDirLock(config.dataDir, parent, fixture = null)
                        }
                        store.close()
                    },
                )
                fixture.use {
                    captureStdout {
                        ReindexCommand.run(
                            emptyList(),
                            objectConfig(config, endpoint),
                            { _, store -> store },
                            CommandOutputCapture.current,
                            operations,
                        ) shouldBe 0
                    }

                    lockStates shouldBe linkedMapOf("object" to "HELD", "search" to "HELD", "driver" to "HELD")
                    events shouldBe listOf("object-close", "search-close", "driver-close")
                    probeDataDirLock(config.dataDir, parent, fixture = null) shouldBe "AVAILABLE"
                    fixture.connections.isNotEmpty() shouldBe true
                    fixture.connections.all { it.isClosed } shouldBe true
                    fixture.drivers.single().closeCount shouldBe 1
                }
                fixture.drivers.single().closeCount shouldBe 1
            }
        }
    }

    test("fixture records an observer failure after closing every retained JDBC delegate") {
        val data = Files.createTempDirectory("pb-fixture-observer-data")
        withRetainedDirectories(data) {
            val fixture = OfflineStoreFixture()
            val observerFailure = IllegalStateException("lock observation failed")
            val bodyFailure = AssertionError("body failed")
            fixture.onConnectionClose = { throw observerFailure }

            val actual = shouldThrow<AssertionError> {
                fixture.use {
                    fixture.operations().openSearch(data.resolve("search.db")).close()
                    throw bodyFailure
                }
            }

            actual shouldBeSameInstanceAs bodyFailure
            actual.suppressed.single() shouldBeSameInstanceAs observerFailure
            fixture.connections.isNotEmpty() shouldBe true
            fixture.connections.all { it.isClosed } shouldBe true
            fixture.connections.all { it.closeCount == 1 } shouldBe true
        }
    }

    test("reindex closes the raw object store when the LIST endpoint refuses hydration") {
        withReindexTree { config ->
            withListRefusalEndpoint { endpoint, requests ->
                val objectConfig = objectConfig(config, endpoint)
                val fixture = OfflineStoreFixture()
                val logger = LoggerFactory.getLogger(ReindexCommand::class.java) as Logger
                val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
                logger.addAppender(appender)
                fixture.use {
                    try {
                        ReindexCommand.run(
                            emptyList(),
                            objectConfig,
                            { _, store -> store },
                            CommandOutputCapture.current,
                            fixture.operations(),
                        ) shouldBe 1
                        appender.list.any { it.formattedMessage == "reindex failed" } shouldBe true
                        (requests.get() > 0) shouldBe true
                        fixture.clients.single().closeCount shouldBe 1
                        fixture.clients.single().transportActive shouldBe false
                        fixture.connections.isNotEmpty() shouldBe true
                        fixture.connections.all { it.isClosed } shouldBe true
                        fixture.drivers.single().closeCount shouldBe 1
                    } finally {
                        logger.detachAppender(appender)
                    }
                }
                fixture.clients.single().closeCount shouldBe 1
                fixture.clients.single().transportActive shouldBe false
                fixture.drivers.single().closeCount shouldBe 1
            }
        }
    }

    test("a raw object close failure returns status 1 and logs the same throwable") {
        withReindexTree { config ->
            withEmptyListEndpoint { endpoint ->
                val objectConfig = objectConfig(config, endpoint)
                val closeFailure = RuntimeException("raw close failed")
                val fixture = OfflineStoreFixture()
                val parent = Stage0cParentDeadline(30_000)
                val clientLocks = mutableListOf<String>()
                val driverLocks = mutableListOf<String>()
                val searchLocks = mutableListOf<String>()
                fixture.onClientClose = { clientLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
                fixture.onDriverClose = { driverLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
                fixture.onConnectionClose = { searchLocks += probeDataDirLock(config.dataDir, parent, fixture = null) }
                val logger = LoggerFactory.getLogger(ReindexCommand::class.java) as Logger
                val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
                logger.addAppender(appender)
                fixture.use {
                    try {
                        val out = captureStdout {
                            ReindexCommand.run(
                                emptyList(),
                                objectConfig,
                                { _, store -> store },
                                CommandOutputCapture.current,
                                fixture.operations(closeObject = { store ->
                                    store.close()
                                    throw closeFailure
                                }),
                            ) shouldBe 1
                        }

                        val event = appender.list.first { it.formattedMessage == "reindex failed" }
                        event.level shouldBe Level.ERROR
                        (event.throwableProxy as? ThrowableProxy)?.throwable shouldBeSameInstanceAs closeFailure
                        out shouldNotContain "reindex: rebuilt the search index"
                        fixture.clients.single().closeCount shouldBe 1
                        fixture.clients.single().transportActive shouldBe false
                        fixture.connections.isNotEmpty() shouldBe true
                        fixture.connections.all { it.isClosed } shouldBe true
                        fixture.drivers.single().closeCount shouldBe 1
                        clientLocks shouldBe listOf("HELD")
                        driverLocks shouldBe listOf("HELD")
                        searchLocks shouldBe List(fixture.connections.size) { "HELD" }
                        probeDataDirLock(config.dataDir, parent, fixture = null) shouldBe "AVAILABLE"
                    } finally {
                        logger.detachAppender(appender)
                    }
                }
                fixture.clients.single().closeCount shouldBe 1
                fixture.clients.single().transportActive shouldBe false
                fixture.drivers.single().closeCount shouldBe 1
            }
        }
    }

    test("an unavailable extra root refuses the whole reindex (exit 1) without purging its search rows") {
        withTwoRootTree { config, handbook ->
            captureStdout { runReindex(emptyList(), config) shouldBe 0 } // seed the engine with BOTH roots
            Files.delete(handbook.resolve("onboarding.md"))
            Files.delete(handbook) // the NAS came unmounted; config validation still accepts this (ADR-0011 D13)

            val err = captureStderr { runReindex(emptyList(), config) shouldBe 1 }
            err shouldContain "root 'handbook' is not available"
            err shouldContain
                "reindex: refusing to rebuild - offline reindex requires every configured root to be available. " +
                "Restore the path(s), or remove the root(s) from the roots {} block if they are gone for good."

            // The old generation is unchanged: the vanished root's documents are still in the engine, and come back when it does.
            SearchDb(config.searchDatabasePath).use { db ->
                Fts5SearchProvider(db).indexedState().keys.map { it.root }.toSet() shouldBe setOf(RootName.PRIMARY, HANDBOOK)
            }
        }
    }

    test(
        "a root that vanishes MID-REBUILD - past the preflight - aborts before the swap (exit 1), leaving the PRIOR " +
            "search generation intact while the page pass reports incomplete coverage",
    ) {
        withTwoRootTree { config, _ ->
            captureStdout { runReindex(emptyList(), config) shouldBe 0 } // seed the engine with BOTH roots

            // handbook answers the preflight probe and is gone from the rebuild's own probe on - the window a
            // check-then-act guard cannot see. The page pass is incomplete, so the previous search generation stays.
            val err = captureStderr { runReindex(emptyList(), config, vanishAfterFirstProbe(HANDBOOK)) shouldBe 1 }
            err shouldContain "root 'handbook' went away while it was being indexed"
            err shouldContain "page pass covers 1 of 2 configured root(s)"
            err shouldContain "previous search generation is unchanged"
            err shouldContain "page pass may already have updated content or application metadata"

            // The swap never happened: handbook's documents are still searchable, exactly as for a root nobody touched.
            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.indexedState().keys.map { it.root }.toSet() shouldBe setOf(RootName.PRIMARY, HANDBOOK)
                provider.search(SearchQuery(text = "onboarding", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            }
        }
    }

    test("main's store is decorated too: main vanishing MID-REBUILD reports incomplete coverage, exactly as an extra does") {
        withTwoRootTree { config, _ ->
            captureStdout { runReindex(emptyList(), config) shouldBe 0 } // seed the engine with BOTH roots

            // `openStores` constructs main's entry EXPLICITLY, outside the extras fold - the one place `decorate` could
            // be dropped without any other test noticing, since the test above only ever drives it through an EXTRA.
            // Undecorated, main's store would be the real one: nothing vanishes, and this run returns 0.
            val err = captureStderr { runReindex(emptyList(), config, vanishAfterFirstProbe(RootName.PRIMARY)) shouldBe 1 }
            err shouldContain "root 'docs' went away while it was being indexed"
            err shouldContain "page pass covers 1 of 2 configured root(s)"
            err shouldContain "previous search generation is unchanged"
            err shouldContain "page pass may already have updated content or application metadata"

            SearchDb(config.searchDatabasePath).use { db ->
                Fts5SearchProvider(db).indexedState().keys.map { it.root }.toSet() shouldBe setOf(RootName.PRIMARY, HANDBOOK)
            }
        }
    }

    test("criterion 14: a running server's DATA_DIR lock makes reindex refuse with exit 1 and write nothing") {
        withReindexTree { config ->
            DataDirLock.tryAcquire(config.dataDir)!!.use {
                val err = captureStderr { runReindex(emptyList(), config) shouldBe 1 }
                err shouldContain "a Plainbase server is holding ${config.dataDir}"
                err shouldContain "POST /api/v1/admin/reindex"
                // The refusal happens before any engine open: no search.db was created underneath the server.
                Files.exists(config.searchDatabasePath) shouldBe false
            }
            // After release, a run succeeds.
            captureStdout { runReindex(emptyList(), config) shouldBe 0 }
        }
    }
})

private val HANDBOOK = RootName.require("handbook")
private val ALPHA_ID = PageId.require("01900000-0000-7000-8000-000000000201")
private val BETA_ID = PageId.require("01900000-0000-7000-8000-000000000202")
private val REPLACEMENT_ALPHA_ID = PageId.require("01900000-0000-7000-8000-000000000203")
private val HANDBOOK_PAGE_ID = PageId.require("01900000-0000-7000-8000-000000000204")

/**
 * The disappearance a preflight structurally cannot catch: [root]'s store answers the FIRST availability probe (the
 * command's preflight) and reports gone from the second on (the rebuild's own probe) - a NAS unmounting in the window
 * between the two. Everything else, and every other root, is the real store. Single-threaded by construction: the
 * probes happen on the CLI's own thread, in order.
 */
private fun vanishAfterFirstProbe(root: RootName): StoreDecorator = { name, store ->
    if (name != root) {
        store
    } else {
        object : ContentStore by store {
            private var probed = false

            override fun available(): Boolean {
                if (probed) return false
                probed = true
                return true
            }
        }
    }
}

/** A two-page tree + fresh DATA_DIR; both cleaned up. */
private fun withReindexTree(block: (PlainbaseConfig) -> Unit) {
    val content = Files.createTempDirectory("pb-reindex-content")
    val data = Files.createTempDirectory("pb-reindex-data")
    withRetainedDirectories(content, data) {
        Files.writeString(
            content.resolve("alpha.md"),
            "---\nid: ${ALPHA_ID.value}\ntitle: Alpha\n---\n\n# Alpha\n\nfind the flux capacitor here.\n",
        )
        Files.writeString(content.resolve("beta.md"), "---\nid: ${BETA_ID.value}\ntitle: Beta\n---\n\n# Beta\n\nplain filler text.\n")
        block(PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0))
    }
}

/**
 * The same two-page `main` plus a one-page `handbook` extra root, wired through an EXPLICIT `roots {}` block (the
 * registry the server builds), and handed to [block] alongside the extra's path so a test can unmount it.
 */
private fun withTwoRootTree(block: (PlainbaseConfig, Path) -> Unit) {
    val handbook = Files.createTempDirectory("pb-reindex-handbook")
    withRetainedDirectories(handbook) {
        withReindexTree { config ->
            Files.writeString(
                handbook.resolve("onboarding.md"),
                "---\nid: ${HANDBOOK_PAGE_ID.value}\ntitle: Onboarding\n---\n\n# Onboarding\n\nday-one onboarding steps.\n",
            )
            block(
                config.copy(
                    roots = RootsConfig.of(
                        list = listOf(
                            Root(RootName.PRIMARY, RootBackend.Local(config.contentDir), editable = true, history = HistoryMode.OFF),
                            Root(HANDBOOK, RootBackend.Local(handbook), editable = true, history = HistoryMode.OFF),
                        ),
                        origin = RootsOrigin.EXPLICIT,
                    ),
                ),
                handbook,
            )
        }
    }
}

private fun runReindex(args: List<String>, config: PlainbaseConfig): Int =
    ReindexCommand.run(args, config, CommandOutputCapture.current)

private fun runReindex(args: List<String>, config: PlainbaseConfig, decorate: StoreDecorator): Int =
    ReindexCommand.run(args, config, decorate, CommandOutputCapture.current)

private fun captureStdout(block: () -> Unit): String = CommandOutputCapture.captureStdout(block)

private fun captureStderr(block: () -> Unit): String = CommandOutputCapture.captureStderr(block)

private fun objectConfig(base: PlainbaseConfig, endpoint: String): PlainbaseConfig = base.copy(
    storage = StorageConfig(
        backend = StorageBackend.OBJECT,
        endpoint = endpoint,
        bucket = "docs",
        accessKeyId = "k",
        secretAccessKey = "s",
    ),
)
