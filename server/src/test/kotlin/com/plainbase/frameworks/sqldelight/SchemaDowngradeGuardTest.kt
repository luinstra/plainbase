package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.sql.Connection

/**
 * The in-memory delegated driver pins exactly one close when schema rejection occurs;
 * [SchemaDowngradeGuardNativeTest] supplies the real file-backed JDBC/native coverage.
 */
class SchemaDowngradeGuardTest : FunSpec({

    test("migrateOrClose throws the downgrade guard and closes the handle EXACTLY once") {
        val delegate = DatabaseFactory.createInMemoryDriver() // created + migrated to current
        val connection = (delegate as BeginImmediateSqliteDriver).getConnection()
        delegate.execute(null, "PRAGMA user_version = 999;", 0) // stamp NEWER so migrate() throws the guard
        val recording = ClosingDelegate(delegate)
        var testFailure: Throwable? = null
        try {
            val error = shouldThrow<IllegalStateException> { DatabaseFactory.migrateOrClose(recording) }
            error.message shouldContain "NEWER than this binary understands"
            recording.closeCount shouldBe 1
            connection.isClosed shouldBe true
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            closeRetainedConnection(connection, testFailure)
        }
    }

    test("migrateOrClose preserves the migration failure when the acquired driver close also fails") {
        val delegate = DatabaseFactory.createInMemoryDriver()
        val connection = (delegate as BeginImmediateSqliteDriver).getConnection()
        delegate.execute(null, "PRAGMA user_version = 999;", 0)
        val recording = ClosingDelegate(delegate)
        val cleanup = IllegalStateException("driver close failed")
        var testFailure: Throwable? = null
        try {
            val error = shouldThrow<IllegalStateException> {
                DatabaseFactory.migrateOrClose(recording) {
                    recording.close()
                    throw cleanup
                }
            }

            error.message shouldContain "NEWER than this binary understands"
            recording.closeCount shouldBe 1
            connection.isClosed shouldBe true
            error.suppressed.single() shouldBe cleanup
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            closeRetainedConnection(connection, testFailure)
        }
    }

    test("migrateOrClose preserves a known migration SQL failure and closes its real connection") {
        val delegate = DatabaseFactory.createInMemoryDriver()
        val connection = (delegate as BeginImmediateSqliteDriver).getConnection()
        val failure = IllegalStateException("migration SQL sentinel")
        val cleanup = IllegalStateException("real driver close failed")
        val recording = MigrationFailureDelegate(delegate, failure)
        var testFailure: Throwable? = null
        try {
            val error = shouldThrow<IllegalStateException> {
                DatabaseFactory.migrateOrClose(recording) {
                    recording.close()
                    throw cleanup
                }
            }

            error shouldBeSameInstanceAs failure
            recording.closeCount shouldBe 1
            connection.isClosed shouldBe true
            error.suppressed.single() shouldBeSameInstanceAs cleanup
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            closeRetainedConnection(connection, testFailure)
        }
    }
})

/** A [SqlDriver] that delegates everything but RECORDS how many times [close] is called - the no-leak observability. */
private class ClosingDelegate(private val delegate: SqlDriver) : SqlDriver by delegate {
    var closeCount = 0
        private set

    override fun close() {
        closeCount++
        delegate.close()
    }
}

private class MigrationFailureDelegate(
    private val delegate: SqlDriver,
    private val failure: Throwable,
) : SqlDriver by delegate {
    var closeCount = 0
        private set

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.trim() == "PRAGMA user_version;") throw failure
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun close() {
        closeCount++
        delegate.close()
    }
}

private fun closeRetainedConnection(connection: Connection, primary: Throwable?) {
    if (connection.isClosed) return
    try {
        connection.close()
        check(connection.isClosed) { "retained app database connection remained open" }
    } catch (cleanup: Throwable) {
        if (primary != null) {
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        } else {
            throw cleanup
        }
    }
}
