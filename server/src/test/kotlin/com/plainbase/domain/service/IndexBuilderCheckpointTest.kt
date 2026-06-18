package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path

/**
 * The resolved Phase-1 down-time move-aliasing deferral (§B3): the first rebuild after a restart
 * compares against the persisted `page_checkpoint` instead of the EMPTY holder, so a MATERIALIZED
 * page moved while the server was down records its `url_alias` row and the old `/docs/...` URL
 * 301s. The companion pins the accepted §5.2 trade-off (unmaterialized → fresh id, no alias), and
 * the advisory tests prove a deleted or garbage checkpoint degrades to exactly the pre-Phase-2
 * behavior — rebuild succeeds, no alias, no error.
 *
 * Restarts are simulated for real: one in-memory app DB outlives multiple [IndexBuilder]s, each
 * built fresh (holder at EMPTY, alias registry re-loaded) the way `serve` builds them at startup.
 */
class IndexBuilderCheckpointTest : FunSpec({

    val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val materializedPage = "---\nid: ${pageId.value}\ntitle: Start\n---\n\n# Start\n"

    test("a MATERIALIZED page moved while the server was down records its alias from the checkpoint; the old URL 301s") {
        withTempTree(seed = { root -> writePage(root, "docs/start.md", materializedPage) }) { root ->
            RestartableHarness(root).use { harness ->
                harness.startProcess().builder.rebuild()
                harness.checkpoints.load() shouldContainExactly mapOf(pageId to TreePath.require("docs/start"))

                // Server down: the page moves on disk before the next process's first rebuild.
                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))

                val restarted = harness.startProcess()
                val snapshot = restarted.builder.rebuild()
                snapshot.byId.getValue(pageId).url shouldBe "/docs/archive/start"
                harness.aliases.find(TreePath.require("docs/start")) shouldBe pageId

                // The acceptance criterion's wire half: the OLD canonical URL answers 301 → new.
                testApplication {
                    application { plainbaseModule(restarted.services()) }
                    val response = createClient { followRedirects = false }.get("/docs/docs/start")
                    response.status shouldBe HttpStatusCode.MovedPermanently
                    response.headers[HttpHeaders.Location] shouldBe "/docs/archive/start"
                }
            }
        }
    }

    test("companion (§5.2 trade-off pinned): an UNMATERIALIZED page moved while down gets a fresh id and no alias") {
        withTempTree(seed = { root -> writePage(root, "docs/loose.md", "---\ntitle: Loose\n---\n\n# Loose\n") }) { root ->
            RestartableHarness(root).use { harness ->
                val firstId = harness.startProcess().builder.rebuild().byUrlPath.getValue(TreePath.require("docs/loose")).id

                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/loose.md"), root.resolve("archive/loose.md"))

                val snapshot = harness.startProcess().builder.rebuild()
                // Path-keyed identity pre-materialization: the moved file is a NEW page to the index.
                snapshot.byUrlPath.getValue(TreePath.require("archive/loose")).id shouldNotBe firstId
                harness.aliases.find(TreePath.require("docs/loose")).shouldBeNull()
            }
        }
    }

    test("advisory: a checkpoint deleted before startup degrades to exactly the pre-Phase-2 behavior") {
        withTempTree(seed = { root -> writePage(root, "docs/start.md", materializedPage) }) { root ->
            RestartableHarness(root).use { harness ->
                harness.startProcess().builder.rebuild()
                harness.checkpoints.replace(emptyMap()) // the operator nuked app state

                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))

                val snapshot = harness.startProcess().builder.rebuild() // must not throw
                snapshot.byId.getValue(pageId).url shouldBe "/docs/archive/start" // index correctness never depends on it
                harness.aliases.find(TreePath.require("docs/start")).shouldBeNull() // the missed alias, exactly as Phase 1
            }
        }
    }

    test("advisory: garbage checkpoint rows load as the empty checkpoint — rebuild succeeds, no alias, no error") {
        withTempTree(seed = { root -> writePage(root, "docs/start.md", materializedPage) }) { root ->
            RestartableHarness(root).use { harness ->
                harness.startProcess().builder.rebuild()
                // A 3-byte id BLOB can never decode to a PageId: the adapter must degrade, not throw.
                harness.seedGarbageCheckpointRow()
                harness.checkpoints.load().shouldBeEmpty()

                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))

                val snapshot = harness.startProcess().builder.rebuild() // must not throw
                snapshot.byId.getValue(pageId).url shouldBe "/docs/archive/start"
                harness.aliases.find(TreePath.require("docs/start")).shouldBeNull()
            }
        }
    }

    test("the checkpoint listener replaces on every publish, with collision losers checkpointed as null") {
        withTempTree(seed = { root ->
            writePage(root, "a b.md", "---\ntitle: Spaced\n---\n\n# Spaced\n")
            writePage(root, "a-b.md", "---\ntitle: Hyphenated\n---\n\n# Hyphenated\n")
        }) { root ->
            RestartableHarness(root).use { harness ->
                val snapshot = harness.startProcess().builder.rebuild()
                val winner = snapshot.byPath.getValue(TreePath.require("a b.md"))
                val loser = snapshot.byPath.getValue(TreePath.require("a-b.md"))

                harness.checkpoints.load() shouldContainExactly mapOf(winner.id to TreePath.require("a-b"), loser.id to null)

                // A second publish REPLACES (not appends): the loser's file disappears, so does its row.
                Files.delete(root.resolve("a-b.md"))
                harness.startProcess().builder.rebuild()
                harness.checkpoints.load() shouldContainExactly mapOf(winner.id to TreePath.require("a-b"))
            }
        }
    }
})

/**
 * One app-DB lifetime spanning simulated process restarts: [startProcess] hands back the fresh
 * `IndexBuilder` + re-loaded `UrlAliasRegistry` a real startup would build — over the SAME
 * database, with the §B3 checkpoint-replace listener registered like `checkpointModule` does.
 */
private class RestartableHarness(private val root: Path) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)

    val aliases = SqlDelightUrlAliasRepository(database)
    val checkpoints = SqlDelightPageCheckpointRepository(database)

    fun startProcess(): Process {
        val store = LocalContentStore(root)
        val registry = UrlAliasRegistry(aliases)
        val builder = IndexBuilder(
            contentStore = store,
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7IdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = SqlDelightIdMapRepository(database),
            aliasRegistry = registry,
            checkpoint = checkpoints,
            citations = CitationFactory(),
            listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)),
        )
        return Process(store, registry, builder)
    }

    fun seedGarbageCheckpointRow() {
        driver.execute(null, "INSERT INTO page_checkpoint(id, url_path) VALUES (x'BADBAD', 'docs/start')", 0)
    }

    override fun close() = driver.close()

    inner class Process(
        private val store: LocalContentStore,
        val registry: UrlAliasRegistry,
        val builder: IndexBuilder,
    ) {
        /** The production route graph over this process's services (the 301 assertion). */
        fun services() = RestServices(
            indexBuilder = builder,
            pageService = PageService(builder, registry, CitationFactory()),
            searchService = SearchService(mockk(relaxed = true), builder), // 301s never touch search
            aliasRegistry = registry,
            contentStore = store,
            writePipeline = mockk(relaxed = true), // 301s never touch the write pipeline
            citations = CitationFactory(),
            idProvider = UuidV7IdProvider(),
            maxWriteBodyBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
        )
    }
}
