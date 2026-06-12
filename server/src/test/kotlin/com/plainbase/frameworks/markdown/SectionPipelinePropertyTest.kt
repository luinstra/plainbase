package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * §B4 exclusivity, proven against the real renderer: over generated Markdown documents, the
 * section stream partitions the body — every generated word lands in exactly ONE section's body,
 * in document order, with no loss and no duplication; heading words never leak into any body
 * (they are the section's `heading` field, not its text); and the non-null section ids equal the
 * page's heading ids, in order. The generator deliberately includes headings NESTED inside
 * blockquotes and list items, with optional sibling text in the SAME container (`> intro` /
 * `> # h` / `> tail` as one blockquote) — the boundary must fall exactly at the heading even
 * mid-container, splitting the container's own text across two sections.
 *
 * The comparison is word-sequence (not byte) equality: chunk separators and trailing-EOL trimming
 * are presentation, exclusivity of CONTENT is the §B4 invariant.
 */
class SectionPipelinePropertyTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("prop/page.md")

    val word = Arb.string(3..8, Codepoint.az())
    val words = Arb.list(word, 1..5)
    val sibling = words.orNull(nullProbability = 0.4)
    val level = Arb.int(1..4)

    val element: Arb<Element> = Arb.choice(
        Arb.bind(level, words) { l, w -> Element.Heading(l, w) },
        Arb.bind(level, words, sibling, sibling) { l, w, lead, tail -> Element.QuotedHeading(l, w, lead, tail) },
        Arb.bind(level, words, sibling, sibling) { l, w, lead, tail -> Element.ItemHeading(l, w, lead, tail) },
        words.map { w -> Element.Text(w) { it.joinToString(" ") } }, // plain paragraph
        words.map { w -> Element.Text(w) { "> " + it.joinToString(" ") } }, // blockquoted paragraph
        words.map { w -> Element.Text(w) { "- " + it.joinToString(" ") } }, // list item
        words.map { w -> Element.Text(w) { "```\n" + it.joinToString(" ") + "\n```" } }, // fenced code
        words.map { w -> Element.Text(w) { "*" + it.joinToString(" ") + "*" } }, // emphasis
    )

    test("sections partition the body: per-section word sequences match the oracle exactly") {
        checkAll(Arb.list(element, 0..15)) { elements ->
            val markdown = elements.joinToString("\n\n") { it.markdown() }
            val rendered = renderer.render(sourcePath, markdown.toByteArray())

            val tokens = elements.flatMap { it.tokens() }
            rendered.sections.map { it.wordList() } shouldBe expectedSections(tokens)
            // The section ids ARE the page's heading ids, in document order (nested ones included).
            rendered.sections.mapNotNull { it.headingId } shouldBe rendered.headings.map { it.id }
            rendered.headings.size shouldBe tokens.count { it == Token.Boundary }
        }
    }
})

/**
 * One generated top-level Markdown element, flattened to its oracle [tokens]: body words in
 * document order, with a [Token.Boundary] wherever a heading sits — possibly BETWEEN words of the
 * same container (the lead/tail variants below).
 */
private sealed interface Element {

    fun markdown(): String

    fun tokens(): List<Token>

    class Text(private val words: List<String>, private val render: (List<String>) -> String) : Element {
        override fun markdown() = render(words)
        override fun tokens() = listOf(Token.Words(words))
    }

    class Heading(private val level: Int, private val words: List<String>) : Element {
        override fun markdown() = "#".repeat(level) + " " + words.joinToString(" ")
        override fun tokens() = listOf(Token.Boundary)
    }

    /** ONE blockquote whose heading may have sibling paragraphs before/after it — the mid-container split. */
    class QuotedHeading(
        private val level: Int,
        private val words: List<String>,
        private val lead: List<String>?,
        private val tail: List<String>?,
    ) : Element {
        override fun markdown() = buildString {
            lead?.let { append("> ").append(it.joinToString(" ")).append('\n') }
            append("> ").append("#".repeat(level)).append(' ').append(words.joinToString(" "))
            tail?.let { append("\n> ").append(it.joinToString(" ")) }
        }

        override fun tokens() = listOfNotNull(lead?.let(Token::Words), Token.Boundary, tail?.let(Token::Words))
    }

    /** ONE list item whose heading may have sibling paragraphs before/after it inside the item. */
    class ItemHeading(
        private val level: Int,
        private val words: List<String>,
        private val lead: List<String>?,
        private val tail: List<String>?,
    ) : Element {
        override fun markdown() = buildString {
            append("- ")
            lead?.let { append(it.joinToString(" ")).append("\n  ") }
            append("#".repeat(level)).append(' ').append(words.joinToString(" "))
            tail?.let { append("\n  ").append(it.joinToString(" ")) }
        }

        override fun tokens() = listOfNotNull(lead?.let(Token::Words), Token.Boundary, tail?.let(Token::Words))
    }
}

private sealed interface Token {
    /** Body words, in document order. */
    class Words(val words: List<String>) : Token

    /** A heading: closes the current section, opens the next. Its words belong to NO body. */
    object Boundary : Token
}

private val WORD = Regex("[a-z]+")

private fun RenderedSection.wordList(): List<String> = WORD.findAll(text).map { it.value }.toList()

/**
 * The independent oracle: fold the token stream into expected per-section word sequences — a
 * preamble (present only when text precedes the first heading), then one section per heading
 * whose words are everything up to the next heading. Heading words appear in NO body.
 */
private fun expectedSections(tokens: List<Token>): List<List<String>> = buildList {
    var current = mutableListOf<String>()
    var open = false // a heading section is emitted even when empty; the preamble only when it has words
    tokens.forEach { token ->
        when (token) {
            is Token.Words -> current += token.words
            Token.Boundary -> {
                if (open || current.isNotEmpty()) add(current)
                current = mutableListOf()
                open = true
            }
        }
    }
    if (open || current.isNotEmpty()) add(current)
}
