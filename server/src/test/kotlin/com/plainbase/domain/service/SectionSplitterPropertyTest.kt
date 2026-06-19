package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterValue
import com.plainbase.domain.page.Heading
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.render.RenderedSection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.merge
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * §B4 splitter invariants, pure domain (zero flexmark imports — the splitter consumes only
 * snapshot data): no C0 control character except `\t`/`\n` survives into ANY document text field,
 * `heading_path` is always consistent with heading levels (independent backwards-walk oracle),
 * and the document set is structurally exact — page-level document first, then one document per
 * heading section in order, each carrying its OWN heading text.
 */
class SectionSplitterPropertyTest : FunSpec({

    val splitter = SectionSplitter()

    // Strings biased toward C0 controls (incl. \t \n \r, which the splitter must treat unequally),
    // merged with the whole codepoint space so the invariant is exercised broadly.
    val c0Heavy: Arb<Codepoint> = Arb.of(
        (0x00..0x1F).map(::Codepoint) + listOf(Codepoint('a'.code), Codepoint('B'.code), Codepoint(0x1F680)),
    )
    val spicyStrings: Arb<String> =
        Arb.string(0..12, c0Heavy)
            .merge(Arb.string(0..16, Codepoint.az()))
            .merge(Arb.string(0..10, Arb.codepoints()))

    fun page(
        headings: List<Heading>,
        sections: List<RenderedSection>,
        frontmatter: Frontmatter = Frontmatter.EMPTY,
        title: String = "Title",
    ) = IndexedPage(
        id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"),
        path = TreePath.require("notes/page.md"),
        slug = "page",
        urlPath = TreePath.require("notes/page"),
        title = title,
        frontmatter = frontmatter,
        materialized = false,
        markdown = "",
        contentHash = "sha256:" + "0".repeat(64),
        commit = null,
        html = "",
        headings = headings,
        links = emptyList(),
        sections = sections,
    )

    fun bannedIn(s: String): List<Char> = s.filter { it < ' ' && it != '\t' && it != '\n' }.toList()

    test("no C0 (except \\t \\n) survives into any document text field") {
        checkAll(spicyStrings, spicyStrings, spicyStrings, Arb.list(spicyStrings, 0..3)) { title, body, owner, tags ->
            val headings = listOf(Heading(id = "h1", level = 1, text = title))
            val sections = listOf(RenderedSection(null, body), RenderedSection("h1", body))
            val frontmatter = Frontmatter(
                mapOf(
                    "owner" to FrontmatterValue.Scalar(owner),
                    "status" to FrontmatterValue.Scalar(owner),
                    "tags" to FrontmatterValue.StringList(tags),
                    "aliases" to FrontmatterValue.StringList(tags),
                ),
            )
            splitter.split(page(headings, sections, frontmatter, title = title)).sections.forEach { doc ->
                val fields = listOfNotNull(doc.title, doc.heading, doc.body, doc.owner, doc.status) +
                    doc.headingPath + doc.tags + doc.aliases
                fields.flatMap(::bannedIn) shouldBe emptyList()
            }
        }
    }

    test("C0 stripping pin: bans removed (\\r included), \\t and \\n kept, everything else untouched") {
        val sections = listOf(RenderedSection("h1", "\u0000a\tb\nc\rd\u001Fe"))
        val doc = splitter.split(page(listOf(Heading("h1", 1, "t")), sections)).sections.last()
        doc.body shouldBe "a\tb\ncde"
    }

    // Random heading outlines: ids unique by construction, levels free-ranging so the breadcrumb
    // stack sees every shape (deepening, flat runs, level jumps in both directions).
    val outlines: Arb<List<Heading>> =
        Arb.list(Arb.pair(Arb.int(1..6), Arb.string(1..8, Codepoint.az())), 0..20)
            .map { rows -> rows.mapIndexed { i, (level, text) -> Heading(id = "h$i", level = level, text = text) } }

    /** The §B7 rule, written independently: nearest preceding heading of a STRICTLY lower level, recursively. */
    fun breadcrumbOracle(headings: List<Heading>, index: Int): List<String> {
        val crumb = ArrayDeque(listOf(headings[index].text))
        var level = headings[index].level
        for (j in index - 1 downTo 0) {
            if (headings[j].level < level) {
                crumb.addFirst(headings[j].text)
                level = headings[j].level
            }
        }
        return crumb.toList()
    }

    test("heading_path is consistent with heading levels (independent oracle) and ends in the OWN text") {
        checkAll(outlines) { headings ->
            val sections = headings.map { RenderedSection(it.id, "body of ${it.id}") }
            val documents = splitter.split(page(headings, sections)).sections

            documents.first().headingId shouldBe null // the page-level document always leads
            documents.first().headingPath shouldBe emptyList()
            val sectionDocs = documents.drop(1)
            sectionDocs.map { it.headingId } shouldBe headings.map { it.id }
            sectionDocs.forEachIndexed { i, doc ->
                doc.headingPath shouldBe breadcrumbOracle(headings, i)
                doc.heading shouldBe headings[i].text // own text, never the joined breadcrumb
                doc.body shouldBe "body of ${headings[i].id}"
            }
        }
    }

    test("preamble text becomes the page-level body; no preamble section means an empty one") {
        val headings = listOf(Heading("h0", 1, "t"))
        val withPreamble = splitter.split(page(headings, listOf(RenderedSection(null, "intro"), RenderedSection("h0", ""))))
        withPreamble.sections.first().body shouldBe "intro"
        val without = splitter.split(page(headings, listOf(RenderedSection("h0", ""))))
        without.sections.first().body shouldBe ""
    }

    test("§C2 scalar-or-list collapse: a scalar tags/aliases value reads as a one-item list") {
        val frontmatter = Frontmatter(
            mapOf(
                "tags" to FrontmatterValue.Scalar("solo"),
                "aliases" to FrontmatterValue.StringList(listOf("a", "b")),
            ),
        )
        val doc = splitter.split(page(emptyList(), emptyList(), frontmatter)).sections.single()
        doc.tags shouldBe listOf("solo")
        doc.aliases shouldBe listOf("a", "b")
    }

    test("status defaults to active; an explicit scalar wins") {
        splitter.split(page(emptyList(), emptyList())).sections.single().status shouldBe "active"
        val archived = Frontmatter(mapOf("status" to FrontmatterValue.Scalar("archived")))
        splitter.split(page(emptyList(), emptyList(), archived)).sections.single().status shouldBe "archived"
    }
})
