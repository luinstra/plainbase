package com.plainbase.frameworks.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

/**
 * The §B5 MATCH builder against the frozen A1 promise: bare `q` is plain text and NO input may
 * ever surface an engine syntax error. Quoting/starring is pinned by unit tests; the no-error
 * guarantee is property-tested with the differential-oracle pattern — the ENGINE ITSELF is the
 * oracle (every generated string runs through the real provider against a real FTS5 index), and
 * the generator is the §A6 tier-3 adversarial corpus: FTS5 operators, quotes, parens, column
 * filters, `^*:`, control characters, lone `%`, unicode whitespace, CJK, non-BMP, and lone
 * surrogates.
 */
class MatchExpressionTest : FunSpec({

    test("pinned: tokens are double-quoted, joined with implicit AND, final token starred") {
        MatchExpression.primary("rolling deploy") shouldBe "\"rolling\" \"deploy\"*"
        MatchExpression.primary("deplo") shouldBe "\"deplo\"*"
        MatchExpression.primary("  spaced \t out \n") shouldBe "\"spaced\" \"out\"*"
    }

    test("pinned: internal double quotes are doubled, operators ride inside the quotes as ordinary text") {
        MatchExpression.primary("""he"llo""") shouldBe "\"he\"\"llo\"*"
        MatchExpression.primary("NOT (a OR b)") shouldBe "\"NOT\" \"(a\" \"OR\" \"b)\"*"
        MatchExpression.primary("title:secret^2 wild*") shouldBe "\"title:secret^2\" \"wild*\"*"
        MatchExpression.primary("\"\"") shouldBe "\"\"\"\"\"\"*"
    }

    test("pinned: C0 control characters are stripped (an embedded NUL can derail the FTS5 query parser)") {
        MatchExpression.primary("a\u0000bc") shouldBe "\"abc\"*"
        MatchExpression.primary("\u0000\u0001\u0002").shouldBeNull()
        // U+001C-U+001F are Java-whitespace but NOT Unicode White_Space: the splitter ignores
        // them, so leaving them inside a token would hand unicode61 a phrase adjacency ("a b").
        MatchExpression.primary("a\u001Cb") shouldBe "\"ab\"*"
        MatchExpression.primary("a\u001Db\u001Ec\u001Fd") shouldBe "\"abcd\"*"
    }

    test("pinned: no tokens -> null (zero hits, not an engine call); unicode whitespace splits too") {
        MatchExpression.primary("").shouldBeNull()
        MatchExpression.primary("   \t\n ").shouldBeNull()
        MatchExpression.primary("a\u00A0b") shouldBe "\"a\" \"b\"*" // NBSP
        MatchExpression.primary("a\u3000b") shouldBe "\"a\" \"b\"*" // ideographic space
    }

    test("pinned: the trigram expression quotes identically but never stars (substring semantics need no prefix)") {
        MatchExpression.trigram("日本語 ガイド") shouldBe "\"日本語\" \"ガイド\""
        MatchExpression.trigram("  ").shouldBeNull()
    }

    test("property: arbitrary adversarial strings NEVER produce an engine error (the engine is the oracle)") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(
                        1,
                        title = "Deploy Guide",
                        preamble = "rolling deploy with kubernetes",
                        sections = listOf("notes" to "café résumé naïve"),
                    ),
                    pageDocuments(2, title = "日本語ガイド", preamble = "これは 日本語ガイド のページです"),
                ),
            )
            checkAll(500, adversarialQuery) { text ->
                val results = provider.search(query(text)) // any SQLException here = a broken A1 promise
                check(results.total >= 0 && results.hits.size <= 20) { "malformed result for ${text.toList()}" }
            }
        }
    }
})

/** §A6 tier-3 generator: hostile fragments + raw code points (incl. lone surrogates), concatenated. */
private val adversarialQuery: Arb<String> = run {
    val fragments = Arb.element(
        "\"", "\"\"", "'", "(", ")", "((", "))", "*", "^", ":", "-", "+", "%", "_", "\\",
        "AND", "OR", "NOT", "NEAR", "NEAR(", "NEAR(a b, 1)", "title:", "body:", "{", "}",
        "deploy", "deplo*", "\"deploy\"", "-deploy", "ガイド", "日本語", "é", "ﬁ", "́",
        " ", "\t", "\n", "\u00A0", "\u3000", "\u0000", "\u0001", "\u0002", "\u001F", "\u007F",
        "😀", "🦑", "𝄞", "\uD800", "\uDC00", "\uFFFD", "\uFEFF", "\u200B", "\u202E",
    )
    val rawCodepoint = Arb.int(0x20..0x10FFFF)
        .map { if (it in 0xD800..0xDFFF) "\uFFFD" else String(Character.toChars(it)) }
    val loneSurrogate = Arb.int(0xD800..0xDFFF).map { it.toChar().toString() }
    val piece = arbitrary { rs ->
        when (rs.random.nextInt(4)) {
            0 -> rawCodepoint.bind()
            1 -> loneSurrogate.bind()
            else -> fragments.bind()
        }
    }
    Arb.list(piece, 0..24).map { it.joinToString("") }
}
