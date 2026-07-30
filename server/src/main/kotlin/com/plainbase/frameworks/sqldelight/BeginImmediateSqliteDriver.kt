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
 * `JdbcSqliteDriver` whenever the SQLDelight dependency is bumped; nothing automated detects that drift. Unlike the
 * stock driver's deferred read-to-write promotion, which returns SQLITE_BUSY WITHOUT waiting, this acquires the write
 * lock at BEGIN, so the xerial 3000 ms busy-handler budget is actually consulted. `DatabaseFactory` also sets that
 * value explicitly; note that the pin is value-identical to xerial's own default, so NO test distinguishes the pinned
 * value from the default and removing the pin would go unnoticed until a bump changed it.
 *
 * `beginTransaction()` is the ONE deliberate divergence from upstream; its own KDoc carries the reason and the invariant.
 *
 * WHAT HOLDING THE LOCK FROM BEGIN COSTS. Stated as a RULE, not an inventory: three attempts at listing the affected
 * sites were each found incomplete, so a hand-maintained list here would be false confidence. The rule classifies every
 * site, including ones added later:
 *
 * 1. EVERY app-DB transaction holds the write lock for its WHOLE body, so any read prefix that used to run under SHARED
 *    now runs under the write lock. The longer the prefix, the longer the hold. Apply this to your own site rather than
 *    looking for it in a list.
 * 2. EVERY app-DB transaction is exposed to SQLITE_BUSY, INCLUDING one that reaches no write at all. Such a transaction
 *    previously took only SHARED and coexisted with a RESERVED holder; now it can fail after the busy budget. Any
 *    read-then-maybe-write body has arms like this, and its early returns are the usual ones.
 *
 * Two consequences worth naming because they are not obvious from the rule. `IdMapRepository.bind` runs inside the write
 * pipeline's `@Synchronized create`, so a contended bind now holds THAT monitor for up to the busy budget instead of
 * failing instantly, stalling other creates and edits behind it. And `RetirementRepository.applyProofs` sits in both
 * halves of the rule at once: when every proof AND every checkpoint advance is rejected, absent, or otherwise
 * non-writing, its corpus-sized loop executes NO DML, so a transaction that was entirely SHARED under DEFERRED becomes
 * an unbounded write-lock holder. Note the condition needs BOTH: a proofless baseline batch is normal, and a valid
 * advance still writes through `upsertHead`.
 *
 * Splitting the never-writing cases back to DEFERRED is NOT the fix: read-then-maybe-write cannot know at BEGIN that the
 * write is unreachable, and doing so would re-open the skipped-busy-handler hole the moment anyone added a write.
 *
 * UNBOUNDED, and tracked separately: checkpoint replacement deletes and re-inserts every page in one transaction, and
 * retirement applies a whole proof batch in another. Their duration scales with corpus size and is NOT measured here.
 * Checkpoint replacement is materially UNCHANGED by the move to IMMEDIATE because it is write-first (its `deleteAll()`
 * already took RESERVED at the first statement); `applyProofs` is the one that can genuinely worsen. New app-DB
 * transactions should stay bounded.
 *
 * No expensive non-DB work such as Argon2-class password hashing, blocking crypto, IO, or network calls may sit inside
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
    /**
     * THE FORK'S ONE DELIBERATE DIVERGENCE FROM UPSTREAM. Everything else in this file is a verbatim copy.
     *
     * Upstream's body here is `prepareStatement("BEGIN TRANSACTION")`, a DEFERRED begin that acquires NO lock, so
     * contention cannot fail it. (`autoCommit = false` is `JdbcDriver`'s BASE implementation, which this manager
     * overrides; that route was evaluated and rejected because it can report failure after a durable commit. Do not
     * confuse the two.) `BEGIN IMMEDIATE` takes RESERVED at BEGIN, so a contended write lock past the busy timeout is
     * SQLITE_BUSY BY DESIGN, and a throw out of this hook becomes an expected path rather than a theoretical one.
     *
     * That matters because `JdbcDriver.newTransaction()` installs the [Transaction] BEFORE calling this hook, and only
     * wraps the transaction BODY in its own try/finally. So a throw out of here would leave an installed transaction
     * that nothing ever ends: the next `newTransaction()` on this thread sees an enclosing transaction, skips BEGIN, and
     * its `endTransaction` skips both END and ROLLBACK, silently discarding writes it reported as successful.
     *
     * TWO flags, because one cannot answer both questions. [began] is "did the real SQLite transaction open", which
     * decides whether there is anything to roll back. [completed] is "did this function return normally", which decides
     * whether to clean up at all. Collapsing them either strands an open transaction (when a throwing `close()` reads as
     * a failed BEGIN) or orphans an installed one (when it reads as a live BEGIN).
     *
     * The guarantee, stated no stronger than it is: an abnormal exit ALWAYS leaves no installed transaction, and makes a
     * BEST-EFFORT attempt to leave no open real one. The rollback must precede `transaction = null`, because
     * [ThreadedConnectionManager]'s setter CLOSES the connection when nulled and a rollback after that cannot land. It is
     * wrapped so a failing rollback cannot mask the original failure, which is the deliberate trade: if the rollback
     * itself fails, a real transaction can survive on the connection. On the threaded manager the subsequent close
     * collects it; on [StaticConnectionManager] the shared connection is not closed, so there it can persist. The
     * `finally` covers every throwable rather than just SQLException.
     */
    override fun Connection.beginTransaction() {
        var began = false
        var completed = false
        try {
            prepareStatement("BEGIN IMMEDIATE").use { statement ->
                statement.execute()
                began = true
            }
            completed = true
        } finally {
            if (!completed) {
                if (began) runCatching { rollbackTransaction() }
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
