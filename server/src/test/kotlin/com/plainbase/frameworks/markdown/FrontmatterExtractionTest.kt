package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterValue
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Acceptance criterion 5 — frontmatter VALUE extraction over the documented §C2 subset:
 * the full §5.2 schema (the editorially-frozen `guides/deploy-guide.md` fixture), flow lists
 * (`[a, b]`), block lists (`- a` lines), quoted scalars, no block (`notes/no-frontmatter.md` →
 * `{}`), and preservation of unknown keys.
 */
class FrontmatterExtractionTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("guides/deploy-guide.md")

    fun renderFixture(rel: String) =
        renderer.render(TreePath.require(rel), Files.readAllBytes(Fixtures.demoDocs.resolve(rel))).frontmatter

    fun renderInline(markdown: String) = renderer.render(sourcePath, markdown.toByteArray()).frontmatter

    test("full §5.2 schema (deploy-guide.md): scalars, a flow list, and unknown keys preserved") {
        val fm = renderFixture("guides/deploy-guide.md")
        fm.scalar("title") shouldBe "Deploy Guide"
        fm.scalar("slug") shouldBe "deploy-guide"
        // `owner` is not a schema key the app reads — it must still be preserved verbatim.
        fm.scalar("owner") shouldBe "ops"
        fm["tags"] shouldBe FrontmatterValue.StringList(listOf("infra", "guide"))
        fm["redirect_from"] shouldBe FrontmatterValue.StringList(listOf("/old/deployment.md"))
    }

    test("flow list `[a, b]` parses to a string list") {
        val fm = renderInline("---\ntags: [alpha, beta, gamma]\n---\n# B\n")
        fm["tags"] shouldBe FrontmatterValue.StringList(listOf("alpha", "beta", "gamma"))
    }

    test("block list (`- item` lines) parses to a string list") {
        val fm = renderInline("---\ntags:\n  - alpha\n  - beta\n---\n# B\n")
        fm["tags"] shouldBe FrontmatterValue.StringList(listOf("alpha", "beta"))
    }

    test("quoted scalars have matching quotes stripped (double and single)") {
        val fm = renderInline("---\ntitle: \"hello world\"\nname: 'jane doe'\n---\n# B\n")
        fm.scalar("title") shouldBe "hello world"
        fm.scalar("name") shouldBe "jane doe"
    }

    test("no frontmatter block (no-frontmatter.md) yields the empty frontmatter ({})") {
        val fm = renderFixture("notes/no-frontmatter.md")
        fm shouldBe Frontmatter.EMPTY
    }

    test("unknown keys are preserved (the map is whatever was parsed, not a fixed schema)") {
        val fm = renderInline("---\ncustom_thing: yes\nanother: 42\n---\n# B\n")
        fm.scalar("custom_thing") shouldBe "yes"
        fm.scalar("another") shouldBe "42"
    }

    // ---- Fix #1: subset enforcement — out-of-subset constructs are DROPPED (with a per-page warning),
    // never leaked as top-level scalars (§C2) -----------------------------------------------------

    test("shallow-nested map leaks no nested key: `author:` then `  name:` keeps neither out-of-subset value") {
        // flexmark's metadata regex surfaces BOTH `author` and `name` as top-level; the subset admits
        // neither (`author:` introduces a nested map — its value is empty and `name` is indented).
        val fm = renderInline("---\nauthor:\n  name: Jane\n  email: jane@x.test\n---\n# B\n")
        fm["name"] shouldBe null
        fm["email"] shouldBe null
        // `author:` itself has an empty value (the nested map is its content); it carries no scalar.
        fm["author"] shouldBe FrontmatterValue.Scalar("")
    }

    test("a block-scalar value (`desc: |`) is outside the subset and dropped") {
        val fm = renderInline("---\ntitle: ok\ndesc: |\n  line one\n  line two\n---\n# B\n")
        fm.scalar("title") shouldBe "ok"
        fm["desc"] shouldBe null
    }

    test("an anchored value (`base: &a x`) is outside the subset and dropped") {
        val fm = renderInline("---\ntitle: ok\nbase: &anchor value\n---\n# B\n")
        fm.scalar("title") shouldBe "ok"
        fm["base"] shouldBe null
    }

    // ---- Fix #3: quote-aware flow-list split (a quoted comma is data, not a separator) ----------

    test("flow list with a quoted comma `[\"a, b\", c]` is not mis-split") {
        val fm = renderInline("---\ntags: [\"a, b\", c]\n---\n# B\n")
        fm["tags"] shouldBe FrontmatterValue.StringList(listOf("a, b", "c"))
    }

    // ---- Fix #5: list-shape wobble PINNED — a single-item block list collapses to a Scalar; the
    // visitor erases the distinction so this cannot be cleanly fixed at this layer. Consumers must
    // accept scalar-or-list for list-typed keys (documented on Frontmatter/FrontmatterValue). ------

    test("single-item block list collapses to a Scalar (documented list-shape wobble)") {
        val fm = renderInline("---\ntags:\n  - solo\n---\n# B\n")
        fm["tags"] shouldBe FrontmatterValue.Scalar("solo")
    }

    test("a two-item flow list of the same key yields a StringList (the diverging shape)") {
        val fm = renderInline("---\ntags: [solo, second]\n---\n# B\n")
        fm["tags"] shouldBe FrontmatterValue.StringList(listOf("solo", "second"))
    }

    // ---- Value-divergence-in-block: an inner `--- ` (trailing content) line closes the re-wrapped
    // FrontmatterReader parse early — flexmark's closer regex tolerates trailing content where the
    // §A3 detector does not. This is a DELIBERATE, documented degradation of the value parse (the
    // authoritative detector still slices the block correctly for rendering). -----------------------

    test("inner `--- x` line closes the re-wrapped value parse early (documented degradation)") {
        // The §A3 detector treats `--- x` as ordinary block content (its closer is exactly `---`), so
        // `after` is inside the detected block region. But FrontmatterReader re-wraps that region and
        // hands it to flexmark, whose closer regex tolerates the trailing ` x` — so flexmark stops at
        // `--- x` and never sees `after`. The degradation is the value `after` being dropped.
        val fm = renderInline("---\nbefore: 1\n--- x\nafter: 2\n---\n# B\n")
        fm.scalar("before") shouldBe "1"
        fm["after"] shouldBe null
    }

    // ---- Cheap pins -----------------------------------------------------------------------------

    test("CRLF in a scalar value extracts cleanly (no stray \\r in the value)") {
        val fm = renderInline("---\r\ntitle: Carriage\r\nslug: cr\r\n---\r\n# B\r\n")
        fm.scalar("title") shouldBe "Carriage"
        fm.scalar("slug") shouldBe "cr"
    }

    test("duplicate keys: last value wins") {
        val fm = renderInline("---\ntitle: first\ntitle: second\n---\n# B\n")
        fm.scalar("title") shouldBe "second"
    }
})
