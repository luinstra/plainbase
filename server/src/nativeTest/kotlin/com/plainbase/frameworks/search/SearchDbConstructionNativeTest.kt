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
        withTempDatabasePath { path, connections ->
            val failure = IllegalStateException("native reader PRAGMA failed")
            val writer = connections.recordingConnection(path)
            val firstReader = connections.recordingConnection(path)
            val failedReader = connections.recordingConnection(
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
            connections.forEach { connection ->
                assertTrue(connection.closeCount == 1)
                assertTrue(connection.isClosed)
            }
        }
    }

    @Test
    fun `a fully constructed close failure does not skip later JDBC connections in the native image`() {
        withTempDatabasePath { path, connections ->
            val closeFailure = IllegalStateException("native reader close failed")
            val secondCloseFailure = IllegalStateException("native second reader close failed")
            val writerCloseFailure = IllegalStateException("native writer close failed")
            val writer = connections.recordingConnection(path, closeFailure = writerCloseFailure)
            val readers = List(SearchDb.READER_POOL_SIZE) { index ->
                connections.recordingConnection(
                    path,
                    closeFailure = when (index) {
                        0 -> closeFailure
                        1 -> secondCloseFailure
                        else -> null
                    },
                )
            }
            val connections = listOf(writer) + readers
            var index = 0
            val search = SearchDb(path) { connections[index++] }

            val actual = thrown { search.close() }

            assertTrue(actual === closeFailure)
            connections.forEach { connection -> assertTrue(connection.isClosed) }
            connections.forEach { connection ->
                assertTrue(connection.closeCount == 1)
            }
            assertTrue(actual.suppressed.contentEquals(arrayOf(secondCloseFailure, writerCloseFailure)))
        }
    }
}

private class NativeConnectionRegistry {
    private val connections = mutableListOf<NativeConstructionRecordingConnection>()

    fun recordingConnection(
        path: Path,
        statementFailure: ((String) -> Unit)? = null,
        closeFailure: Throwable? = null,
    ): NativeConstructionRecordingConnection = NativeConstructionRecordingConnection(
        DriverManager.getConnection("jdbc:sqlite:$path"),
        statementFailure,
        closeFailure,
    ).also(connections::add)

    fun closeRetained(primary: Throwable?): Throwable? {
        var cleanupFailure: Throwable? = null
        connections.filter { !it.isClosed }.forEach { connection ->
            try {
                connection.close()
                check(connection.isClosed) { "retained search.db connection remained open" }
            } catch (cleanup: Throwable) {
                if (primary != null) {
                    if (cleanup !== primary) primary.addSuppressed(cleanup)
                } else if (cleanupFailure == null) {
                    cleanupFailure = cleanup
                } else if (cleanup !== cleanupFailure) {
                    requireNotNull(cleanupFailure).addSuppressed(cleanup)
                }
            }
        }
        if (connections.any { !it.isClosed }) {
            val survivor = IllegalStateException("search.db connection remained open after fallback cleanup")
            if (primary != null) primary.addSuppressed(survivor) else cleanupFailure = survivor
        }
        return cleanupFailure
    }

    fun allClosed(): Boolean = connections.all { it.isClosed }
}

private class NativeConstructionRecordingConnection(
    private val delegate: Connection,
    private val statementFailure: ((String) -> Unit)?,
    private val closeFailure: Throwable?,
) : Connection by delegate {
    var closeCount: Int = 0
        private set

    override fun createStatement(): Statement = NativeConstructionRecordingStatement(delegate.createStatement(), statementFailure)

    override fun close() {
        closeCount++
        delegate.close()
        closeFailure?.let { throw it }
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

private fun withTempDatabasePath(block: (Path, NativeConnectionRegistry) -> Unit) {
    val directory = Files.createTempDirectory("plainbase-searchdb-construction-native")
    val connections = NativeConnectionRegistry()
    var primary: Throwable? = null
    try {
        block(directory.resolve("search.db"), connections)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        val cleanupFailure = connections.closeRetained(primary)
        if (cleanupFailure != null) {
            if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
        }
        if (connections.allClosed()) {
            directory.toFile().deleteRecursively()
        } else {
            val survivor = IllegalStateException("retaining search.db fixture because a connection is open")
            if (primary != null) primary.addSuppressed(survivor) else throw survivor
        }
    }
}
