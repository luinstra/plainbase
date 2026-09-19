package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.HistoryCommandException
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootAvailability
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
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Source-reader production-rebuild characterizations. These tests deliberately exercise the real [IndexBuilder] graph:
 * issues are observed at the repository decorator, while the underlying SQLDelight repository remains available for
 * the distinct persisted-state assertions. The same three bodies run before and after the source-reader move.
 */
class IndexSourceReaderIssueBufferingTest : FunSpec({

    test("should discard buffered issues on a live history failure and record them once on retry") {
        withSourceReaderTempTree { root ->
            val history = SourceReaderArmedHistory(NoOpHistoryProvider)
            val store = LocalContentStore(root)
            val rootName = RootName.PRIMARY
            val graph = SourceReaderBufferingGraph(
                registry = RootRegistry.of(listOf(localRoot("docs", root))),
                sources = listOf(IndexBuilder.Source(localRoot("docs", root), store, history)),
            )
            graph.use {
                writeSourceReaderPage(root, "anchor.md", SOURCE_READER_ANCHOR_ID, "Anchor", "old marker")
                val warm = graph.builder.rebuild()
                val anchorPath = TreePath.require("anchor.md")
                val anchorUrlPath = TreePath.require("anchor")
                val warmAnchor = warm.pageAt(RootedPageId(rootName, SOURCE_READER_ANCHOR_ID)).shouldNotBeNull()
                warmAnchor.markdown shouldBe sourceReaderPage(SOURCE_READER_ANCHOR_ID, "Anchor", "old marker")
                warmAnchor.urlPath shouldBe anchorUrlPath
                graph.realIdMap.bindings() shouldContainExactly listOf(
                    IdBinding(RootedPath(rootName, anchorPath), SOURCE_READER_ANCHOR_ID, materialized = true),
                )
                graph.checkpoints.load() shouldContainExactly mapOf(
                    RootedPageId(rootName, SOURCE_READER_ANCHOR_ID) to anchorUrlPath,
                )
                graph.limbo.current() shouldBe emptyMap()
                graph.realIdMap.issues() shouldBe emptyList()
                history.calls.clear()
                graph.recordedIssues.clear()
                graph.observedSnapshots.clear()
                graph.observedCurrents.clear()
                graph.observedCheckpoints.clear()

                writeSourceReaderPage(root, "anchor.md", SOURCE_READER_ANCHOR_ID, "Anchor", "new marker")
                writeSourceReaderPage(root, "a b.md", SOURCE_READER_COLLISION_ID_A, "Spaced", "collision a")
                writeSourceReaderPage(root, "a-b.md", SOURCE_READER_COLLISION_ID_B, "Hyphenated", "collision b")
                val expectedPaths = listOf(
                    TreePath.require("a b.md"),
                    TreePath.require("a-b.md"),
                    anchorPath,
                )
                history.armed = true

                val failed = graph.builder.rebuild()
                (failed === warm) shouldBe false
                (graph.builder.current === failed) shouldBe true
                graph.observedSnapshots.single() shouldBeSameInstanceAs failed
                graph.observedCurrents.single() shouldBeSameInstanceAs failed
                graph.observedCurrents.single() shouldBeSameInstanceAs graph.observedSnapshots.single()
                graph.observedCheckpoints.single() shouldContainExactly mapOf(
                    RootedPageId(rootName, SOURCE_READER_ANCHOR_ID) to anchorUrlPath,
                )
                failed.pageAt(RootedPageId(rootName, SOURCE_READER_ANCHOR_ID)) shouldBeSameInstanceAs warmAnchor
                failed.pageAt(RootedPageId(rootName, SOURCE_READER_ANCHOR_ID))!!.markdown shouldBe
                    sourceReaderPage(SOURCE_READER_ANCHOR_ID, "Anchor", "old marker")
                failed.pageAt(RootedPageId(rootName, SOURCE_READER_COLLISION_ID_A)).shouldBeNull()
                failed.pageAt(RootedPageId(rootName, SOURCE_READER_COLLISION_ID_B)).shouldBeNull()
                history.calls shouldContainExactly listOf(expectedPaths)
                graph.recordedIssues shouldBe emptyList()
                graph.realIdMap.issues() shouldBe emptyList()
                graph.availability.current().isAvailable(rootName) shouldBe true
                graph.limbo.current() shouldBe mapOf(
                    rootName to setOf(BindingRef(anchorPath, SOURCE_READER_ANCHOR_ID)),
                )

                history.armed = false
                history.calls.clear()
                graph.recordedIssues.clear()
                graph.observedSnapshots.clear()
                graph.observedCurrents.clear()
                graph.observedCheckpoints.clear()
                val expectedIssue = IdentityIssue.PathSlugCollision(
                    root = rootName,
                    keptPath = TreePath.require("a b.md"),
                    loserPath = TreePath.require("a-b.md"),
                )

                val retried = graph.builder.rebuild()
                (retried === failed) shouldBe false
                (graph.builder.current === retried) shouldBe true
                graph.observedSnapshots.single() shouldBeSameInstanceAs retried
                graph.observedCurrents.single() shouldBeSameInstanceAs retried
                graph.observedCurrents.single() shouldBeSameInstanceAs graph.observedSnapshots.single()
                graph.observedCheckpoints.single() shouldContainExactly mapOf(
                    RootedPageId(rootName, SOURCE_READER_ANCHOR_ID) to TreePath.require("anchor"),
                    RootedPageId(rootName, SOURCE_READER_COLLISION_ID_A) to TreePath.require("a-b"),
                    RootedPageId(rootName, SOURCE_READER_COLLISION_ID_B) to null,
                )
                retried.pageAt(RootedPageId(rootName, SOURCE_READER_ANCHOR_ID))!!.markdown shouldBe
                    sourceReaderPage(SOURCE_READER_ANCHOR_ID, "Anchor", "new marker")
                retried.pageAt(RootedPageId(rootName, SOURCE_READER_COLLISION_ID_A))!!.url shouldBe "/docs/a-b"
                val loser = retried.pageAt(RootedPageId(rootName, SOURCE_READER_COLLISION_ID_B)).shouldNotBeNull()
                loser.url.shouldBeNull()
                graph.recordedIssues shouldContainExactly listOf(expectedIssue)
                graph.realIdMap.issues() shouldContainExactly listOf(expectedIssue)
                graph.limbo.current() shouldBe emptyMap()
                history.calls shouldContainExactly listOf(expectedPaths)
            }
        }
    }

    test("should record issues from a materialized incomplete scan") {
        withSourceReaderTempTree { root ->
            val real = LocalContentStore(root)
            val store = SourceReaderIncompleteScanStore(real)
            val rootName = RootName.PRIMARY
            val graph = SourceReaderBufferingGraph.single(root, store, NoOpHistoryProvider)
            graph.use {
                writeSourceReaderPage(root, "anchor.md", SOURCE_READER_ANCHOR_ID, "Anchor", "stable")
                graph.builder.rebuild()
                graph.recordedIssues.clear()
                graph.realIdMap.issues() shouldBe emptyList()

                writeSourceReaderPage(root, "a b.md", SOURCE_READER_COLLISION_ID_A, "Spaced", "collision a")
                writeSourceReaderPage(root, "a-b.md", SOURCE_READER_COLLISION_ID_B, "Hyphenated", "collision b")
                store.armed = true

                val warm = graph.builder.current
                val snapshot = graph.builder.rebuild()
                (snapshot === warm) shouldBe false
                (graph.builder.current === snapshot) shouldBe true
                store.observed!!.complete shouldBe false
                store.observed!!.files.map { it.path.value } shouldContainExactlyInAnyOrder
                    listOf("a b.md", "a-b.md", "anchor.md")
                snapshot.pages shouldHaveSize 3
                snapshot.pageAt(RootedPageId(rootName, SOURCE_READER_COLLISION_ID_A))!!.url shouldBe "/docs/a-b"
                snapshot.pageAt(RootedPageId(rootName, SOURCE_READER_COLLISION_ID_B))!!.url.shouldBeNull()
                val expectedIssue = IdentityIssue.PathSlugCollision(
                    root = rootName,
                    keptPath = TreePath.require("a b.md"),
                    loserPath = TreePath.require("a-b.md"),
                )
                graph.recordedIssues shouldContainExactly listOf(expectedIssue)
                graph.realIdMap.issues() shouldContainExactly listOf(expectedIssue)
            }
        }
    }

    test("should discard every source buffer when a later source aborts unexpectedly") {
        withSourceReaderTempTrees { primary, extra ->
            val primaryRoot = RootName.PRIMARY
            val extraRoot = RootName.require("extra")
            val events = mutableListOf<String>()
            val primaryHistory = SourceReaderEventHistory(NoOpHistoryProvider, events, "primary history complete")
            val failure = IllegalStateException("source-reader later-source scan failure")
            val primaryStore = LocalContentStore(primary)
            val extraStore = SourceReaderFailingScanStore(LocalContentStore(extra), events, failure)
            val registry = RootRegistry.of(listOf(localRoot("docs", primary), localRoot("extra", extra)))
            val graph = SourceReaderBufferingGraph(
                registry = registry,
                sources = listOf(
                    IndexBuilder.Source(registry.primary, primaryStore, primaryHistory),
                    IndexBuilder.Source(requireNotNull(registry.byName(extraRoot)), extraStore, NoOpHistoryProvider),
                ),
            )
            graph.use {
                writeSourceReaderPage(primary, "anchor.md", SOURCE_READER_ANCHOR_ID, "Primary", "primary stable")
                writeSourceReaderPage(extra, "anchor.md", SOURCE_READER_EXTRA_ID, "Extra", "extra stable")
                graph.builder.rebuild()
                val previous = graph.builder.current
                graph.recordedIssues.clear()
                graph.observedSnapshots.clear()
                graph.observedCurrents.clear()
                graph.observedCheckpoints.clear()
                events.clear()

                writeSourceReaderPage(primary, "a b.md", SOURCE_READER_COLLISION_ID_A, "Spaced", "collision a")
                writeSourceReaderPage(primary, "a-b.md", SOURCE_READER_COLLISION_ID_B, "Hyphenated", "collision b")
                extraStore.armed = true

                val thrown = shouldThrow<IllegalStateException> { graph.builder.rebuild() }
                (thrown === failure) shouldBe true
                events shouldContainExactly listOf("primary history complete", "secondary scan failure")
                primaryHistory.lastPaths shouldBe listOf(
                    TreePath.require("a b.md"),
                    TreePath.require("a-b.md"),
                    TreePath.require("anchor.md"),
                )
                (graph.builder.current === previous) shouldBe true
                graph.observedSnapshots shouldBe emptyList()
                graph.observedCurrents shouldBe emptyList()
                graph.observedCheckpoints shouldBe emptyList()
                graph.recordedIssues shouldBe emptyList()
                graph.realIdMap.issues() shouldBe emptyList()
                primaryStore.available() shouldBe true
                extraStore.delegate.available() shouldBe true
                graph.availability.current().isAvailable(primaryRoot) shouldBe true
                graph.availability.current().isAvailable(extraRoot) shouldBe true
            }
        }
    }
})

private val SOURCE_READER_ANCHOR_ID = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
private val SOURCE_READER_COLLISION_ID_A = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b")
private val SOURCE_READER_COLLISION_ID_B = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5c")
private val SOURCE_READER_EXTRA_ID = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5d")

private fun sourceReaderPage(id: PageId, title: String, marker: String): String =
    "---\nid: ${id.value}\ntitle: $title\n---\n\n# $title\n\n$marker\n"

private fun writeSourceReaderPage(root: Path, path: String, id: PageId, title: String, marker: String) {
    val file = root.resolve(path)
    Files.createDirectories(file.parent)
    Files.writeString(file, sourceReaderPage(id, title, marker))
}

private fun <T> withSourceReaderTempTree(block: (Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-source-reader-buffering")
    return try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun <T> withSourceReaderTempTrees(block: (Path, Path) -> T): T {
    val primary = Files.createTempDirectory("plainbase-source-reader-primary")
    val extra = Files.createTempDirectory("plainbase-source-reader-secondary")
    return try {
        block(primary, extra)
    } finally {
        primary.toFile().deleteRecursively()
        extra.toFile().deleteRecursively()
    }
}

private class SourceReaderFixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
}

private class SourceReaderBufferingGraph(
    val registry: RootRegistry,
    sources: List<IndexBuilder.Source>,
) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val fixedClock = SourceReaderFixedClock()

    val realIdMap = SqlDelightIdMapRepository(database)
    val recordedIssues = mutableListOf<IdentityIssue>()
    val idMap: IdMapRepository = SourceReaderRecordingIdMap(realIdMap, recordedIssues)
    val checkpoints: PageCheckpointRepository = SqlDelightPageCheckpointRepository(database)
    val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val availability = RootAvailability(fixedClock)
    val limbo = RootLimbo()
    val observedSnapshots = mutableListOf<PageIndex>()
    val observedCurrents = mutableListOf<PageIndex>()
    val observedCheckpoints = mutableListOf<Map<RootedPageId, TreePath?>>()

    val builder: IndexBuilder

    init {
        lateinit var built: IndexBuilder
        built = IndexBuilder(
            sources = sources,
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = PageIdentityService(TestIdProvider()),
            patcher = FrontmatterPatcher(),
            idMap = idMap,
            aliasRegistry = aliases,
            checkpoint = checkpoints,
            citations = CitationFactory(),
            rootRank = registry::rank,
            registeredRoots = registry.roots.map { it.name }.toSet(),
            listeners = listOf(
                IndexBuilder.PublicationListener(checkpoints::replaceFrom),
                IndexBuilder.PublicationListener { snapshot, _ ->
                    observedSnapshots += snapshot
                    observedCurrents += built.current
                    observedCheckpoints += checkpoints.load()
                },
            ),
            availability = availability,
            limbo = limbo,
            policies = allowAllPolicies(registry.roots.map { it.name }),
        )
        builder = built
    }

    override fun close() = driver.close()

    companion object {
        fun single(root: Path, store: ContentStore, history: HistoryProvider): SourceReaderBufferingGraph {
            val registry = RootRegistry.of(listOf(localRoot("docs", root)))
            return SourceReaderBufferingGraph(
                registry = registry,
                sources = listOf(IndexBuilder.Source(registry.primary, store, history)),
            )
        }
    }
}

private class SourceReaderRecordingIdMap(
    private val delegate: IdMapRepository,
    private val calls: MutableList<IdentityIssue>,
) : IdMapRepository by delegate {
    override fun record(issue: IdentityIssue) {
        calls += issue
        delegate.record(issue)
    }
}

private class SourceReaderArmedHistory(
    private val delegate: HistoryProvider,
) : HistoryProvider by delegate {
    var armed = false
    val calls = mutableListOf<List<TreePath>>()

    override fun lastCommits(
        paths: List<TreePath>,
    ): Map<TreePath, Commit> {
        calls += paths.toList()
        if (armed) throw HistoryCommandException("source-reader live history failure")
        return delegate.lastCommits(paths)
    }
}

private class SourceReaderIncompleteScanStore(
    private val delegate: ContentStore,
) : ContentStore by delegate {
    var armed = false
    var observed: ScanResult? = null

    override fun scan(): ScanResult {
        val real = delegate.scan()
        val result = if (armed) real.copy(complete = false) else real
        observed = result
        return result
    }
}

private class SourceReaderEventHistory(
    private val delegate: HistoryProvider,
    private val events: MutableList<String>,
    private val event: String,
) : HistoryProvider by delegate {
    var lastPaths: List<TreePath> = emptyList()

    override fun lastCommits(
        paths: List<TreePath>,
    ): Map<TreePath, Commit> {
        lastPaths = paths.toList()
        val result = delegate.lastCommits(paths)
        events += event
        return result
    }
}

private class SourceReaderFailingScanStore(
    val delegate: ContentStore,
    private val events: MutableList<String>,
    private val failure: IllegalStateException,
) : ContentStore by delegate {
    var armed = false

    override fun scan(): ScanResult {
        if (armed) {
            events += "secondary scan failure"
            throw failure
        }
        return delegate.scan()
    }
}
