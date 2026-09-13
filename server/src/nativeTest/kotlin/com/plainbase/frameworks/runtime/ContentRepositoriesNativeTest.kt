package com.plainbase.frameworks.runtime

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Native/JVM proof that content repositories borrow one real SQLDelight database without constructor I/O. */
@Tag("native")
class ContentRepositoriesNativeTest {

    @Test
    fun `content repositories are stable and usable over the supplied driver`() {
        val real = DatabaseFactory.createInMemoryDriver()
        val observed = CountingDriver(real)
        try {
            val database = DatabaseFactory.createDatabase(observed)
            observed.reset()
            val repositories = ContentRepositories(database)

            repeat(2) {
                assertSame(repositories.idMap, repositories.idMap)
                assertSame(repositories.aliases, repositories.aliases)
                assertSame(repositories.checkpoints, repositories.checkpoints)
                assertSame(repositories.dirtyPages, repositories.dirtyPages)
                assertSame(repositories.retirements, repositories.retirements)
                assertSame(repositories.topology, repositories.topology)
            }
            assertEquals(0, observed.executeCount)
            assertEquals(0, observed.executeQueryCount)
            assertEquals(0, observed.closeCount)

            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val path = RootedPath(RootName.PRIMARY, TreePath.require("guides/a.md"))
            val rootedId = RootedPageId(RootName.PRIMARY, pageId)
            val expected = DirtyPage(pageId, path, "sha256:abc", Stage.WRITING)
            repositories.dirtyPages.mark(pageId, path, expected.expectedHash, expected.stage)

            val throughGroup = repositories.dirtyPages.get(rootedId)
            val throughAdapter = SqlDelightDirtyPageRepository(database).get(rootedId)
            assertEquals(expected, throughGroup)
            assertEquals(expected, throughAdapter)
            assertEquals(expected.path, throughGroup?.path)
            assertEquals(expected.pageId, throughGroup?.pageId)
            assertEquals(expected.expectedHash, throughGroup?.expectedHash)
            assertEquals(expected.stage, throughGroup?.stage)
            assertTrue(observed.executeCount > 0)
            assertTrue(observed.executeQueryCount > 0)
            assertEquals(0, observed.closeCount)
        } finally {
            observed.close()
        }
        assertEquals(1, observed.closeCount)
    }
}

private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var executeCount = 0
        private set
    var executeQueryCount = 0
        private set
    var closeCount = 0
        private set

    fun reset() {
        executeCount = 0
        executeQueryCount = 0
        closeCount = 0
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        executeCount++
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        executeQueryCount++
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun close() {
        closeCount++
        delegate.close()
    }
}
