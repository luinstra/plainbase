package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Heading
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files

/**
 * Acceptance criterion 6 — the `headings` list (the §A4 `/html` payload, Phase 5 recovery + TOC).
 * For a fixture page it must match DOCUMENT ORDER, carry the correct level per heading, and the
 * §A1 text-content extraction (PB-SLUG-1's input rule: code-span/link/emphasis text kept, markup
 * stripped) feeding the allocated id.
 */
class HeadingsListTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))

    test("deploy-guide.md headings: document order, levels, and PB-SLUG-1 ids") {
        val rel = "guides/deploy-guide.md"
        val page = renderer.render(TreePath.require(rel), Files.readAllBytes(Fixtures.demoDocs.resolve(rel)))
        page.headings shouldContainExactly listOf(
            Heading(id = "deploy-guide", level = 1, text = "Deploy Guide"),
            Heading(id = "prerequisites", level = 2, text = "Prerequisites"),
            Heading(id = "rolling-deploy", level = 2, text = "Rolling deploy"),
            Heading(id = "rollback", level = 2, text = "Rollback"),
        )
    }

    test("PB-SLUG-1 text extraction keeps code-span / link / emphasis text and strips markup") {
        // Inline markup, a code span, and a link — §A1 keeps their TEXT, drops the delimiters/URL.
        val markdown = "# Use `git status` with **bold** and [a link](https://x.test)\n"
        val page = renderer.render(TreePath.require("notes/just-text.md"), markdown.toByteArray())
        page.headings shouldContainExactly listOf(
            Heading(id = "use-git-status-with-bold-and-a-link", level = 1, text = "Use git status with bold and a link"),
        )
    }
})
