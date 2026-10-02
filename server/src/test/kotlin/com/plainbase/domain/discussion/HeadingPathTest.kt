package com.plainbase.domain.discussion

import com.plainbase.domain.page.Heading
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class HeadingPathTest : FunSpec({
    val raw = "# A\n\n## B\n\n### C\n\ntext\n\n## D\n\nmore\n"
    val headings = listOf(
        Heading("a", 1, "A", 0, 3),
        Heading("b", 2, "B", 5, 9),
        Heading("c", 3, "C", 11, 16),
        Heading("d", 2, "D", 24, 28),
    )

    test("nested headings form the path") {
        HeadingPath.capture(headings, raw.indexOf("text")) shouldBe HeadingPath(
            listOf(HeadingPath.Entry(1, "A"), HeadingPath.Entry(2, "B"), HeadingPath.Entry(3, "C")),
        )
    }

    test("a sibling heading pops deeper levels") {
        HeadingPath.capture(headings, raw.indexOf("more")) shouldBe HeadingPath(
            listOf(HeadingPath.Entry(1, "A"), HeadingPath.Entry(2, "D")),
        )
    }

    test("a quote starting on a heading includes it") {
        HeadingPath.capture(headings, headings[1].byteStart ?: -1) shouldBe HeadingPath(
            listOf(HeadingPath.Entry(1, "A"), HeadingPath.Entry(2, "B")),
        )
    }

    test("a heading after the quote is excluded") {
        HeadingPath.capture(headings, 3) shouldBe HeadingPath(listOf(HeadingPath.Entry(1, "A")))
    }

    test("duplicate heading texts keep distinct ids") {
        val duplicates = listOf(Heading("a", 1, "A"), Heading("a-1", 1, "A"))
        val paths = HeadingPath.pathsOf(duplicates)
        paths.map { it.second } shouldContainExactly listOf("a", "a-1")
        paths[0].first shouldBe paths[1].first
    }

    test("heading path levels are valid") {
        shouldThrow<IllegalArgumentException> { HeadingPath.Entry(0, "A") }
        shouldThrow<IllegalArgumentException> { HeadingPath.Entry(7, "A") }
    }
})
