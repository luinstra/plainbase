package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.SqlDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The in-memory delegated driver pins exactly one close when schema rejection occurs;
 * [SchemaDowngradeGuardNativeTest] supplies the real file-backed JDBC/native coverage.
 */
class SchemaDowngradeGuardTest : FunSpec({

    test("migrateOrClose throws the downgrade guard and closes the handle EXACTLY once") {
        val delegate = DatabaseFactory.createInMemoryDriver() // created + migrated to current
        delegate.execute(null, "PRAGMA user_version = 999;", 0) // stamp NEWER so migrate() throws the guard
        val recording = ClosingDelegate(delegate)

        val error = shouldThrow<IllegalStateException> { DatabaseFactory.migrateOrClose(recording) }
        error.message shouldContain "NEWER than this binary understands"
        recording.closeCount shouldBe 1
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
