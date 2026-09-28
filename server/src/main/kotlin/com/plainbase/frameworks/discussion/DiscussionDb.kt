package com.plainbase.frameworks.discussion

import com.plainbase.domain.discussion.DiscussionPersistenceFailure
import com.plainbase.frameworks.search.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

class DiscussionDb internal constructor(
    path: Path,
    private val connectionOpener: (String) -> Connection,
) : AutoCloseable {
    constructor(path: Path) : this(path, { url -> DriverManager.getConnection(url) })

    private val url = "jdbc:sqlite:$path"
    private val writer: Connection
    private val readers = ArrayBlockingQueue<Connection>(READER_POOL_SIZE)
    val writeLock = ReentrantLock(true)

    init {
        path.parent?.createDirectories()
        val acquired = mutableListOf<Connection>()
        @Suppress("TooGenericExceptionCaught")
        try {
            writer = open()
            acquired += writer
            ensureSchema()
            repeat(READER_POOL_SIZE) {
                val reader = open()
                acquired += reader
                readers.add(reader)
            }
        } catch (failure: Throwable) {
            acquired.forEach { connection ->
                runCatching { connection.close() }.onFailure { cleanup ->
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                    logger.warn(cleanup) { "closing a partially constructed discussions.db connection failed" }
                }
            }
            throw failure
        }
    }

    fun <T> writing(block: (Connection) -> T): T = writeLock.withLock { databaseCall { block(writer) } }

    fun <T> tryWriting(block: (Connection) -> T): T? {
        if (!writeLock.tryLock()) return null
        return try {
            databaseCall { block(writer) }
        } finally {
            writeLock.unlock()
        }
    }

    fun <T> read(block: (Connection) -> T): T {
        val connection = readers.take()
        return try {
            databaseCall { block(connection) }
        } finally {
            readers.add(connection)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        var primary: Throwable? = null
        repeat(READER_POOL_SIZE) {
            val reader = readers.take()
            try {
                reader.close()
            } catch (failure: Throwable) {
                if (primary == null) {
                    primary = failure
                } else if (failure !== primary) {
                    primary.addSuppressed(failure)
                }
            }
        }
        try {
            writeLock.withLock { writer.close() }
        } catch (failure: Throwable) {
            if (primary == null) {
                primary = failure
            } else if (failure !== primary) {
                primary.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun open(): Connection {
        val connection = connectionOpener(url)
        return try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            }
            connection
        } catch (failure: Throwable) {
            runCatching { connection.close() }.onFailure { cleanup ->
                if (cleanup !== failure) failure.addSuppressed(cleanup)
                logger.warn(cleanup) { "closing a failed discussions.db connection setup failed" }
            }
            throw failure
        }
    }

    private fun ensureSchema() {
        val hasMeta = writer.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='discussion_meta'").use { it.next() }
        }
        val recorded = if (hasMeta) {
            writer.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM discussion_meta WHERE key='schema_version'").use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        } else {
            null
        }
        if (recorded == SCHEMA_VERSION.toString()) return
        if (recorded != null) {
            logger.info { "discussions.db schema version $recorded differs from $SCHEMA_VERSION; rebuilding derived rows" }
        }
        writer.transaction {
            dropTables()
            createSchema()
        }
    }

    private fun dropTables() {
        val tables = writer.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
        tables.forEach { table ->
            writer.createStatement().use { statement ->
                statement.execute("DROP TABLE \"${table.replace("\"", "\"\"")}\"")
            }
        }
    }

    private fun createSchema() {
        writer.createStatement().use { statement ->
            statement.execute("CREATE TABLE discussion_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            statement.execute(
                """
                CREATE TABLE discussion(
                  root TEXT NOT NULL, id TEXT NOT NULL, state TEXT NOT NULL, reason TEXT, stamp TEXT,
                  page_id TEXT, page_path TEXT, status TEXT, anchor_kind TEXT, anchor_hash TEXT, starter_key TEXT,
                  created INTEGER, updated INTEGER, comment_count INTEGER NOT NULL, PRIMARY KEY(root, id)
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX discussion_by_page ON discussion(root, page_id, id)")
            statement.execute("CREATE INDEX discussion_by_path ON discussion(root, page_path, id)")
            statement.execute(
                """
                CREATE TABLE discussion_entry(
                  root TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, sha256 TEXT NOT NULL,
                  version TEXT NOT NULL, author_key TEXT NOT NULL, PRIMARY KEY(root, id, name)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE anchor_match(
                  root TEXT NOT NULL, id TEXT NOT NULL, anchor_hash TEXT NOT NULL, page_hash TEXT NOT NULL,
                  match TEXT NOT NULL, placement TEXT, PRIMARY KEY(root, id)
                )
                """.trimIndent(),
            )
            statement.execute("INSERT INTO discussion_meta(key, value) VALUES ('schema_version', '$SCHEMA_VERSION')")
        }
    }

    private fun <T> databaseCall(block: () -> T): T = try {
        block()
    } catch (failure: SQLException) {
        throw DiscussionPersistenceFailure(failure)
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val READER_POOL_SIZE: Int = 4
        const val BUSY_TIMEOUT_MS: Int = 5_000
        private val logger = KotlinLogging.logger {}
    }
}
