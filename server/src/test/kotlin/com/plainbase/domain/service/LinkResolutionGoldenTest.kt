package com.plainbase.domain.service

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.LinkOutcome.BrokenReason
import com.plainbase.domain.render.GoldenTsv
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PB-LINK-1 (§A2) golden test — every row of `golden/link-resolution.tsv` (1–34, incl. the `/docs`
 * URL column, decoding rows 25–29, and the chunk-2 free-window rows 30–34: B2 asset-query, B3
 * undecodable page fragment, B4 same-page anchor strict decode/re-encode + undecodable, B5
 * extensionless wrong-case page rescue) must produce its frozen outcome/URL against a stub index
 * containing the fixture path set ([FixtureIndexStub], which implements §A4 URL construction).
 *
 * Renderer-independent: lives in `domain/`-adjacent test code and imports ZERO flexmark types — the
 * raw link strings come straight from the spec table. The contract-smoke spike separately drives the
 * same rows to confirm the predictions.
 */
class LinkResolutionGoldenTest : FunSpec({

    val rows = GoldenTsv.load("/golden/link-resolution.tsv")
    val resolver = LinkResolver(FixtureIndexStub(Fixtures.demoDocs))

    test("the golden corpus is fully transcribed (34 rows)") {
        rows.size shouldBe 34
    }

    rows.forEach { row ->
        // A trailing empty field (row 24's empty link) collapses under split; pad to 5 fields.
        val padded = (row + List(5) { "" }).take(5)
        val rowNo = padded[0]
        val sourcePath = TreePath.require(padded[1])
        val link = padded[2]
        val expectedOutcome = padded[3]
        val expectedUrlOrHint = padded[4]

        test("row $rowNo: \"$link\" -> $expectedOutcome ${expectedUrlOrHint.ifEmpty { "" }}".trim()) {
            val outcome = resolver.resolve(sourcePath, link)
            assertOutcome(outcome, expectedOutcome, expectedUrlOrHint)
        }
    }
})

private fun assertOutcome(outcome: LinkOutcome, expectedClass: String, expectedUrlOrHint: String) {
    when (expectedClass) {
        "page" -> {
            check(outcome is LinkOutcome.Resolved.Page) { "expected a resolved page, got $outcome" }
            outcome.url shouldBe Nfc.normalize(expectedUrlOrHint)
        }
        "asset" -> {
            check(outcome is LinkOutcome.Resolved.Asset) { "expected a resolved asset, got $outcome" }
            outcome.url shouldBe expectedUrlOrHint
        }
        "external" -> {
            check(outcome is LinkOutcome.Resolved.External) { "expected an external, got $outcome" }
            outcome.url shouldBe expectedUrlOrHint
        }
        "anchor" -> {
            check(outcome is LinkOutcome.Resolved.Anchor) { "expected an anchor, got $outcome" }
            outcome.url shouldBe expectedUrlOrHint
        }
        else -> {
            check(outcome is LinkOutcome.Broken) { "expected broken($expectedClass), got $outcome" }
            outcome.reason shouldBe brokenReasonOf(expectedClass)
            // For case-mismatch the hint (candidate path) is carried in the golden but the frozen
            // surface is the CLASS; the candidate is reported by the link-checker, not the resolver
            // outcome — so we assert the class here and leave the hint as documentation.
        }
    }
}

private fun brokenReasonOf(wire: String): BrokenReason =
    BrokenReason.entries.firstOrNull { it.wireValue == wire }
        ?: error("unknown broken class in golden: $wire")
