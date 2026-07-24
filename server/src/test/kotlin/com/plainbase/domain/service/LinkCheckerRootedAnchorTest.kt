package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.PageLink
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.Heading
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * R16: [LinkChecker] memoizes each page's anchor set by [com.plainbase.domain.root.RootedPageId], so a same-page
 * anchor is validated against the LINKING page's OWN root's headings. When two roots hold the same page id, one
 * root's heading set can no longer shadow the other's - a bare-id cache (`getOrPut(page.id)`) would let the
 * first-checked page's anchors mask a genuinely broken anchor in the other root.
 */
class LinkCheckerRootedAnchorTest : FunSpec({

    val x = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val extra = RootName.require("extra")

    fun page(root: RootName, path: String, headingIds: List<String>, anchor: String) = IndexedPage(
        id = x,
        root = root,
        path = TreePath.require(path),
        slug = "p",
        urlPath = TreePath.require(path.removeSuffix(".md")),
        title = "T",
        frontmatter = Frontmatter.EMPTY,
        materialized = false,
        markdown = "",
        contentHash = "h",
        commit = null,
        html = "",
        headings = headingIds.map { Heading(id = it, level = 2, text = it) },
        links = listOf(PageLink(target = "#$anchor", text = "see", outcome = LinkOutcome.Resolved.Anchor("#$anchor"))),
        sections = listOf(RenderedSection(null, "body")),
    )

    test("a same-page anchor is validated per root: the same id in another root never shadows a broken anchor") {
        // main:X HAS the heading its anchor names; extra:X (SAME id) does NOT - so only extra's anchor is broken.
        val index = PageIndex(
            listOf(
                RootSection(RootName.MAIN, listOf(page(RootName.MAIN, "a.md", listOf("target"), "target")), emptyList(), emptySet()),
                RootSection(extra, listOf(page(extra, "b.md", emptyList(), "target")), emptyList(), emptySet()),
            ),
        )

        val broken = LinkChecker().check(index).broken
        broken.map { it.page } shouldBe listOf(RootedPath(extra, TreePath.require("b.md")))
    }

    test("both roots resolve their own anchor -> nothing broken (the control)") {
        val index = PageIndex(
            listOf(
                RootSection(RootName.MAIN, listOf(page(RootName.MAIN, "a.md", listOf("target"), "target")), emptyList(), emptySet()),
                RootSection(extra, listOf(page(extra, "b.md", listOf("target"), "target")), emptyList(), emptySet()),
            ),
        )

        LinkChecker().check(index).broken.shouldBeEmpty()
    }
})
