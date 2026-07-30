package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Comparator
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

private const val TEST_URL_PREFIX = "jdbc:plainbase-begin-close:"
private const val BEGIN_CLOSE_FAILURE_MESSAGE = "synthetic BEGIN statement close failure"
private const val SECOND_TRANSACTION_FAILURE_MESSAGE = "second transaction rollback probe"

// Raw SQL, injected on the connection the moment BEGIN IMMEDIATE succeeds, because the transaction BODY never runs
// (the throw comes out of newTransaction) and the orphaned transaction needs a write for the rollback to be observable.
// The root literal is arbitrary test data: every assertion keys on the page id, never on the root.
private const val FIRST_WRITE_SQL =
    "INSERT INTO id_map(root, path, id, materialized) " +
        "VALUES ('main', 'begin-close.md', X'01010101010101010101010101010101', 1)"

private enum class ConnectionEvent {
    BEGIN_PREPARED,
    ROLLBACK_PREPARED,
    ROLLBACK_EXECUTED,
    CONNECTION_CLOSED,
}

private enum class ConnectionManagerSelection(
    val label: String,
    val urlSuffix: String,
    val expectsConnectionClosed: Boolean,
) {
    THREADED("threaded manager", "", true),
    STATIC("static manager", "?mode=memory", false),
}

class BeginImmediateSqliteDriverRecoveryTest : FunSpec({

    ConnectionManagerSelection.values().forEach { selection ->
        test("should clean up when the opened BEGIN statement throws from close on ${selection.label}") {
            runRecoveryScenario(selection)
        }
    }
})

private fun runRecoveryScenario(selection: ConnectionManagerSelection) {
    val directory = Files.createTempDirectory("pb-begin-close-recovery")
    val databasePath = directory.resolve("plainbase.db")
    val events = mutableListOf<ConnectionEvent>()
    val testUrl = "$TEST_URL_PREFIX${databasePath.toAbsolutePath()}${selection.urlSuffix}"
    val testDriver = CloseFailingSqliteDriver(databasePath, events)

    try {
        DriverManager.registerDriver(testDriver)
        BeginImmediateSqliteDriver(testUrl).use { driver ->
            PlainbaseDb.Schema.create(driver)
            events.clear()
            val db = DatabaseFactory.createDatabase(driver)
            val firstId = PageId.require("01010101-0101-0101-0101-010101010101")
            val secondId = PageId.require("02020202-0202-0202-0202-020202020202")

            val firstFailure = runCatching {
                db.transactionWithResult {
                    error("the first transaction body must not run")
                }
            }

            val secondFailure = runCatching {
                db.transactionWithResult {
                    db.idMapQueries.upsertBinding(
                        root = RootName.PRIMARY,
                        path = TreePath.require("second-transaction.md"),
                        id = secondId,
                        materialized = true,
                    )
                    error(SECOND_TRANSACTION_FAILURE_MESSAGE)
                }
            }

            val persistedBindings = db.idMapQueries.selectAllBindings().executeAsList()
            val beginIndices = events.withIndex()
                .filter { it.value == ConnectionEvent.BEGIN_PREPARED }
                .map { it.index }
            val firstRollbackPreparedIndex = events.indexOf(ConnectionEvent.ROLLBACK_PREPARED)
            val firstRollbackExecutedIndex = events.indexOf(ConnectionEvent.ROLLBACK_EXECUTED)
            val firstCloseIndex = events.indexOf(ConnectionEvent.CONNECTION_CLOSED)

            assertSoftly {
                withClue("the original BEGIN close failure must reach the caller") {
                    firstFailure.exceptionOrNull()
                        .shouldBeInstanceOf<SQLException>()
                        .message shouldBe BEGIN_CLOSE_FAILURE_MESSAGE
                }
                withClue("rollback must execute before manager cleanup and the next BEGIN") {
                    (firstRollbackPreparedIndex >= 0) shouldBe true
                    (firstRollbackExecutedIndex >= 0) shouldBe true
                    (beginIndices.size >= 2) shouldBe true
                    (firstRollbackPreparedIndex < firstRollbackExecutedIndex) shouldBe true
                    (firstRollbackExecutedIndex < beginIndices[1]) shouldBe true
                    if (selection.expectsConnectionClosed) {
                        (firstCloseIndex >= 0) shouldBe true
                        (firstRollbackExecutedIndex < firstCloseIndex) shouldBe true
                    } else {
                        firstCloseIndex shouldBe -1
                    }
                }
                withClue("the next transaction must perform a fresh BEGIN and roll back its write") {
                    beginIndices.size shouldBe 2
                    secondFailure.exceptionOrNull()?.message shouldBe SECOND_TRANSACTION_FAILURE_MESSAGE
                    persistedBindings.any { it.id == secondId } shouldBe false
                }
                withClue("the first transaction write must not persist") {
                    persistedBindings.any { it.id == firstId } shouldBe false
                }
            }
        }
    } finally {
        testDriver.closeConnections()
        DriverManager.deregisterDriver(testDriver)
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private class CloseFailingSqliteDriver(
    private val databasePath: Path,
    private val events: MutableList<ConnectionEvent>,
) : Driver {
    private val failFirstBeginClose = AtomicBoolean(true)
    private val connections = mutableListOf<Connection>()

    override fun connect(url: String?, info: Properties?): Connection? {
        if (!acceptsURL(url)) return null
        val delegate = DriverManager.getConnection("jdbc:sqlite:$databasePath", info)
        return recordingConnection(delegate).also(connections::add)
    }

    override fun acceptsURL(url: String?): Boolean = url?.startsWith(TEST_URL_PREFIX) == true

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()

    override fun getMajorVersion(): Int = 1

    override fun getMinorVersion(): Int = 0

    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger = Logger.getGlobal()

    fun closeConnections() {
        connections.forEach { connection ->
            runCatching { connection.close() }
        }
    }

    private fun recordingConnection(delegate: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            ConnectionHandler(delegate),
        ) as Connection

    private inner class ConnectionHandler(
        private val delegate: Connection,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "CloseFailingConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments.firstOrNull()
                    else -> invokeDelegate(method, arguments)
                }
            }

            if (method.name == "prepareStatement" && arguments.firstOrNull() is String) {
                val sql = arguments.first() as String
                if (sql == "BEGIN IMMEDIATE") {
                    events += ConnectionEvent.BEGIN_PREPARED
                    val statement = invokeDelegate(method, arguments) as PreparedStatement
                    val failClose = failFirstBeginClose.compareAndSet(true, false)
                    return beginStatement(statement, failClose)
                }
                if (sql == "ROLLBACK TRANSACTION") {
                    events += ConnectionEvent.ROLLBACK_PREPARED
                    val statement = invokeDelegate(method, arguments) as PreparedStatement
                    return rollbackStatement(statement)
                }
            }
            if (method.name == "close" && method.parameterCount == 0) {
                events += ConnectionEvent.CONNECTION_CLOSED
            }
            return invokeDelegate(method, arguments)
        }

        private fun beginStatement(statement: PreparedStatement, failClose: Boolean): PreparedStatement =
            Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
                BeginStatementHandler(statement, failClose),
            ) as PreparedStatement

        private fun rollbackStatement(statement: PreparedStatement): PreparedStatement =
            Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
                RollbackStatementHandler(statement),
            ) as PreparedStatement

        private fun invokeDelegate(method: Method, args: Array<out Any?>): Any? =
            try {
                method.invoke(delegate, *args)
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }

        private inner class BeginStatementHandler(
            private val delegate: PreparedStatement,
            private val failClose: Boolean,
        ) : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                val arguments = args ?: emptyArray()
                if (method.declaringClass == Any::class.java) {
                    return when (method.name) {
                        "toString" -> "CloseFailingBeginStatement"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments.firstOrNull()
                        else -> invokeDelegate(method, arguments)
                    }
                }
                if (method.name == "execute" && method.parameterCount == 0) {
                    val result = invokeDelegate(method, arguments)
                    if (failClose) {
                        delegate.connection.createStatement().use { it.executeUpdate(FIRST_WRITE_SQL) }
                    }
                    return result
                }
                if (method.name == "close" && method.parameterCount == 0 && failClose) {
                    throw SQLException(BEGIN_CLOSE_FAILURE_MESSAGE)
                }
                return invokeDelegate(method, arguments)
            }

            private fun invokeDelegate(method: Method, args: Array<out Any?>): Any? =
                try {
                    method.invoke(delegate, *args)
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
        }

        private inner class RollbackStatementHandler(
            private val delegate: PreparedStatement,
        ) : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                val arguments = args ?: emptyArray()
                if (method.declaringClass == Any::class.java) {
                    return when (method.name) {
                        "toString" -> "RecordingRollbackStatement"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments.firstOrNull()
                        else -> invokeDelegate(method, arguments)
                    }
                }
                if (method.name == "execute" && method.parameterCount == 0) {
                    val result = invokeDelegate(method, arguments)
                    events += ConnectionEvent.ROLLBACK_EXECUTED
                    return result
                }
                return invokeDelegate(method, arguments)
            }

            private fun invokeDelegate(method: Method, args: Array<out Any?>): Any? =
                try {
                    method.invoke(delegate, *args)
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
        }
    }
}
