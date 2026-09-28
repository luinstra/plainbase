package com.plainbase.domain.discussion

import com.plainbase.domain.page.Heading
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlacementTest : FunSpec({
    val oldPath = HeadingPath(listOf(HeadingPath.Entry(1, "A"), HeadingPath.Entry(2, "Notes")))

    test("a heading path matching twice falls back to the line") {
        val headings = listOf(
            Heading("a", 1, "A"),
            Heading("notes", 2, "Notes"),
            Heading("a-1", 1, "A"),
            Heading("notes-1", 2, "Notes"),
        )
        val page = ReanchorPage.of("# A\n\n## Notes\n\nNew.\n\n# A\n\n## Notes\n\nOther.\n".encodeToByteArray(), headings)
        Placement.of(oldPath, 5, page) shouldBe Placement.Line(5)
    }

    test("a heading path matching once places at the heading") {
        val headings = listOf(Heading("a", 1, "A"), Heading("notes", 2, "Notes"))
        val page = ReanchorPage.of("# A\n\n## Notes\n\nNew text.\n".encodeToByteArray(), headings)
        Placement.of(oldPath, 5, page) shouldBe Placement.Heading("notes")
    }

    test("placement walks headings only on the changed arm") {
        var reads = 0
        val headings = object : AbstractList<Heading>() {
            override val size: Int = 10_000

            override fun get(index: Int): Heading {
                reads++
                return when (index) {
                    size - 2 -> Heading("a", 1, "A")
                    size - 1 -> Heading("notes", 2, "Notes")
                    else -> Heading("section-$index", 1, "Section $index")
                }
            }
        }
        val raw = "# A\n\n## Notes\n\nquote\n".encodeToByteArray()
        val start = raw.decodeToString().indexOf("quote")
        val anchor = Anchor.Quote(
            "sha256:${"0".repeat(64)}",
            null,
            QuoteCapture(
                "quote",
                "",
                "",
                start.toLong(),
                (start + 5).toLong(),
                0,
                4,
                AnchorSelection.NARROWED,
                oldPath,
            ),
        )

        Reanchor.match(anchor, ReanchorPage.of(raw, headings)) shouldBe AnchorMatch.Exact(MatchRange(start, start + 5))
        reads shouldBe 0

        val changedPage = ReanchorPage.of("# A\n\n## Notes\n\nnew text\n".encodeToByteArray(), headings)
        Reanchor.match(anchor, changedPage) shouldBe AnchorMatch.Changed(Placement.Heading("notes"))
        reads shouldBe 10_000
    }

    test("a line past eof is clamped") {
        val page = ReanchorPage.of("Short.\n".encodeToByteArray(), emptyList())
        Placement.of(HeadingPath.EMPTY, 9, page) shouldBe Placement.Line(1)
    }

    test("line numbers follow the eol grammar") {
        SourceLines.lineOf("a\r\nb".encodeToByteArray(), 3) shouldBe 2
        SourceLines.lineOf("a\rb".encodeToByteArray(), 2) shouldBe 2
        SourceLines.lineOf("a\nb".encodeToByteArray(), 2) shouldBe 2
        SourceLines.lineOf("a\r\nb".encodeToByteArray(), 2) shouldBe 1
        SourceLines.lineOf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte()), 3) shouldBe 1
        SourceLines.lineCount(byteArrayOf()) shouldBe 1
        SourceLines.lineCount("a\n".encodeToByteArray()) shouldBe 1
        SourceLines.lineCount("a\nb".encodeToByteArray()) shouldBe 2
        SourceLines.lineCount("a\r\n".encodeToByteArray()) shouldBe 1
    }

    test("an empty heading path never places at a heading") {
        val headings = listOf(Heading("a", 1, "A"))
        val page = ReanchorPage.of("# A\n\nText.\n".encodeToByteArray(), headings)
        Placement.of(HeadingPath.EMPTY, 4, page) shouldBe Placement.Line(3)
    }
})
