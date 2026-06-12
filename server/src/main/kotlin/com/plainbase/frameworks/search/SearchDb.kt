package com.plainbase.frameworks.search

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ArrayBlockingQueue
import kotlin.io.path.createDirectories

/**
 * The raw-JDBC opener for `DATA_DIR/search.db` (§B5, ADR-0004): every statement against the
 * derived-state search database flows through this class's two access paths. SQLDelight is
 * deliberately absent — search.db has no domain types to map and NO migrations, by privilege:
 * a [SCHEMA_VERSION] mismatch (recorded in `search_meta`) drops every table and recreates the
 * schema; the next `SearchIndexer.sync` repopulates from engine-truth diffing (which also makes
 * a deleted search.db self-healing).
 *
 * Connections (§B5): ONE writer, confined to the serialized rebuild/sync path via [write]
 * (synchronized — the engine never sees two concurrent writers), plus a pool of
 * [READER_POOL_SIZE] reader connections behind [read], bounding effective query concurrency.
 * All connections run `journal_mode=WAL` with a [BUSY_TIMEOUT_MS] busy timeout, so readers are
 * never blocked by the writer and always observe one complete WAL snapshot.
 *
 * [read] executes [block] on the CALLER's thread — blocking JDBC must never park a CIO
 * event-loop thread, so the serving layer dispatches search calls on `Dispatchers.IO` before
 * they reach this class (the S4 route owns that hop; tests and the sync path may call directly).
 */
class SearchDb(path: Path) : AutoCloseable {

    private val url = "jdbc:sqlite:$path"
    private val writer: Connection
    private val readers = ArrayBlockingQueue<Connection>(READER_POOL_SIZE)

    init {
        path.parent?.createDirectories()
        writer = open()
        ensureSchema()
        repeat(READER_POOL_SIZE) { readers.put(open()) }
    }

    /** Runs [block] on the single writer connection (serialized — §B5 writer confinement). */
    fun <T> write(block: (Connection) -> T): T = synchronized(writer) { block(writer) }

    /** Borrows a reader connection for [block], blocking while all [READER_POOL_SIZE] are out. */
    fun <T> read(block: (Connection) -> T): T {
        val connection = readers.take()
        return try {
            block(connection)
        } finally {
            readers.put(connection)
        }
    }

    override fun close() {
        repeat(READER_POOL_SIZE) { readers.take().close() }
        synchronized(writer) { writer.close() }
    }

    private fun open(): Connection = DriverManager.getConnection(url).apply {
        createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
        }
    }

    private fun ensureSchema() {
        val recorded = runCatching { writer.queryValue("SELECT value FROM search_meta WHERE key='schema_version'") }
            .getOrNull() // no search_meta table at all = a fresh (or foreign) file: rebuild it
        if (recorded == SCHEMA_VERSION.toString()) return
        if (recorded != null) {
            logger.info {
                "search.db schema_version $recorded != $SCHEMA_VERSION — dropping and recreating (derived state never migrates)"
            }
        }
        dropAllTables()
        createSchema()
    }

    /**
     * Drops every user table, looping because FTS5 shadow tables (`section_fts_data`, …) refuse a
     * direct DROP but vanish when their virtual table goes — each pass drops what it can until the
     * database is empty.
     */
    private fun dropAllTables() {
        while (true) {
            val tables = writer.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            if (tables.isEmpty()) return
            val dropped = tables.count { table ->
                runCatching {
                    writer.createStatement().use { it.execute("""DROP TABLE "${table.replace("\"", "\"\"")}"""") }
                }.isSuccess
            }
            check(dropped > 0) { "search.db schema reset made no progress; remaining tables: $tables" }
        }
    }

    private fun createSchema() = writer.transaction {
        writer.createStatement().use { statement ->
            statement.execute("CREATE TABLE search_meta(key TEXT PRIMARY KEY, value TEXT)")
            statement.execute(
                """
                CREATE TABLE search_page(generation INTEGER NOT NULL, page_id BLOB NOT NULL,
                                         content_hash TEXT NOT NULL, path TEXT NOT NULL,
                                         PRIMARY KEY(generation, page_id))
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE section_doc(doc_id INTEGER PRIMARY KEY, generation INTEGER NOT NULL,
                                         page_id BLOB NOT NULL, heading_id TEXT, status TEXT NOT NULL)
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX section_doc_gen_page ON section_doc(generation, page_id)")
            // heading = the section's OWN heading text only, never the breadcrumb (§B4 engine note).
            statement.execute(
                """
                CREATE VIRTUAL TABLE section_fts USING fts5(
                  title, heading, body, tags, aliases, owner,
                  tokenize = 'unicode61 remove_diacritics 1'
                )
                """.trimIndent(),
            )
            // The §B5 trigram rescue (S0 verdict: PASS): substring semantics for what unicode61
            // cannot match (CJK runs), consulted only when the primary MATCH yields zero hits.
            statement.execute("CREATE VIRTUAL TABLE section_trigram USING fts5(title, body, tokenize = 'trigram')")
            statement.execute("INSERT INTO search_meta(key, value) VALUES ('schema_version', '$SCHEMA_VERSION')")
            statement.execute("INSERT INTO search_meta(key, value) VALUES ('active_generation', '0')")
        }
    }

    private fun Connection.queryValue(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
    }

    companion object {
        /** Bump on ANY schema change: the mismatch path is drop-and-recreate, never a migration. */
        const val SCHEMA_VERSION: Int = 1

        const val READER_POOL_SIZE: Int = 4

        const val BUSY_TIMEOUT_MS: Int = 5_000

        private val logger = KotlinLogging.logger {}
    }
}

/** One SQLite transaction on this connection: commit on success, roll back (then rethrow) on failure. */
internal inline fun <T> Connection.transaction(block: () -> T): T {
    autoCommit = false // xerial BEGINs DEFERRED: the first statement establishes the WAL read snapshot
    return try {
        block().also { commit() }
    } catch (t: Throwable) {
        rollback()
        throw t
    } finally {
        autoCommit = true
    }
}
