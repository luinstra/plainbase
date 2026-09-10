package com.plainbase.frameworks.search

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertTrue

/** Native twin for the constructor-local JDBC cleanup contract. */
@Tag("native")
class SearchDbConstructionNativeTest {

    @Test
    fun `a later reader setup failure closes every acquired connection exactly once in the native image`() {
        withTempDatabasePath { path ->
            val failure = IllegalStateException("native reader PRAGMA failed")
            val writer = recordingConnection(path)
            val firstReader = recordingConnection(path)
            val failedReader = recordingConnection(
                path,
                statementFailure = { sql ->
                    if (sql.startsWith("PRAGMA journal_mode=WAL")) throw failure
                },
            )
            val connections = listOf(writer, firstReader, failedReader)
            var index = 0

            val actual = thrown {
                SearchDb(path, { connections[index++] })
            }

            assertTrue(actual === failure)
            connections.forEach { connection -> assertTrue(connection.closeCount == 1) }
        }
    }
}

private fun recordingConnection(path: Path, statementFailure: ((String) -> Unit)? = null): NativeConstructionRecordingConnection =
    NativeConstructionRecordingConnection(
        DriverManager.getConnection("jdbc:sqlite:$path"),
        statementFailure,
    )

private class NativeConstructionRecordingConnection(
    private val delegate: Connection,
    private val statementFailure: ((String) -> Unit)?,
) : Connection by delegate {
    var closeCount: Int = 0
        private set

    override fun createStatement(): Statement = NativeConstructionRecordingStatement(delegate.createStatement(), statementFailure)

    override fun close() {
        closeCount++
        delegate.close()
    }
}

private class NativeConstructionRecordingStatement(
    private val delegate: Statement,
    private val failure: ((String) -> Unit)?,
) : Statement by delegate {
    override fun execute(sql: String): Boolean {
        failure?.invoke(sql)
        return delegate.execute(sql)
    }
}

private fun thrown(block: () -> Unit): Throwable =
    try {
        block()
        error("expected construction to fail")
    } catch (failure: Throwable) {
        failure
    }

private fun withTempDatabasePath(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("plainbase-searchdb-construction-native")
    try {
        block(directory.resolve("search.db"))
    } finally {
        directory.toFile().deleteRecursively()
    }
}
