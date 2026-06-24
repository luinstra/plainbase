package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PB-DIFF-1 FOREVER golden corpus — the six CANONICAL [unifiedDiff] cases, frozen EXACTLY (the §WI-2 format rules
 * 1-6: 3-line context, explicit `,<n>` counts, elided file headers, `0,0` empty side, empty-string no-op). The diff
 * string IS part of the frozen PB-PROPOSE-1 wire (`get_change.unified_diff`), so this is registered under
 * `ForeverApiGoldenSuite`. The byte-pair inputs (`.base`/`.proposed`) recompute the diff at test time and compare to
 * the frozen `.diff` (the PB-PATCH-1 byte-pair idiom).
 *
 * NEVER-CHANGE: the diff algorithm output + the format rules froze when P1a landed. A diff-algo or format change is
 * a CONTRACT change, not a fix. The benign-rare adversarial tail (CRLF/BOM/no-final-newline/non-UTF8) is frozen by
 * INVARIANTS in [UnifiedDiffInvariantTest], NOT exact goldens (the freeze-surface policy).
 */
class UnifiedDiffGoldenTest : FunSpec({

    val cases = listOf(
        "pure-insert",
        "pure-delete",
        "single-line-change",
        "multi-hunk",
        "empty-base",
        "content-to-empty",
    )

    for (name in cases) {
        test("PB-DIFF-1 canonical: $name renders the frozen unified diff") {
            val base = readBytes("$name.base")
            val proposed = readBytes("$name.proposed")
            val expected = readText("$name.diff")
            unifiedDiff(base, proposed) shouldBe expected
        }
    }

    test("PB-DIFF-1: a byte-identical base/proposed pair is the EMPTY STRING (rule 6)") {
        val bytes = "a\nb\nc\n".toByteArray()
        unifiedDiff(bytes, bytes.copyOf()) shouldBe ""
    }
})

private fun readBytes(name: String): ByteArray =
    checkNotNull(UnifiedDiffGoldenTest::class.java.getResourceAsStream("/golden/diff/$name")) { "missing golden: $name" }
        .use { it.readBytes() }

private fun readText(name: String): String = readBytes(name).toString(Charsets.UTF_8)
