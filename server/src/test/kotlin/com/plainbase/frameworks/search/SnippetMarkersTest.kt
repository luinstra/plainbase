package com.plainbase.frameworks.search

import com.plainbase.domain.search.Highlight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The sentinel→A3-offset conversion in isolation: the well-formed path, the R7 defensive path
 * (unbalanced markers — impossible from FTS5 over C0-stripped text, still never a 500), and the
 * surrogate snap on marker positions FTS5 itself would never emit (belt and braces — the
 * invariant is frozen tier-3, the conversion enforces it unconditionally).
 */
class SnippetMarkersTest : FunSpec({

    fun parse(marked: String) = SnippetMarkers.toHighlights(marked)

    test("balanced sentinels become half-open UTF-16 ranges over the cleaned text") {
        val (text, highlights) = parse("…a \u0001rolling\u0002 \u0001deploy\u0002 z…")
        text shouldBe "…a rolling deploy z…"
        highlights shouldBe listOf(Highlight(3, 10), Highlight(11, 17))
        highlights.map { text.substring(it.start, it.end) } shouldBe listOf("rolling", "deploy")
    }

    test("R7 defensive: stray markers are stripped, unpaired ranges dropped, text intact") {
        parse("a\u0002b\u0001c") shouldBe ("abc" to emptyList<Highlight>())
        parse("\u0001\u0002empty span dropped") shouldBe ("empty span dropped" to emptyList<Highlight>())
    }

    test("a range end falling between surrogates snaps OUTWARD to the code-point boundary") {
        val squid = "🦑"
        val (text, highlights) = parse("a\u0001b${squid[0]}\u0002${squid[1]}c")
        text shouldBe "ab${squid}c"
        highlights shouldBe listOf(Highlight(1, 4)) // end 3 would split the pair; snapped to 4
    }

    test("a range start falling between surrogates snaps OUTWARD too") {
        val squid = "🦑"
        val (text, highlights) = parse("a${squid[0]}\u0001${squid[1]}b\u0002c")
        text shouldBe "a${squid}bc"
        highlights shouldBe listOf(Highlight(1, 4)) // start 2 would split the pair; snapped to 1
    }
})
