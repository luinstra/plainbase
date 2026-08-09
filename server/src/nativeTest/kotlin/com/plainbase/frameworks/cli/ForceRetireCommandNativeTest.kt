package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.IdResolution
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * `plainbase admin force-retire <root> <id>` over a file-backed DB IN-IMAGE (§0.3.1, R9/R10): the OPERATOR un-wedge
 * hatch reaps a live binding into `retired_binding` (R9), and a FRESH resolver over the SAME file then reads the id as
 * gone - `resolve == None`, tombstone present, no live binding (R10, the un-wedge ANSWER). The xerial JDBC/JNI seam,
 * the whole `applyProofs` reap transaction under the closed-world image.
 *
 * @Tag("native") + kotlin.test only.
 */
@Tag("native")
class ForceRetireCommandNativeTest {

    private val extra = RootName.require("extra")
    private val x = PageId.require("01010101-0101-0101-0101-010101010101")
    private val path = RootedPath(extra, TreePath.require("guides/p.md"))

    private fun <T> withSeededExtra(block: (PlainbaseConfig) -> T): T {
        val data = Files.createTempDirectory("pb-native-force-retire")
        return try {
            val content = Files.createDirectories(data.resolve("content"))
            val extraDir = Files.createDirectories(data.resolve("extra"))
            val config = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0).copy(
                roots = RootsConfig.of(
                    list = listOf(
                        Root(RootName.PRIMARY, RootBackend.Local(content), editable = true, history = HistoryMode.OFF),
                        Root(extra, RootBackend.Local(extraDir), editable = true, history = HistoryMode.OFF),
                    ),
                    origin = RootsOrigin.EXPLICIT,
                ),
            )
            // A live (extra, p) binding for X, seeded through the adapter over the same on-disk DB.
            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)).bind(path, x, materialized = false)
            }
            block(config)
        } finally {
            Files.walk(data).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `force-retire reaps a live binding into retired_binding in-image`() {
        withSeededExtra { config ->
            assertEquals(0, AdminCommand.run(listOf("force-retire", "extra", x.value), config, NativeCommandOutputCapture.current))

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                assertEquals(emptyList(), repo.rootsHoldingId(x)) // left id_map
                assertEquals(path, assertNotNull(repo.retiredAt(extra, x)).path) // in retired_binding, its last-known path
            }
        }
    }

    @Test
    fun `force-retire un-wedges the id - a fresh resolver over the same file reads it as gone`() {
        withSeededExtra { config ->
            assertEquals(0, AdminCommand.run(listOf("force-retire", "extra", x.value), config, NativeCommandOutputCapture.current))

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                val resolver = PageRootResolver(repo, RootRegistry.of(config.roots.list))
                assertEquals(IdResolution.None, resolver.resolve(x)) // the §6.1 precondition: 0 live -> None
                assertNotNull(repo.retiredAt(extra, x))
                assertFalse(resolver.bindsLive(extra, x))
            }
        }
    }

    @Test
    fun `force-retire refuses a same-id re-bind before the binding lookup in-image`() {
        withSeededExtra { config ->
            var exit: Int? = null
            val stderr = NativeCommandOutputCapture.captureStderr {
                exit = AdminCommand.run(
                    listOf("force-retire", "extra", x.value),
                    config,
                    NativeCommandOutputCapture.current,
                    driverFactory = { databasePath ->
                        NativeRebindBeforeBindingLookupDriver(DatabaseFactory.createDriver(databasePath)) {
                            DatabaseFactory.createDriver(databasePath).use { secondDriver ->
                                SqlDelightIdMapRepository(DatabaseFactory.createDatabase(secondDriver))
                                    .bind(path, x, materialized = false)
                            }
                        }
                    },
                )
            }

            assertEquals(1, exit)
            assertContains(stderr, "admin force-retire: refused to retire ${x.value} in root '${extra.value}'")
            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                assertNotNull(repo.bindingInRoot(extra, x))
            }
        }
    }
}

private val NATIVE_SELECT_BINDING_BY_ROOT_ID_SQL = """
    SELECT id_map.root, id_map.path, id_map.id, id_map.materialized
    FROM id_map
    WHERE id = ? AND root = ?
""".trimIndent()

private class NativeRebindBeforeBindingLookupDriver(
    private val delegate: SqlDriver,
    private val beforeBindingLookup: () -> Unit,
) : SqlDriver by delegate {
    private var fired = false

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (!fired && sql.trim() == NATIVE_SELECT_BINDING_BY_ROOT_ID_SQL) {
            fired = true
            beforeBindingLookup()
        }
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}
