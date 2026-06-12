package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.UuidV7
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Native-image smoke test for the chunk-5 index pass: the closed-world image prunes unreachable
 * code, so without this test nothing in the native gate would even COMPILE the new `IndexBuilder`/
 * `PageIndex`/`CanonicalUrlBuilder` path (incl. the `AtomicReference` snapshot publication and its
 * `updateAndGet` lambdas in the alias registry). One rebuild + one rescan-with-move over a temp
 * tree exercises scan → frontmatter → identity → URLs → render → snapshot swap → move-alias
 * recording, natively.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image.
 */
@Tag("native")
class IndexBuilderNativeTest {

    @Test
    fun `rebuild publishes a snapshot with canonical URLs and a rescan after a move records the alias`() {
        val content = Files.createTempDirectory("pb-native-index")
        DatabaseFactory.createInMemoryDriver().use { driver ->
            try {
                Files.createDirectories(content.resolve("docs"))
                Files.writeString(
                    content.resolve("docs/start.md"),
                    "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: Start\n---\n\n# Start\n",
                )

                val database = DatabaseFactory.createDatabase(driver)
                val idMap = SqlDelightIdMapRepository(database)
                val aliases = SqlDelightUrlAliasRepository(database)
                val registry = UrlAliasRegistry(aliases)
                val builder = IndexBuilder(
                    contentStore = LocalContentStore(content),
                    frontmatterParser = FrontmatterReader(),
                    rendererFactory = { view -> FlexmarkRenderer(view) },
                    identity = PageIdentityService(UuidV7()),
                    patcher = FrontmatterPatcher(),
                    idMap = idMap,
                    aliasRegistry = registry,
                )

                val first = builder.rebuild()
                assertEquals(1, first.pages.size)
                val page = first.pages.single()
                assertEquals("/docs/docs/start", page.url)
                assertEquals(first, builder.current) // the AtomicReference swap published it

                // Move the page; the rescan must re-point the id and record the old canonical path.
                Files.createDirectories(content.resolve("archive"))
                Files.move(content.resolve("docs/start.md"), content.resolve("archive/start.md"))
                val second = builder.rebuild()
                assertEquals("/docs/archive/start", second.byId.getValue(page.id).url)
                assertNotNull(registry.find(TreePath.require("docs/start")))
                assertEquals(page.id, aliases.find(TreePath.require("docs/start")))
            } finally {
                Files.walk(content).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
