package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.FolderMeta
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * §A4 canonical-URL construction unit tests (chunk 5): segment slugification, `_folder.yaml` slug
 * overrides, the frozen empty-result fallbacks, the raw-unsigned-byte-order collision tie-break,
 * and a losing FOLDER taking its subtree out of path space. The fixture-wide golden URL set lives
 * in [IndexBuilderFixtureTest]; these pin the construction rules in isolation.
 */
class CanonicalUrlBuilderTest : FunSpec({

    fun page(path: String, slug: String? = null) =
        CanonicalUrlBuilder.PageInput(path = TreePath.require(path), rawName = path.substringAfterLast('/'), slugOverride = slug)

    fun folder(path: String, meta: FolderMeta? = null) =
        ContentFolder(path = TreePath.require(path), meta = meta)

    fun urlPathOf(result: CanonicalUrlBuilder.Result, path: String): String? =
        result.byPage.getValue(TreePath.require(path)).urlPath?.value

    test("ancestor segments and the page slug are slugified; frontmatter slug overrides the stem") {
        val result = CanonicalUrlBuilder.build(
            pages = listOf(
                page("Release Notes/release notes 2026.md"),
                page("guides/deploy-guide.md", slug = "Deploy Guide"),
                page("top.md"),
            ),
            folders = listOf(folder("Release Notes"), folder("guides")),
        )
        urlPathOf(result, "Release Notes/release notes 2026.md") shouldBe "release-notes/release-notes-2026"
        // The frontmatter slug is itself passed through steps 1-6 (URL safety by construction).
        urlPathOf(result, "guides/deploy-guide.md") shouldBe "guides/deploy-guide"
        urlPathOf(result, "top.md") shouldBe "top" // a root-level page is /docs/{page-slug}
        result.issues.shouldBeEmpty()
    }

    test("a _folder.yaml slug override replaces the directory segment and is itself slugified") {
        val result = CanonicalUrlBuilder.build(
            pages = listOf(page("v2 docs/setup.md")),
            folders = listOf(folder("v2 docs", FolderMeta(slug = "Version Two"))),
        )
        urlPathOf(result, "v2 docs/setup.md") shouldBe "version-two/setup"
    }

    test("empty slugification results fall back to the frozen literals: page / folder") {
        val result = CanonicalUrlBuilder.build(
            // '!!!' and '?' delete to empty under steps 1-6 (PB-SLUG-1 row 18's input class).
            pages = listOf(page("!!!/?.md")),
            folders = listOf(folder("!!!")),
        )
        urlPathOf(result, "!!!/?.md") shouldBe "folder/page"
    }

    test("same-parent page collision: raw-unsigned-byte-order winner owns the path; loser url is null + issue") {
        val result = CanonicalUrlBuilder.build(
            // Both slugify to 'a-b'. Raw bytes: 'a b.md' has 0x20 at index 1, 'a-b.md' has 0x2D -> space wins.
            pages = listOf(page("notes/a-b.md"), page("notes/a b.md")),
            folders = listOf(folder("notes")),
        )
        urlPathOf(result, "notes/a b.md") shouldBe "notes/a-b"
        urlPathOf(result, "notes/a-b.md").shouldBeNull()
        result.issues shouldContainExactly listOf(
            IdentityIssue.PathSlugCollision(keptPath = TreePath.require("notes/a b.md"), loserPath = TreePath.require("notes/a-b.md")),
        )
    }

    test("the tie-break is UNSIGNED byte order: a high UTF-8 byte sorts after every ASCII byte") {
        val result = CanonicalUrlBuilder.build(
            // Both slugify to 'café' ('~' is punctuation, deleted by step 4). First differing raw
            // byte (index 3): 'café.md' has the UTF-8 lead 0xC3 (195); 'caf~é.md' has '~' = 0x7E
            // (126). Unsigned: 0x7E < 0xC3 -> 'caf~é.md' wins. A signed comparison would flip it
            // (0xC3 as signed = -61 < 126) and crown 'café.md' — the regression this test pins out.
            pages = listOf(page("café.md"), page("caf~é.md")),
            folders = emptyList(),
        )
        urlPathOf(result, "caf~é.md") shouldBe "café"
        urlPathOf(result, "café.md").shouldBeNull()
        result.issues shouldContainExactly listOf(
            IdentityIssue.PathSlugCollision(keptPath = TreePath.require("caf~é.md"), loserPath = TreePath.require("café.md")),
        )
    }

    test("a page and a sibling folder sharing a slug do NOT collide — distinct URLs, no issue (ADR-0002)") {
        val result = CanonicalUrlBuilder.build(
            // The common overview-page-next-to-detail-folder layout: setup.md beside setup/.
            pages = listOf(page("docs/setup.md"), page("docs/setup/intro.md")),
            folders = listOf(folder("docs"), folder("docs/setup")),
        )
        urlPathOf(result, "docs/setup.md") shouldBe "docs/setup"
        urlPathOf(result, "docs/setup/intro.md") shouldBe "docs/setup/intro"
        result.issues.shouldBeEmpty()
    }

    test("a losing folder takes its whole subtree out of path space") {
        val result = CanonicalUrlBuilder.build(
            pages = listOf(page("a b/deep/page.md"), page("a-b/other.md")),
            // Sibling folders 'a b' and 'a-b' both slugify to 'a-b'; 'a b' (0x20) wins.
            folders = listOf(folder("a b"), folder("a b/deep"), folder("a-b")),
        )
        urlPathOf(result, "a b/deep/page.md") shouldBe "a-b/deep/page"
        urlPathOf(result, "a-b/other.md").shouldBeNull()
        result.issues shouldContainExactly listOf(
            IdentityIssue.PathSlugCollision(keptPath = TreePath.require("a b"), loserPath = TreePath.require("a-b")),
        )
    }

    test("redirect_from values convert through the same construction: strip .md, slugify segments") {
        CanonicalUrlBuilder.redirectUrlPath("/old/Deployment Guide.md")?.value shouldBe "old/deployment-guide"
        CanonicalUrlBuilder.redirectUrlPath("old/deployment.md")?.value shouldBe "old/deployment"
        CanonicalUrlBuilder.redirectUrlPath("plain")?.value shouldBe "plain"
        CanonicalUrlBuilder.redirectUrlPath("/").shouldBeNull()
        CanonicalUrlBuilder.redirectUrlPath("").shouldBeNull()
    }
})
