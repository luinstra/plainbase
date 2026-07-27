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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 *
 * **The DELETE side is now proof-gated (C0).** A row absent from the snapshot is no longer "stale" - it is an
 * open question, and an unplugged disk asks it about a thousand rows at once. The only pages that leave the
 * engine are the ones an `AbsenceProof` RETIRED, which is what [NO_PROOF] pins: in C0 nothing mints a proof, so
 * a sync deletes nothing at all. The UPSERT side is untouched.
 */
// C0's steady state: no proof source exists yet, so no page is ever retired and no engine row is ever deleted.
private val NO_PROOF = emptySet<RootedPageId>()

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

    fun harness(engineState: Map<RootedPageId, PageSearchState>): Pair<SearchProvider, SearchIndexer> {
        val provider = mockk<SearchProvider>()
        every { provider.indexedState() } returns engineState
        justRun { provider.index(any()) }
        justRun { provider.delete(any()) }
        return provider to SearchIndexer(provider, SectionSplitter())
    }

    test("add: a page the engine lacks is indexed; nothing is deleted") {
        val added = page(idA, "a.md", hash('a'))
        val (provider, indexer) = harness(emptyMap())

        indexer.sync(snapshot(added), NO_PROOF)

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

        indexer.sync(snapshot(after, same), NO_PROOF)

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

        indexer.sync(snapshot(moved), NO_PROOF)

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

        indexer.sync(snapshot(current), NO_PROOF)

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        indexed.captured.single().root shouldBe RootName.PRIMARY
    }

    test("root rides the split documents: every PageDocuments carries its page's root") {
        val a = page(idA, "a.md", hash('a'))
        val (provider, indexer) = harness(emptyMap())

        indexer.sync(snapshot(a), NO_PROOF)

        val indexed = slot<List<PageDocuments>>()
        verify(exactly = 1) { provider.index(capture(indexed)) }
        indexed.captured.single().root shouldBe a.root
    }

    test("delete: a RETIRED page is deleted; nothing is indexed") {
        val kept = page(idA, "a.md", hash('a'))
        val gone = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(kept), rooted(idB) to state(gone)))

        indexer.sync(snapshot(kept), retired = setOf(rooted(idB)))

        val deleted = slot<Collection<RootedPageId>>()
        verify(exactly = 1) { provider.delete(capture(deleted)) }
        verify(exactly = 0) { provider.index(any()) }
        deleted.captured.toSet() shouldBe setOf(rooted(idB))
    }

    // THE SAFETY FLOOR (C0), and the counterpart to the row above: the two inputs are IDENTICAL except for the
    // proof. A page missing from the snapshot with no proof behind it is not a deletion - it is a page we did not
    // read, which is what a failed submount, a partial restore and a decoy tree all look like from here. Under the
    // old rule this row purged the engine on behalf of an unplugged disk.
    test("a page gone from the snapshot with NO proof is KEPT - absence is not evidence") {
        val kept = page(idA, "a.md", hash('a'))
        val unwitnessed = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(kept), rooted(idB) to state(unwitnessed)))

        indexer.sync(snapshot(kept), NO_PROOF)

        verify(exactly = 0) { provider.delete(any()) }
        verify(exactly = 0) { provider.index(any()) }
    }

    test("unchanged corpus: the no-op fast path makes ZERO engine calls beyond the state read") {
        val a = page(idA, "a.md", hash('a'))
        val b = page(idB, "b.md", hash('b'))
        val (provider, indexer) = harness(mapOf(rooted(idA) to state(a), rooted(idB) to state(b)))

        indexer.sync(snapshot(a, b), NO_PROOF)

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
        val (provider, indexer) = harness(engineState)

        indexer.sync(snapshot(unchanged, changed, added), retired = setOf(rooted(idD)))

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

        indexer.sync(snapshot(a, b), NO_PROOF)

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

        indexer.sync(snapshot(*many.toTypedArray()), NO_PROOF)

        val batches = mutableListOf<List<PageDocuments>>()
        verify { provider.index(capture(batches)) }
        (batches.size > 1) shouldBe true // bounded: never one corpus-sized call
        batches.all { it.size <= SearchIndexer.INDEX_BATCH } shouldBe true
        batches.flatten().map { it.pageId } shouldContainExactlyInAnyOrder many.map { it.id }
    }
})
