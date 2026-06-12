package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Native-image smoke test for the `adopt` path (chunk 4b): the previous status was a manual run of
 * the native binary; this puts the same exercise inside the automated native gate. A tiny temp tree
 * plus a file-backed DATA_DIR DB drives the real CLI entry (`AdoptCommand.run`), covering
 * LocalContentStore, the patcher, DatabaseFactory's create/migrate path, and the SQLDelight
 * repositories under the closed-world image.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image, so
 * Kotest/MockK must never appear here (see the test-stack split in build.gradle.kts).
 */
@Tag("native")
class AdoptCommandNativeTest {

    @Test
    fun `adopt --write-ids assigns 16-byte ids and a second run performs zero writes`() {
        val content = Files.createTempDirectory("pb-native-adopt-content")
        val data = Files.createTempDirectory("pb-native-adopt-data")
        try {
            val pages = listOf("plain.md", "nested.md")
            Files.writeString(content.resolve("plain.md"), "# Plain\n\nNo frontmatter here.\n")
            Files.writeString(content.resolve("nested.md"), "---\ntitle: Nested\n---\n# Nested\n")
            val config = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0)

            val first = captureStdout { assertEquals(0, AdoptCommand.run(listOf("--write-ids"), config)) }
            assertContains(first, "intent: write id")
            pages.forEach { assertContains(Files.readString(content.resolve(it)), "id: ", message = "$it not materialized") }

            // Binary at rest, asserted below the typed query layer (the chunk 4b direct-SQL criterion).
            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                assertEquals(2L, driver.queryLong("SELECT count(*) FROM id_map"))
                assertEquals(0L, driver.queryLong("SELECT count(*) FROM id_map WHERE length(id) != 16"))
            }

            // Idempotence: the second run announces no write intent and leaves every byte in place.
            val bytesAfterFirst = pages.map { Files.readAllBytes(content.resolve(it)) }
            val second = captureStdout { assertEquals(0, AdoptCommand.run(listOf("--write-ids"), config)) }
            assertFalse("intent:" in second, "second adopt run intended a write:\n$second")
            pages.zip(bytesAfterFirst).forEach { (page, before) ->
                assertContentEquals(before, Files.readAllBytes(content.resolve(page)), "$page changed on the second run")
            }
        } finally {
            listOf(content, data).forEach { dir ->
                Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}

/** Captures System.out for the duration of [block] — `adopt`'s stdout is its output contract. */
private fun captureStdout(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.out
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}

/** Single-value raw SQL against the driver (src/test's queryLong helper is Kotest-side, not here). */
private fun SqlDriver.queryLong(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
