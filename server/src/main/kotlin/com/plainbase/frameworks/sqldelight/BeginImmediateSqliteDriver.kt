package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.ConnectionManager.Transaction
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.Properties
import kotlin.concurrent.getOrSet

/**
 * SQLDelight 2.3.2's JDBC SQLite driver with its connection manager forked to issue `BEGIN IMMEDIATE`.
 *
 * This is a pinned copy of SQLDelight's `JdbcSqliteDriver`. Compare the connection managers with the upstream
 * `JdbcSqliteDriver` whenever the SQLDelight dependency is bumped. `BEGIN IMMEDIATE` uses the xerial default 3000 ms
 * busy-handler budget; the explicit app-DB pin guards that default against a future dependency bump. Unlike the stock
 * driver's deferred read-to-write promotion, which returns SQLITE_BUSY without waiting, it acquires the write lock at
 * BEGIN. It also serializes read-only transactions, including transactions that do not reach a write statement.
 *
 * The `beginTransaction()` hook is the ONE deliberate divergence from upstream: SQLDelight installs its
 * `Transaction` before invoking the connection hook, so this fork clears the manager's installed transaction when
 * `BEGIN IMMEDIATE` fails and rethrows. This retains the upstream guards and connection-closing setter.
 *
 * With `BEGIN IMMEDIATE`, the write lock is held from BEGIN. Checkpoint replacement deletes and inserts every page in
 * one transaction, and retirement proof application applies a proof batch in one transaction; both are known
 * corpus-scaled holders whose lock duration is not measured here. New app-DB transactions should remain bounded. No
 * expensive non-DB work such as Argon2-class password hashing, blocking crypto, IO, or network calls may sit inside
 * an app-DB transaction. Cheap per-call crypto needed for a transaction's DB result, such as session minting, is
 * permitted.
 */
@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class BeginImmediateSqliteDriver constructor(
    /**
     * Database connection URL in the form of `jdbc:sqlite:path?key1=value1&...` where:
     * - `jdbc:sqlite:` is the prefix which instructs [DriverManager] to open a connection
     *   using the provided [org.sqlite.JDBC] Driver.
     * - `path` is a file path which instructs sqlite *where* it should open the database
     *   connection.
     * - `?key1=value1&...` is an optional query string which instruct sqlite *how* it
     *   should open the connection.
     *
     * Examples:
     * - `jdbc:sqlite:/path/to/myDatabase.db` opens a database connection, writing changes
     *   to the filesystem at the specified `path`.
     * - `jdbc:sqlite:` (i.e. an empty path) will create a temporary database whereby
     *   the temp file is deleted upon connection closure.
     * - `jdbc:sqlite::memory:` will create a purely in-memory database.
     * - `jdbc:sqlite:file:memdb1?mode=memory&cache=shared` will create a named in-memory
     *   database which can be shared across connections until all are closed.
     *
     * [sqlite.org/inmemorydb](https://www.sqlite.org/inmemorydb.html)
     */
    url: String,
    properties: Properties = Properties(),
) : JdbcDriver(),
    ConnectionManager by connectionManager(url, properties) {
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach {
                listeners.getOrPut(it, { linkedSetOf() }).add(listener)
            }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach {
                listeners[it]?.remove(listener)
            }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val listenersToNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(listenersToNotify::addAll) }
        }
        listenersToNotify.forEach(Query.Listener::queryResultsChanged)
    }

    companion object {
        const val IN_MEMORY = "jdbc:sqlite:"
    }
}

private fun connectionManager(url: String, properties: Properties): ConnectionManager {
    val path = url.substringBefore('?').substringAfter("jdbc:sqlite:")

    return when {
        path.isEmpty() ||
            path == ":memory:" ||
            path == "file::memory:" ||
            path.startsWith(":resource:") ||
            url.contains("mode=memory") -> StaticConnectionManager(url, properties)
        else -> ThreadedConnectionManager(url, properties)
    }
}

private abstract class JdbcSqliteDriverConnectionManager : ConnectionManager {
    override fun Connection.beginTransaction() {
        var began = false
        try {
            // Flag inside `use`, not after it: a throw from the statement's own close() must NOT be read as a
            // failed BEGIN, or the finally below would clear a transaction that is actually live.
            prepareStatement("BEGIN IMMEDIATE").use { statement ->
                statement.execute()
                began = true
            }
        } finally {
            if (!began) {
                transaction = null
            }
        }
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use(PreparedStatement::execute)
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use(PreparedStatement::execute)
    }
}

private class StaticConnectionManager(
    url: String,
    properties: Properties,
) : JdbcSqliteDriverConnectionManager() {
    override var transaction: Transaction? = null
    private val connection: Connection = DriverManager.getConnection(url, properties)

    override fun getConnection() = connection
    override fun closeConnection(connection: Connection) = Unit
    override fun close() = connection.close()
}

private class ThreadedConnectionManager(
    private val url: String,
    private val properties: Properties,
) : JdbcSqliteDriverConnectionManager() {
    private val transactions = ThreadLocal<Transaction>()
    private val connections = ThreadLocal<Connection>()

    override var transaction: Transaction?
        get() = transactions.get()
        set(value) {
            val currentTransaction = transactions.get()
            transactions.set(value)

            if (value == null && currentTransaction != null) {
                closeConnection(currentTransaction.connection)
            }
        }

    override fun getConnection() = connections.getOrSet {
        DriverManager.getConnection(url, properties)
    }

    override fun closeConnection(connection: Connection) {
        check(connections.get() == connection) { "Connections must be closed on the thread that opened them" }
        if (transaction == null) {
            connection.close()
            connections.remove()
        }
    }

    override fun close() = Unit
}
