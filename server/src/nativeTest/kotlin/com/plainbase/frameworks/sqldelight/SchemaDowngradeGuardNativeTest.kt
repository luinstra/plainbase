package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The FORWARD-only old-binary guard (§8.2) at the xerial JDBC/JNI seam. Two IN-IMAGE proofs over the real file-backed
 * driver:
 *  1. `createDriver` on a file stamped v999 THROWS the named "NEWER than this binary understands" error (and does not
 *     leak: the throw propagates through `migrateOrClose`, which closes the handle);
 *  2. `createReadOnlyDriver` serves BOTH a current-schema file and a NEWER (v999) one file-backed: a read-only open
 *     cannot corrupt anything, so `adopt --dry-run`'s nothing-was-written promise is honored even against a schema
 *     this binary does not understand. The forward-only refusal is the WRITABLE path's alone (proof 1).
 *
 * The `migrateOrClose` close-EXACTLY-once lifecycle proof (a delegating close-recording driver) lives in the JVM
 * `SchemaDowngradeGuardTest`: `migrateOrClose` is `internal`, so it is visible from the friend-pathed `test` source set
 * but NOT from `nativeTest` - and that proof drives an IN-MEMORY delegate, so it needs no JDBC/JNI seam anyway.
 *
 * @Tag("native") + kotlin.test only.
 */
@Tag("native")
class SchemaDowngradeGuardNativeTest {

    @Test
    fun `createDriver refuses a NEWER-schema database in-image`() {
        val dir = Files.createTempDirectory("pb-native-downgrade")
        try {
            val dbPath = dir.resolve("plainbase.db")
            DatabaseFactory.createDriver(dbPath).use { /* create + migrate to current */ }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { it.execute("PRAGMA user_version = 999") } // a still-NEWER binary wrote it
            }
            val error = runCatching { DatabaseFactory.createDriver(dbPath).close() }.exceptionOrNull()
            assertTrue(error is IllegalStateException, "expected an IllegalStateException, was $error")
            assertTrue(
                error.message?.contains("NEWER than this binary understands") == true,
                "expected the named downgrade-guard message, was: ${error.message}",
            )
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `createReadOnlyDriver over a NEWER-schema file serves it file-backed in-image`() {
        val dir = Files.createTempDirectory("pb-native-readonly-newer")
        try {
            val dbPath = dir.resolve("plainbase.db")
            DatabaseFactory.createDriver(dbPath).use { /* create + migrate to current */ }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { it.execute("PRAGMA user_version = 999") } // a still-NEWER binary wrote it
            }
            // A read-only open cannot corrupt anything, so the newer file is served rather than refused: the driver is
            // the FILE-BACKED one (user_version 999), not the empty in-memory stand-in a downgrade would fall back to.
            DatabaseFactory.createReadOnlyDriver(dbPath).use { driver ->
                val version = driver.executeQuery(
                    identifier = null,
                    sql = "PRAGMA user_version",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: fail("no row"))
                    },
                    parameters = 0,
                ).value
                assertEquals(999L, version, "expected the file-backed v999 driver, not the in-memory stand-in")
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `createReadOnlyDriver over a current-schema file serves a trivial SELECT in-image`() {
        val dir = Files.createTempDirectory("pb-native-readonly")
        try {
            val dbPath = dir.resolve("plainbase.db")
            DatabaseFactory.createDriver(dbPath).use { /* current schema on disk */ }
            DatabaseFactory.createReadOnlyDriver(dbPath).use { driver ->
                val value = driver.executeQuery(
                    identifier = null,
                    sql = "SELECT 1",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: fail("no row"))
                    },
                    parameters = 0,
                ).value
                assertEquals(1L, value)
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
