package com.plainbase.frameworks.cli

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native-image lifecycle proof for the offline retirement/rebuild path. The older search image is deliberately
 * restored after a successful cleanup so the second real command proves startup-style recovery from stale derived
 * state, rather than merely re-running a clean rebuild.
 */
@Tag("native")
class SearchRetirementLifecycleNativeTest {

    @Test
    fun `offline reindex after force-retire removes stale victim while preserving app authority`() {
        val data = Files.createTempDirectory("pb-native-search-retirement-lifecycle")
        val content = Files.createDirectories(data.resolve("content"))
        val config = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0)
        val victimPath = TreePath.require("victim.md")
        val anchorPath = TreePath.require("anchor.md")
        val victimRooted = RootedPageId(RootName.PRIMARY, VICTIM_ID)
        val anchorRooted = RootedPageId(RootName.PRIMARY, ANCHOR_ID)
        val victimRootedPath = RootedPath(RootName.PRIMARY, victimPath)
        val anchorRootedPath = RootedPath(RootName.PRIMARY, anchorPath)
        val expectedInitialKeys = setOf(victimRooted, anchorRooted)
        val searchPath = config.searchDatabasePath
        val backupPath = data.resolve("search-before-retirement.db")

        try {
            Files.writeString(
                content.resolve(victimPath.value),
                "---\nid: ${VICTIM_ID.value}\ntitle: Native victim\n---\n\n# Native victim\n\nnative-lifecycle-victim-term\n",
            )
            Files.writeString(
                content.resolve(anchorPath.value),
                "---\nid: ${ANCHOR_ID.value}\ntitle: Native anchor\n---\n\n# Native anchor\n\nnative-lifecycle-anchor-term\n",
            )

            val initialOutput = NativeCommandOutputCapture.captureStdout {
                assertEquals(0, ReindexCommand.run(emptyList(), config, NativeCommandOutputCapture.current))
            }
            assertEquals(
                "reindex: rebuilt the search index for 2 page(s) under ${config.contentDir}${System.lineSeparator()}",
                initialOutput,
            )
            val initialStates = readSearch(config) { provider ->
                assertEquals(expectedInitialKeys, provider.indexedState().keys)
                assertTerm(provider, VICTIM_TERM, 1L)
                assertTerm(provider, ANCHOR_TERM, 1L)
                provider.indexedState()
            }
            assertEquals(victimPath, initialStates.getValue(victimRooted).path)
            assertEquals(anchorPath, initialStates.getValue(anchorRooted).path)

            clearEmptySearchSidecars(searchPath)
            Files.copy(searchPath, backupPath)

            Files.delete(content.resolve(victimPath.value))
            val retireOutput = NativeCommandOutputCapture.captureStdout {
                assertEquals(
                    0,
                    AdminCommand.run(
                        listOf("force-retire", RootName.PRIMARY.value, VICTIM_ID.value),
                        config,
                        NativeCommandOutputCapture.current,
                    ),
                )
            }
            assertEquals(
                "force-retired ${VICTIM_ID.value} in root '${RootName.PRIMARY.value}' (last at ${victimPath.value}); " +
                    "/p/${RootName.PRIMARY.value}/${VICTIM_ID.value} now answers 410 for a snapshot-absent page. " +
                    "If the file is still present, the next pass reclaims the id at its own (root, path)." +
                    System.lineSeparator(),
                retireOutput,
            )
            readAuthorityAndStaleEngine(config, victimRooted, anchorRooted, victimRootedPath, anchorRootedPath, initialStates)

            val firstRecoveryOutput = NativeCommandOutputCapture.captureStdout {
                assertEquals(0, ReindexCommand.run(emptyList(), config, NativeCommandOutputCapture.current))
            }
            assertEquals(
                "reindex: rebuilt the search index for 1 page(s) under ${config.contentDir}${System.lineSeparator()}",
                firstRecoveryOutput,
            )
            readCleanSearchAndAuthority(config, victimRooted, anchorRooted, anchorPath, victimRootedPath, initialStates)

            clearEmptySearchSidecars(searchPath)
            assertTrue(Files.deleteIfExists(searchPath), "the current search.db must exist before restoring the older image")
            Files.copy(backupPath, searchPath)
            readRestoredStaleSearchAndAuthority(config, victimRooted, anchorRooted, victimPath, anchorPath, initialStates)

            val secondRecoveryOutput = NativeCommandOutputCapture.captureStdout {
                assertEquals(0, ReindexCommand.run(emptyList(), config, NativeCommandOutputCapture.current))
            }
            assertEquals(
                "reindex: rebuilt the search index for 1 page(s) under ${config.contentDir}${System.lineSeparator()}",
                secondRecoveryOutput,
            )
            readCleanSearchAndAuthority(config, victimRooted, anchorRooted, anchorPath, victimRootedPath, initialStates)
        } finally {
            deleteOwnedTree(data)
        }
    }

    private fun readAuthorityAndStaleEngine(
        config: PlainbaseConfig,
        victimRooted: RootedPageId,
        anchorRooted: RootedPageId,
        victimPath: RootedPath,
        anchorPath: RootedPath,
        initialStates: Map<RootedPageId, com.plainbase.domain.search.PageSearchState>,
    ) {
        withAuthority(config) { idMap ->
            assertEquals(null, idMap.bindingInRoot(RootName.PRIMARY, VICTIM_ID))
            assertEquals(victimPath, idMap.retiredAt(RootName.PRIMARY, VICTIM_ID)?.path)
            assertEquals(setOf(victimRooted), idMap.retiredUnboundIds())
            assertTrue(idMap.isRetiredUnbound(victimRooted))
            assertEquals(anchorPath, idMap.bindingInRoot(RootName.PRIMARY, ANCHOR_ID)?.path)
            assertEquals(null, idMap.retiredAt(RootName.PRIMARY, ANCHOR_ID))
        }
        readSearch(config) { provider ->
            assertEquals(initialStates.keys, provider.indexedState().keys)
            assertEquals(initialStates.getValue(victimRooted), provider.indexedState().getValue(victimRooted))
            assertEquals(initialStates.getValue(anchorRooted), provider.indexedState().getValue(anchorRooted))
            assertTerm(provider, VICTIM_TERM, 1L)
            assertTerm(provider, ANCHOR_TERM, 1L)
        }
    }

    private fun readCleanSearchAndAuthority(
        config: PlainbaseConfig,
        victimRooted: RootedPageId,
        anchorRooted: RootedPageId,
        anchorPath: TreePath,
        victimPath: RootedPath,
        initialStates: Map<RootedPageId, com.plainbase.domain.search.PageSearchState>,
    ) {
        withAuthority(config) { idMap ->
            assertEquals(null, idMap.bindingInRoot(RootName.PRIMARY, VICTIM_ID))
            assertEquals(victimPath, idMap.retiredAt(RootName.PRIMARY, VICTIM_ID)?.path)
            assertEquals(victimRooted, idMap.retiredUnboundIds().single())
            assertTrue(idMap.isRetiredUnbound(victimRooted))
            assertEquals(anchorPath, idMap.bindingInRoot(RootName.PRIMARY, ANCHOR_ID)?.path?.path)
            assertEquals(null, idMap.retiredAt(RootName.PRIMARY, ANCHOR_ID))
        }
        readSearch(config) { provider ->
            assertEquals(setOf(anchorRooted), provider.indexedState().keys)
            assertEquals(initialStates.getValue(anchorRooted), provider.indexedState().getValue(anchorRooted))
            assertTerm(provider, VICTIM_TERM, 0L)
            assertTerm(provider, ANCHOR_TERM, 1L)
        }
    }

    private fun readRestoredStaleSearchAndAuthority(
        config: PlainbaseConfig,
        victimRooted: RootedPageId,
        anchorRooted: RootedPageId,
        victimPath: TreePath,
        anchorPath: TreePath,
        initialStates: Map<RootedPageId, com.plainbase.domain.search.PageSearchState>,
    ) {
        withAuthority(config) { idMap ->
            assertEquals(null, idMap.bindingInRoot(RootName.PRIMARY, VICTIM_ID))
            assertEquals(victimPath, idMap.retiredAt(RootName.PRIMARY, VICTIM_ID)?.path?.path)
            assertEquals(setOf(victimRooted), idMap.retiredUnboundIds())
            assertEquals(anchorPath, idMap.bindingInRoot(RootName.PRIMARY, ANCHOR_ID)?.path?.path)
        }
        readSearch(config) { provider ->
            assertEquals(setOf(victimRooted, anchorRooted), provider.indexedState().keys)
            assertEquals(initialStates.getValue(victimRooted), provider.indexedState().getValue(victimRooted))
            assertEquals(initialStates.getValue(anchorRooted), provider.indexedState().getValue(anchorRooted))
            assertTerm(provider, VICTIM_TERM, 1L)
            assertTerm(provider, ANCHOR_TERM, 1L)
        }
    }

    private fun <T> readSearch(config: PlainbaseConfig, block: (Fts5SearchProvider) -> T): T =
        SearchDb(config.searchDatabasePath).use { db -> block(Fts5SearchProvider(db)) }

    private fun <T> withAuthority(config: PlainbaseConfig, block: (SqlDelightIdMapRepository) -> T): T =
        DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
            block(SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)))
        }

    private fun assertTerm(provider: Fts5SearchProvider, term: String, total: Long) {
        val result = provider.search(com.plainbase.domain.search.SearchQuery(term, limit = 20, offset = 0))
        assertEquals(total, result.total)
        assertEquals(total > 0L, result.hits.isNotEmpty())
        if (total == 0L) assertTrue(result.hits.isEmpty())
    }

    private fun clearEmptySearchSidecars(searchPath: Path) {
        val sidecars = listOf(
            searchPath.resolveSibling("${searchPath.fileName}-wal"),
            searchPath.resolveSibling("${searchPath.fileName}-shm"),
        )
        val wal = sidecars.first()
        if (Files.exists(wal)) {
            val size = Files.size(wal)
            assertEquals(0L, size, "refusing to replace search.db with non-empty ${wal.fileName} ($size bytes)")
        }
        sidecars.forEach { Files.deleteIfExists(it) }
    }

    private fun deleteOwnedTree(root: Path) {
        if (Files.notExists(root)) return
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        private val VICTIM_ID = PageId.require("01900000-0000-7000-8000-000000000301")
        private val ANCHOR_ID = PageId.require("01900000-0000-7000-8000-000000000302")
        private const val VICTIM_TERM = "native-lifecycle-victim-term"
        private const val ANCHOR_TERM = "native-lifecycle-anchor-term"
    }
}
