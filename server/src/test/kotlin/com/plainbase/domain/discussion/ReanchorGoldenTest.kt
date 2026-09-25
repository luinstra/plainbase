package com.plainbase.domain.discussion

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.Heading
import com.plainbase.domain.principal.SubjectKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class ReanchorGoldenTest : FunSpec({
    test("crlf conversion against an lf quote is changed") {
        val before = "# Setup\n\nfirst line\nsecond line\n"
        val after = before.replace("\n", "\r\n")
        val anchor = quoteAnchor(before, "first line\nsecond line", listOf(Heading("setup", 1, "Setup", 0, 7)))
        val result = Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), listOf(Heading("setup", 1, "Setup"))))
        result shouldBe AnchorMatch.Changed(Placement.Heading("setup"))
    }

    test("adding a bom keeps the match exact") {
        val before = "Intro.\n\nKeep this sentence.\n"
        val anchor = quoteAnchor(before, "Keep this sentence.")
        val after = "\uFEFF$before"
        exactAt(Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())), captureStart(anchor) + 3, anchor)
    }

    test("a frontmatter only edit keeps the match exact") {
        val before = "---\ntitle: A\n---\nIntro.\n\nKeep this sentence.\n"
        val anchor = quoteAnchor(before, "Keep this sentence.")
        val after = before.replace("title: A", "title: A longer title")
        val delta = "title: A longer title".length - "title: A".length
        exactAt(Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())), captureStart(anchor) + delta, anchor)
    }

    test("adopt inserting an id line keeps the match exact") {
        val before = "---\ntitle: A\n---\nIntro.\n\nKeep this sentence.\n"
        val anchor = quoteAnchor(before, "Keep this sentence.")
        val after = "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: A\n---\nIntro.\n\nKeep this sentence.\n"
        val delta = after.indexOf("Intro.") - before.indexOf("Intro.")
        exactAt(Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())), captureStart(anchor) + delta, anchor)
    }

    test("adopt adding a frontmatter block keeps the match exact") {
        val before = "Intro.\n\nKeep this sentence.\n"
        val anchor = quoteAnchor(before, "Keep this sentence.")
        val after = "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\n---\n$before"
        val bodyStart = FrontmatterBlock.detect(after.encodeToByteArray()).bodyStart
        exactAt(Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())), captureStart(anchor) + bodyStart, anchor)
    }

    test("a context unique quote after an insertion is moved") {
        val before = "Build:\n\nRun make.\n\nDeploy:\n\nRun make.\n"
        val anchor = quoteAnchor(before, "Run make.", occurrence = 1)
        val insertion = "New intro paragraph.\n\n"
        // The clipped 28-byte prefix excludes the earlier occurrence.
        val result = Reanchor.match(anchor, ReanchorPage.of((insertion + before).encodeToByteArray(), emptyList()))
        movedAt(result, captureStart(anchor) + insertion.encodeToByteArray().size, anchor)
    }

    test("identical copies with identical context are ambiguous") {
        val unit = "x".repeat(78) + "\n\n" + "Run make.\n" + "x".repeat(78) + "\n\n"
        val before = unit + unit
        val anchor = quoteAnchor(before, "Run make.", occurrence = 1)
        val result = Reanchor.match(anchor, ReanchorPage.of(before.encodeToByteArray(), emptyList()))
        ambiguousAt(result, listOf(80, 250), 9)
    }

    test("offset never breaks an ambiguity tie") {
        val unit = "x".repeat(78) + "\n\n" + "Run make.\n" + "x".repeat(78) + "\n\n"
        val before = unit + unit
        val anchor = quoteAnchor(before, "Run make.", occurrence = 1)
        val after = "z".repeat(168) + "\n\n" + before
        val result = Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList()))
        ambiguousAt(result, listOf(250, 420), 9)
    }

    test("a substring of a longer paragraph is exact") {
        val before = "The quick brown fox jumps over the lazy dog.\n"
        val anchor = quoteAnchor(before, "brown fox")
        exactAt(Reanchor.match(anchor, ReanchorPage.of(before.encodeToByteArray(), emptyList())), captureStart(anchor), anchor)
    }

    test("a quote now spanning the frontmatter boundary is changed") {
        val before = "---\nKeep this line.\n"
        val anchor = quoteAnchor(before, "---\nKeep")
        val after = "---\nKeep this line.\n---\nTail.\n"
        Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())) shouldBe
            AnchorMatch.Changed(Placement.Line(1))
    }

    test("a page anchor is page level") {
        val anchor = Anchor.Page("sha256:${"0".repeat(64)}", null)
        Reanchor.match(anchor, ReanchorPage.of("page".encodeToByteArray(), emptyList())) shouldBe AnchorMatch.PageLevel
    }

    test("a reattachment matches its current text") {
        val before = "Old wording.\n"
        val after = "New wording.\n"
        val original = quoteAnchor(before, "Old wording.")
        val replacement = quoteAnchor(after, "New wording.")
        val reattachment = Reattachment(
            Actor(SubjectKey("human", "1"), "Alex"),
            Instant.parse("2026-09-24T12:00:00Z"),
            replacement,
        )
        val effective = effectiveAnchor(original, reattachment)
        exactAt(Reanchor.match(effective, ReanchorPage.of(after.encodeToByteArray(), emptyList())), captureStart(replacement), replacement)
    }

    test("the original anchor stays changed beside its reattachment") {
        val before = "Old wording.\n"
        val after = "New wording.\n"
        val original = quoteAnchor(before, "Old wording.")
        val replacement = quoteAnchor(after, "New wording.")
        val reattachment = Reattachment(
            Actor(SubjectKey("human", "1"), "Alex"),
            Instant.parse("2026-09-24T12:00:00Z"),
            replacement,
        )
        Reanchor.match(original, ReanchorPage.of(after.encodeToByteArray(), emptyList())) shouldBe
            AnchorMatch.Changed(Placement.Line(1))
    }

    test("broken context at the same offset is exact") {
        val before = "Alpha.\n\nTarget sentence.\n\nOmega.\n"
        val after = "Gamma.\n\nTarget sentence.\n\nDelta.\n"
        val anchor = quoteAnchor(before, "Target sentence.")
        exactAt(Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())), captureStart(anchor), anchor)
    }

    test("broken context elsewhere is moved") {
        val before = "Alpha.\n\nTarget sentence.\n\nOmega.\n"
        val after = "Gamma.\n\nNew line.\n\nTarget sentence.\n\nDelta.\n"
        val anchor = quoteAnchor(before, "Target sentence.")
        movedAt(Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())), 19, anchor)
    }

    test("broken context with two bare copies is ambiguous") {
        val before = "Alpha.\n\nTarget sentence.\n\nOmega.\n"
        val after = "Gamma.\n\nTarget sentence.\n\nTarget sentence.\n\nDelta.\n"
        val anchor = quoteAnchor(before, "Target sentence.")
        val result = Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList()))
        ambiguousAt(result, listOf(8, 26), 16)
    }

    test("a one byte quote with 5000 occurrences is ambiguous and truncated") {
        val capture = QuoteCapture("x", "", "", 0, 1, 0, 1, AnchorSelection.NARROWED, HeadingPath.EMPTY)
        val anchor = Anchor.Quote("sha256:${"0".repeat(64)}", null, capture)
        val result = Reanchor.match(anchor, ReanchorPage.of("x".repeat(5_000).encodeToByteArray(), emptyList()))
        result shouldBe AnchorMatch.Ambiguous(
            5_000,
            (0 until AnchorLimits.MAX_CANDIDATES).map { MatchRange(it, it + 1) },
            true,
        )
    }

    test("a crlf quote on a crlf page moves") {
        val before = "Build:\r\n\r\nRun make.\r\n\r\nDeploy:\r\n\r\nRun make.\r\n"
        val anchor = quoteAnchor(before, "Run make.", occurrence = 1)
        val insertion = "New intro paragraph.\r\n\r\n"
        movedAt(
            Reanchor.match(anchor, ReanchorPage.of((insertion + before).encodeToByteArray(), emptyList())),
            captureStart(anchor) + insertion.encodeToByteArray().size,
            anchor,
        )
    }

    test("a multibyte quote moves by the inserted byte count") {
        val before = "前文\n\n😀 café 漢字\n"
        val anchor = quoteAnchor(before, "café 漢字")
        val after = "é$before"
        movedAt(
            Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())),
            captureStart(anchor) + 2,
            anchor,
        )
    }

    test("decomposed text does not match a composed quote") {
        val before = "Visit café.\n"
        val anchor = quoteAnchor(before, "café")
        val after = "Visit cafe\u0301.\n"
        Reanchor.match(anchor, ReanchorPage.of(after.encodeToByteArray(), emptyList())) shouldBe
            AnchorMatch.Changed(Placement.Line(1))
    }

    test("a page turned non strict utf8 still matches bytewise") {
        val before = "The quick brown fox jumps over the lazy dog.\n"
        val anchor = quoteAnchor(before, "brown fox")
        val after = byteArrayOf(0xFF.toByte()) + before.encodeToByteArray()
        movedAt(Reanchor.match(anchor, ReanchorPage.of(after, emptyList())), captureStart(anchor) + 1, anchor)
    }

    test("context filtered quote a over a run of a is ambiguous") {
        val capture = QuoteCapture(
            "a", "a".repeat(64), "a".repeat(64), 64, 65, 0, 1, AnchorSelection.NARROWED, HeadingPath.EMPTY,
        )
        val anchor = Anchor.Quote("sha256:${"0".repeat(64)}", null, capture)
        val result = Reanchor.match(anchor, ReanchorPage.of("a".repeat(1_048_576).encodeToByteArray(), emptyList()))
        result shouldBe AnchorMatch.Ambiguous(
            1_048_448,
            (64 until 84).map { MatchRange(it, it + 1) },
            true,
        )
    }

    test("a capture with a lone surrogate is rejected") {
        fun capture(quote: String = "q", prefix: String = "", suffix: String = "") = QuoteCapture(
            quote, prefix, suffix, 0, 1, 0, 1, AnchorSelection.NARROWED, HeadingPath.EMPTY,
        )
        shouldThrow<IllegalArgumentException> { capture(quote = "\uD800") }
        shouldThrow<IllegalArgumentException> { capture(prefix = "\uD800") }
        shouldThrow<IllegalArgumentException> { capture(suffix = "\uD800") }
    }
})

private fun quoteAnchor(
    page: String,
    quote: String,
    headings: List<Heading> = emptyList(),
    occurrence: Int = 0,
): Anchor.Quote {
    var charStart = -1
    var from = 0
    repeat(occurrence + 1) {
        charStart = page.indexOf(quote, from)
        require(charStart >= 0)
        from = charStart + quote.length
    }
    val start = page.substring(0, charStart).encodeToByteArray().size
    val end = start + quote.encodeToByteArray().size
    val capture = QuoteCapture.at(page.encodeToByteArray(), start, end, headings, AnchorSelection.NARROWED)
    return Anchor.Quote("sha256:${"0".repeat(64)}", "main", capture)
}

private fun captureStart(anchor: Anchor.Quote): Int = anchor.capture.byteStart.toInt()

private fun exactAt(result: AnchorMatch, start: Int, anchor: Anchor.Quote) {
    result shouldBe AnchorMatch.Exact(MatchRange(start, start + anchor.capture.quote.encodeToByteArray().size))
}

private fun movedAt(result: AnchorMatch, start: Int, anchor: Anchor.Quote) {
    result shouldBe AnchorMatch.Moved(MatchRange(start, start + anchor.capture.quote.encodeToByteArray().size))
}

private fun ambiguousAt(result: AnchorMatch, starts: List<Int>, quoteLength: Int) {
    result shouldBe AnchorMatch.Ambiguous(starts.size, starts.map { MatchRange(it, it + quoteLength) }, false)
}
