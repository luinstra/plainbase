package com.plainbase.frameworks.ktor.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MarkdownNegotiationTest : FunSpec({
    data class Case(
        val name: String,
        val headers: List<String>,
        val json: PageRepresentation,
        val html: PageRepresentation,
    )

    listOf(
        Case("missing", emptyList(), PageRepresentation.JSON, PageRepresentation.HTML),
        Case("wildcard only", listOf("*/*"), PageRepresentation.JSON, PageRepresentation.HTML),
        Case(
            "text wildcard does not enable markdown",
            listOf("text/*, application/json;q=0.2"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case(
            "standalone text wildcard stays the default",
            listOf("text/*"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case("markdown alone", listOf("text/markdown"), PageRepresentation.MARKDOWN, PageRepresentation.MARKDOWN),
        Case(
            "markdown and default tie",
            listOf("text/markdown, application/json"),
            PageRepresentation.JSON,
            PageRepresentation.MARKDOWN,
        ),
        Case(
            "markdown beats lower quality default",
            listOf("text/markdown;q=0.8, application/json;q=0.2"),
            PageRepresentation.MARKDOWN,
            PageRepresentation.MARKDOWN,
        ),
        Case(
            "zero markdown cannot use a wildcard",
            listOf("text/markdown;q=0, */*;q=1"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case(
            "specific markdown beats a zero JSON default and broad wildcard",
            listOf("text/markdown;q=0.8, application/json;q=0, */*;q=1"),
            PageRepresentation.MARKDOWN,
            PageRepresentation.HTML,
        ),
        Case("unsupported markdown charset", listOf("text/markdown; charset=iso-8859-1"), PageRepresentation.JSON, PageRepresentation.HTML),
        Case(
            "unsupported markdown parameter is ineligible",
            listOf("text/markdown; profile=plain"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case("invalid quality is ineligible", listOf("text/markdown;q=wat"), PageRepresentation.JSON, PageRepresentation.HTML),
        Case("out-of-range quality is ineligible", listOf("text/markdown;q=1.1"), PageRepresentation.JSON, PageRepresentation.HTML),
        Case("excess precision is ineligible", listOf("text/markdown;q=0.1234"), PageRepresentation.JSON, PageRepresentation.HTML),
        Case(
            "uppercase quality name works",
            listOf("text/markdown;Q=0.8, application/json;q=0.2"),
            PageRepresentation.MARKDOWN,
            PageRepresentation.MARKDOWN,
        ),
        Case(
            "uppercase zero quality vetoes markdown",
            listOf("text/markdown;Q=0., */*;q=1"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case(
            "zero and one with empty decimal work",
            listOf("text/markdown;q=1."),
            PageRepresentation.MARKDOWN,
            PageRepresentation.MARKDOWN,
        ),
        Case(
            "mixed quality names duplicate is ineligible",
            listOf("text/markdown;q=0.8;Q=0.2"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case(
            "repeated fields are combined",
            listOf("text/markdown;q=0.8", "application/json;q=0.2"),
            PageRepresentation.MARKDOWN,
            PageRepresentation.MARKDOWN,
        ),
        Case(
            "identical ranges use first occurrence",
            listOf("text/markdown;q=0, text/markdown;q=1"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case(
            "specific zero quality beats text wildcard",
            listOf("text/markdown;q=0, text/*;q=1"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case(
            "UTF-8 parameter specificity beats an unparameterized range",
            listOf(
                "text/markdown; charset=UTF-8;q=0.2, text/markdown;q=0.9, application/json;q=0.5, " +
                    "text/html;q=0.5",
            ),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
        Case("markdown and wildcard tie favors default", listOf("text/markdown, */*"), PageRepresentation.JSON, PageRepresentation.HTML),
        Case(
            "quoted UTF-8 charset matches",
            listOf("text/markdown; charset=\"UTF-8\""),
            PageRepresentation.MARKDOWN,
            PageRepresentation.MARKDOWN,
        ),
        Case(
            "realistic browser accept stays HTML",
            listOf("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            PageRepresentation.JSON,
            PageRepresentation.HTML,
        ),
    ).forEach { case ->
        test("should resolve ${case.name}") {
            selectPageRepresentation(case.headers, PageDefaultRepresentation.JSON) shouldBe case.json
            selectPageRepresentation(case.headers, PageDefaultRepresentation.HTML) shouldBe case.html
        }
    }
})
