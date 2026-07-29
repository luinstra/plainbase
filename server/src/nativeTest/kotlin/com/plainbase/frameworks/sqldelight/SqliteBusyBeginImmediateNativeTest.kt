package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import org.sqlite.SQLiteErrorCode
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SQLITE_BUSY_TIMEOUT_MS = 3_000L
private const val SQLITE_BUSY_BINDER_THREAD_NAME = "sqlite-busy-binder"

/**
 * Native companion to [SqliteBusyBeginImmediateTest]. It keeps the xerial JDBC/JNI falsifier in the native gate as
 * well as the JVM suite, while remaining a file-backed, two-thread test wired through [DatabaseFactory.createDriver].
 * The JVM and native harnesses are intentional copies; changes to contention barriers or assertions must be made
 * together.
 */
@Tag("native")
class SqliteBusyBeginImmediateNativeTest {

    @Test
    fun `a file-backed bind waits for a real writer in the native image`() {
        val directory = Files.createTempDirectory("pb-native-sqlite-busy")
        val databasePath = directory.resolve("plainbase.db")
        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val bindStarted = CountDownLatch(1)
        val transactionStarts = CountDownLatch(2)
        val binderBeginReturned = CountDownLatch(1)
        val holderFailure = AtomicReference<Throwable?>()
        val bindFailure = AtomicReference<Throwable?>()
        val bindOutcome = AtomicReference<BindOutcome?>()
        val holderPath = RootedPath(RootName.PRIMARY, TreePath.require("holder.md"))
        val contenderPath = RootedPath(RootName.PRIMARY, TreePath.require("contender.md"))
        val holderId = PageId.require("01900000-0000-7000-8000-0000000000f1")
        val contenderId = PageId.require("01900000-0000-7000-8000-0000000000f2")

        try {
            DatabaseFactory.createDriver(databasePath).use { driver ->
                val db = DatabaseFactory.createDatabase(
                    TransactionStartSignalDriver(driver, transactionStarts, binderBeginReturned),
                )
                val idMap = SqlDelightIdMapRepository(db)

                val holder = thread(name = "native-sqlite-busy-holder") {
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
                    }
                    binder = runningBinder
                    check(bindStarted.await(10, TimeUnit.SECONDS)) { "binder never started" }
                    check(transactionStarts.await(10, TimeUnit.SECONDS)) { "binder never reached newTransaction" }
                    assertTrue(runningBinder.isAlive, "binder was not alive after its transaction start was observed")
                    assertFalse(
                        binderBeginReturned.await(200, TimeUnit.MILLISECONDS),
                        "binder returned from BEGIN while the holder was still held",
                    )
                    releaseHolder.countDown()
                    assertTrue(
                        binderBeginReturned.await(10, TimeUnit.SECONDS),
                        "binder did not return from BEGIN after holder release",
                    )

                    holder.join(10_000)
                    runningBinder.join(10_000)
                    assertFalse(holder.isAlive, "holder did not finish")
                    assertFalse(runningBinder.isAlive, "binder did not finish")
                    assertNull(holderFailure.get())
                    assertNull(bindFailure.get())
                    assertEquals(BindOutcome.Bound, bindOutcome.get())
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

    @Test
    fun `a failed begin leaves the same native worker transaction clean`() {
        val directory = Files.createTempDirectory("pb-native-sqlite-busy-recovery")
        val databasePath = directory.resolve("plainbase.db")
        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val firstFailureObserved = CountDownLatch(1)
        val allowSecond = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val holderFailure = AtomicReference<Throwable?>()
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val holderPath = RootedPath(RootName.PRIMARY, TreePath.require("native-recovery-holder.md"))
        val recoveryPath = RootedPath(RootName.PRIMARY, TreePath.require("native-recovery-probe.md"))
        val holderId = PageId.require("01900000-0000-7000-8000-0000000000f5")
        val recoveryId = PageId.require("01900000-0000-7000-8000-0000000000f6")

        try {
            DatabaseFactory.createDriver(databasePath).use { driver ->
                assertEquals(SQLITE_BUSY_TIMEOUT_MS, driver.queryLongNative("PRAGMA busy_timeout"))
                val db = DatabaseFactory.createDatabase(driver)
                val holder = thread(name = "native-sqlite-busy-recovery-holder") {
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
                    val runningWorker = thread(name = "native-sqlite-busy-recovery-worker") {
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
                    assertFalse(holder.isAlive, "holder did not finish")
                    allowSecond.countDown()
                    assertTrue(secondDone.await(10, TimeUnit.SECONDS), "the recovery transaction did not finish")
                    runningWorker.join(10_000)
                    assertFalse(runningWorker.isAlive, "recovery worker did not finish")

                    assertNull(holderFailure.get())
                    val firstException = assertNotNull(firstFailure.get())
                    assertTrue(firstException.message.orEmpty().contains(SQLiteErrorCode.SQLITE_BUSY.toString()))
                    assertEquals("rollback probe", secondFailure.get()?.message)
                    val recoveryPersisted = db.idMapQueries.selectAllBindings().executeAsList().any { it.id == recoveryId }
                    assertFalse(recoveryPersisted, "the throwing recovery transaction persisted its write")
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
}

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

private fun SqlDriver.queryLongNative(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
