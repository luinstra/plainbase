package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.FolderMeta
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.root.RootName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class FirstIndexCompletenessTest : FunSpec({
    fun paths(vararg paths: String): Set<TreePath> = paths.mapTo(mutableSetOf(), TreePath::require)
    val complete = ScanCompleteness(true, true)
    val pages = paths("a.md")
    val assets = paths("logo.svg", "guides/_folder.yaml")
    val equal = IndexedSources(pages, assets)
    fun page(root: RootName, id: String, path: String, url: String?) = IndexedPage(
        id = PageId.require(id), root = root, path = TreePath.require(path), slug = "page",
        urlPath = url?.let(TreePath::require), title = "Page", frontmatter = Frontmatter.EMPTY, materialized = true,
        markdown = "body", contentHash = "h", commit = null, html = "", headings = emptyList(), links = emptyList(),
        sections = emptyList(),
    )

    test("equal page and other file sets with a complete pass are admitted") {
        FirstIndexCompleteness.refusal(pages + assets, equal, complete).shouldBeNull()
    }

    test("an uppercase Markdown suffix is an other file in both the listing and index") {
        isPageSource(TreePath.require("Logo.MD")) shouldBe false
        isPageSource(TreePath.require("a.md")) shouldBe true
        FirstIndexCompleteness.refusal(pages + paths("Logo.MD"), IndexedSources(pages, paths("Logo.MD")), complete).shouldBeNull()
    }

    listOf(emptySet(), paths("logo.svg")).forEach { listed ->
        test("a prefix with no page refuses for listing $listed") {
            FirstIndexCompleteness.refusal(listed, IndexedSources(emptySet(), listed), complete)
                .shouldNotBeNull() shouldContain "no page"
        }
    }

    test("a listed page missing from the index is named") {
        FirstIndexCompleteness.refusal(pages, IndexedSources(emptySet(), emptySet()), complete)
            .shouldNotBeNull() shouldContain "a.md"
    }

    test("equal page counts with different paths refuse and name both paths") {
        val refusal = FirstIndexCompleteness.refusal(pages, IndexedSources(paths("b.md"), emptySet()), complete).shouldNotBeNull()
        refusal shouldContain "a.md"
        refusal shouldContain "b.md"
        refusal shouldContain "1 listed page(s) not indexed"
        refusal shouldContain "1 indexed page(s) not listed"
    }

    test("a skipped first pass names the skip before comparing the empty index with the listing") {
        val indexed = FirstIndexCompleteness.indexedSources(PageIndex.EMPTY, RootName.PRIMARY, "_folder.yaml")
        FirstIndexCompleteness.refusal(pages + assets, indexed, null) shouldBe "the first pass skipped the root"
    }

    test("incomplete page reads refuse even when the indexed paths agree") {
        FirstIndexCompleteness.refusal(pages + assets, equal, ScanCompleteness(true, false))
            .shouldNotBeNull() shouldContain "page read"
    }

    test("a listed asset missing from the index refuses even with another indexed asset") {
        val refusal = FirstIndexCompleteness.refusal(
            pages + paths("logo.svg"), IndexedSources(pages, paths("other.svg")), complete,
        ).shouldNotBeNull()

        refusal shouldContain "logo.svg"
        refusal shouldContain "other.svg"
        refusal shouldContain "1 listed other file(s) not indexed"
        refusal shouldContain "1 indexed other file(s) not listed"
    }

    test("a listed folder sidecar refuses when the indexed folder has no metadata") {
        val root = RootName.PRIMARY
        val index = PageIndex(
            listOf(
                RootSection(
                    root, listOf(page(root, "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "a.md", "a")),
                    listOf(ContentFolder(TreePath.require("guides"))), emptySet(),
                ),
            ),
        )
        index.sections.single().folders.single().meta.shouldBeNull()
        val indexed = FirstIndexCompleteness.indexedSources(index, root, "_folder.yaml")
        indexed.pages shouldBe pages
        indexed.otherFiles shouldBe emptySet()

        FirstIndexCompleteness.refusal(pages + paths("guides/_folder.yaml"), indexed, complete)
            .shouldNotBeNull() shouldContain "guides/_folder.yaml"
    }

    test("a listed metadata-only folder refuses when the folder is absent from the index") {
        val root = RootName.PRIMARY
        val index = PageIndex(
            listOf(
                RootSection(
                    root, listOf(page(root, "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "a.md", "a")), emptyList(), emptySet(),
                ),
            ),
        )
        index.sections.single().folders shouldBe emptyList()
        val indexed = FirstIndexCompleteness.indexedSources(index, root, "_folder.yaml")
        indexed.pages shouldBe pages
        indexed.otherFiles shouldBe emptySet()

        FirstIndexCompleteness.refusal(pages + paths("meta-only/_folder.yaml"), indexed, complete)
            .shouldNotBeNull() shouldContain "meta-only/_folder.yaml"
    }

    test("an incomplete walk refuses even when all indexed file sets agree") {
        FirstIndexCompleteness.refusal(pages + assets, equal, ScanCompleteness(false, true))
            .shouldNotBeNull() shouldContain "did not see the whole listed generation"
    }

    test("indexed sources retain root-local file paths including URL losers and folder metadata") {
        val docs = RootName.PRIMARY
        val extra = RootName.require("extra")
        val index = PageIndex(
            listOf(
                RootSection(
                    docs,
                    listOf(
                        page(docs, "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "a.md", "a"),
                        page(docs, "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b", "loser.md", null),
                    ),
                    listOf(
                        ContentFolder(TreePath.require("guides"), meta = FolderMeta(title = "Guides")),
                        ContentFolder(TreePath.require("plain")),
                    ),
                    paths("logo.svg"),
                ),
                RootSection(
                    extra, listOf(page(extra, "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "other.md", "other")),
                    listOf(ContentFolder(TreePath.require("private"), meta = FolderMeta(title = "Private"))), paths("private.svg"),
                ),
            ),
        )

        FirstIndexCompleteness.indexedSources(index, docs, "_folder.yaml") shouldBe
            IndexedSources(paths("a.md", "loser.md"), paths("logo.svg", "guides/_folder.yaml"))
        FirstIndexCompleteness.indexedSources(index, RootName.require("missing"), "_folder.yaml") shouldBe
            IndexedSources(emptySet(), emptySet())
    }
})
