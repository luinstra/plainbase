package com.plainbase.domain.page

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * R13: [PageIndex]'s identity map is PER-ROOT (C5). The same page id living in TWO roots is LEGAL and constructs;
 * `pageAt` returns each root's OWN page. A WITHIN-root duplicate (a genuine builder bug) still collides on
 * ([RootedPageId]) and fails as loudly as it ever did.
 */
class PageIndexPerRootIdTest : FunSpec({

    val extra = RootName.require("extra")
    val x = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    fun page(id: PageId, root: RootName, path: String) = IndexedPage(
        id = id,
        root = root,
        path = TreePath.require(path),
        slug = path.substringAfterLast('/').removeSuffix(".md"),
        urlPath = TreePath.require(path.removeSuffix(".md")),
        title = "T",
        frontmatter = Frontmatter.EMPTY,
        materialized = false,
        markdown = "",
        contentHash = "h",
        commit = null,
        html = "",
        headings = emptyList(),
        links = emptyList(),
        sections = listOf(RenderedSection(null, "body")),
    )

    fun section(root: RootName, vararg pages: IndexedPage) = RootSection(root, pages.toList(), emptyList(), emptySet())

    test("the SAME id in two DIFFERENT roots constructs, and pageAt returns each root's own page") {
        val index = PageIndex(
            listOf(
                section(RootName.MAIN, page(x, RootName.MAIN, "guides/a.md")),
                section(extra, page(x, extra, "notes/b.md")),
            ),
        )

        index.pageAt(RootedPageId(RootName.MAIN, x)).shouldNotBeNull().path shouldBe TreePath.require("guides/a.md")
        index.pageAt(RootedPageId(extra, x)).shouldNotBeNull().path shouldBe TreePath.require("notes/b.md")
        index.pageAt(RootedPageId(RootName.require("ghost"), x)).shouldBeNull() // a root nobody indexed
    }

    test("the SAME id twice in ONE root still throws - the per-root byRootedId uniqueness check") {
        val failure = shouldThrow<IllegalStateException> {
            PageIndex(listOf(section(RootName.MAIN, page(x, RootName.MAIN, "guides/a.md"), page(x, RootName.MAIN, "guides/b.md"))))
        }
        failure.message.shouldNotBeNull() shouldContain "duplicate (root, page id)"
    }
})
