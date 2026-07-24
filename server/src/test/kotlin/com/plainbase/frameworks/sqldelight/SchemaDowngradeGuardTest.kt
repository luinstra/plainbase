package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.SqlDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The FORWARD-only old-binary guard's no-leak lifecycle (§8.2, R7 part 2): `DatabaseFactory.migrateOrClose`, handed a
 * driver whose delegate is stamped NEWER than this binary, THROWS the named guard error AND closes the handle EXACTLY
 * once - the observable proof that a rejected boot does not leak the open connection (an idle leaked connection holds no
 * lock a reopen check could catch). Lives in the JVM `test` source set because `migrateOrClose` is `internal` (visible
 * from the friend-pathed `test`, not from `nativeTest`); it drives an IN-MEMORY delegate, so it needs no JDBC/JNI seam.
 * The in-image half (the real file-backed refuse + read-only serve) is `SchemaDowngradeGuardNativeTest`.
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
