package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Native generated-query coverage for durable retired-unbound whole/point reads. */
@Tag("native")
class SearchRetirementQueriesNativeTest {

    @Test
    fun `whole and point reads agree across roots, reclaim, and tombstone cardinality in-image`() {
        val dir = Files.createTempDirectory("pb-native-retirement-queries")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { real ->
                val driver = RetirementCountingDriver(real)
                val database = DatabaseFactory.createDatabase(driver)
                val repo = SqlDelightIdMapRepository(database)
                val retirements = SqlDelightRetirementRepository(database)
                val checkpoints = SqlDelightPageCheckpointRepository(database)
                val dirty = SqlDelightDirtyPageRepository(database)
                val main = RootName.PRIMARY
                val extra = RootName.require("extra")
                val x = PageId.require("01010101-0101-0101-0101-010101010101")
                val y = PageId.require("02020202-0202-0202-0202-020202020202")
                val z = PageId.require("03030303-0303-0303-0303-030303030303")
                val mainX = RootedPageId(main, x)
                val extraX = RootedPageId(extra, x)
                val mainY = RootedPageId(main, y)
                val mainZ = RootedPageId(main, z)
                val pathX = RootedPath(main, TreePath.require("x.md"))
                val pathY = RootedPath(main, TreePath.require("y.md"))
                val pathZ = RootedPath(main, TreePath.require("z.md"))
                val survivor = PageId.require("05050505-0505-0505-0505-050505050505")
                val survivorPath = RootedPath(extra, TreePath.require("survivor.md"))

                assertWhole(driver, repo, emptySet())
                assertPoint(driver, repo, mainX, expected = false)

                repo.bind(RootedPath(extra, TreePath.require("x.md")), x, materialized = true)
                retire(repo, retirements, pathX, x)
                retire(repo, retirements, pathY, y)
                retire(repo, retirements, pathZ, z)
                retirements.observation(extra)
                assertEquals(BindOutcome.Bound, repo.bind(survivorPath, survivor, materialized = true))
                val survivorRooted = RootedPageId(extra, survivor)
                checkpoints.replace(mapOf(survivorRooted to survivorPath.path))
                dirty.mark(survivor, survivorPath, expectedHash = "sha256:" + "s".repeat(64), stage = Stage.WRITING)

                val metadataBefore = authorityMetadata(repo, retirements, checkpoints, dirty, main, extra)
                val wholeCapture = assertWhole(driver, repo, setOf(mainX, mainY, mainZ))
                val mainPointCapture = assertPoint(driver, repo, mainX, expected = true)
                assertPoint(driver, repo, extraX, expected = false)
                val metadataAfter = authorityMetadata(repo, retirements, checkpoints, dirty, main, extra)
                assertEquals(metadataBefore, metadataAfter, "authority reads must not mutate durable state")

                assertEquals(0, wholeCapture.parameters)
                assertEquals(null, wholeCapture.binders)
                assertEquals(2, mainPointCapture.parameters)
                assertNotNull(mainPointCapture.binders)

                val wholePlan = driver.explain(wholeCapture)
                val nestedWholePlan = wholePlan.filter { it.parent != 0L }
                assertTrue(
                    nestedWholePlan.any { it.detail.startsWith("SEARCH") },
                    "whole query must use an indexed nested live lookup: $wholePlan",
                )
                assertFalse(
                    nestedWholePlan.any { it.detail.startsWith("SCAN") },
                    "whole query must not scan the nested live-binding table: $wholePlan",
                )
                val pointPlan = driver.explain(mainPointCapture)
                assertTrue(pointPlan.any { it.detail.startsWith("SEARCH") }, "point query must use an index: $pointPlan")
                assertFalse(pointPlan.any { it.detail.startsWith("SCAN") }, "point query must not scan a table: $pointPlan")

                val countSql = "SELECT count(*) FROM retired_binding WHERE root = ? AND id = ?"
                assertEquals(
                    1L,
                    driver.rawLong(countSql, 2) {
                        bindString(0, main.value)
                        bindBytes(1, PageIdColumnAdapter.encode(x))
                    },
                )
                assertEquals(BindOutcome.Bound, repo.bind(pathX, x, materialized = true))
                assertEquals(null, repo.retiredAt(main, x), "same-path reclaim must remove the physical tombstone")
                assertEquals(
                    0L,
                    driver.rawLong(countSql, 2) {
                        bindString(0, main.value)
                        bindBytes(1, PageIdColumnAdapter.encode(x))
                    },
                )
                assertWhole(driver, repo, setOf(mainY, mainZ))
                assertPoint(driver, repo, mainX, expected = false)
                assertPoint(driver, repo, extraX, expected = false)
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `same-root live and retired imported state fails closed for whole and point reads`() {
        val dir = Files.createTempDirectory("pb-native-retirement-defensive")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { real ->
                val driver = RetirementCountingDriver(real)
                val database = DatabaseFactory.createDatabase(driver)
                val repo = SqlDelightIdMapRepository(database)
                val retirements = SqlDelightRetirementRepository(database)
                val main = RootName.PRIMARY
                val x = PageId.require("04040404-0404-0404-0404-040404040404")
                val path = RootedPath(main, TreePath.require("original.md"))
                val importedPath = RootedPath(main, TreePath.require("imported.md"))
                retire(repo, retirements, path, x)

                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO id_map(root, path, id, materialized) VALUES (?, ?, ?, ?)",
                    parameters = 4,
                    binders = {
                        bindString(0, main.value)
                        bindString(1, importedPath.path.value)
                        bindBytes(2, PageIdColumnAdapter.encode(x))
                        bindLong(3, 1L)
                    },
                )
                val before = repo.bindings() to repo.retiredBindings()
                assertEquals(setOf(x), repo.bindingInRoot(main, x)?.let { setOf(it.id) })
                assertWhole(driver, repo, emptySet())
                assertPoint(driver, repo, RootedPageId(main, x), expected = false)
                assertEquals(before, repo.bindings() to repo.retiredBindings())
                assertEquals(importedPath, repo.bindingInRoot(main, x)?.path)
                assertEquals(path, repo.retiredAt(main, x)?.path)
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun retire(
        repo: SqlDelightIdMapRepository,
        retirements: SqlDelightRetirementRepository,
        path: RootedPath,
        id: PageId,
    ) {
        retirements.observation(path.root)
        assertEquals(BindOutcome.Bound, repo.bind(path, id, materialized = true))
        val observation = retirements.observation(path.root)
        val epoch = retirements.bindingEpoch(path.root)
        val binding = requireNotNull(repo.bindingInRoot(path.root, id))
        val proof = AbsenceProof.accepted(
            root = path.root,
            source = ProofSource.OPERATOR,
            observationId = observation,
            bindingEpoch = epoch,
            covers = setOf(BindingRef(binding.path.path, id)),
        )
        assertEquals(
            setOf(RootedPageId(path.root, id)),
            retirements.applyProofs(listOf(proof), witnessed = emptySet(), unavailableNow = { emptySet() }),
        )
    }

    private fun assertWhole(
        driver: RetirementCountingDriver,
        repo: SqlDelightIdMapRepository,
        expected: Set<RootedPageId>,
    ): QueryCapture {
        driver.reset()
        assertEquals(expected, repo.retiredUnboundIds())
        assertEquals(0, driver.transactions)
        assertEquals(1, driver.executeQueries)
        return requireNotNull(driver.lastQuery)
    }

    private fun assertPoint(
        driver: RetirementCountingDriver,
        repo: SqlDelightIdMapRepository,
        rooted: RootedPageId,
        expected: Boolean,
    ): QueryCapture {
        driver.reset()
        assertEquals(expected, repo.isRetiredUnbound(rooted))
        assertEquals(0, driver.transactions)
        assertEquals(1, driver.executeQueries)
        return requireNotNull(driver.lastQuery)
    }

    private fun authorityMetadata(
        repo: SqlDelightIdMapRepository,
        retirements: SqlDelightRetirementRepository,
        checkpoints: SqlDelightPageCheckpointRepository,
        dirty: SqlDelightDirtyPageRepository,
        main: RootName,
        extra: RootName,
    ): List<Any> = listOf(
        repo.bindings(),
        repo.retiredBindings(),
        checkpoints.load(),
        dirty.all(),
        retirements.observations(),
        retirements.bindingEpoch(main),
        retirements.bindingEpoch(extra),
    )
}

private data class QueryCapture(
    val sql: String,
    val parameters: Int,
    val binders: (SqlPreparedStatement.() -> Unit)?,
)

private data class ExplainRow(
    val id: Long,
    val parent: Long,
    val detail: String,
)

private class RetirementCountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var transactions = 0
        private set
    var executeQueries = 0
        private set
    var lastQuery: QueryCapture? = null
        private set

    fun reset() {
        transactions = 0
        executeQueries = 0
        lastQuery = null
    }

    fun rawLong(
        sql: String,
        parameters: Int = 0,
        binders: (SqlPreparedStatement.() -> Unit)? = null,
    ): Long = delegate.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = parameters,
        binders = binders,
    ).value

    fun explain(capture: QueryCapture): List<ExplainRow> = delegate.executeQuery(
        identifier = null,
        sql = "EXPLAIN QUERY PLAN ${capture.sql}",
        mapper = { cursor ->
            val details = buildList {
                while (cursor.next().value) {
                    add(
                        ExplainRow(
                            id = requireNotNull(cursor.getLong(0)),
                            parent = requireNotNull(cursor.getLong(1)),
                            detail = requireNotNull(cursor.getString(3)),
                        ),
                    )
                }
            }
            QueryResult.Value(details)
        },
        parameters = capture.parameters,
        binders = capture.binders,
    ).value

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
        lastQuery = QueryCapture(sql, parameters, binders)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}
