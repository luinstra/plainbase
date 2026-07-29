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

private const val SQLITE_BUSY_BINDER_THREAD_NAME = "sqlite-busy-binder"

/**
 * The STOCK-DRIVER CONTROL for the SQLITE_BUSY falsifier, and the only row of it that cannot live in `nativeTest`.
 *
 * The positive rows (a file-backed bind waiting out a real writer, and a failed BEGIN leaving the thread's next
 * transaction clean) live in `SqliteBusyBeginImmediateNativeTest`. That source set is folded into the JVM `test` task
 * (see server/build.gradle.kts), so those rows ALREADY run on both the JVM and the native image; duplicating them here
 * would buy nothing and add two harnesses to keep in step.
 *
 * This row is the differential half. The same race on the STOCK SQLDelight driver returns SQLITE_BUSY without waiting,
 * because a deferred read-to-write promotion skips the busy handler. It proves the harness can OBSERVE the defect, so a
 * green positive row is evidence rather than an assumption. It lives here because the stock driver is
 * `testImplementation` only and is deliberately absent from the native classpath, which is what keeps
 * `JdbcSqliteDriver` unreachable from main source.
 *
 * [runRace] is kept identical to the native companion's copy; change the two together.
 */
class SqliteBusyBeginImmediateTest : FunSpec({

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
})

private data class RaceResult(
    val holderFailure: Throwable?,
    val bindFailure: Throwable?,
    val bindOutcome: BindOutcome?,
    val binderAliveBeforeRelease: Boolean,
    val binderBeginReturnedBeforeRelease: Boolean,
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
