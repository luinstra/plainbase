package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FrontmatterReader
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every step of a save must address the page by its PINNED TARGET — the (root, path) the intent carries — and
 * never by the bare page id (ADR-0011 D17; per-root identity, C5).
 *
 * A page id does not durably name a location, and under per-root identity it does not even name ONE root: the
 * same frontmatter `id:` may live in several roots at once (a legal cross-root duplicate). A watcher `rebuild()`
 * takes the INDEX BUILDER's monitor, not the pipeline's, so one can land anywhere in a save's critical section
 * and publish a snapshot where a second root now holds the same id. Two distinct failures follow from an
 * id-addressed lookup, one per test here:
 *  - the post-CAS REINDEX re-reads, re-renders and search-syncs a DIFFERENT root's file than the one the save
 *    just wrote: disk truth on root A, index truth on root B, no error anywhere;
 *  - the pre-CAS edit-classification GUARD diffs the submitted frontmatter against a DIFFERENT page's, so a
 *    rename (id/slug/redirect_from) is PERMITTED whenever that foreign page happens to carry the new value.
 *    The guard picks no write path, which is what made it look benign — but what it compares against is what
 *    it decides on.
 *
 * The reindex seam is injected at the STORE (a real [LocalContentStore] delegate that runs the interleaving
 * rebuild immediately after its real CAS returns) rather than by sleeping, so the interleaving is
 * deterministic; the guard runs before any store call, so its test just publishes the contested snapshot
 * first. The pipeline, the builder, the identity contest and the reindex under test are all REAL — nothing
 * about the behavior being asserted is supplied by a double.
 */
class WritePipelineCrossRootReindexTest : FunSpec({

    val path = TreePath.require("notes/rollback.md")
    val pageId = PageId.require("01900000-0000-7000-8000-0000000000d1")

    fun body(text: String) = "---\nid: ${pageId.value}\ntitle: Rollback\n---\n\n# Rollback\n\n$text\n"

    fun slugged(slug: String, text: String) =
        "---\nid: ${pageId.value}\nslug: $slug\ntitle: Rollback\n---\n\n# Rollback\n\n$text\n"

    test("a rebuild that re-awards the page id mid-save must NOT send the reindex at the other root's file") {
        withTwoRoots { mainDir, extraDir ->
            // The id lives in `extra` alone, so `extra` owns it — and the save below is gated and CAS-written there.
            Files.createDirectories(extraDir.resolve("notes"))
            Files.writeString(extraDir.resolve("notes/rollback.md"), body("original."))

            val registry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("extra", extraDir)))
            val mainStore = LocalContentStore(mainDir, rootName = RootName.MAIN)
            val extraRoot = RootName.require("extra")
            val extraStore = LocalContentStore(extraDir, rootName = extraRoot)
            val search = RecordingSearchProvider()

            IndexHarness(
                root = mainDir,
                rootRegistry = registry,
                sources = listOf(
                    IndexBuilder.Source(registry.main, mainStore, NoOpHistoryProvider),
                    IndexBuilder.Source(requireNotNull(registry.byName(extraRoot)), extraStore, NoOpHistoryProvider),
                ),
                // NO search publication listener: a full rebuild therefore syncs NOTHING to search, so whatever the
                // engine ends up holding was put there by the targeted reindex — which is precisely what is on trial.
                searchIndexer = SearchIndexer(search, SectionSplitter()),
            ).use { harness ->
                harness.builder.rebuild()
                val before = harness.builder.current.pageAt(RootedPageId(extraRoot, pageId))!!
                before.root shouldBe extraRoot

                // The store seam: the CAS is REAL and writes to `extra`; the instant it returns, a rebuild lands that
                // publishes a colliding page in `main` carrying the SAME id - a legal cross-root duplicate post-flip,
                // so main and extra now each hold their own page under the id. This is the window the reindex runs in.
                val saved = body("revised.")
                val interleaving = object : ContentStore by extraStore {
                    override fun compareAndSwapWrite(
                        path: TreePath,
                        baseHash: String,
                        bytes: ByteArray,
                        hasher: (ByteArray) -> String,
                    ): CasResult {
                        val result = extraStore.compareAndSwapWrite(path, baseHash, bytes, hasher)
                        Files.createDirectories(mainDir.resolve("notes"))
                        Files.writeString(mainDir.resolve("notes/rollback.md"), body("a colliding page in main."))
                        harness.builder.rebuild()
                        return result
                    }
                }

                val pipeline = WritePipeline(
                    stores = { name -> if (name == extraRoot) interleaving else mainStore },
                    indexBuilder = harness.builder,
                    citations = CitationFactory(),
                    frontmatterParser = FrontmatterReader(),
                    dirtyPages = harness.dirtyPages,
                    idMap = harness.idMap,
                    aliasRegistry = harness.registry,
                    availability = harness.availability,
                )

                val outcome = pipeline.write(
                    grantForTests(),
                    WriteIntent(pageId, extraRoot, path, before.contentHash, saved.toByteArray()),
                )

                outcome.shouldBeInstanceOf<WriteOutcome.Written>()
                withClue("per-root identity (C5): main's colliding page and extra's page now BOTH hold the id, each its own page") {
                    harness.builder.current.pageAt(RootedPageId(RootName.MAIN, pageId)).shouldNotBeNull()
                    harness.builder.current.pageAt(RootedPageId(extraRoot, pageId)).shouldNotBeNull()
                }

                val written = harness.builder.current.pageAt(RootedPageId(extraRoot, pageId))!!
                written.id shouldBe pageId // extra keeps its OWN id - the flip re-awards nothing
                Files.readString(extraDir.resolve("notes/rollback.md")) shouldContain "revised."
                withClue(
                    "the reindex must follow the BYTES to EXTRA. Addressed by the bare id (now ambiguous), it could " +
                        "re-read, re-render and search-sync MAIN's colliding file instead - leaving the page this save " +
                        "actually wrote out of the propagating upsert, while the pipeline answers a clean Written",
                ) {
                    search.indexed shouldBe setOf(RootedPageId(extraRoot, written.id))
                    // MAIN's same-id page is BYTE-IDENTICAL: the targeted reindex never touched it.
                    Files.readString(mainDir.resolve("notes/rollback.md")) shouldContain "a colliding page in main."
                }
            }
        }
    }

    test("a rebuild that re-awards the page id mid-save must NOT let a rename slip past the edit-classification guard") {
        withTwoRoots { mainDir, extraDir ->
            // The SAME contest, one step earlier in the pipeline. The gate resolved this id to extra's page and
            // minted the intent; by the time write() runs, `main` has claimed the id (rank 0) and the snapshot's
            // byId entry is MAIN's file - which happens to carry the very slug this edit is trying to introduce.
            Files.createDirectories(extraDir.resolve("notes"))
            Files.createDirectories(mainDir.resolve("notes"))
            Files.writeString(extraDir.resolve("notes/rollback.md"), slugged("extra-rollback", "original."))
            Files.writeString(mainDir.resolve("notes/rollback.md"), slugged("renamed", "a colliding page in main."))

            val registry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("extra", extraDir)))
            val extraRoot = RootName.require("extra")

            IndexHarness(
                root = mainDir,
                rootRegistry = registry,
                sources = listOf(
                    IndexBuilder.Source(registry.main, LocalContentStore(mainDir, rootName = RootName.MAIN), NoOpHistoryProvider),
                    IndexBuilder.Source(
                        requireNotNull(registry.byName(extraRoot)),
                        LocalContentStore(extraDir, rootName = extraRoot),
                        NoOpHistoryProvider,
                    ),
                ),
            ).use { harness ->
                harness.builder.rebuild()
                withClue(
                    "per-root identity (C5): main's page and extra's page BOTH hold the id, so an " +
                        "id-addressed guard could read MAIN's frontmatter",
                ) {
                    harness.builder.current.pageAt(RootedPageId(RootName.MAIN, pageId)).shouldNotBeNull()
                    harness.builder.current.pageAt(RootedPageId(extraRoot, pageId)).shouldNotBeNull()
                }
                val target = harness.builder.current.byPath.getValue(RootedPath(extraRoot, path))

                // The rename: extra's page goes from `slug: extra-rollback` to `slug: renamed` - a URL-identity change,
                // and one that MAIN's page already matches, so a guard comparing against MAIN sees no change at all.
                val renamed = slugged("renamed", "original.")
                val outcome = harness.writePipeline().write(
                    grantForTests(),
                    WriteIntent(pageId, extraRoot, path, target.contentHash, renamed.toByteArray()),
                )

                withClue("the guard must classify against the page the BYTES are going to, not whatever page holds the id") {
                    outcome.shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "slug"
                }
                withClue("a rejected rename writes NOTHING") {
                    Files.readString(extraDir.resolve("notes/rollback.md")) shouldContain "slug: extra-rollback"
                }
            }
        }
    }
})

/** Two temp content roots, always cleaned up. */
private fun withTwoRoots(block: (main: Path, extra: Path) -> Unit) {
    val main = Files.createTempDirectory("pb-xroot-main")
    val extra = Files.createTempDirectory("pb-xroot-extra")
    try {
        block(main, extra)
    } finally {
        listOf(main, extra).forEach { it.toFile().deleteRecursively() }
    }
}

/**
 * Records the ROOTED ids handed to the engine — here, exactly the ones the targeted reindex upserted. The root is
 * load-bearing: main's colliding page and extra's page share the bare [PageId], so a bare-id record cannot tell a
 * reindex of the WRONG root's file apart from the right one, and the assertion would pass either way.
 */
private class RecordingSearchProvider : SearchProvider {
    val indexed = mutableSetOf<RootedPageId>()

    override fun index(pages: List<PageDocuments>) = pages.forEach { indexed += RootedPageId(it.root, it.pageId) }
    override fun delete(ids: Collection<RootedPageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(total = 0, hits = emptyList())
    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) =
        pages.forEach { indexed += RootedPageId(it.root, it.pageId) }
    override fun indexedState(): Map<RootedPageId, PageSearchState> = emptyMap()
}
