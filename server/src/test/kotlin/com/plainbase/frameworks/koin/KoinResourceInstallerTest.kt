package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.IdentitySafeFailureAccumulator
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.security.ProxyCsrf
import com.plainbase.frameworks.sqldelight.BeginImmediateSqliteDriver
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.koin.core.error.ClosedScopeException
import org.koin.core.error.DefinitionOverrideException
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Focused ownership controls for ordinary factory inputs and standalone Koin graphs. */
class KoinResourceInstallerTest : FunSpec({

    test("ordinary owner-aware factories transfer real driver and SearchDb exactly once") {
        val dataDir = Files.createTempDirectory("plainbase-koin-owner")
        val contentDir = Files.createTempDirectory("plainbase-koin-content")
        val owner = ServerResourceOwner()
        val openedDriver = AtomicReference<SqlDriver?>()
        val driverClosed = AtomicBoolean(false)
        val driverCloseCount = AtomicInteger()
        val openedSearch = AtomicReference<SearchDb?>()
        val searchClosed = AtomicBoolean(false)
        val searchCloseCount = AtomicInteger()
        try {
            val config = PlainbaseConfig.fromEnv(
                mapOf(
                    "CONTENT_DIR" to contentDir.toString(),
                    "DATA_DIR" to dataDir.toString(),
                    "PLAINBASE_GIT_ENABLED" to "false",
                ),
            )
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createRepositoryModule(
                        openDriver = { path -> DatabaseFactory.createDriver(path).also(openedDriver::set) },
                        closeDriver = { driver ->
                            if (driverClosed.compareAndSet(false, true)) {
                                driverCloseCount.incrementAndGet()
                                driver.close()
                            }
                        },
                        resourceOwner = owner,
                    ),
                    createSearchModule(
                        openSearch = { path -> SearchDb(path).also(openedSearch::set) },
                        closeSearch = { search ->
                            if (searchClosed.compareAndSet(false, true)) {
                                searchCloseCount.incrementAndGet()
                                search.close()
                            }
                        },
                        resourceOwner = owner,
                    ),
                ),
            )

            app.koin.get<SqlDriver>() shouldBe openedDriver.get()
            app.koin.get<SearchDb>() shouldBe openedSearch.get()
            owner.drainServices()

            driverCloseCount.get() shouldBe 1
            searchCloseCount.get() shouldBe 1
            owner.close()
            driverCloseCount.get() shouldBe 1
            searchCloseCount.get() shouldBe 1
        } finally {
            owner.close()
            openedSearch.get()?.let { closeOnce(searchClosed, it::close, searchCloseCount) }
            openedDriver.get()?.let { closeOnce(driverClosed, it::close, driverCloseCount) }
            deleteTree(contentDir)
            deleteTree(dataDir)
        }
    }

    test("standalone app.close drains ordinary real factories in owner order") {
        val dataDir = Files.createTempDirectory("plainbase-koin-standalone")
        val contentDir = Files.createTempDirectory("plainbase-koin-standalone-content")
        val owner = ServerResourceOwner()
        val order = ConcurrentLinkedQueue<String>()
        val driverClosed = AtomicBoolean(false)
        val searchClosed = AtomicBoolean(false)
        val driverCloseCount = AtomicInteger()
        val searchCloseCount = AtomicInteger()
        var driver: SqlDriver? = null
        var search: SearchDb? = null
        try {
            val config = PlainbaseConfig.fromEnv(
                mapOf("CONTENT_DIR" to contentDir.toString(), "DATA_DIR" to dataDir.toString()),
            )
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createRepositoryModule(
                        openDriver = { path -> DatabaseFactory.createDriver(path).also { driver = it } },
                        closeDriver = { value ->
                            if (driverClosed.compareAndSet(false, true)) {
                                order += "driver"
                                driverCloseCount.incrementAndGet()
                                value.close()
                            }
                        },
                        resourceOwner = owner,
                    ),
                    createSearchModule(
                        openSearch = { path -> SearchDb(path).also { search = it } },
                        closeSearch = { value ->
                            if (searchClosed.compareAndSet(false, true)) {
                                order += "search"
                                searchCloseCount.incrementAndGet()
                                value.close()
                            }
                        },
                        resourceOwner = owner,
                    ),
                ),
            )
            app.koin.get<SqlDriver>()
            app.koin.get<SearchDb>()

            app.close()

            order.toList() shouldBe listOf("search", "driver")
            driverCloseCount.get() shouldBe 1
            searchCloseCount.get() shouldBe 1
        } finally {
            owner.close()
            search?.let { closeOnce(searchClosed, it::close, searchCloseCount) }
            driver?.let { closeOnce(driverClosed, it::close, driverCloseCount) }
            deleteTree(contentDir)
            deleteTree(dataDir)
        }
    }

    test("cached real SQL dependency remains usable while a later admitted provider fails") {
        val dataDir = Files.createTempDirectory("plainbase-koin-nested-sql")
        val contentDir = Files.createTempDirectory("plainbase-koin-nested-sql-content")
        val owner = ServerResourceOwner()
        val failingEntered = CountDownLatch(1)
        val failingRelease = CountDownLatch(1)
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val failingResult = AtomicReference<Throwable?>()
        val holderResult = AtomicReference<Throwable?>()
        val heldValue = AtomicReference<String?>()
        val driverClosed = AtomicBoolean(false)
        val driverCloseCount = AtomicInteger()
        var driver: SqlDriver? = null
        var connection: Connection? = null
        var failing: Thread? = null
        var holder: Thread? = null
        var primary: Throwable? = null
        var interrupted = false
        try {
            val config = PlainbaseConfig.fromEnv(
                mapOf("CONTENT_DIR" to contentDir.toString(), "DATA_DIR" to dataDir.toString()),
            )
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createRepositoryModule(
                        openDriver = {
                            DatabaseFactory.createInMemoryDriver().also {
                                driver = it
                                connection = (it as BeginImmediateSqliteDriver).getConnection()
                            }
                        },
                        closeDriver = { value -> closeOnce(driverClosed, value::close, driverCloseCount) },
                        resourceOwner = owner,
                    ),
                    module {
                        single<String>(named("failing")) {
                            owner.construct("failing SQL provider") {
                                get<SqlDriver>()
                                failingEntered.countDown()
                                check(failingRelease.await(5, TimeUnit.SECONDS)) { "failing provider release timed out" }
                                error("outer provider failed")
                            }
                        }
                        single<String>(named("holder")) {
                            owner.construct("held SQL provider") {
                                val heldDriver = get<SqlDriver>()
                                holderEntered.countDown()
                                check(holderRelease.await(5, TimeUnit.SECONDS)) { "holder release timed out" }
                                heldDriver.execute(null, "SELECT 1", 0)
                                "held"
                            }
                        }
                    },
                ),
            )
            val failingThread = thread(isDaemon = true, name = "koin-real-sql-failing") {
                runCatching { app.koin.get<String>(named("failing")) }.onFailure(failingResult::set)
            }
            failing = failingThread
            failingEntered.await(5, TimeUnit.SECONDS) shouldBe true
            val holderThread = thread(isDaemon = true, name = "koin-real-sql-holder") {
                runCatching { app.koin.get<String>(named("holder")) }
                    .onSuccess(heldValue::set)
                    .onFailure(holderResult::set)
            }
            holder = holderThread
            holderEntered.await(5, TimeUnit.SECONDS) shouldBe true
            driverCloseCount.get() shouldBe 0
            connection?.isClosed shouldBe false
            failingRelease.countDown()
            failingThread.join(5_000)
            failingThread.isAlive shouldBe false
            var current = requireNotNull(failingResult.get())
            var sawPrimary = false
            while (true) {
                if (current.message?.contains("outer provider failed") == true) sawPrimary = true
                current = current.cause ?: break
            }
            sawPrimary shouldBe true
            driverCloseCount.get() shouldBe 0
            connection?.isClosed shouldBe false
            holderThread.isAlive shouldBe true

            holderRelease.countDown()
            holderThread.join(5_000)
            holderThread.isAlive shouldBe false
            holderResult.get() shouldBe null
            heldValue.get() shouldBe "held"
            driverCloseCount.get() shouldBe 0
            connection?.isClosed shouldBe false

            owner.drainServices()
            driverCloseCount.get() shouldBe 1
            connection?.isClosed shouldBe true
        } catch (failure: Throwable) {
            primary = failure
            interrupted = failure is InterruptedException
        } finally {
            failingRelease.countDown()
            holderRelease.countDown()
            val failures = IdentitySafeFailureAccumulator()
            failures.add(primary)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            listOf(failing, holder).forEach { worker ->
                if (worker == null) return@forEach
                val remaining = deadline - System.nanoTime()
                try {
                    worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                } catch (failure: InterruptedException) {
                    interrupted = true
                    failures.add(failure)
                }
            }
            val workersStopped = listOf(failing, holder).all { it?.isAlive != true }
            if (!workersStopped) {
                val survivor = IllegalStateException("nested SQL fixture thread survived cleanup")
                failures.add(survivor)
                listOf(failing, holder).forEach { worker -> if (worker?.isAlive == true) worker.interrupt() }
            } else {
                val ownerCleanup = boundedKoinCleanup("nested SQL owner") { owner.close() }
                interrupted = interrupted || ownerCleanup.interrupted
                failures.add(ownerCleanup.failure)
                if (ownerCleanup.completed) {
                    val driverCleanup = boundedKoinCleanup("nested SQL driver") {
                        driver?.let { closeOnce(driverClosed, it::close, driverCloseCount) }
                    }
                    interrupted = interrupted || driverCleanup.interrupted
                    failures.add(driverCleanup.failure)
                }
            }
            primary = failures.failure
            if (workersStopped && primary == null) {
                deleteTree(contentDir)
                deleteTree(dataDir)
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        primary?.let { throw it }
    }

    test("definition installation closes the captured isolated context after a real module failure") {
        val owner = ServerResourceOwner()
        val contextCloseCount = AtomicInteger()
        val app = owner.construct("Koin context") {
            koinApplication().also {
                owner.own(ServerResourcePhase.KOIN_CONTEXT, it) {
                    contextCloseCount.incrementAndGet()
                    it.close()
                }
            }
        }
        var testFailure: Throwable? = null
        try {
            app.allowOverride(false)
            shouldThrow<DefinitionOverrideException> {
                app.modules(
                    module { single<String>(named("same")) { "first" } },
                    module { single<String>(named("same")) { "second" } },
                )
            }
            contextCloseCount.get() shouldBe 0
            owner.close()
            contextCloseCount.get() shouldBe 1
            shouldThrow<ClosedScopeException> { app.koin.get<String>(named("same")) }
        } catch (caught: Throwable) {
            testFailure = caught
        } finally {
            if (contextCloseCount.get() == 0) {
                runCatching { app.close() }.onFailure { cleanup ->
                    if (testFailure != null) {
                        requireNotNull(testFailure).addSuppressed(cleanup)
                    } else {
                        testFailure = cleanup
                    }
                }
            }
            owner.close()
        }
        testFailure?.let { throw it }
    }

    test("effectful ProxyCsrf admission waits for held app_meta work before service drain") {
        val dataDir = Files.createTempDirectory("plainbase-koin-csrf")
        val contentDir = Files.createTempDirectory("plainbase-koin-csrf-content")
        val owner = ServerResourceOwner()
        val operationEntered = CountDownLatch(1)
        val operationRelease = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val initializerFailure = AtomicReference<Throwable?>()
        val drainerFailure = AtomicReference<Throwable?>()
        val initialized = AtomicReference<ProxyCsrf?>()
        val driverClosed = AtomicBoolean(false)
        val driverCloseCount = AtomicInteger()
        var driver: SqlDriver? = null
        var initializer: Thread? = null
        var drainer: Thread? = null
        var probe: Thread? = null
        val sealObserved = CountDownLatch(1)
        val sealSeen = AtomicBoolean(false)
        val probeFailure = AtomicReference<Throwable?>()
        val postSealSuccesses = AtomicInteger()
        var primary: Throwable? = null
        var interrupted = false
        try {
            val config = PlainbaseConfig.fromEnv(
                mapOf("CONTENT_DIR" to contentDir.toString(), "DATA_DIR" to dataDir.toString()),
            )
            val app = createOwnedTestKoinApplication(
                owner,
                listOf(
                    module { single { config } },
                    createRepositoryModule(
                        openDriver = { path ->
                            HoldingAppMetaDriver(DatabaseFactory.createDriver(path), operationEntered, operationRelease)
                                .also { driver = it }
                        },
                        closeDriver = { value -> closeOnce(driverClosed, value::close, driverCloseCount) },
                        resourceOwner = owner,
                    ),
                    createRestModule(owner),
                ),
            )
            app.koin.get<SqlDriver>()
            app.koin.get<PlainbaseDb>()
            initializer = thread(isDaemon = true, name = "koin-proxy-csrf-initializer") {
                runCatching { app.koin.get<ProxyCsrf>() }
                    .onSuccess {
                        initialized.set(it)
                    }
                    .onFailure(initializerFailure::set)
            }
            operationEntered.await(5, TimeUnit.SECONDS) shouldBe true
            drainer = thread(isDaemon = true, name = "koin-proxy-csrf-drainer") {
                runCatching { owner.drainServices() }
                    .onFailure(drainerFailure::set)
                    .also { drainFinished.countDown() }
            }
            probe = thread(isDaemon = true, name = "koin-proxy-csrf-seal-probe") {
                try {
                    val sealDeadline = System.nanoTime() + 5_000_000_000L
                    while (System.nanoTime() < sealDeadline && !sealSeen.get()) {
                        try {
                            owner.construct("post-seal probe") {}
                        } catch (failure: IllegalStateException) {
                            if (failure.message?.contains("sealed") != true) throw failure
                            sealSeen.set(true)
                            sealObserved.countDown()
                        }
                    }
                    if (!sealSeen.get()) {
                        throw IllegalStateException("owner did not expose sealed rejection")
                    }
                    val negativeDeadline = System.nanoTime() + 250_000_000L
                    while (System.nanoTime() < negativeDeadline) {
                        try {
                            owner.construct("post-seal negative probe") {}
                            postSealSuccesses.incrementAndGet()
                        } catch (failure: IllegalStateException) {
                            if (failure.message?.contains("sealed") != true) throw failure
                        }
                    }
                } catch (failure: Throwable) {
                    probeFailure.set(failure)
                }
            }
            sealObserved.await(5, TimeUnit.SECONDS) shouldBe true
            drainFinished.await(250, TimeUnit.MILLISECONDS) shouldBe false
            driverCloseCount.get() shouldBe 0
            operationRelease.countDown()
            initializer.join(5_000)
            drainer.join(5_000)
            initializer.isAlive shouldBe false
            drainer.isAlive shouldBe false
            initializerFailure.get() shouldBe null
            drainerFailure.get() shouldBe null
            val completedProbe = requireNotNull(probe)
            completedProbe.join(5_000)
            completedProbe.isAlive shouldBe false
            probeFailure.get() shouldBe null
            postSealSuccesses.get() shouldBe 0
            val csrf = requireNotNull(initialized.get())
            val token = csrf.issue()
            csrf.validate(token, token) shouldBe true
            driverCloseCount.get() shouldBe 1
        } catch (failure: Throwable) {
            primary = failure
            interrupted = failure is InterruptedException
        } finally {
            operationRelease.countDown()
            val failures = IdentitySafeFailureAccumulator()
            failures.add(primary)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            listOf(initializer, drainer, probe).forEach { worker ->
                if (worker == null) return@forEach
                val remaining = deadline - System.nanoTime()
                try {
                    worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                } catch (failure: InterruptedException) {
                    interrupted = true
                    failures.add(failure)
                }
            }
            val workersStopped = listOf(initializer, drainer, probe).all { it?.isAlive != true }
            if (!workersStopped) {
                val survivor = IllegalStateException("ProxyCsrf fixture thread survived cleanup")
                failures.add(survivor)
                listOf(initializer, drainer, probe).forEach { worker -> if (worker?.isAlive == true) worker.interrupt() }
            } else {
                val ownerCleanup = boundedKoinCleanup("ProxyCsrf owner") { owner.close() }
                interrupted = interrupted || ownerCleanup.interrupted
                failures.add(ownerCleanup.failure)
                if (ownerCleanup.completed) {
                    val driverCleanup = boundedKoinCleanup("ProxyCsrf driver") {
                        driver?.let { closeOnce(driverClosed, it::close, driverCloseCount) }
                    }
                    interrupted = interrupted || driverCleanup.interrupted
                    failures.add(driverCleanup.failure)
                }
            }
            primary = failures.failure
            if (workersStopped && primary == null) {
                deleteTree(contentDir)
                deleteTree(dataDir)
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        primary?.let { throw it }
    }

    test("borrowed graph inputs are not entered into the owner's service drain") {
        val owner = ServerResourceOwner()
        val borrowedCloseCount = AtomicInteger()
        val borrowed = AutoCloseable { borrowedCloseCount.incrementAndGet() }
        val app = createOwnedTestKoinApplication(
            owner,
            listOf(module { single<AutoCloseable> { borrowed } }),
        )
        try {
            app.koin.get<AutoCloseable>() shouldBe borrowed
            app.close()
            owner.close()
            borrowedCloseCount.get() shouldBe 0
        } finally {
            owner.close()
            borrowed.close()
        }
        borrowedCloseCount.get() shouldBe 1
    }
})

private fun closeOnce(closed: AtomicBoolean, close: () -> Unit, count: AtomicInteger) {
    if (closed.compareAndSet(false, true)) {
        count.incrementAndGet()
        close()
    }
}

private data class BoundedCleanupResult(
    val completed: Boolean,
    val interrupted: Boolean,
    val failure: Throwable?,
)

private fun boundedKoinCleanup(label: String, action: () -> Unit): BoundedCleanupResult {
    val failure = AtomicReference<Throwable?>()
    val worker = thread(start = false, isDaemon = true, name = "koin-cleanup-${label.replace(' ', '-')}") {
        runCatching(action).onFailure(failure::set)
    }
    worker.start()
    var interrupted = false
    try {
        worker.join(5_000)
    } catch (_: InterruptedException) {
        interrupted = true
        worker.interrupt()
    }
    if (worker.isAlive) {
        try {
            worker.join(100)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    val cleanupFailure = failure.get() ?: if (worker.isAlive) {
        IllegalStateException("$label cleanup survived its bound")
    } else {
        null
    }
    return BoundedCleanupResult(!worker.isAlive, interrupted, cleanupFailure)
}

private class HoldingAppMetaDriver(
    private val delegate: SqlDriver,
    private val operationEntered: CountDownLatch,
    private val operationRelease: CountDownLatch,
) : SqlDriver by delegate {

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.contains("app_meta", ignoreCase = true)) {
            operationEntered.countDown()
            check(operationRelease.await(5, TimeUnit.SECONDS)) { "app_meta operation release timed out" }
        }
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

private fun deleteTree(root: Path) {
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
