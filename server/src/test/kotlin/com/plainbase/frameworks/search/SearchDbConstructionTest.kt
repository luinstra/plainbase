package com.plainbase.frameworks.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Regression tests for connections acquired before [SearchDb] has returned an instance to its caller. */
class SearchDbConstructionTest : FunSpec({

    test("should preserve a CREATE TABLE failure and close the acquired writer exactly once") {
        withTempDatabasePath { path, connections ->
            val failure = IllegalStateException("create search_meta failed")
            val writerCloseFailure = IllegalStateException("writer close failed")
            val writer = connections.recordingConnection(
                path,
                statementFailure = { sql ->
                    if (sql.startsWith("CREATE TABLE search_meta")) throw failure
                },
                closeFailure = writerCloseFailure,
            )

            val actual = thrown { SearchDb(path, { writer }) }

            actual shouldBeSameInstanceAs failure
            writer.closeCount shouldBe 1
            writer.isClosed shouldBe true
            actual.suppressed.single() shouldBeSameInstanceAs writerCloseFailure
        }
    }

    test("outer cleanup preserves its primary close failure and closes later readers") {
        withTempDatabasePath { path, connections ->
            val failure = IllegalStateException("reader PRAGMA failed")
            val writer = connections.recordingConnection(path, closeFailure = failure)
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
                SearchDb(path, {
                    connections[index++]
                })
            }

            actual shouldBeSameInstanceAs failure
            connections.forEach {
                it.closeCount shouldBe 1
                it.isClosed shouldBe true
            }
            actual.suppressed.none { it === failure } shouldBe true
        }
    }

    test("failed reader cleanup preserves its primary when its real close throws the same instance") {
        withTempDatabasePath { path, connections ->
            val failure = IllegalStateException("reader PRAGMA failed")
            val writer = connections.recordingConnection(path)
            val firstReader = connections.recordingConnection(path)
            val failedReader = connections.recordingConnection(
                path,
                statementFailure = { sql ->
                    if (sql.startsWith("PRAGMA journal_mode=WAL")) throw failure
                },
                closeFailure = failure,
            )
            val connections = listOf(writer, firstReader, failedReader)
            var index = 0

            val actual = thrown {
                SearchDb(path, {
                    connections[index++]
                })
            }

            actual shouldBeSameInstanceAs failure
            connections.forEach {
                it.closeCount shouldBe 1
                it.isClosed shouldBe true
            }
            actual.suppressed.none { it === failure } shouldBe true
        }
    }

    test("should close a real connection when PRAGMA setup fails immediately after acquisition") {
        withTempDatabasePath { path, connections ->
            val failure = IllegalStateException("journal mode failed")
            val closeFailure = IllegalStateException("connection close failed")
            val connection = connections.recordingConnection(
                path,
                statementFailure = { sql ->
                    if (sql.startsWith("PRAGMA journal_mode=WAL")) throw failure
                },
                closeFailure = closeFailure,
            )

            val actual = thrown { SearchDb(path, { connection }) }

            actual shouldBeSameInstanceAs failure
            connection.closeCount shouldBe 1
            connection.isClosed shouldBe true
            actual.suppressed.single() shouldBeSameInstanceAs closeFailure
        }
    }

    test("should close every fully constructed connection when one reader close fails") {
        withTempDatabasePath { path, connections ->
            val closeFailure = IllegalStateException("first reader close failed")
            val secondCloseFailure = IllegalStateException("second reader close failed")
            val writerCloseFailure = IllegalStateException("writer close failed")
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

            actual shouldBeSameInstanceAs closeFailure
            connections.map { it.isClosed } shouldBe List(connections.size) { true }
            connections.forEach {
                it.closeCount shouldBe 1
            }
            actual.suppressed.toList() shouldBe listOf(secondCloseFailure, writerCloseFailure)
        }
    }
})

private class ConnectionRegistry {
    private val connections = mutableListOf<ConstructionRecordingConnection>()

    fun recordingConnection(
        path: Path,
        statementFailure: ((String) -> Unit)? = null,
        closeFailure: Throwable? = null,
    ): ConstructionRecordingConnection = ConstructionRecordingConnection(
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

private class ConstructionRecordingConnection(
    private val delegate: Connection,
    private val statementFailure: ((String) -> Unit)?,
    private val closeFailure: Throwable?,
) : Connection by delegate {
    var closeCount: Int = 0
        private set

    override fun createStatement(): Statement = ConstructionRecordingStatement(delegate.createStatement(), statementFailure)

    override fun close() {
        closeCount++
        delegate.close()
        closeFailure?.let { throw it }
    }
}

private class ConstructionRecordingStatement(
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

private fun withTempDatabasePath(block: (Path, ConnectionRegistry) -> Unit) {
    val directory = Files.createTempDirectory("plainbase-searchdb-construction")
    val connections = ConnectionRegistry()
    var primary: Throwable? = null
    try {
        block(directory.resolve("search.db"), connections)
    } catch (failure: Throwable) {
        primary = failure
    } finally {
        val cleanupFailure = connections.closeRetained(primary)
        if (cleanupFailure != null) {
            if (primary != null) primary.addSuppressed(cleanupFailure) else primary = cleanupFailure
        }
        if (connections.allClosed()) {
            directory.toFile().deleteRecursively()
        } else {
            val survivor = IllegalStateException("retaining search.db fixture because a connection is open")
            if (primary != null) primary.addSuppressed(survivor) else primary = survivor
        }
    }
    primary?.let { throw it }
}
