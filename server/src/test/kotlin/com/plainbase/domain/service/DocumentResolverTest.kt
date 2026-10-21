package com.plainbase.domain.service

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DocumentResolverTest : FunSpec({
    fun resolverTest(block: (IndexHarness, DocumentIndex) -> Unit) = withTempTree(::readerCorpus) { root ->
        IndexHarness(root).use { harness ->
            val snapshot = harness.builder.rebuild()
            assertReaderCorpus(snapshot, harness.registry)
            block(harness, DocumentResolver.index(snapshot, RootName.PRIMARY, emptyMap()))
        }
    }

    listOf(
        ResolutionCase("guides/index", ReaderPages.G, "/docs/guides", redirect = true),
        ResolutionCase("shadow/index", ReaderPages.SI, "/docs/shadow/index"),
        ResolutionCase("old/alpha", ReaderPages.A, "/docs/guides/alpha", redirect = true),
        ResolutionCase("old-guides", ReaderPages.G, "/docs/guides", redirect = true),
        ResolutionCase("named", ReaderPages.N, "/docs/named"),
        ResolutionCase("named/index", ReaderPages.N, "/docs/named", redirect = true),
        ResolutionCase("ref", ReaderPages.O, "/docs/other", redirect = true),
        ResolutionCase("ref/index", ReaderPages.RF, "/docs/ref/index"),
        ResolutionCase("guides", ReaderPages.G, "/docs/guides"),
        ResolutionCase(null, ReaderPages.R, "/docs"),
        ResolutionCase("index", ReaderPages.R, "/docs", redirect = true),
        ResolutionCase("readme", ReaderPages.M, "/docs/readme"),
        ResolutionCase("renamed/index", ReaderPages.CU, "/docs/renamed", redirect = true),
        ResolutionCase("custom/index"),
        ResolutionCase("renamed", ReaderPages.CU, "/docs/renamed"),
        ResolutionCase("deep", folderListing = true),
        ResolutionCase("deep/sub", ReaderPages.D, "/docs/deep/sub"),
        ResolutionCase("Guides/Alpha"),
        ResolutionCase("nope"),
        ResolutionCase("caf\u00e9/index", ReaderPages.C, "/docs/caf%C3%A9", redirect = true),
    ).forEach { row ->
        test("document ${row.path ?: "root"} resolves to ${row.url ?: if (row.folderListing) "folder listing" else "miss"}") {
            resolverTest { harness, index ->
                val unverified = mutableListOf<RootedPageId>()
                val page = row.page?.let { checkNotNull(index.snapshot.pageAt(it.rooted)) }
                val expected = when {
                    row.folderListing -> DocumentResolution.FolderListing
                    page == null -> DocumentResolution.Miss
                    row.redirect -> DocumentResolution.Redirect(page, checkNotNull(row.url))
                    else -> DocumentResolution.Serve(page, checkNotNull(row.url))
                }
                resolve(index, row.path, harness.registry::find, unverifiedAlias = unverified::add) shouldBe expected
                unverified.shouldBeEmpty()
            }
        }
    }

    test("a bare root without a landing resolves to a folder listing") {
        withTempTree(::bareCorpus) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                assertReaderCorpus(snapshot, harness.registry, listOf(ReaderPages.top))
                val index = DocumentResolver.index(snapshot, RootName.PRIMARY, emptyMap())
                resolve(index, null, harness.registry::find) shouldBe DocumentResolution.FolderListing
            }
        }
    }

    test("a bound missing alias claims its requested folder URL and invokes the absence check once") {
        resolverTest { harness, index ->
            val target = RootedPageId(RootName.PRIMARY, ReaderPages.absentId)
            index.snapshot.pageAt(target) shouldBe null
            val unverified = mutableListOf<RootedPageId>()
            val result = resolve(
                index, "ref", aliasOverReference(harness, target), bindsLive = { true }, unverifiedAlias = unverified::add,
            )
            result shouldBe DocumentResolution.Miss
            unverified shouldBe listOf(target)
        }
    }

    test("a landing child keeps its own URL when a bound missing alias owns the folder URL") {
        resolverTest { harness, index ->
            val target = RootedPageId(RootName.PRIMARY, ReaderPages.absentId)
            index.snapshot.pageAt(target) shouldBe null
            val unverified = mutableListOf<RootedPageId>()
            resolve(
                index, "ref/index", aliasOverReference(harness, target), bindsLive = { true }, unverifiedAlias = unverified::add,
            ) shouldBe DocumentResolution.Serve(checkNotNull(index.snapshot.pageAt(ReaderPages.RF.rooted)), "/docs/ref/index")
            unverified.shouldBeEmpty()
        }
    }

    listOf("ref", "ref/index").forEach { path ->
        test("a dangling alias yields the folder URL to its landing for $path") {
            resolverTest { harness, index ->
                val target = RootedPageId(RootName.PRIMARY, ReaderPages.absentId)
                index.snapshot.pageAt(target) shouldBe null
                val unverified = mutableListOf<RootedPageId>()
                val page = checkNotNull(index.snapshot.pageAt(ReaderPages.RF.rooted))
                val expected = if (path == "ref") {
                    DocumentResolution.Serve(page, "/docs/ref")
                } else {
                    DocumentResolution.Redirect(page, "/docs/ref")
                }
                resolve(index, path, aliasOverReference(harness, target), unverifiedAlias = unverified::add) shouldBe expected
                unverified.shouldBeEmpty()
            }
        }
    }

    listOf("guides/index", "old-guides", "guides").forEach { path ->
        test("an unservable landing does not expose a page at $path") {
            resolverTest { harness, index ->
                val unverified = mutableListOf<RootedPageId>()
                val expected = if (path == "guides") DocumentResolution.FolderListing else DocumentResolution.Miss
                resolve(
                    index, path, harness.registry::find,
                    servable = { it.rooted != ReaderPages.G.rooted },
                    unverifiedAlias = unverified::add,
                ) shouldBe expected
                unverified.shouldBeEmpty()
            }
        }
    }

    test("every redirected corpus URL terminates at a served URL for the same rooted page") {
        resolverTest { harness, index ->
            val urls = buildSet {
                addAll(index.snapshot.pages.mapNotNull { it.url })
                fun folders(folder: TreeNode.Folder) {
                    folder.url?.let(::add)
                    folder.children.filterIsInstance<TreeNode.Folder>().forEach(::folders)
                }
                folders(index.tree)
                addAll(harness.registry.all().keys.map { "/${it.root.value}/${PercentCoding.encodePath(it.path.value)}" })
            }
            val redirects = mutableListOf<String>()
            val unverified = mutableListOf<RootedPageId>()
            urls.forEach { url ->
                withClue(url) {
                    val result = resolve(index, documentPath(url), harness.registry::find, unverifiedAlias = unverified::add)
                    if (result is DocumentResolution.Redirect) {
                        redirects += url
                        val target = resolve(
                            index, documentPath(result.location), harness.registry::find, unverifiedAlias = unverified::add,
                        ).shouldBeInstanceOf<DocumentResolution.Serve>()
                        target.page.rooted shouldBe result.page.rooted
                        target.canonicalUrl shouldBe result.location
                    }
                }
            }
            redirects.shouldNotBeEmpty()
            unverified.shouldBeEmpty()
        }
    }
})

private data class ResolutionCase(
    val path: String?,
    val page: ReaderPage? = null,
    val url: String? = null,
    val redirect: Boolean = false,
    val folderListing: Boolean = false,
)

private fun resolve(
    index: DocumentIndex,
    path: String?,
    aliasOf: (RootedPath) -> RootedPageId?,
    bindsLive: (RootedPageId) -> Boolean = { false },
    servable: (IndexedPage) -> Boolean = { true },
    unverifiedAlias: (RootedPageId) -> Unit = {},
): DocumentResolution = DocumentResolver.resolve(index, path?.let(TreePath::require), aliasOf, bindsLive, servable, unverifiedAlias)

private fun aliasOverReference(harness: IndexHarness, target: RootedPageId): (RootedPath) -> RootedPageId? = { path ->
    if (path == RootedPath(RootName.PRIMARY, TreePath.require("ref"))) target else harness.registry.find(path)
}

private fun documentPath(url: String): String? {
    if (url == "/docs") return null
    check(url.startsWith("/docs/"))
    val decoded = PercentCoding.decodeOnce(url.removePrefix("/docs/"))
    return (decoded as PercentCoding.DecodeResult.Success).value
}
