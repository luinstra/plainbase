package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import com.plainbase.frameworks.search.Fts5SearchProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * DI wiring for the S2 search stack, end to end through the REAL module graph: [searchModule]
 * resolves the provider behind the port, and — the §B4 seam this chunk exists to close —
 * `indexModule`'s `getAll()` collects the registered `SearchIndexer` publication listener, so an
 * `IndexBuilder.rebuild()` leaves the engine synced WITHOUT anyone calling the indexer by hand.
 * A "declared but not collected" listener would make every rebuild silently skip search sync.
 */
class SearchModuleWiringTest : FunSpec({

    test("rebuild through the production wiring syncs the search engine via the publication listener") {
        withTempTree(seed = { root ->
            writePage(root, "docs/widget.md", "# Widget Catalog\n\nflux capacitors and sprockets\n")
        }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val env = mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString())
                val config = PlainbaseConfig.fromEnv(env)
                val openers = ServerOpeners()
                val inputs = prepareRootBootInputs(config, openers.openLocal)
                val app = koinApplication {
                    modules(
                        module { single { config } },
                        createContentModule(config, inputs, openers.openObject),
                        repositoryModule,
                        createHistoryModule(config, inputs.history),
                        indexModule,
                        searchModule,
                    )
                }
                try {
                    inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
                    val provider = app.koin.get<SearchProvider>()
                    provider.shouldBeInstanceOf<Fts5SearchProvider>()
                    app.koin.getAll<IndexBuilder.PublicationListener>() shouldHaveSize 1

                    app.koin.get<IndexBuilder>().rebuild()
                    val results = provider.search(SearchQuery("sprockets", 10, 0))
                    results.total shouldBe 1L
                } finally {
                    app.close() // also closes SearchDb (onClose)
                }
            }
        }
    }

    test("production search-only rebuild uses shared durable retirement authority and leaves checkpoints unchanged") {
        withTempTree(seed = { root ->
            writePage(root, "docs/widget.md", "# Widget Catalog\n\nretirement wiring term\n")
            writePage(root, "docs/control.md", "# Control Page\n\nretirement control term\n")
        }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val env = mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString())
                val config = PlainbaseConfig.fromEnv(env)
                val openers = ServerOpeners()
                val inputs = prepareRootBootInputs(config, openers.openLocal)
                val app = koinApplication {
                    modules(
                        module { single { config } },
                        createContentModule(config, inputs, openers.openObject),
                        repositoryModule,
                        createHistoryModule(config, inputs.history),
                        checkpointModule,
                        indexModule,
                        searchModule,
                    )
                }
                try {
                    inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
                    app.koin.getAll<IndexBuilder.PublicationListener>() shouldHaveSize 2
                    val builder = app.koin.get<IndexBuilder>()
                    val provider = app.koin.get<SearchProvider>()
                    val idMap = app.koin.get<IdMapRepository>()
                    val checkpoints = app.koin.get<PageCheckpointRepository>()
                    val indexer = app.koin.get<SearchIndexer>()
                    val before = builder.rebuild()
                    val victim = before.pages.single { it.path.value == "docs/widget.md" }
                    val control = before.pages.single { it.path.value == "docs/control.md" }
                    val oldRootedPath = RootedPath(RootName.PRIMARY, victim.path)
                    val checkpointsBefore = checkpoints.load()
                    val replacementId = PageId.require("01900000-0000-7000-8000-000000000201")

                    idMap.bind(oldRootedPath, replacementId, materialized = true)
                    idMap.bindingInRoot(RootName.PRIMARY, victim.id) shouldBe null
                    val retired = idMap.retiredAt(RootName.PRIMARY, victim.id)
                    retired shouldNotBe null
                    retired!!.path shouldBe oldRootedPath

                    builder.rebuildSearchIndex() shouldBe 1
                    (builder.current === before) shouldBe true
                    provider.indexedState().keys shouldBe setOf(control.rooted)
                    val controlState = provider.indexedState()[control.rooted]
                    controlState shouldNotBe null
                    controlState!!.contentHash shouldBe control.contentHash
                    controlState.path shouldBe control.path
                    provider.search(SearchQuery("retirement control term", 10, 0)).total shouldBe 1L
                    provider.search(SearchQuery("retirement wiring term", 10, 0)).total shouldBe 0L
                    checkpoints.load() shouldBe checkpointsBefore
                    shouldThrow<IllegalStateException> { indexer.syncPage(victim) }
                } finally {
                    app.close()
                }
            }
        }
    }

    test("a prepared LOCAL rebind reaches the real graph epoch before a later rebuild") {
        val base = Files.createTempDirectory("plainbase-search-rebind")
        val original = Files.createDirectory(base.resolve("content"))
        val replacement = Files.createDirectory(base.resolve("replacement"))
        val dataDir = Files.createDirectory(base.resolve("data"))
        val retained = base.resolve("content-retained")
        writePage(original, "docs/widget.md", "# Original\n\nrebind source\n")
        writePage(replacement, "docs/widget.md", "# Replacement\n\nrebind target\n")
        try {
            val config = PlainbaseConfig.fromEnv(
                mapOf("CONTENT_DIR" to original.toString(), "DATA_DIR" to dataDir.toString()),
            )
            val openers = ServerOpeners()
            val inputs = prepareRootBootInputs(config, openers.openLocal)
            var driver: SqlDriver? = null
            val app = koinApplication {
                modules(
                    module { single { config } },
                    createContentModule(config, inputs, openers.openObject),
                    createRepositoryModule(
                        openDriver = { path -> openers.openDriver(path).also { driver = it } },
                    ),
                    createHistoryModule(config, inputs.history),
                    indexModule,
                    searchModule,
                )
            }
            try {
                inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
                val builder = app.koin.get<IndexBuilder>()
                builder.rebuild()
                val retirements = app.koin.get<RetirementRepository>()
                val before = retirements.observation(RootName.PRIMARY)
                val originalKey = fileKey(original)

                Files.move(original, retained)
                Files.move(replacement, original)
                val replacementKey = fileKey(original)
                originalKey shouldNotBe replacementKey

                val store = inputs.localStores.getValue(RootName.PRIMARY)
                store.available() shouldBe true
                val after = retirements.observation(RootName.PRIMARY)
                after.value shouldBe before.value + 1
                app.koin.get<RootAvailability>() shouldBeSameInstanceAs inputs.availability
                inputs.availability.current().isAvailable(RootName.PRIMARY) shouldBe true
            } finally {
                try {
                    app.close()
                } finally {
                    driver?.close()
                }
            }
        } finally {
            if (Files.exists(retained)) retained.toFile().deleteRecursively()
            base.toFile().deleteRecursively()
        }
    }
})

private fun fileKey(path: Path): Any = requireNotNull(
    Files.readAttributes(path, BasicFileAttributes::class.java).fileKey(),
) { "the fixture filesystem does not expose directory file keys" }
