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
        withTempDatabasePath { path ->
            val failure = IllegalStateException("create search_meta failed")
            val writerCloseFailure = IllegalStateException("writer close failed")
            val writer = recordingConnection(
                path,
                statementFailure = { sql ->
                    if (sql.startsWith("CREATE TABLE search_meta")) throw failure
                },
                closeFailure = writerCloseFailure,
            )

            val actual = thrown { SearchDb(path, { writer }) }

            actual shouldBeSameInstanceAs failure
            writer.closeCount shouldBe 1
            actual.suppressed.single() shouldBeSameInstanceAs writerCloseFailure
        }
    }

    test("outer cleanup preserves its primary close failure and closes later readers") {
        withTempDatabasePath { path ->
            val failure = IllegalStateException("reader PRAGMA failed")
            val writer = recordingConnection(path, closeFailure = failure)
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
                SearchDb(path, {
                    connections[index++]
                })
            }

            actual shouldBeSameInstanceAs failure
            connections.forEach { it.closeCount shouldBe 1 }
            actual.suppressed.none { it === failure } shouldBe true
        }
    }

    test("failed reader cleanup preserves its primary when its real close throws the same instance") {
        withTempDatabasePath { path ->
            val failure = IllegalStateException("reader PRAGMA failed")
            val writer = recordingConnection(path)
            val firstReader = recordingConnection(path)
            val failedReader = recordingConnection(
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
            connections.forEach { it.closeCount shouldBe 1 }
            actual.suppressed.none { it === failure } shouldBe true
        }
    }

    test("should close a real connection when PRAGMA setup fails immediately after acquisition") {
        withTempDatabasePath { path ->
            val failure = IllegalStateException("journal mode failed")
            val closeFailure = IllegalStateException("connection close failed")
            val connection = recordingConnection(
                path,
                statementFailure = { sql ->
                    if (sql.startsWith("PRAGMA journal_mode=WAL")) throw failure
                },
                closeFailure = closeFailure,
            )

            val actual = thrown { SearchDb(path, { connection }) }

            actual shouldBeSameInstanceAs failure
            connection.closeCount shouldBe 1
            actual.suppressed.single() shouldBeSameInstanceAs closeFailure
        }
    }
})

private fun recordingConnection(
    path: Path,
    statementFailure: ((String) -> Unit)? = null,
    closeFailure: Throwable? = null,
): ConstructionRecordingConnection =
    ConstructionRecordingConnection(
        DriverManager.getConnection("jdbc:sqlite:$path"),
        statementFailure,
        closeFailure,
    )

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

private fun withTempDatabasePath(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("plainbase-searchdb-construction")
    try {
        block(directory.resolve("search.db"))
    } finally {
        directory.toFile().deleteRecursively()
    }
}
