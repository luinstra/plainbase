package com.plainbase.frameworks.sqldelight

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LATCH_TIMEOUT_SECONDS = 10L
private const val WORKER_THREAD_NAME = "pb-deferred-log-worker"

/**
 * THE falsifier for the invariant at [BeginImmediateSqliteDriver]'s KDoc: no IO may sit inside an app-DB
 * transaction. A blocked log consumer must not be able to hold the write lock, so each row blocks the logging
 * appender on the message the transaction emits and then proves a SECOND connection can still take
 * `BEGIN IMMEDIATE` with a zero busy timeout.
 *
 * File-backed ONLY, never `:memory:`: `connectionManager` routes in-memory URLs to `StaticConnectionManager`,
 * which hands every thread the SAME connection, and a two-connection lock test over one connection is vacuous.
 *
 * Each row also asserts the captured `formattedMessage` character for character, so the byte identity of the
 * moved lines is pinned here rather than by review.
 */
@Tag("native")
class TransactionLogEmissionNativeTest {

    @Test
    fun `a blocked log consumer does not hold the write lock through a stale proof`() {
        val root = RootName.require("stale-row")
        withDatabase("pb-native-deferred-log-stale") { db, databasePath ->
            val retirements = SqlDelightRetirementRepository(db)
            // A row must EXIST with MISMATCHED tokens: a root with no observation row renders `null/null`, which the
            // fix drops at accumulation, so that seed would look broken against a correct implementation.
            val minted = AbsenceProof(
                root = root,
                source = ProofSource.EPOCH,
                observationId = retirements.observation(root),
                bindingEpoch = retirements.bindingEpoch(root),
                covers = setOf(BindingRef(TreePath.require("a.md"), PageId.require("01900000-0000-7000-8000-0000000000a1"))),
            )
            retirements.revoke(root)
            withBlockingAppender(SqlDelightRetirementRepository::class.java, Level.WARN) { appender ->
                val phase = runLockPhase(databasePath, appender) {
                    retirements.applyProofs(listOf(minted), witnessed = emptySet(), unavailableNow = { emptySet() })
                }
                assertNull(
                    phase.contenderFailure,
                    "row 1 (stale proof): a second connection could not take BEGIN IMMEDIATE while the log consumer was blocked",
                )
                assertNull(phase.workerFailure, "row 1 (stale proof): the worker failed")
                assertEquals(
                    listOf(
                        "discarding a EPOCH proof for root '$root': it was minted under observation 1/binding-epoch 0, and the " +
                            "root is now at 2/0 - the view it was minted from has been revoked or a binding it covers was " +
                            "re-created, so it authorizes nothing",
                    ),
                    appender.messages,
                )
            }
        }
    }

    @Test
    fun `a blocked log consumer does not hold the write lock through a revoke`() {
        val root = RootName.require("revoke-row")
        withDatabase("pb-native-deferred-log-revoke") { db, databasePath ->
            val retirements = SqlDelightRetirementRepository(db)
            withBlockingAppender(SqlDelightRetirementRepository::class.java, Level.INFO) { appender ->
                val phase = runLockPhase(databasePath, appender) { retirements.revoke(root) }
                assertNull(
                    phase.contenderFailure,
                    "row 2 (revoke): a second connection could not take BEGIN IMMEDIATE while the log consumer was blocked",
                )
                assertNull(phase.workerFailure, "row 2 (revoke): the worker failed")
                assertEquals(
                    listOf("revoked root '$root''s observation; every proof minted before 1 is now worthless"),
                    appender.messages,
                )
            }
        }
    }

    @Test
    fun `a blocked log consumer does not hold the write lock through a binding change`() {
        val root = RootName.require("change-row")
        withDatabase("pb-native-deferred-log-change") { db, databasePath ->
            val topologies = SqlDelightRootTopologyRepository(db)
            topologies.observeBinding(root, RootBinding("/a"))
            // Two durable bindings, so the at-risk snapshot the change takes is non-empty and the expected line
            // carries a varying count rather than a constant.
            db.idMapQueries.upsertBinding(
                root = root,
                path = TreePath.require("one.md"),
                id = PageId.require("01900000-0000-7000-8000-0000000000b1"),
                materialized = true,
            )
            db.idMapQueries.upsertBinding(
                root = root,
                path = TreePath.require("two.md"),
                id = PageId.require("01900000-0000-7000-8000-0000000000b2"),
                materialized = true,
            )
            withBlockingAppender(SqlDelightRootTopologyRepository::class.java, Level.WARN) { appender ->
                val phase = runLockPhase(databasePath, appender) { topologies.observeBinding(root, RootBinding("/b")) }
                assertNull(
                    phase.contenderFailure,
                    "row 3 (binding change): a second connection could not take BEGIN IMMEDIATE while the log consumer was blocked",
                )
                assertNull(phase.workerFailure, "row 3 (binding change): the worker failed")
                assertEquals(
                    listOf(
                        "root '$root' is now bound to /b (it was bound to /a): UNRESOLVED, with 2 binding(s) at risk. It proves " +
                            "NOTHING until they are witnessed BY IDENTITY there - a LIST of the wrong bucket is a perfectly " +
                            "successful LIST",
                    ),
                    appender.messages,
                )
            }
        }
    }

    @Test
    fun `a blocked log consumer does not hold the write lock through a corrupt at-risk snapshot`() {
        val root = RootName.require("decode-row")
        val binding = RootBinding("/only")
        withDatabase("pb-native-deferred-log-decode") { db, databasePath ->
            val topologies = SqlDelightRootTopologyRepository(db)
            topologies.observeBinding(root, binding)
            // A VERSION mismatch, not malformed JSON: the undecodable branch depends on kotlinx throwing
            // SerializationException, and a lock falsifier should not rest on which exception a library picks.
            rewriteAtRisk(databasePath, root, """{"version":999,"bindings":[]}""")
            withBlockingAppender(SqlDelightRootTopologyRepository::class.java, Level.ERROR) { appender ->
                val phase = runLockPhase(databasePath, appender) { topologies.observeBinding(root, binding) }
                assertNull(
                    phase.contenderFailure,
                    "row 4 (corrupt at-risk snapshot): a second connection could not take BEGIN IMMEDIATE while the log " +
                        "consumer was blocked",
                )
                assertNull(phase.workerFailure, "row 4 (corrupt at-risk snapshot): the worker failed")
                assertEquals(
                    listOf(
                        "root '$root''s at-risk snapshot is version 999 (expected 1): it can satisfy NOTHING, so the root " +
                            "stays UNRESOLVED",
                    ),
                    appender.messages,
                )

                // Phase 2, still under the same appender: the invalid-binding text, which no lock phase reaches. It makes
                // no lock claim and renders identically on both sides of the fix; it is a byte-identity pin only.
                rewriteAtRisk(databasePath, root, """{"version":1,"bindings":[{"path":"x","id":"not-a-uuid"}]}""")
                topologies.observeBinding(root, binding)
                assertEquals(
                    "root '$root''s at-risk snapshot names an invalid binding ('x'): the root stays UNRESOLVED",
                    appender.messages.getOrNull(1),
                )
            }
        }
    }
}

private class LockPhase(val contenderFailure: Throwable?, val workerFailure: Throwable?)

/**
 * Records EVERY event's `formattedMessage`, and blocks on the FIRST one only: a one-shot block, so the emission
 * that follows the fix's commit cannot wedge the test a second time.
 */
private class BlockingRecordingAppender : AppenderBase<ILoggingEvent>() {
    val blocked = CountDownLatch(1)
    val release = CountDownLatch(1)

    /**
     * Whether the block gave up on its own timeout instead of being released. The await MUST stay bounded (an
     * unbounded one turns a RED into a hung run), but a timeout also unblocks the appender without the test having
     * released it: the row would then observe a worker that finished for the wrong reason, so the phase asserts this
     * is false rather than letting a timeout degrade into a pass.
     */
    val releaseTimedOut = AtomicBoolean(false)
    private val blockTaken = AtomicBoolean(false)
    private val recorded = mutableListOf<String>()

    val messages: List<String> get() = synchronized(recorded) { recorded.toList() }

    override fun append(eventObject: ILoggingEvent) {
        synchronized(recorded) { recorded.add(eventObject.formattedMessage) }
        if (blockTaken.compareAndSet(false, true)) {
            blocked.countDown()
            if (!release.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) releaseTimedOut.set(true)
        }
    }
}

private fun withDatabase(prefix: String, body: (PlainbaseDb, Path) -> Unit) {
    val directory = Files.createTempDirectory(prefix)
    val databasePath = directory.resolve("plainbase.db")
    try {
        DatabaseFactory.createDriver(databasePath).use { driver ->
            body(DatabaseFactory.createDatabase(driver), databasePath)
        }
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private fun withBlockingAppender(owner: Class<*>, level: Level, body: (BlockingRecordingAppender) -> Unit) {
    // The cast fails loud if logback is not the backend, and the level assertion below fails loud if the level is
    // off - between them a vacuous green (no event, no block, nothing asserted) cannot happen quietly.
    val logbackLogger = LoggerFactory.getLogger(owner) as Logger
    val previousLevel = logbackLogger.level
    val appender = BlockingRecordingAppender().apply { start() }
    try {
        logbackLogger.level = level
        val active = when (level) {
            Level.ERROR -> logbackLogger.isErrorEnabled
            Level.WARN -> logbackLogger.isWarnEnabled
            else -> logbackLogger.isInfoEnabled
        }
        assertTrue(active, "logback does not report $level enabled on ${logbackLogger.name}")
        logbackLogger.addAppender(appender)
        body(appender)
    } finally {
        appender.release.countDown()
        logbackLogger.detachAppender(appender)
        appender.stop()
        logbackLogger.level = previousLevel
    }
}

private fun runLockPhase(databasePath: Path, appender: BlockingRecordingAppender, trigger: () -> Unit): LockPhase {
    val workerFailure = AtomicReference<Throwable?>()
    // Daemon so a worker that somehow outlives the bounded join below cannot hold up process exit under the
    // native-image runner; the liveness assertion is what turns that case into a failure.
    val worker = thread(name = WORKER_THREAD_NAME, isDaemon = true) { runCatching(trigger).onFailure(workerFailure::set) }
    var contenderFailure: Throwable? = null
    var seeded = false
    try {
        seeded = appender.blocked.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (seeded) contenderFailure = beginImmediateOnASecondConnection(databasePath)
    } finally {
        appender.release.countDown()
        worker.join(LATCH_TIMEOUT_SECONDS * 1_000)
    }
    // Assert the seeding AFTER the join: the usual cause of no block is a trigger that threw before emitting, and
    // workerFailure only holds it once the worker has finished. Asserting inline would build the message eagerly
    // against a still-empty reference and discard the real exception.
    val seedingFailure = workerFailure.get()
    assertTrue(
        seeded,
        "the appender never blocked, so the line under test never fired: the row is mis-seeded" +
            (seedingFailure?.let { ", and the trigger failed first with $it" } ?: ""),
    )
    assertFalse(
        appender.releaseTimedOut.get(),
        "the appender's block timed out instead of being released, so the worker finished on its own: this row " +
            "proves nothing about the write lock",
    )
    assertFalse(worker.isAlive, "the worker did not finish after the appender was released")
    return LockPhase(contenderFailure, workerFailure.get())
}

/**
 * A second, independent connection taking the write lock with NO busy budget, so a held lock fails instantly
 * rather than after a wait. The insert is rolled back, so the table choice cannot perturb the row's own state.
 */
private fun beginImmediateOnASecondConnection(databasePath: Path): Throwable? =
    DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout = 0")
            statement.executeQuery("PRAGMA busy_timeout").use { rows ->
                assertTrue(rows.next(), "PRAGMA busy_timeout returned no row")
                assertEquals(0, rows.getInt(1), "the contender's busy timeout was not zero")
            }
        }
        runCatching {
            connection.createStatement().use { statement ->
                statement.execute("BEGIN IMMEDIATE")
                statement.execute(
                    "INSERT INTO root_topology(root, binding, status, at_risk) " +
                        """VALUES ('__contender__', 'x', 'UNRESOLVED', '{"version":1,"bindings":[]}')""",
                )
                statement.execute("ROLLBACK")
            }
        }.exceptionOrNull()
    }

private fun rewriteAtRisk(databasePath: Path, root: RootName, atRisk: String) {
    DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
        connection.prepareStatement("UPDATE root_topology SET at_risk = ? WHERE root = ?").use { statement ->
            statement.setString(1, atRisk)
            statement.setString(2, root.value)
            assertEquals(1, statement.executeUpdate(), "the at-risk rewrite matched no row")
        }
    }
}
