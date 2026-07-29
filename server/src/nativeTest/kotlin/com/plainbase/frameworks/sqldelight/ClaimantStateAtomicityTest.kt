package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The single-durable-read atomicity (§6.2), C5-regression form: `claimantState` reads the live claimants AND the
 * tombstones in ONE `selectClaimantsById` statement with NO transaction. A single SQLite SELECT is already a consistent
 * cross-table snapshot, so it keeps the torn-read atomicity a concurrent unbind+retire could otherwise split - WITHOUT
 * issuing a BEGIN. That BEGIN was the C5 regression: the app DB is one shared non-thread-safe connection, so a bare
 * resolve's BEGIN racing a second BEGIN died with `cannot start a transaction within a transaction` and 500'd
 * (AssetUploadRouteTest's concurrent case). PRIMARY proof, no concurrency needed: a [CountingDriver] spy records that
 * `claimantState` opens ZERO transactions and runs EXACTLY one `executeQuery`. Back-out either way and it reds: wrap the
 * read in `transactionWithResult` again -> `transactions` climbs off 0; split it back into two auto-commit SELECTs ->
 * `executeQueries` climbs off 1.
 *
 * @Tag("native") + kotlin.test - the xerial JDBC/JNI seam, run under the native image.
 */
@Tag("native")
class ClaimantStateAtomicityTest {

    @Test
    fun `claimantState reads live and retired in ONE statement with no transaction in-image`() {
        val dir = Files.createTempDirectory("pb-native-atomicity")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { real ->
                val spy = CountingDriver(real)
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(spy))
                val main = RootName.PRIMARY
                val x = PageId.require("01010101-0101-0101-0101-010101010101")
                val y = PageId.require("02020202-0202-0202-0202-020202020202")
                val p = RootedPath(main, TreePath.require("a.md"))

                // Displace X so it carries a tombstone (bind X, then bind Y over it) - the combined read returns X's
                // retired row, and the spy still sees exactly ONE statement whichever side has rows.
                repo.bind(p, x, materialized = false)
                repo.bind(p, y, materialized = false)

                spy.reset()
                val state = repo.claimantState(x)

                assertEquals(0, spy.transactions, "claimantState must NOT open a transaction on the shared connection")
                assertEquals(1, spy.executeQueries, "ONE combined statement reads live claimants + tombstones")
                // The snapshot itself is coherent: X's tombstone is present (it was displaced by Y).
                assertEquals(1, state.retired.count { it.id == x })
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** A [SqlDriver] spy that counts `newTransaction` and `executeQuery`, delegating both - the torn-read tripwire. */
private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var transactions = 0
        private set
    var executeQueries = 0
        private set

    fun reset() {
        transactions = 0
        executeQueries = 0
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        transactions++
        return delegate.newTransaction()
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        executeQueries++
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}
