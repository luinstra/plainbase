package com.plainbase.acceptance

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.page.UuidV7
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.LinkChecker
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The native half of the Phase-1 acceptance gate (chunk 8): the link gate and renderer determinism
 * must hold INSIDE the native binary, not just on the JVM. The full Kotest suite
 * (`Phase1AcceptanceTest`/`ForeverApiGoldenSuite` in `src/test`) is JVM-only by the test-stack
 * split; this kotlin.test mirror runs the same `LinkChecker`-vs-manifest comparison over the real
 * fixture tree, with the manifest read as a CLASSPATH RESOURCE so `resources.autodetect()` proves
 * it survives into the image (risk R6).
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image.
 */
@Tag("native")
@Tag("acceptance")
class Phase1AcceptanceNativeTest {

    @Test
    fun `link gate inside the image - zero broken links except the pinned manifest set`() {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val snapshot = builderFor(LocalContentStore(fixturesRoot()), driver).rebuild()
            val report = LinkChecker().check(snapshot)

            val actual = report.broken.map { listOf(it.page.value, it.text, it.target, it.reason.wireValue) }
            // The manifest is non-empty (3 pinned entries), so an empty/missing index can never pass.
            assertEquals(loadManifest(), actual)
        }
    }

    @Test
    fun `renderer determinism inside the image - warm renderer matches a fresh one on every fixture page`() {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            // The SAME store the builder scanned: read() is indexed-only-gated, so a never-scanned
            // store instance would answer null for every page.
            val store = LocalContentStore(fixturesRoot())
            val snapshot = builderFor(store, driver).rebuild()

            // Warm-vs-fresh (the chunk-3 criterion's shape): one renderer accumulates the whole
            // tree while a fresh instance renders each page cold — byte-identical HTML required.
            val warm = FlexmarkRenderer(snapshot)
            for (page in snapshot.pages) {
                val bytes = assertNotNull(store.read(page.path), "fixture page vanished: ${page.path.value}")
                assertEquals(
                    warm.render(page.path, bytes).html,
                    FlexmarkRenderer(snapshot).render(page.path, bytes).html,
                    "non-deterministic render for ${page.path.value}",
                )
            }
        }
    }

    private fun builderFor(store: LocalContentStore, driver: SqlDriver): IndexBuilder {
        val database = DatabaseFactory.createDatabase(driver)
        return IndexBuilder(
            contentStore = store,
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7()),
            patcher = FrontmatterPatcher(),
            idMap = SqlDelightIdMapRepository(database),
            aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database)),
            citations = CitationFactory(),
        )
    }

    /** The manifest entries as (page, text, target, class) rows, in document order. */
    private fun loadManifest(): List<List<String>> {
        val resource = "/golden/known-broken-links.json"
        val json = assertNotNull(
            Phase1AcceptanceNativeTest::class.java.getResource(resource)?.readText(),
            "manifest missing from the native image — resources.autodetect() must embed $resource (R6)",
        )
        return Json.parseToJsonElement(json).jsonObject.getValue("broken").jsonArray.map { entry ->
            val fields = entry.jsonObject
            listOf("page", "text", "target", "class").map { fields.getValue(it).jsonPrimitive.content }
        }
    }

    /** `src/nativeTest` mirror of the JVM tests' `Fixtures` locator: walk up from `user.dir`. */
    private fun fixturesRoot(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("fixtures").resolve("demo-docs")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        error("could not locate fixtures/demo-docs from ${System.getProperty("user.dir")}")
    }
}
