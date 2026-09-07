package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify

/**
 * §B4 engine-truth diff sync: each corpus delta produces EXACTLY the right `index`/`delete`
 * calls — add, change (contentHash), move (path only), delete — and an unchanged corpus makes
 * ZERO mutating calls (the no-op fast path). The diff base is the mocked engine's own
 * [SearchProvider.indexedState], never a previous snapshot.
 *
 * **The DELETE side is durable-authority-gated.** A row absent from the snapshot is deleted only when the rooted
 * id is currently retired and unbound; the same authority excludes stale snapshot input from a later publication.
 */
// Most cases model an empty durable retirement query; focused authority tests below supply a non-empty set.
private val NO_RETIREMENT = emptySet<RootedPageId>()

class SearchIndexerTest : FunSpec({

    val idA = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val idB = PageId.require("0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01")
    val idC = PageId.require("0197c2d1-6f3b-7c45-8d2e-3a7b9f5c8e02")

    fun rooted(id: PageId, root: RootName = RootName.PRIMARY) = RootedPageId(root, id)

    fun hash(seed: Char) = "sha256:" + seed.toString().repeat(64)

    fun page(id: PageId, path: String, contentHash: String) = IndexedPage(
        id = id,
        root = RootName.PRIMARY,
        path = TreePath.require(path),
        slug = "p",
        urlPath = TreePath.require(path.removeSuffix(".md")),
        title = "T",
        frontmatter = Frontmatter.EMPTY,
        materialized = false,
        markdown = "",
        contentHash = contentHash,
        commit = null,
        html = "",
        headings = emptyList(),
        links = emptyList(),
        sections = listOf(RenderedSection(null, "body")),
    )

    fun snapshot(vararg pages: IndexedPage) = PageIndex(listOf(RootSection(RootName.PRIMARY, pages.toList(), emptyList(), emptySet())))

    fun state(page: IndexedPage) = PageSearchState(contentHash = page.contentHash, path = page.path)

    fun harness(
        engineState: Map<RootedPageId, PageSearchState>,
        retired: Set<RootedPageId> = NO_RETIREMENT,
    ): Pair<SearchProvider, SearchIndexer> {
        val provider = mockk<SearchProvider>()
        every { provider.indexedState() } returns engineState
        justRun { provider.index(any()) }
        justRun { provider.delete(any()) }
        return provider to SearchIndexer(provider, SectionSplitter(), { retired }, { it in retired })
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
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(before), rooted(idB) to state(same)))

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
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(before)))

        indexer.sync(snapshot(moved))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        verify(exactly = 0) { provider.delete(any()) }
        indexed.captured.single().path shouldBe TreePath.require("new/a.md")
    }

    test("root-only change: same hash, same relative path, different root still re-upserts (the engine key distinguishes roots)") {
        val current = page(idA, "a.md", hash('a'))
        val engineThinks = PageSearchState(contentHash = current.contentHash, path = current.path)
        // The engine holds id A under a DIFFERENT root, so `engineState[page.rooted]` misses and re-upserts.
        val (provider, indexer) = harness(mapOf(RootedPageId(RootName.require("extra"), idA) to engineThinks))

        indexer.sync(snapshot(current))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        indexed.captured.single().root shouldBe RootName.PRIMARY
    }

    test("root rides the split documents: every PageDocuments carries its page's root") {
        val a = page(idA, "a.md", hash('a'))
        val (provider, indexer) = harness(emptyMap())

        indexer.sync(snapshot(a))

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        indexed.captured.single().root shouldBe a.root
    }

    test("delete: a RETIRED page is deleted; nothing is indexed") {
        val kept = page(idA, "a.md", hash('a'))
        val gone = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(
            mapOf(rooted(idA) to state(kept), rooted(idB) to state(gone)),
            retired = setOf(rooted(idB)),
        )

        indexer.sync(snapshot(kept))

        val deleted = slot<Collection<RootedPageId>>()
        verify(exactly = 1) { provider.delete(capture(deleted)) }
        verify(exactly = 0) { provider.index(any()) }
        deleted.captured.toSet() shouldBe setOf(rooted(idB))
    }

    // The counterpart to the row above: the two inputs are IDENTICAL except for durable retirement. A page missing
    // from the snapshot with no current retirement authority is not a deletion - it is a page we did not read, which
    // is what a failed submount, a partial restore, and a decoy tree all look like from here.
    test("a page gone from the snapshot without current retirement authority is kept") {
        val kept = page(idA, "a.md", hash('a'))
        val unwitnessed = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(kept), rooted(idB) to state(unwitnessed)))

        indexer.sync(snapshot(kept))

        verify(exactly = 0) { provider.delete(any()) }
        verify(exactly = 0) { provider.index(any()) }
    }

    test("unchanged corpus: the no-op fast path makes ZERO engine calls beyond the state read") {
        val a = page(idA, "a.md", hash('a'))
        val b = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(a), rooted(idB) to state(b)))

        indexer.sync(snapshot(a, b))

        verify(exactly = 1) { provider.indexedState() }
        confirmVerified(provider) // no index, no delete, no search, no rebuild
    }

    test("mixed delta: one add + one change + one delete + one unchanged, each routed exactly once") {
        val unchanged = page(idA, "a.md", hash('a'))
        val changed = page(idB, "b.md", hash('e'))
        val added = page(idC, "c.md", hash('c'))
        val idD = PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03")
        val engineState = mapOf(
            rooted(idA) to state(unchanged),
            rooted(idB) to PageSearchState(hash('b'), TreePath.require("b.md")),
            rooted(idD) to PageSearchState(hash('d'), TreePath.require("d.md")),
        )
        val (provider, indexer) = harness(engineState, retired = setOf(rooted(idD)))

        indexer.sync(snapshot(unchanged, changed, added))

        val indexed = slot<List<PageDocuments>>()
        val deleted = slot<Collection<RootedPageId>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        verify(exactly = 1) { provider.delete(capture(deleted)) }
        indexed.captured.map { it.pageId }.toSet() shouldBe setOf(idB, idC)
        deleted.captured.toSet() shouldBe setOf(rooted(idD))
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

    test("cold-start full upsert streams in bounded batches, not one corpus-sized index call") {
        // The other tests drive <=3-page corpora (a single index call), so they never exercise the
        // >INDEX_BATCH chunking path. A cold sync of a large corpus must upsert in bounded slices —
        // once each, in full — so the batching is a locked-in behavior, not an accident.
        val many = (0 until 600).map { i ->
            page(PageId.require("0197a3f2-8c4d-7e91-b3a2-%012x".format(i)), "s/p-$i.md", hash('a'))
        }
        val (provider, indexer) = harness(emptyMap())

        indexer.sync(snapshot(*many.toTypedArray()))

        val batches = mutableListOf<List<PageDocuments>>()
        verify { provider.index(capture(batches)) }
        (batches.size > 1) shouldBe true // bounded: never one corpus-sized call
        batches.all { it.size <= SearchIndexer.INDEX_BATCH } shouldBe true
        batches.flatten().map { it.pageId } shouldContainExactlyInAnyOrder many.map { it.id }
    }

    test("sync reads durable retirement once before engine state and filters unchanged, retired, and missing input") {
        val unchanged = page(idA, "a.md", hash('a'))
        val retired = page(idB, "b.md", hash('b'))
        val missing = page(idC, "c.md", hash('c'))
        val staleEngineId = PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03")
        val retiredIds = setOf(rooted(idB), rooted(staleEngineId))
        val provider = IndexerRecordingProvider(
            engineState = mapOf(
                rooted(idA) to state(unchanged),
                rooted(idB) to state(retired),
                rooted(staleEngineId) to PageSearchState(hash('d'), TreePath.require("d.md")),
            ),
        )
        val order = mutableListOf<String>()
        var authorityReads = 0
        val indexer = SearchIndexer(
            provider,
            SectionSplitter(),
            {
                authorityReads++
                order += "authority"
                retiredIds
            },
            { it in retiredIds },
        )
        provider.beforeIndexedState = { order += "indexedState" }
        provider.beforeIndex = { order += "index" }
        provider.beforeDelete = { order += "delete" }

        indexer.sync(snapshot(unchanged, retired, missing))

        authorityReads shouldBe 1
        provider.deleted.toSet() shouldBe retiredIds
        provider.indexed.single().pageId shouldBe idC
        order shouldBe listOf("authority", "indexedState", "delete", "index")
    }

    test("sync excludes retired unchanged, changed/path-moved, and engine-missing inputs before splitting") {
        val unchangedRetired = page(idB, "retired-same.md", hash('b'))
        val changedRetired = page(idC, "retired-new.md", hash('c'))
        val missingRetired = page(PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03"), "retired-missing.md", hash('d'))
        val accepted = page(idA, "accepted.md", hash('a'))
        val retiredIds = setOf(unchangedRetired.rooted, changedRetired.rooted, missingRetired.rooted)
        val provider = IndexerRecordingProvider(
            engineState = mapOf(
                unchangedRetired.rooted to state(unchangedRetired),
                changedRetired.rooted to PageSearchState(hash('o'), TreePath.require("retired-old.md")),
            ),
        )
        val splitter = spyk(SectionSplitter())
        val order = mutableListOf<String>()
        every { splitter.split(any()) } answers {
            order += "split"
            callOriginal()
        }
        provider.beforeIndexedState = { order += "state" }
        provider.beforeIndex = { order += "index" }
        val indexer = SearchIndexer(provider, splitter, {
            order += "authority"
            retiredIds
        }, { it in retiredIds })

        indexer.sync(snapshot(accepted, unchangedRetired, changedRetired, missingRetired))

        provider.deleted.toSet() shouldBe setOf(unchangedRetired.rooted, changedRetired.rooted)
        provider.indexed.map { it.pageId } shouldBe listOf(idA)
        verify(exactly = 1) { splitter.split(match { it.id == idA }) }
        verify(exactly = 0) { splitter.split(match { it.id != idA }) }
        order shouldBe listOf("authority", "state", "split", "index")
    }

    test("rebuild reads authority once, forwards the full set, streams accepted pages, and returns accepted count") {
        val a = page(idA, "a.md", hash('a'))
        val retired = page(idB, "b.md", hash('b'))
        val c = page(idC, "c.md", hash('c'))
        val retiredIds = setOf(rooted(idB), rooted(PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03")))
        val provider = IndexerRecordingProvider()
        val splitter = spyk(SectionSplitter())
        val order = mutableListOf<String>()
        every { splitter.split(any()) } answers {
            order += "split:${firstArg<IndexedPage>().id}"
            callOriginal()
        }
        provider.beforeRebuild = { order += "rebuild" }
        var authorityReads = 0
        val indexer = SearchIndexer(
            provider,
            splitter,
            {
                authorityReads++
                order += "authority"
                retiredIds
            },
            { it in retiredIds },
        )

        val accepted = indexer.rebuild(snapshot(a, retired, c))

        accepted shouldBe 2
        authorityReads shouldBe 1
        provider.indexedStateCalls shouldBe 0
        provider.rebuilt.map { it.pageId } shouldBe listOf(idA, idC)
        provider.rebuildRetired shouldBe retiredIds
        order shouldBe listOf("authority", "rebuild", "split:$idA", "split:$idC")
        verify(exactly = 0) { splitter.split(match { it.id == retired.id }) }
    }

    test("rebuild reads authority before splitting and propagates the original read failure without work") {
        val provider = IndexerRecordingProvider()
        val splitter = spyk(SectionSplitter())
        val failure = IllegalStateException("rebuild retirement query failed")
        val indexer = SearchIndexer(provider, splitter, { throw failure }, { false })

        shouldThrow<IllegalStateException> { indexer.rebuild(snapshot(page(idA, "a.md", hash('a')))) } shouldBe failure

        provider.operations shouldBe emptyList()
        provider.indexedStateCalls shouldBe 0
        provider.rebuildCalls shouldBe 0
        verify(exactly = 0) { splitter.split(any()) }
    }

    test("a nonempty retirement set disjoint from engine and snapshot is a no-op") {
        val a = page(idA, "a.md", hash('a'))
        val disjoint = rooted(PageId.require("0197d3e2-7a4c-7d56-9e3f-4b8c0a6d9f03"))
        val provider = IndexerRecordingProvider(mapOf(rooted(idA) to state(a)))
        var wholeReads = 0
        val indexer = SearchIndexer(provider, SectionSplitter(), {
            wholeReads++
            setOf(disjoint)
        }, { it == disjoint })

        indexer.sync(snapshot(a))

        wholeReads shouldBe 1
        provider.indexedStateCalls shouldBe 1
        provider.operations shouldBe emptyList()
    }

    test("whole-set authority failure aborts before indexedState, splitting, or mutation") {
        val provider = IndexerRecordingProvider()
        val splitter = spyk(SectionSplitter())
        val failure = IllegalStateException("retirement query failed")
        var wholeReads = 0
        val indexer = SearchIndexer(
            provider,
            splitter,
            {
                wholeReads++
                throw failure
            },
            { false },
        )

        shouldThrow<IllegalStateException> { indexer.sync(snapshot(page(idA, "a.md", hash('a')))) } shouldBe failure

        wholeReads shouldBe 1
        provider.indexedStateCalls shouldBe 0
        provider.operations shouldBe emptyList()
        verify(exactly = 0) { splitter.split(any()) }
    }

    test("point authority is read once and refuses without whole-state, split, or index work") {
        val page = page(idA, "a.md", hash('a'))
        val provider = IndexerRecordingProvider()
        val splitter = spyk(SectionSplitter())
        var pointReads = 0
        val indexer = SearchIndexer(
            provider,
            splitter,
            { error("whole-set read must not run") },
            {
                pointReads++
                true
            },
        )

        val failure = shouldThrow<IllegalStateException> { indexer.syncPage(page) }

        pointReads shouldBe 1
        failure.message shouldBe "cannot sync retired/unbound page root=${page.root.value} id=${page.id.value}"
        provider.indexedStateCalls shouldBe 0
        provider.operations shouldBe emptyList()
        verify(exactly = 0) { splitter.split(any()) }
    }

    test("point authority failure propagates without a whole-state read or provider work") {
        val provider = IndexerRecordingProvider()
        val splitter = spyk(SectionSplitter())
        var pointReads = 0
        val failure = IllegalStateException("point retirement query failed")
        val indexer = SearchIndexer(
            provider,
            splitter,
            { error("whole-set read must not run") },
            {
                pointReads++
                throw failure
            },
        )

        shouldThrow<IllegalStateException> { indexer.syncPage(page(idA, "a.md", hash('a'))) } shouldBe failure

        pointReads shouldBe 1
        provider.indexedStateCalls shouldBe 0
        provider.operations shouldBe emptyList()
        verify(exactly = 0) { splitter.split(any()) }
    }

    test("point authority permits one split and one single-page index without a whole read") {
        val provider = IndexerRecordingProvider()
        var pointReads = 0
        val splitter = spyk(SectionSplitter())
        val indexer = SearchIndexer(
            provider,
            splitter,
            { error("whole-set read must not run") },
            {
                pointReads++
                false
            },
        )

        indexer.syncPage(page(idA, "a.md", hash('a')))

        pointReads shouldBe 1
        provider.indexedStateCalls shouldBe 0
        provider.operations shouldBe listOf("index")
        provider.indexed.single().pageId shouldBe idA
        verify(exactly = 1) { splitter.split(match { it.id == idA }) }
    }
})

private class IndexerRecordingProvider(
    private val engineState: Map<RootedPageId, PageSearchState> = emptyMap(),
) : SearchProvider {
    val operations = mutableListOf<String>()
    val indexed = mutableListOf<PageDocuments>()
    val deleted = mutableListOf<RootedPageId>()
    var rebuilt: List<PageDocuments> = emptyList()
    var rebuildRetired: Set<RootedPageId>? = null
    var indexedStateCalls = 0
    var rebuildCalls = 0
    var beforeIndexedState: (() -> Unit)? = null
    var beforeIndex: (() -> Unit)? = null
    var beforeDelete: (() -> Unit)? = null
    var beforeRebuild: (() -> Unit)? = null

    override fun index(pages: List<PageDocuments>) {
        beforeIndex?.invoke()
        operations += "index"
        indexed += pages
    }

    override fun delete(ids: Collection<RootedPageId>) {
        beforeDelete?.invoke()
        operations += "delete"
        deleted += ids
    }

    override fun search(query: com.plainbase.domain.search.SearchQuery): com.plainbase.domain.search.SearchResults =
        com.plainbase.domain.search.SearchResults(0L, emptyList())

    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) {
        beforeRebuild?.invoke()
        rebuildCalls++
        operations += "rebuild"
        rebuilt = pages.toList()
        rebuildRetired = retired
    }

    override fun indexedState(): Map<RootedPageId, PageSearchState> {
        beforeIndexedState?.invoke()
        indexedStateCalls++
        return engineState
    }
}
