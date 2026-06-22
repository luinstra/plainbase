package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * PB-WRITE-1 chunk W1 — the serialized write pipeline's per-save behavior (named tests 1, 3, 4, 5).
 * Built on [IndexHarness]/[withTempTree]/[writePage] over a real [com.plainbase.frameworks.filesystem
 * .LocalContentStore] + [CitationFactory]: the harness and the pipeline share ONE content store.
 */
class WritePipelineTest : FunSpec({

    val citations = CitationFactory()

    fun seedOne(root: Path) {
        writePage(root, "guides/edit-me.md", "---\ntitle: Edit Me\n---\n\n# Edit Me\n\noriginal body.\n")
    }

    fun targetOf(harness: IndexHarness) = harness.builder.current.pages.single()

    // Test 1: disk-clobber — an external on-disk edit before the CAS is never clobbered (fix A, the #1 gate).
    test("an external on-disk edit before reindex is never clobbered (disk-authoritative)") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val baseHash = page.contentHash // the hash the client last saw

                // Edit the file directly on disk WITHOUT a rebuild — the snapshot still shows the old bytes.
                val external = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nexternally edited.\n".toByteArray()
                Files.write(root.resolve("guides/edit-me.md"), external)

                val saveBytes = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nmy save.\n".toByteArray()
                val outcome = harness.writePipeline().write(grantForTests(), WriteIntent(page.id, page.path, baseHash, saveBytes))

                outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe "content_changed"
                // Disk-authoritative: the external bytes survive, NOT the save bytes.
                Files.readAllBytes(root.resolve("guides/edit-me.md")) shouldBe external
            }
        }
    }

    // Test 3: byte-fidelity round-trip — a matching base_hash writes bytes verbatim (master criterion 1, R7).
    test("a matching base_hash writes bytes verbatim and reflects in the snapshot") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val saveBytes = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nverbatim new body.\n".toByteArray()

                val outcome = harness.writePipeline().write(grantForTests(), WriteIntent(page.id, page.path, page.contentHash, saveBytes))

                val written = outcome.shouldBeInstanceOf<WriteOutcome.Written>()
                written.newHash shouldBe citations.contentHash(saveBytes)
                Files.readAllBytes(root.resolve("guides/edit-me.md")) shouldBe saveBytes
                val reindexed = harness.builder.current.byId.getValue(page.id)
                reindexed.markdown shouldBe String(saveBytes, Charsets.UTF_8)
                reindexed.contentHash shouldBe citations.contentHash(saveBytes)
            }
        }
    }

    // Test 4: edit-classification rejection — slug/redirect_from/id changes are rejected (MUST-FIX 1).
    test("a slug change is rejected; a redirect_from change is rejected; an id change is rejected") {
        // A page with a frontmatter [id], [slug], and [redirect_from]; [body] varies the content edit.
        fun doc(id: String = "", slug: String = "original-slug", redirect: String = "old/path", body: String = "body.") = buildString {
            append("---\n")
            if (id.isNotEmpty()) append("id: $id\n")
            append("title: Slugged\n")
            append("slug: $slug\n")
            append("redirect_from:\n  - $redirect\n")
            append("---\n\n# Slugged\n\n$body\n")
        }.toByteArray()

        withTempTree({ root -> writePage(root, "guides/has-slug.md", String(doc(), Charsets.UTF_8)) }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val before = Files.readAllBytes(root.resolve("guides/has-slug.md"))
                val urlBefore = page.urlPath
                val pipeline = harness.writePipeline()

                pipeline.write(grantForTests(), WriteIntent(page.id, page.path, page.contentHash, doc(slug = "new-slug")))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "slug"

                pipeline.write(grantForTests(), WriteIntent(page.id, page.path, page.contentHash, doc(redirect = "other/path")))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "redirect_from"

                pipeline.write(
                    grantForTests(),
                    WriteIntent(page.id, page.path, page.contentHash, doc(id = "00000000-0000-0000-0000-000000000000")),
                )
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "id"

                // File untouched on disk; the snapshot's urlPath unchanged; the journal stayed empty (no write happened).
                Files.readAllBytes(root.resolve("guides/has-slug.md")) shouldBe before
                harness.builder.current.byId.getValue(page.id).urlPath shouldBe urlBefore
                harness.dirtyPages.all().isEmpty() shouldBe true
            }
        }
    }

    // Test 4b: id-classification compares like-for-like against the CURRENT id (the Codex FIX 1 holes).
    // (a) REMOVING a materialized id line is a change (null ≠ present) → rejected, file unchanged.
    // (b) CHANGING the id → rejected. Both compared against the file's own current id, not the pageId.
    test("removing a materialized id is rejected; changing it is rejected; the file is untouched") {
        val materialized = "---\nid: 0190aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee\ntitle: Stable\n---\n\n# Stable\n\nbody.\n"
        withTempTree({ root -> writePage(root, "guides/stable.md", materialized) }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val before = Files.readAllBytes(root.resolve("guides/stable.md"))
                val pipeline = harness.writePipeline()

                // (a) Removing the id line entirely (null ≠ present) — a rename, rejected.
                val idRemoved = "---\ntitle: Stable\n---\n\n# Stable\n\nbody edited.\n".toByteArray()
                pipeline.write(grantForTests(), WriteIntent(page.id, page.path, page.contentHash, idRemoved))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "id"

                // (b) Changing the id to a different value — also rejected.
                val idChanged = "---\nid: 0190ffff-bbbb-7ccc-8ddd-eeeeeeeeeeee\ntitle: Stable\n---\n\n# Stable\n\nbody.\n".toByteArray()
                pipeline.write(grantForTests(), WriteIntent(page.id, page.path, page.contentHash, idChanged))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "id"

                Files.readAllBytes(root.resolve("guides/stable.md")) shouldBe before // nothing written either time
                harness.dirtyPages.all().isEmpty() shouldBe true
            }
        }
    }

    // Test 4c: a body-only edit to a page whose on-disk id ≠ its ASSIGNED pageId is ALLOWED (FIX 1 hole b).
    // A duplicate (copied) page keeps its on-disk frontmatter id but is reassigned a fresh pageId; the
    // guard must compare the submitted id to the file's CURRENT id, not the pageId — so a stable-id body
    // edit on that page is a content edit, not a falsely-rejected rename.
    test("a body-only edit to a page whose on-disk id differs from its assigned pageId is allowed") {
        val sharedId = "0190aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee"
        fun dup(id: String, body: String) =
            "---\nid: $id\ntitle: Dup\n---\n\n# Dup\n\n$body\n"
        withTempTree({ root ->
            // Two files with the SAME frontmatter id: the older path keeps it, the newer is reassigned a
            // minted pageId while its on-disk id stays the shared one (PageIdentityService duplicate policy).
            writePage(root, "a-original.md", dup(sharedId, "original owner."))
            writePage(root, "b-copy.md", dup(sharedId, "the copy."))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                // The copy: its on-disk frontmatter id is the shared id, but its assigned pageId is minted (different).
                val copy = harness.builder.current.byPath.getValue(TreePath.require("b-copy.md"))
                copy.frontmatter.scalar("id") shouldBe sharedId
                copy.id.value shouldNotBe sharedId // the reassigned (minted) pageId genuinely differs

                // A body-only edit keeping the SAME on-disk id is a content edit — must be Written, not rejected.
                val edited = dup(sharedId, "the copy, edited.").toByteArray()
                harness.writePipeline().write(grantForTests(), WriteIntent(copy.id, copy.path, copy.contentHash, edited))
                    .shouldBeInstanceOf<WriteOutcome.Written>()
                Files.readAllBytes(root.resolve("b-copy.md")) shouldBe edited
            }
        }
    }

    // Test 5: read-failure — an unreadable on-disk file yields a typed Unreadable, not a throw (MEDIUM 6).
    test("an unreadable on-disk file yields a typed Unreadable, not a throw") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                // A ContentStore whose CAS reports Unreadable (simulating an IOException on the internal read).
                val failingStore = object : ContentStore by realStoreDelegate(root) {
                    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String) =
                        CasResult.Unreadable("simulated permission denied")
                }
                // Build a pipeline over the failing store but the harness's real builder/repos.
                val pipeline = WritePipeline(
                    contentStore = failingStore,
                    indexBuilder = harness.builder,
                    citations = citations,
                    frontmatterParser = com.plainbase.frameworks.markdown.FrontmatterReader(),
                    dirtyPages = harness.dirtyPages,
                    idMap = harness.idMap,
                    aliasRegistry = harness.registry,
                )
                val outcome = pipeline.write(grantForTests(), WriteIntent(page.id, page.path, page.contentHash, "x".toByteArray()))
                outcome.shouldBeInstanceOf<WriteOutcome.Unreadable>().cause shouldBe "simulated permission denied"
                harness.dirtyPages.all().isEmpty() shouldBe true // nothing written ⇒ mark cleared
            }
        }
    }

    // W2 P2: a search-sync failure on CREATE surfaces as WrittenButUnindexed (NOT a clean Written), the
    // dirty row is RETAINED, and a later reconcile (once search recovers) clears it. Proves the create
    // path does NOT rely on rebuild()'s swallowed-listener search sync for its searchability guarantee.
    test("a create whose search sync fails is WrittenButUnindexed; the dirty row is retained and reconcile recovers it") {
        withTempTree({}) { root ->
            val provider = TogglingSearchProvider()
            val searchIndexer = SearchIndexer(provider, SectionSplitter())
            // No search-sync LISTENER on rebuild() — the propagating guarantee is the reindex() syncPage,
            // exactly what createAndIndex relies on; rebuild() still publishes the page (read-visible).
            IndexHarness(root, searchIndexer = searchIndexer).use { harness ->
                harness.builder.rebuild()
                val pipeline = harness.writePipeline()
                val pageId = PageId.require("01900000-0000-7000-8000-0000000000a1")
                val bytes = "---\nid: ${pageId.value}\ntitle: Created\n---\n\n# Created\n\nbody.\n".toByteArray()

                provider.failOnIndex = true
                val outcome = pipeline.create(createGrantForTests(), CreateIntent(pageId, TreePath.require("created.md"), bytes))

                outcome.shouldBeInstanceOf<WriteOutcome.WrittenButUnindexed>()
                Files.readAllBytes(root.resolve("created.md")) shouldBe bytes // bytes ARE on disk
                harness.dirtyPages.all().shouldHaveSize(1) // dirty row RETAINED for reconcile

                // Search recovers; reconcile re-runs the (now-succeeding) single-page sync and clears the mark.
                provider.failOnIndex = false
                pipeline.reconcileDirtyPages()
                harness.dirtyPages.all().isEmpty() shouldBe true
                provider.indexedPageIds.contains(pageId) shouldBe true
            }
        }
    }
})

/**
 * A [SearchProvider] stand-in whose [index] throws while [failOnIndex] is set (simulating an FTS lock),
 * recording the page ids it successfully indexed otherwise. [rebuild]/[indexedState] never fail, so a
 * full `rebuild()` still publishes the snapshot (read-visibility) even while single-page sync fails.
 */
private class TogglingSearchProvider : SearchProvider {
    var failOnIndex = false
    val indexedPageIds = mutableSetOf<PageId>()

    override fun index(pages: List<PageDocuments>) {
        if (failOnIndex) throw java.io.IOException("simulated FTS lock on index")
        pages.forEach { indexedPageIds += it.pageId }
    }

    override fun delete(ids: Collection<PageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(total = 0, hits = emptyList())
    override fun rebuild(pages: Sequence<PageDocuments>) = pages.forEach { indexedPageIds += it.pageId }
    override fun indexedState(): Map<PageId, PageSearchState> = emptyMap()
}

/** A real LocalContentStore over [root] — the delegate behind a CAS-failing test stand-in. */
private fun realStoreDelegate(root: Path): ContentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root)
