package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.sqlite.SQLiteErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val SQLITE_BUSY_TIMEOUT_MS = 3_000L
private const val SQLITE_BUSY_BINDER_THREAD_NAME = "sqlite-busy-binder"

/**
 * The file-backed, two-thread falsifier for deferred transaction promotion.
 *
 * Thread A reads, writes, and holds its transaction after acquiring SQLite's RESERVED lock. Thread B calls the real
 * repository through [DatabaseFactory.createDriver]. With the stock SQLDelight driver, B's deferred read-to-write
 * promotion returns SQLITE_BUSY immediately. With [BeginImmediateSqliteDriver], B waits for A and returns [BindOutcome.Bound].
 * The native companion intentionally mirrors this harness; changes to its contention barriers or assertions must be
 * made together.
 */
class SqliteBusyBeginImmediateTest : FunSpec({

    test("should wait for the holder when a file-backed bind races a real writer") {
        val result = runRace(driverFactory = DatabaseFactory::createDriver)

        result.holderFailure shouldBe null
        result.bindFailure shouldBe null
        result.bindOutcome shouldBe BindOutcome.Bound
        result.binderAliveBeforeRelease shouldBe true
        result.binderBeginReturnedBeforeRelease shouldBe false
    }

    test("stock SQLDelight driver should report SQLITE_BUSY for the same race") {
        val result = runRace(waitForBindFailureBeforeRelease = true) { databasePath ->
            val driver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")
            PlainbaseDb.Schema.create(driver)
            driver
        }

        result.holderFailure shouldBe null
        val bindFailure = checkNotNull(result.bindFailure) { "stock driver unexpectedly completed the bind" }
        bindFailure.message shouldContain SQLiteErrorCode.SQLITE_BUSY.toString()
        result.bindOutcome shouldBe null
        result.binderBeginReturnedBeforeRelease shouldBe true
    }

    test("clears a failed begin before the same thread's next transaction") {
        val result = runBeginFailureRecovery(DatabaseFactory::createDriver)

        result.holderFailure shouldBe null
        val firstFailure = checkNotNull(result.firstFailure) { "the first transaction unexpectedly completed" }
        firstFailure.message shouldContain SQLiteErrorCode.SQLITE_BUSY.toString()
        result.secondFailure?.message shouldBe "rollback probe"
        result.recoveryPersisted shouldBe false
    }
})

private data class RaceResult(
    val holderFailure: Throwable?,
    val bindFailure: Throwable?,
    val bindOutcome: BindOutcome?,
    val binderAliveBeforeRelease: Boolean,
    val binderBeginReturnedBeforeRelease: Boolean,
)

private data class RecoveryResult(
    val holderFailure: Throwable?,
    val firstFailure: Throwable?,
    val secondFailure: Throwable?,
    val recoveryPersisted: Boolean,
)

private class TransactionStartSignalDriver(
    private val delegate: SqlDriver,
    private val transactionStarts: CountDownLatch,
    private val binderBeginReturned: CountDownLatch,
) : SqlDriver by delegate {
    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        transactionStarts.countDown()
        return try {
            delegate.newTransaction()
        } finally {
            if (Thread.currentThread().name == SQLITE_BUSY_BINDER_THREAD_NAME) {
                binderBeginReturned.countDown()
            }
        }
    }
}

private fun runRace(
    waitForBindFailureBeforeRelease: Boolean = false,
    driverFactory: (Path) -> SqlDriver,
): RaceResult {
    val directory = Files.createTempDirectory("pb-sqlite-busy")
    val databasePath = directory.resolve("plainbase.db")
    val holderReady = CountDownLatch(1)
    val releaseHolder = CountDownLatch(1)
    val bindStarted = CountDownLatch(1)
    val transactionStarts = CountDownLatch(2)
    val binderBeginReturned = CountDownLatch(1)
    val bindDone = CountDownLatch(1)
    val holderFailure = AtomicReference<Throwable?>()
    val bindFailure = AtomicReference<Throwable?>()
    val bindOutcome = AtomicReference<BindOutcome?>()
    val holderPath = RootedPath(RootName.PRIMARY, TreePath.require("holder.md"))
    val contenderPath = RootedPath(RootName.PRIMARY, TreePath.require("contender.md"))
    val holderId = PageId.require("01900000-0000-7000-8000-0000000000f1")
    val contenderId = PageId.require("01900000-0000-7000-8000-0000000000f2")

    try {
        driverFactory(databasePath).use { driver ->
            val db = DatabaseFactory.createDatabase(
                TransactionStartSignalDriver(driver, transactionStarts, binderBeginReturned),
            )
            val idMap = SqlDelightIdMapRepository(db)

            val holder = thread(name = "sqlite-busy-holder") {
                runCatching {
                    db.transactionWithResult {
                        db.idMapQueries.selectAllBindings().executeAsList()
                        db.idMapQueries.upsertBinding(
                            root = holderPath.root,
                            path = holderPath.path,
                            id = holderId,
                            materialized = true,
                        )
                        holderReady.countDown()
                        check(releaseHolder.await(10, TimeUnit.SECONDS)) { "releaseHolder never fired" }
                    }
                }.onFailure {
                    holderFailure.set(it)
                    holderReady.countDown()
                }
            }

            var binder: Thread? = null
            try {
                check(holderReady.await(10, TimeUnit.SECONDS)) { "holder never acquired its write lock" }
                val runningBinder = thread(name = SQLITE_BUSY_BINDER_THREAD_NAME) {
                    bindStarted.countDown()
                    runCatching {
                        idMap.bind(contenderPath, contenderId, materialized = true)
                    }.onSuccess { bindOutcome.set(it) }
                        .onFailure { bindFailure.set(it) }
                        .also { bindDone.countDown() }
                }
                binder = runningBinder
                check(bindStarted.await(10, TimeUnit.SECONDS)) { "binder never started" }
                check(transactionStarts.await(10, TimeUnit.SECONDS)) { "binder never reached newTransaction" }
                val binderBeginReturnedBeforeRelease = binderBeginReturned.await(200, TimeUnit.MILLISECONDS)
                if (waitForBindFailureBeforeRelease) {
                    check(bindDone.await(10, TimeUnit.SECONDS)) { "stock binder did not finish while the holder was held" }
                }
                val binderAliveBeforeRelease = runningBinder.isAlive
                releaseHolder.countDown()
                check(binderBeginReturned.await(10, TimeUnit.SECONDS)) {
                    "binder did not return from BEGIN after holder release"
                }

                holder.join(10_000)
                runningBinder.join(10_000)
                check(!holder.isAlive) { "holder did not finish" }
                check(!runningBinder.isAlive) { "binder did not finish" }

                return RaceResult(
                    holderFailure.get(),
                    bindFailure.get(),
                    bindOutcome.get(),
                    binderAliveBeforeRelease,
                    binderBeginReturnedBeforeRelease,
                )
            } finally {
                releaseHolder.countDown()
                holder.join(10_000)
                binder?.join(10_000)
            }
        }
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private fun runBeginFailureRecovery(driverFactory: (Path) -> SqlDriver): RecoveryResult {
    val directory = Files.createTempDirectory("pb-sqlite-busy-recovery")
    val databasePath = directory.resolve("plainbase.db")
    val holderReady = CountDownLatch(1)
    val releaseHolder = CountDownLatch(1)
    val firstFailureObserved = CountDownLatch(1)
    val allowSecond = CountDownLatch(1)
    val secondDone = CountDownLatch(1)
    val holderFailure = AtomicReference<Throwable?>()
    val firstFailure = AtomicReference<Throwable?>()
    val secondFailure = AtomicReference<Throwable?>()
    val holderPath = RootedPath(RootName.PRIMARY, TreePath.require("recovery-holder.md"))
    val recoveryPath = RootedPath(RootName.PRIMARY, TreePath.require("recovery-probe.md"))
    val holderId = PageId.require("01900000-0000-7000-8000-0000000000f3")
    val recoveryId = PageId.require("01900000-0000-7000-8000-0000000000f4")

    try {
        driverFactory(databasePath).use { driver ->
            driver.queryLong("PRAGMA busy_timeout") shouldBe SQLITE_BUSY_TIMEOUT_MS
            val db = DatabaseFactory.createDatabase(driver)
            val holder = thread(name = "sqlite-busy-recovery-holder") {
                runCatching {
                    db.transactionWithResult {
                        db.idMapQueries.upsertBinding(
                            root = holderPath.root,
                            path = holderPath.path,
                            id = holderId,
                            materialized = true,
                        )
                        holderReady.countDown()
                        check(releaseHolder.await(10, TimeUnit.SECONDS)) { "releaseHolder never fired" }
                    }
                }.onFailure {
                    holderFailure.set(it)
                    holderReady.countDown()
                }
            }

            var worker: Thread? = null
            try {
                check(holderReady.await(10, TimeUnit.SECONDS)) { "holder never acquired its write lock" }
                val runningWorker = thread(name = "sqlite-busy-recovery-worker") {
                    val first = runCatching {
                        db.transactionWithResult {
                            db.idMapQueries.selectAllBindings().executeAsList()
                        }
                    }
                    firstFailure.set(first.exceptionOrNull())
                    firstFailureObserved.countDown()
                    check(allowSecond.await(10, TimeUnit.SECONDS)) { "the recovery transaction was not released" }

                    val second = runCatching {
                        db.transactionWithResult {
                            db.idMapQueries.upsertBinding(
                                root = recoveryPath.root,
                                path = recoveryPath.path,
                                id = recoveryId,
                                materialized = true,
                            )
                            error("rollback probe")
                        }
                    }
                    secondFailure.set(second.exceptionOrNull())
                    secondDone.countDown()
                }
                worker = runningWorker
                check(firstFailureObserved.await(10, TimeUnit.SECONDS)) { "the first begin did not finish" }
                releaseHolder.countDown()
                holder.join(10_000)
                check(!holder.isAlive) { "holder did not finish" }
                allowSecond.countDown()

                check(secondDone.await(10, TimeUnit.SECONDS)) { "the recovery transaction did not finish" }
                runningWorker.join(10_000)
                check(!runningWorker.isAlive) { "recovery worker did not finish" }

                val recoveryPersisted = db.idMapQueries.selectAllBindings().executeAsList().any { it.id == recoveryId }
                return RecoveryResult(
                    holderFailure = holderFailure.get(),
                    firstFailure = firstFailure.get(),
                    secondFailure = secondFailure.get(),
                    recoveryPersisted = recoveryPersisted,
                )
            } finally {
                releaseHolder.countDown()
                holder.join(10_000)
                worker?.join(10_000)
            }
        }
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
