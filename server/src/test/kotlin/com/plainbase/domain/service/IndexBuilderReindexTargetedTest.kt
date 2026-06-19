package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
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
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * PB-WRITE-1 named tests 8, 9, 10 — the targeted reindex is O(changed-page) END-TO-END and never
 * silently no-ops. The three correctness guards: render count = 1, search work = single-page (no
 * corpus diff), checkpoint writes = 0; plus the vanished-page throw (MUST-FIX 4).
 *
 * Built with explicit spy collaborators (a counting renderer, a counting [SearchProvider], a counting
 * [PageCheckpointRepository]) so each O(changed-page) claim is asserted independently of corpus size.
 */
class IndexBuilderReindexTargetedTest : FunSpec({

    fun seedCorpus(root: Path, n: Int) {
        repeat(n) { i -> writePage(root, "p%03d.md".format(i), "---\ntitle: Page $i\n---\n\n# Page $i\n\nbody $i.\n") }
    }

    test("reindex(pageId) re-renders exactly one page and shares every other page instance") {
        withTempTree({ seedCorpus(it, 5) }) { root ->
            ReindexHarness(root).use { h ->
                h.builder.rebuild()
                val before = h.builder.current
                val targetId = before.pages.first().id
                val targetPath = before.byId.getValue(targetId).path.value
                h.renders.clear()

                // Change that page's bytes on disk so reindex re-reads.
                val edited = "---\ntitle: Page 0\n---\n\n# Page 0\n\nedited.\n"
                Files.write(root.resolve(targetPath), edited.toByteArray())

                val after = h.builder.reindex(targetId)

                h.renders.keys shouldBe setOf(targetPath)
                h.renders.values.all { it == 1 } shouldBe true
                after.byId.getValue(targetId).markdown shouldBe edited
                // Every other page is the SAME instance — untouched.
                for (page in before.pages) {
                    if (page.id != targetId) after.byId.getValue(page.id) shouldBeSameInstanceAs page
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
                    val targetPath = h.builder.current.byId.getValue(targetId).path.value
                    h.renders.clear()
                    h.search.reset()
                    h.checkpoint.replaceCalls = 0
                    Files.write(root.resolve(targetPath), "---\ntitle: Page 0\n---\n\n# Page 0\n\nnow $n.\n".toByteArray())

                    h.builder.reindex(targetId)

                    // render count = 1, regardless of N.
                    h.renders.values.sum() shouldBe 1
                    // search work = single-page: exactly one index call, with a one-element list, no indexedState/rebuild.
                    h.search.indexCalls shouldBe 1
                    h.search.lastIndexSize shouldBe 1
                    h.search.indexedStateCalls shouldBe 0
                    h.search.rebuildCalls shouldBe 0
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
                shouldThrow<IllegalStateException> { h.builder.reindex(PageId.require("00000000-0000-0000-0000-000000000000")) }
            }
        }
    }
})

/** A reindex harness with counting collaborators — built directly (not via IndexHarness) for spy control. */
private class ReindexHarness(root: Path) : AutoCloseable {
    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val store = com.plainbase.frameworks.filesystem.LocalContentStore(root)

    val renders = ConcurrentHashMap<String, Int>()
    val search = CountingSearchProvider()
    val checkpoint = CountingCheckpoint(SqlDelightPageCheckpointRepository(database))

    private val countingRenderer = { view: PageIndexView ->
        val delegate = FlexmarkRenderer(view)
        object : MarkdownRenderer {
            override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
                renders.merge(sourcePath.value, 1, Int::plus)
                return delegate.render(sourcePath, source)
            }
        }
    }

    val builder = IndexBuilder(
        contentStore = store,
        frontmatterParser = FrontmatterReader(),
        rendererFactory = countingRenderer,
        identity = PageIdentityService(UuidV7IdProvider()),
        patcher = FrontmatterPatcher(),
        idMap = SqlDelightIdMapRepository(database),
        aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database)),
        checkpoint = checkpoint,
        citations = CitationFactory(),
        history = NoOpHistoryProvider,
        listeners = listOf(IndexBuilder.PublicationListener(checkpoint::replaceFrom)),
        searchIndexer = SearchIndexer(search, SectionSplitter()),
    )

    override fun close() = driver.close()
}

/** Counts the search provider calls reindex must (and must not) make. */
private class CountingSearchProvider : SearchProvider {
    var indexCalls = 0
    var lastIndexSize = -1
    var indexedStateCalls = 0
    var rebuildCalls = 0

    override fun index(pages: List<PageDocuments>) {
        indexCalls += 1
        lastIndexSize = pages.size
    }

    override fun delete(ids: Collection<PageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(hits = emptyList(), total = 0L)
    override fun rebuild(pages: Sequence<PageDocuments>) {
        rebuildCalls += 1
        pages.count() // drain
    }

    override fun indexedState(): Map<PageId, PageSearchState> {
        indexedStateCalls += 1
        return emptyMap()
    }

    fun reset() {
        indexCalls = 0
        lastIndexSize = -1
        indexedStateCalls = 0
        rebuildCalls = 0
    }
}

/** Counts checkpoint replaces — reindex must perform zero, a full rebuild exactly one. */
private class CountingCheckpoint(private val delegate: PageCheckpointRepository) : PageCheckpointRepository {
    var replaceCalls = 0
    override fun load() = delegate.load()
    override fun replace(urlPaths: Map<PageId, TreePath?>) {
        replaceCalls += 1
        delegate.replace(urlPaths)
    }
}
