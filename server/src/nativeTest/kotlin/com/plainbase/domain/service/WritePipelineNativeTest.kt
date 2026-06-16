package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PB-WRITE-1 named test 12 (master criterion 1 in the closed-world image): the [WritePipeline] save
 * path runs end-to-end under the native gate — real [LocalContentStore], real renderer, a file-backed
 * [SqlDelightDirtyPageRepository] over a real [DatabaseFactory.createDriver] DB (exercising the 3.sqm
 * migration / dirty_page table), and the new [com.plainbase.domain.content.ContentStore.compareAndSwapWrite]
 * (pure JDK NIO — no reflection). Keeps the native UID list non-empty.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image, so
 * Kotest/MockK must never appear here.
 */
@Tag("native")
class WritePipelineNativeTest {

    @Test
    fun `a matching-hash save writes bytes verbatim and reports Written`() {
        val content = Files.createTempDirectory("pb-native-write-content")
        val data = Files.createTempDirectory("pb-native-write-data")
        try {
            Files.writeString(content.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\noriginal.\n")
            val config = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0)

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val database = DatabaseFactory.createDatabase(driver)
                val store = LocalContentStore(content)
                val citations = CitationFactory()
                val builder = IndexBuilder(
                    contentStore = store,
                    frontmatterParser = FrontmatterReader(),
                    rendererFactory = { view -> FlexmarkRenderer(view) },
                    identity = PageIdentityService(UuidV7IdProvider()),
                    patcher = FrontmatterPatcher(),
                    idMap = SqlDelightIdMapRepository(database),
                    aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database)),
                    checkpoint = SqlDelightPageCheckpointRepository(database),
                    citations = citations,
                )
                builder.rebuild()
                val pipeline = WritePipeline(
                    contentStore = store,
                    indexBuilder = builder,
                    citations = citations,
                    frontmatterParser = FrontmatterReader(),
                    dirtyPages = SqlDelightDirtyPageRepository(database),
                )

                val page = builder.current.pages.single()
                val saveBytes = "---\ntitle: Doc\n---\n\n# Doc\n\nnatively saved.\n".toByteArray()
                val outcome = pipeline.write(WriteIntent(page.id, page.path, page.contentHash, saveBytes))

                assertTrue(outcome is WriteOutcome.Written, "expected Written, got $outcome")
                assertEquals(citations.contentHash(saveBytes), (outcome as WriteOutcome.Written).newHash)
                assertContentEquals(saveBytes, Files.readAllBytes(content.resolve("doc.md")))
                // The targeted reindex ran: the snapshot reflects the new bytes.
                assertEquals(String(saveBytes, Charsets.UTF_8), builder.current.byId.getValue(page.id).markdown)
                // The write-ahead mark was cleared on success.
                assertTrue(SqlDelightDirtyPageRepository(database).all().isEmpty(), "dirty mark not cleared")
                // The CAS resolved the indexed path (sanity that the path round-trips).
                assertEquals(TreePath.require("doc.md"), page.path)
            }
        } finally {
            listOf(content, data).forEach { dir ->
                Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
