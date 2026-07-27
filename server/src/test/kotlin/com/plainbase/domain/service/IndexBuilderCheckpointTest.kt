package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.buildRouteContext
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
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

    fun rooted(path: String) = RootedPath(RootName.PRIMARY, TreePath.require(path))

    test("a MATERIALIZED page moved while the server was down records its alias from the checkpoint; the old URL 301s") {
        withTempTree(seed = { root -> writePage(root, "docs/start.md", materializedPage) }) { root ->
            RestartableHarness(root).use { harness ->
                harness.startProcess().builder.rebuild()
                harness.checkpoints.load() shouldContainExactly
                    mapOf(RootedPageId(RootName.PRIMARY, pageId) to TreePath.require("docs/start"))

                // Server down: the page moves on disk before the next process's first rebuild.
                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))

                val restarted = harness.startProcess()
                val snapshot = restarted.builder.rebuild()
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, pageId))!!.url shouldBe "/docs/main/archive/start"
                harness.aliases.find(rooted("docs/start")) shouldBe RootedPageId(RootName.PRIMARY, pageId)

                // The acceptance criterion's wire half: the OLD canonical URL answers 301 → new.
                testApplication {
                    application { plainbaseModule(restarted.services()) }
                    val response = createClient { followRedirects = false }.get("/docs/main/docs/start")
                    response.status shouldBe HttpStatusCode.MovedPermanently
                    response.headers[HttpHeaders.Location] shouldBe "/docs/main/archive/start"
                }
            }
        }
    }

    test("companion (§5.2 trade-off pinned): an UNMATERIALIZED page moved while down gets a fresh id and no alias") {
        withTempTree(seed = { root -> writePage(root, "docs/loose.md", "---\ntitle: Loose\n---\n\n# Loose\n") }) { root ->
            RestartableHarness(root).use { harness ->
                val firstId = harness.startProcess().builder.rebuild().byUrlPath.getValue(rooted("docs/loose")).id

                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/loose.md"), root.resolve("archive/loose.md"))

                val snapshot = harness.startProcess().builder.rebuild()
                // Path-keyed identity pre-materialization: the moved file is a NEW page to the index.
                snapshot.byUrlPath.getValue(rooted("archive/loose")).id shouldNotBe firstId
                harness.aliases.find(rooted("docs/loose")).shouldBeNull()
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
                // index correctness never depends on it
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, pageId))!!.url shouldBe "/docs/main/archive/start"
                harness.aliases.find(rooted("docs/start")).shouldBeNull() // the missed alias, exactly as Phase 1
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
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, pageId))!!.url shouldBe "/docs/main/archive/start"
                harness.aliases.find(rooted("docs/start")).shouldBeNull()
            }
        }
    }

    test("the checkpoint listener upserts on every publish, with collision losers checkpointed as null") {
        withTempTree(seed = { root ->
            writePage(root, "a b.md", "---\ntitle: Spaced\n---\n\n# Spaced\n")
            writePage(root, "a-b.md", "---\ntitle: Hyphenated\n---\n\n# Hyphenated\n")
        }) { root ->
            RestartableHarness(root).use { harness ->
                val snapshot = harness.startProcess().builder.rebuild()
                val winner = snapshot.byPath.getValue(rooted("a b.md"))
                val loser = snapshot.byPath.getValue(rooted("a-b.md"))

                harness.checkpoints.load() shouldContainExactly
                    mapOf(winner.rooted to TreePath.require("a-b"), loser.rooted to null)

                // A second publish UPSERTS what it saw - and, since C0, DELETES only what an AbsenceProof retired.
                // The loser's file disappears, and its row STAYS: "the scan did not find it" is not evidence that
                // it is gone, and this row is the down-time-move alias fact. It is in LIMBO, and it self-heals if
                // the page comes back. (Under the old rule the whole root's rows died to a decoy tree here.)
                Files.delete(root.resolve("a-b.md"))
                harness.startProcess().builder.rebuild()
                harness.checkpoints.load() shouldContainExactly mapOf(
                    winner.rooted to TreePath.require("a-b"),
                    loser.rooted to null,
                )
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
    private val rootRegistry = RootRegistry.of(listOf(localRoot("main", root)))

    fun startProcess(): Process {
        val store = LocalContentStore(root)
        val registry = UrlAliasRegistry(aliases)
        val availability = RootAvailability(kotlin.time.Clock.System)
        val idMap = SqlDelightIdMapRepository(database)
        val builder = IndexBuilder(
            sources = listOf(IndexBuilder.Source(rootRegistry.primary, store, NoOpHistoryProvider)),
            availability = availability,
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(UuidV7IdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = idMap,
            aliasRegistry = registry,
            checkpoint = checkpoints,
            citations = CitationFactory(),
            rootRank = rootRegistry::rank,
            registeredRoots = rootRegistry.roots.map { it.name }.toSet(),
            listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)),
        )
        return Process(store, registry, builder, availability, idMap)
    }

    fun seedGarbageCheckpointRow() {
        driver.execute(null, "INSERT INTO page_checkpoint(id, root, url_path) VALUES (x'BADBAD', 'main', 'docs/start')", 0)
    }

    override fun close() = driver.close()

    inner class Process(
        private val store: LocalContentStore,
        val registry: UrlAliasRegistry,
        val builder: IndexBuilder,
        private val availability: RootAvailability,
        private val idMap: SqlDelightIdMapRepository,
    ) {
        /** The A3 route graph (RouteContext) over this process's services (the 301 alias-redirect assertion). */
        fun services() = buildRouteContext(
            // Loopback-dev (OFF) open mode: the 301 path is read-gated, so a real (open) read gate must pass.
            policy = PolicyService(
                roles = SqlDelightRoleRepository(database),
                apiTokens = SqlDelightApiTokenRepository(database),
                audit = SqlDelightAuditRepository(database),
                idProvider = UuidV7IdProvider(),
                clock = kotlin.time.Clock.System,
                enforced = false,
            ),
            indexBuilder = builder,
            pageService = PageService(builder, registry, CitationFactory()),
            searchService = SearchService(mockk(relaxed = true), builder, availability), // 301s never touch search
            aliasRegistry = registry,
            writePipeline = mockk(relaxed = true), // 301s never touch the write pipeline
            registry = rootRegistry,
            availability = availability,
            resolver = PageRootResolver(idMap, rootRegistry),
            absence = AbsenceClassifier(idMap),
            stores = { store },
            histories = { NoOpHistoryProvider },
            idProvider = UuidV7IdProvider(),
            // 301 alias-redirects never touch the proposal surface; relaxed mocks satisfy the wiring.
            proposalService = mockk(relaxed = true),
            proposalLabeler = mockk(relaxed = true),
            tokens = ApiTokenService(
                minter = ApiTokenMinter(),
                hasher = TokenHasher(),
                tokens = SqlDelightApiTokenRepository(database),
                clock = kotlin.time.Clock.System,
            ),
            // 301 alias-redirects never touch the auth services; a relaxed mock satisfies the wiring.
            auth = mockk(relaxed = true),
            trustedProxyCidrs = emptyList(),
            maxWriteBodyBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
        )
    }
}
