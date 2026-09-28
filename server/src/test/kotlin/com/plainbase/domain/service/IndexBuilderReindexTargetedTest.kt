package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * PB-WRITE-1 named tests 8, 9, 10; the targeted reindex updates one page and never silently no-ops.
 * The three correctness guards: render count = 1, search work = single-page (no corpus diff),
 * checkpoint writes = 0; plus the vanished-page throw (MUST-FIX 4). These guards do not establish
 * end-to-end constant cost for the published snapshot.
 *
 * Built with explicit spy collaborators (a counting renderer, a counting [SearchProvider], a counting
 * [PageCheckpointRepository]) so each targeted-work claim is asserted independently of corpus size.
 */
class IndexBuilderReindexTargetedTest : FunSpec({

    fun seedCorpus(root: Path, n: Int) {
        repeat(n) { i -> writePage(root, "p%03d.md".format(i), "---\ntitle: Page $i\n---\n\n# Page $i\n\nbody $i.\n") }
    }

    test("reindex(target) re-renders exactly one page and shares every other page instance") {
        withTempTree({ seedCorpus(it, 5) }) { root ->
            ReindexHarness(root).use { h ->
                h.builder.rebuild()
                val before = h.builder.current
                val targetId = before.pages.first().id
                val targetPath = before.pageAt(RootedPageId(RootName.PRIMARY, targetId))!!.path.value
                h.renders.clear()

                // Change that page's bytes on disk so reindex re-reads.
                val edited = "---\ntitle: Page 0\n---\n\n# Page 0\n\nedited.\n"
                Files.write(root.resolve(targetPath), edited.toByteArray())

                val after = h.builder.reindex(mainPath(targetPath))

                h.renders.keys shouldBe setOf(targetPath)
                h.renders.values.all { it == 1 } shouldBe true
                after.pageAt(RootedPageId(RootName.PRIMARY, targetId))!!.markdown shouldBe edited
                // Every other page is the SAME instance — untouched.
                for (page in before.pages) {
                    if (page.id != targetId) after.pageAt(page.rooted)!! shouldBeSameInstanceAs page
                }
            }
        }
    }

    test("reindex's render, search, AND checkpoint work are independent of corpus size") {
        for (n in listOf(3, 30)) {
            withTempTree({ seedCorpus(it, n) }) { root ->
                ReindexHarness(root).use { h ->
                    h.builder.rebuild()
                    val targetId = h.builder.current.pages.first().id
                    val targetPath = h.builder.current.pageAt(RootedPageId(RootName.PRIMARY, targetId))!!.path.value
                    h.renders.clear()
                    h.search.reset()
                    h.wholeReadCalls = 0
                    h.pointReadCalls = 0
                    h.checkpoint.replaceCalls = 0
                    Files.write(root.resolve(targetPath), "---\ntitle: Page 0\n---\n\n# Page 0\n\nnow $n.\n".toByteArray())

                    h.builder.reindex(mainPath(targetPath))

                    // render count = 1, regardless of N.
                    h.renders.values.sum() shouldBe 1
                    // search work = single-page: exactly one index call, with a one-element list, no indexedState/rebuild.
                    h.search.indexCalls shouldBe 1
                    h.search.lastIndexSize shouldBe 1
                    h.search.indexedStateCalls shouldBe 0
                    h.search.rebuildCalls shouldBe 0
                    h.wholeReadCalls shouldBe 0
                    h.pointReadCalls shouldBe 1
                    // checkpoint writes = 0 (skipped — sound because url-changing edits never reach reindex).
                    h.checkpoint.replaceCalls shouldBe 0
                }
            }
        }
    }

    test("a full rebuild DOES write the checkpoint once — proving the reindex asymmetry") {
        withTempTree({ seedCorpus(it, 4) }) { root ->
            ReindexHarness(root).use { h ->
                h.checkpoint.replaceCalls = 0
                h.builder.rebuild()
                h.checkpoint.replaceCalls shouldBe 1
            }
        }
    }

    test("reindex throws IllegalStateException on a vanished save-path page (never a silent no-op)") {
        withTempTree({ seedCorpus(it, 2) }) { root ->
            ReindexHarness(root).use { h ->
                h.builder.rebuild()
                shouldThrow<IllegalStateException> { h.builder.reindex(mainPath("gone.md")) }
            }
        }
    }

    test("a page save precomputes that page with watcher events disabled") {
        withTempTree({ seedCorpus(it, 2) }) { root ->
            val discussionRoot = Files.createTempDirectory("plainbase-reindex-discussions")
            val data = Files.createTempDirectory("plainbase-reindex-discussion-db")
            val signal = DiscussionPublicationSignal()
            try {
                ReindexHarness(root, pageListeners = listOf(signal)).use { h ->
                    h.builder.rebuild()
                    val target = h.builder.current.pages.first()
                    val original = Files.readAllBytes(root.resolve(target.path.value))
                    val quote = "body 0."
                    val start = original.decodeToString().indexOf(quote)
                    check(start >= 0)
                    val discussionId = DiscussionId.require("01900000-0000-7000-8000-000000000151")
                    val anchorHash = CitationFactory().contentHash(original)
                    val author = Author(
                        Actor(SubjectKey("index-reindex", "starter"), "Starter"),
                        AuthorKind.HUMAN,
                    )
                    val marker = DiscussionRecord(
                        id = discussionId,
                        page = PageRef(target.id, target.path),
                        status = DiscussionStatus.OPEN,
                        created = Instant.parse("2026-09-26T10:00:00Z"),
                        startedBy = author,
                        statusChange = null,
                        anchor = Anchor.Quote(
                            anchorHash,
                            null,
                            QuoteCapture.at(
                                original,
                                start,
                                start + quote.length,
                                emptyList(),
                                AnchorSelection.NARROWED,
                            ),
                        ),
                        reattachment = null,
                        extras = FrontmatterExtras.NONE,
                    )
                    val discussionStore = LocalDiscussionStore(mapOf(RootName.PRIMARY to discussionRoot))
                    DiscussionDb(data.resolve("discussions.db")).use { db ->
                        val rows = JdbcDiscussionRows(db)
                        val fullReads = DiscussionFullReads(discussionStore)
                        val sync = DiscussionSyncState(setOf(RootName.PRIMARY))
                        val index = SyncedDiscussionIndex(rows, discussionStore, fullReads, sync)
                        val alarm = ReindexAlarm()
                        val precompute = AnchorPrecompute(
                            rows = rows,
                            discussions = discussionStore,
                            contents = { h.store },
                            fullReads = fullReads,
                            absence = AbsenceClassifier(h.idMap, allowAllPolicies()),
                            sync = sync,
                            availability = h.availability,
                            current = h.builder::current,
                            alarm = alarm,
                        )
                        signal.attach(precompute)
                        discussionStore.createFiles(
                            RootName.PRIMARY,
                            discussionId,
                            listOf(EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker))),
                        ).shouldBeInstanceOf<StoreWrite.Written>()
                        index.publish(RootName.PRIMARY, discussionId, markerChanged = false) {
                            discussionStore.read(RootName.PRIMARY, discussionId)
                        }

                        val edited = "---\ntitle: Edited\n---\n\n# Edited\n\nbody 0.\npage save value.\n"
                        val editedBytes = edited.toByteArray()
                        Files.write(root.resolve(target.path.value), editedBytes)
                        h.builder.reindex(mainPath(target.path.value))
                        alarm.runNext()

                        h.builder.current.pageAt(target.rooted)?.markdown shouldBe edited
                        rows.cached(RootName.PRIMARY, discussionId)?.pageHash shouldBe
                            CitationFactory().contentHash(editedBytes)
                    }
                }
            } finally {
                discussionRoot.toFile().deleteRecursively()
                data.toFile().deleteRecursively()
            }
        }
    }

    test("a failing search sync never loses the page precompute") {
        withTempTree({ seedCorpus(it, 2) }) { root ->
            val enqueued = mutableListOf<Pair<RootName, String>>()
            ReindexHarness(
                root,
                pageListeners = listOf(
                    PageReindexListener { pageRoot, page ->
                    enqueued += pageRoot to page.id.value
                },
                ),
            ).use { h ->
                h.builder.rebuild()
                val target = h.builder.current.pages.first()
                Files.write(root.resolve(target.path.value), "# Search failure\n\nnew page bytes.\n".toByteArray())
                h.search.indexFailure = IllegalStateException("search sync failed")

                shouldThrow<IllegalStateException> { h.builder.reindex(mainPath(target.path.value)) }

                enqueued shouldBe listOf(RootName.PRIMARY to target.id.value)
                h.builder.current.pageAt(target.rooted)?.markdown shouldBe "# Search failure\n\nnew page bytes.\n"
            }
        }
    }
})

/** The reindex target for a path in `main` — the write location, which is what [IndexBuilder.reindex] addresses. */
private fun mainPath(path: String) = RootedPath(RootName.PRIMARY, TreePath.require(path))

/** A reindex harness with counting collaborators — built directly (not via IndexHarness) for spy control. */
private class ReindexHarness(
    root: Path,
    pageListeners: List<PageReindexListener> = emptyList(),
) : AutoCloseable {
    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)
    private val rootRegistry = RootRegistry.of(listOf(localRoot("docs", root)))
    val idMap = SqlDelightIdMapRepository(database)
    val availability = RootAvailability(Clock.System)

    val renders = ConcurrentHashMap<String, Int>()
    val search = CountingSearchProvider()
    val checkpoint = CountingCheckpoint(SqlDelightPageCheckpointRepository(database))
    var wholeReadCalls = 0
    var pointReadCalls = 0

    private val countingRenderer = { view: PageIndexView ->
        val delegate = FlexmarkRenderer(view)
        object : MarkdownRenderer {
            override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
                renders.merge(sourcePath.value, 1, Int::plus)
                return delegate.render(sourcePath, source)
            }

            override fun renderFragment(sourcePath: TreePath, markdown: String): String =
                delegate.renderFragment(sourcePath, markdown)
        }
    }

    val builder = IndexBuilder(
        sources = listOf(IndexBuilder.Source(rootRegistry.primary, store, NoOpHistoryProvider)),
        frontmatterParser = FrontmatterReader(),
        rendererFactory = countingRenderer,
        identity = PageIdentityService(UuidV7IdProvider()),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database)),
        checkpoint = checkpoint,
        citations = CitationFactory(),
        rootRank = rootRegistry::rank,
        registeredRoots = rootRegistry.roots.map { it.name }.toSet(),
        availability = availability,
        listeners = listOf(IndexBuilder.PublicationListener(checkpoint::replaceFrom)),
        pageListeners = pageListeners,
        searchIndexer = SearchIndexer(
            search,
            SectionSplitter(),
            {
                wholeReadCalls++
                idMap.retiredUnboundIds()
            },
            {
                pointReadCalls++
                idMap.isRetiredUnbound(it)
            },
            allowAllPolicies(),
        ),
        policies = allowAllPolicies(),
    )

    override fun close() = driver.close()
}

/** Counts the search provider calls reindex must (and must not) make. */
private class ReindexAlarm : RebuildScheduler.Alarm {
    private val actions = ArrayDeque<() -> Unit>()

    override fun after(delayMillis: Long, action: () -> Unit) {
        actions.addLast(action)
    }

    fun runNext() = actions.removeFirst().invoke()
}

private class CountingSearchProvider : SearchProvider {
    var indexFailure: RuntimeException? = null
    var indexCalls = 0
    var lastIndexSize = -1
    var indexedStateCalls = 0
    var rebuildCalls = 0

    override fun index(pages: List<PageDocuments>) {
        indexFailure?.let { throw it }
        indexCalls += 1
        lastIndexSize = pages.size
    }

    override fun delete(ids: Collection<RootedPageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(hits = emptyList(), total = 0L)
    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) {
        rebuildCalls += 1
        pages.count() // drain
    }

    override fun indexedState(): Map<RootedPageId, PageSearchState> {
        indexedStateCalls += 1
        return emptyMap()
    }

    fun reset() {
        indexCalls = 0
        lastIndexSize = -1
        indexedStateCalls = 0
        rebuildCalls = 0
        indexFailure = null
    }
}

/** Counts checkpoint replaces — reindex must perform zero, a full rebuild exactly one. */
private class CountingCheckpoint(private val delegate: PageCheckpointRepository) : PageCheckpointRepository {
    var replaceCalls = 0
    override fun load() = delegate.load()
    override fun replace(urlPaths: Map<RootedPageId, TreePath?>) {
        replaceCalls += 1
        delegate.replace(urlPaths)
    }
}
