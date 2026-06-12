package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

/**
 * §B4 engine-truth diff sync: each corpus delta produces EXACTLY the right `index`/`delete`
 * calls — add, change (contentHash), move (path only), delete — and an unchanged corpus makes
 * ZERO mutating calls (the no-op fast path). The diff base is the mocked engine's own
 * [SearchProvider.indexedState], never a previous snapshot.
 */
class SearchIndexerTest : FunSpec({

    val idA = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val idB = PageId.require("0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01")
    val idC = PageId.require("0197c2d1-6f3b-7c45-8d2e-3a7b9f5c8e02")

    fun hash(seed: Char) = "sha256:" + seed.toString().repeat(64)

    fun page(id: PageId, path: String, contentHash: String) = IndexedPage(
        id = id,
        path = TreePath.require(path),
        slug = "p",
        urlPath = TreePath.require(path.removeSuffix(".md")),
        title = "T",
        frontmatter = Frontmatter.EMPTY,
        materialized = false,
        markdown = "",
        contentHash = contentHash,
        html = "",
        headings = emptyList(),
        links = emptyList(),
        sections = listOf(RenderedSection(null, "body")),
    )

    fun snapshot(vararg pages: IndexedPage) = PageIndex(pages.toList(), emptyList(), emptySet())

    fun state(page: IndexedPage) = PageSearchState(contentHash = page.contentHash, path = page.path)

    fun harness(engineState: Map<PageId, PageSearchState>): Pair<SearchProvider, SearchIndexer> {
        val provider = mockk<SearchProvider>()
        every { provider.indexedState() } returns engineState
        justRun { provider.index(any()) }
        justRun { provider.delete(any()) }
        return provider to SearchIndexer(provider, SectionSplitter())
    }

    test("add: a page the engine lacks is indexed; nothing is deleted") {
        val added = page(idA, "a.md", hash('a'))
        val (provider, indexer) = harness(emptyMap())

        indexer.sync(snapshot(added))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        verify(exactly = 0) { provider.delete(any()) }
        indexed.captured.map { it.pageId } shouldBe listOf(idA)
        indexed.captured.single().sections.map { it.body } shouldBe listOf("body")
    }

    test("change: a contentHash drift re-indexes exactly that page") {
        val before = page(idA, "a.md", hash('a'))
        val after = page(idA, "a.md", hash('b'))
        val same = page(idB, "b.md", hash('c'))
        val (provider, indexer) = harness(mapOf(idA to state(before), idB to state(same)))

        indexer.sync(snapshot(after, same))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        verify(exactly = 0) { provider.delete(any()) }
        indexed.captured.map { it.pageId } shouldBe listOf(idA)
        indexed.captured.single().contentHash shouldBe hash('b')
    }

    test("move: a path change WITHOUT a content change still re-indexes (path rides the documents)") {
        val before = page(idA, "old/a.md", hash('a'))
        val moved = page(idA, "new/a.md", hash('a'))
        val (provider, indexer) = harness(mapOf(idA to state(before)))

        indexer.sync(snapshot(moved))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        verify(exactly = 0) { provider.delete(any()) }
        indexed.captured.single().path shouldBe TreePath.require("new/a.md")
    }

    test("delete: a page gone from the snapshot is deleted; nothing is indexed") {
        val kept = page(idA, "a.md", hash('a'))
        val gone = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(idA to state(kept), idB to state(gone)))

        indexer.sync(snapshot(kept))

        val deleted = slot<Collection<PageId>>()
        verify(exactly = 1) { provider.delete(capture(deleted)) }
        verify(exactly = 0) { provider.index(any()) }
        deleted.captured.toSet() shouldBe setOf(idB)
    }

    test("unchanged corpus: the no-op fast path makes ZERO engine calls beyond the state read") {
        val a = page(idA, "a.md", hash('a'))
        val b = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(idA to state(a), idB to state(b)))

        indexer.sync(snapshot(a, b))

        verify(exactly = 1) { provider.indexedState() }
        confirmVerified(provider) // no index, no delete, no search, no rebuild
    }

    test("mixed delta: one add + one change + one delete + one unchanged, each routed exactly once") {
        val unchanged = page(idA, "a.md", hash('a'))
        val changed = page(idB, "b.md", hash('e'))
        val added = page(idC, "c.md", hash('c'))
        val engineState = mapOf(
            idA to state(unchanged),
            idB to PageSearchState(hash('b'), TreePath.require("b.md")),
            PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03") to PageSearchState(hash('d'), TreePath.require("d.md")),
        )
        val (provider, indexer) = harness(engineState)

        indexer.sync(snapshot(unchanged, changed, added))

        val indexed = slot<List<PageDocuments>>()
        val deleted = slot<Collection<PageId>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        verify(exactly = 1) { provider.delete(capture(deleted)) }
        indexed.captured.map { it.pageId }.toSet() shouldBe setOf(idB, idC)
        deleted.captured.toSet() shouldBe setOf(PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03"))
    }

    test("self-healing framing: an emptied engine state means a FULL upsert, no special path") {
        val a = page(idA, "a.md", hash('a'))
        val b = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(emptyMap()) // search.db deleted / first start

        indexer.sync(snapshot(a, b))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        indexed.captured.map { it.pageId } shouldBe listOf(idA, idB)
    }
})
