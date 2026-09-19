package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Direct publication characterizations for the IndexBuilder snapshot holder. These two bodies use the real SQLDelight repositories
 * and filesystem/rendering graph, while the retirement decorator isolates the obsolete plural read from the required
 * singular capture read.
 */
class IndexPublicationStateTest : FunSpec({

    test("should publish updated content without an obsolete plural observation read") {
        withTempTree(seed = {}) { root ->
            PublicationGraph(root).use { graph ->
                graph.builder.current shouldBeSameInstanceAs PageIndex.EMPTY
                val successfulEmpty = graph.builder.rebuild()
                successfulEmpty shouldBeSameInstanceAs graph.builder.current
                successfulEmpty.pages.size shouldBe 0
                successfulEmpty.byRootedId.size shouldBe 0
                successfulEmpty.byPath.size shouldBe 0
                successfulEmpty.byUrlPath.size shouldBe 0
                successfulEmpty shouldBeSameInstanceAs graph.callbackSnapshots.single()
                successfulEmpty shouldBeSameInstanceAs graph.callbackCurrents.single()
                (successfulEmpty !== PageIndex.EMPTY) shouldBe true

                val rootedPath = RootedPath(RootName.PRIMARY, TreePath.require("article.md"))
                writePage(root, "article.md", publicationPage("Old title", "old-slug", "old body"))
                val warm = graph.builder.rebuild()
                val oldPage = warm.byPath.getValue(rootedPath)
                val expectedRootedId = RootedPageId(RootName.PRIMARY, FIXED_PAGE_ID)
                val expectedOldBinding = IdBinding(rootedPath, FIXED_PAGE_ID, materialized = true)
                oldPage.rooted shouldBe expectedRootedId
                val expectedOldCheckpoint = mapOf(expectedRootedId to TreePath.require("old-slug"))
                graph.idMap.bindings() shouldBe listOf(expectedOldBinding)
                graph.checkpoints.load() shouldBe expectedOldCheckpoint
                graph.reset()

                graph.retirements.pluralFailure = LatePluralObservationFailure()
                val updatedBytes = publicationPage("New title", "new-slug", "new body")
                writePage(root, "article.md", updatedBytes)

                val published = graph.builder.rebuild()
                val updatedPage = published.byPath.getValue(rootedPath)
                published shouldBeSameInstanceAs graph.builder.current
                published shouldBeSameInstanceAs graph.callbackSnapshots.single()
                published shouldBeSameInstanceAs graph.callbackCurrents.single()
                graph.callbackSnapshots shouldBe listOf(published)
                graph.retirements.pluralCalls shouldBe 0
                graph.retirements.singularCalls shouldBe 1
                graph.scanCalls shouldBe 1
                graph.renderCalls shouldBe 1
                updatedPage.id shouldBe FIXED_PAGE_ID
                updatedPage.rooted shouldBe expectedRootedId
                updatedPage.id shouldBe oldPage.id
                updatedPage.title shouldBe "New title"
                updatedPage.markdown shouldBe updatedBytes
                updatedPage.contentHash shouldBe graph.citations.contentHash(updatedBytes.toByteArray())
                updatedPage.html.contains("new body") shouldBe true
                updatedPage.url shouldBe "/docs/new-slug"
                graph.aliases.find(RootedPath(RootName.PRIMARY, TreePath.require("old-slug"))) shouldBe updatedPage.rooted
                graph.checkpoints.load() shouldBe mapOf(expectedRootedId to TreePath.require("new-slug"))
                graph.idMap.bindings() shouldBe listOf(expectedOldBinding)
                graph.idMap.retiredBindings().size shouldBe 0
            }
        }
    }

    test("should propagate required singular observation failure and recover on retry") {
        withTempTree(seed = { root -> writePage(root, "article.md", publicationPage("Old title", "old-slug", "old body")) }) { root ->
            PublicationGraph(root).use { graph ->
                val rootedPath = RootedPath(RootName.PRIMARY, TreePath.require("article.md"))
                val warm = graph.builder.rebuild()
                val expectedRootedId = RootedPageId(RootName.PRIMARY, FIXED_PAGE_ID)
                warm.byPath.getValue(rootedPath).rooted shouldBe expectedRootedId
                val expectedOldBinding = IdBinding(rootedPath, FIXED_PAGE_ID, materialized = true)
                val expectedOldCheckpoint = mapOf(expectedRootedId to TreePath.require("old-slug"))
                graph.idMap.bindings() shouldBe listOf(expectedOldBinding)
                graph.checkpoints.load() shouldBe expectedOldCheckpoint
                val oldAliases = graph.aliases.all()
                val previous = graph.builder.current
                graph.reset()

                val singularFailure = EarlySingularObservationFailure()
                graph.retirements.singularFailure = singularFailure
                writePage(root, "article.md", publicationPage("New title", "new-slug", "new body"))

                val thrown = shouldThrow<EarlySingularObservationFailure> { graph.builder.rebuild() }
                thrown shouldBe singularFailure
                graph.builder.current shouldBeSameInstanceAs previous
                graph.checkpoints.load() shouldBe expectedOldCheckpoint
                graph.idMap.bindings() shouldBe listOf(expectedOldBinding)
                graph.aliases.all() shouldBe oldAliases
                graph.callbackSnapshots.size shouldBe 0
                graph.callbackCurrents.size shouldBe 0
                graph.scanCalls shouldBe 0
                graph.renderCalls shouldBe 0
                graph.retirements.singularCalls shouldBe 1
                graph.retirements.pluralCalls shouldBe 0

                graph.retirements.singularFailure = null
                graph.reset()
                val retried = graph.builder.rebuild()
                val updatedPage = retried.byPath.getValue(rootedPath)
                retried shouldBeSameInstanceAs graph.builder.current
                retried shouldBeSameInstanceAs graph.callbackSnapshots.single()
                retried shouldBeSameInstanceAs graph.callbackCurrents.single()
                graph.retirements.singularCalls shouldBe 1
                graph.idMap.bindings() shouldBe listOf(expectedOldBinding)
                updatedPage.rooted shouldBe expectedRootedId
                updatedPage.title shouldBe "New title"
                updatedPage.url shouldBe "/docs/new-slug"
                graph.aliases.find(RootedPath(RootName.PRIMARY, TreePath.require("old-slug"))) shouldBe expectedRootedId
                graph.checkpoints.load() shouldBe mapOf(expectedRootedId to TreePath.require("new-slug"))
            }
        }
    }
})

private val FIXED_PAGE_ID = PageId.require("01900000-0000-7000-8000-000000000101")

private fun publicationPage(title: String, slug: String, body: String): String =
    "---\nid: ${FIXED_PAGE_ID.value}\ntitle: $title\nslug: $slug\n---\n\n# $title\n\n$body\n"

private class PublicationFixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
}

private class LatePluralObservationFailure : RuntimeException("late plural observation read must be obsolete")

private class EarlySingularObservationFailure : RuntimeException("required singular capture read failed")

private class CountingRetirementRepository(
    private val delegate: RetirementRepository,
) : RetirementRepository by delegate {
    var pluralCalls = 0
    var singularCalls = 0
    var pluralFailure: RuntimeException? = null
    var singularFailure: RuntimeException? = null

    override fun observations(): Map<RootName, ObservationId> {
        pluralCalls += 1
        pluralFailure?.let { throw it }
        return delegate.observations()
    }

    override fun observation(root: RootName): ObservationId {
        singularCalls += 1
        singularFailure?.let { throw it }
        return delegate.observation(root)
    }

    fun reset() {
        pluralCalls = 0
        singularCalls = 0
        pluralFailure = null
        singularFailure = null
    }
}

private class PublicationGraph(root: Path) : AutoCloseable {
    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val storeDelegate = LocalContentStore(root)
    private val registry = RootRegistry.of(listOf(localRoot("docs", root)))
    private val store = object : ContentStore by storeDelegate {
        override fun scan(): ScanResult {
            scanCalls += 1
            return storeDelegate.scan()
        }
    }
    private val realRetirements = SqlDelightRetirementRepository(database)
    val retirements = CountingRetirementRepository(realRetirements)
    val idMap = SqlDelightIdMapRepository(database)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val citations = CitationFactory()
    private val availability = RootAvailability(PublicationFixedClock())
    private val limbo = RootLimbo()
    private val epochs = ObservationEpoch(retirements, RootConvergence())
    val callbackSnapshots = mutableListOf<PageIndex>()
    val callbackCurrents = mutableListOf<PageIndex>()
    var scanCalls = 0
    var renderCalls = 0
    lateinit var builder: IndexBuilder

    init {
        val rendererFactory = { view: PageIndexView ->
            val delegate = FlexmarkRenderer(view)
            object : MarkdownRenderer {
                override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
                    renderCalls += 1
                    return delegate.render(sourcePath, source)
                }
            }
        }
        builder = IndexBuilder(
            sources = listOf(IndexBuilder.Source(registry.primary, store, NoOpHistoryProvider)),
            frontmatterParser = FrontmatterReader(),
            rendererFactory = rendererFactory,
            identity = PageIdentityService(TestIdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = idMap,
            aliasRegistry = aliases,
            checkpoint = checkpoints,
            citations = citations,
            rootRank = registry::rank,
            registeredRoots = registry.roots.map { it.name }.toSet(),
            listeners = listOf(
                IndexBuilder.PublicationListener(checkpoints::replaceFrom),
                IndexBuilder.PublicationListener { snapshot, _ ->
                    callbackSnapshots += snapshot
                    callbackCurrents += builder.current
                },
            ),
            availability = availability,
            retirements = retirements,
            limbo = limbo,
            epochs = epochs,
            policies = allowAllPolicies(registry.roots.map { it.name }),
        )
    }

    fun reset() {
        scanCalls = 0
        renderCalls = 0
        callbackSnapshots.clear()
        callbackCurrents.clear()
        retirements.reset()
    }

    override fun close() = driver.close()
}
