package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path

/** Functional coverage for a finite lifetime retirement set without making a performance claim. */
class SearchRetirementBacklogTest : FunSpec({

    test("4096 retired identities do not cause no-op mutations and explicit rebuild carries only live control rows") {
        val content = Files.createTempDirectory("pb-search-retirement-backlog-content")
        val searchDir = Files.createTempDirectory("pb-search-retirement-backlog-search")
        try {
            val finalId = PageId.require("01900000-0000-7000-8000-000000004097")
            Files.writeString(
                content.resolve("backlog.md"),
                "---\nid: ${finalId.value}\ntitle: Backlog\n---\n\n# Backlog\n\nbacklog-live-term.\n",
            )
            SearchDb(searchDir.resolve("search.db")).use { searchDb ->
                val counting = CountingProvider(Fts5SearchProvider(searchDb))
                lateinit var harness: IndexHarness
                var wholeReads = 0
                val indexer = SearchIndexer(
                    counting,
                    SectionSplitter(),
                    {
                        wholeReads++
                        harness.idMap.retiredUnboundIds()
                    },
                    { rooted -> harness.idMap.isRetiredUnbound(rooted) },
                )
                harness = IndexHarness(
                    content,
                    contentStore = LocalContentStore(content),
                    listeners = listOf(IndexBuilder.PublicationListener { snapshot, _ -> indexer.sync(snapshot) }),
                    searchIndexer = indexer,
                )
                harness.use {
                    val path = RootedPath(RootName.PRIMARY, TreePath.require("backlog.md"))
                    val idProvider = TestIdProvider()
                    val ids = (0 until 4097).map { idProvider.next() }
                    val expectedRetired = ids.dropLast(1).map { RootedPageId(RootName.PRIMARY, it) }.toSet()
                    ids.forEach { id -> harness.idMap.bind(path, id, materialized = true) }

                    harness.idMap.retiredUnboundIds() shouldBe expectedRetired
                    harness.idMap.bindingInRoot(RootName.PRIMARY, ids.last()) shouldNotBe null

                    harness.builder.rebuild()
                    val live = RootedPageId(RootName.PRIMARY, ids.last())
                    harness.idMap.retiredUnboundIds() shouldBe expectedRetired
                    harness.builder.current.pageAt(live)?.id shouldBe finalId
                    counting.reset()
                    wholeReads = 0
                    indexer.sync(harness.builder.current)
                    wholeReads shouldBe 1
                    counting.indexCalls shouldBe 0
                    counting.deleteCalls shouldBe 0
                    counting.rebuildCalls shouldBe 0

                    val retiredId = ids.first()
                    val controlId = PageId.require("01900000-0000-7000-8000-000000004098")
                    val controlPath = TreePath.require("control.md")
                    val controlHash = "sha256:" + "c".repeat(64)
                    counting.index(
                        listOf(
                            document(retiredId, "retired.md", "sha256:" + "r".repeat(64), "backlog-retired-term"),
                            document(controlId, controlPath.value, controlHash, "backlog-control-term"),
                        ),
                    )
                    counting.reset()
                    wholeReads = 0
                    val accepted = indexer.rebuild(harness.builder.current)

                    accepted shouldBe 1
                    wholeReads shouldBe 1
                    counting.indexCalls shouldBe 0
                    counting.deleteCalls shouldBe 0
                    counting.rebuildCalls shouldBe 1
                    counting.indexedState().keys shouldContainExactlyInAnyOrder
                        setOf(live, RootedPageId(RootName.PRIMARY, controlId))
                    counting.indexedState()[live]?.contentHash shouldBe
                        harness.builder.current.pageAt(live)?.contentHash
                    counting.indexedState()[RootedPageId(RootName.PRIMARY, controlId)]?.contentHash shouldBe controlHash
                    counting.indexedState()[RootedPageId(RootName.PRIMARY, controlId)]?.path shouldBe controlPath
                    counting.search(SearchQuery("backlog-live-term", 20, 0)).total shouldBe 1L
                    counting.search(SearchQuery("backlog-retired-term", 20, 0)).total shouldBe 0L
                    counting.search(SearchQuery("backlog-control-term", 20, 0)).total shouldBe 1L
                }
            }
        } finally {
            deleteTree(content)
            deleteTree(searchDir)
        }
    }
})

private class CountingProvider(private val delegate: SearchProvider) : SearchProvider by delegate {
    var indexCalls = 0
        private set
    var deleteCalls = 0
        private set
    var rebuildCalls = 0
        private set

    fun reset() {
        indexCalls = 0
        deleteCalls = 0
        rebuildCalls = 0
    }

    override fun index(pages: List<PageDocuments>) {
        indexCalls++
        delegate.index(pages)
    }

    override fun delete(ids: Collection<RootedPageId>) {
        deleteCalls++
        delegate.delete(ids)
    }

    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) {
        rebuildCalls++
        delegate.rebuild(pages, retired)
    }
}

private fun document(id: PageId, path: String, hash: String, term: String): PageDocuments {
    val rootedPath = TreePath.require(path)
    return PageDocuments(
        pageId = id,
        contentHash = hash,
        root = RootName.PRIMARY,
        path = rootedPath,
        sections = listOf(
            SectionDocument(
                pageId = id,
                headingId = null,
                title = "Backlog fixture",
                heading = null,
                headingPath = emptyList(),
                body = term,
                tags = emptyList(),
                owner = null,
                aliases = emptyList(),
                path = rootedPath,
                status = "active",
            ),
        ),
    )
}

private fun deleteTree(path: Path) {
    if (Files.notExists(path)) return
    Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
