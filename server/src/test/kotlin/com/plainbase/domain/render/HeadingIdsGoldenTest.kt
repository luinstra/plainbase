package com.plainbase.domain.render

import com.plainbase.domain.content.Nfc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PB-SLUG-1 (§A1) golden test — every row of `golden/heading-ids.tsv` (1–25, incl. non-BMP rows
 * 23–24 and combining-mark row 25) must produce its frozen anchor id. Data-driven: rows sharing a
 * `group` are fed in document order through ONE [HeadingIdAllocator] so the dedup rows (1–4, the
 * two all-punctuation `section`/`section-1` headings) exercise the linear probe (step 7).
 *
 * Renderer-independent by construction: this test lives in `domain/` and imports ZERO flexmark
 * types — it feeds the §A1 "text content" column straight to the domain slugger/allocator. (The
 * contract-smoke spike, which DOES touch flexmark, separately proves the text-extraction column is
 * what flexmark yields.)
 */
class HeadingIdsGoldenTest : FunSpec({

    val rows = GoldenTsv.load("/golden/heading-ids.tsv")

    // 25 §A1 spec entries; row 18 ("a second all-punctuation heading -> section-1") is two physical
    // golden rows, plus the chunk-2 free-window step-6 stranded-mark row (`A!` + U+0301 -> `á`), so
    // the corpus has 27 data rows. Every spec entry is represented.
    test("the golden corpus is fully transcribed (27 data rows)") {
        rows.size shouldBe 27
    }

    // One allocator per group, preserving document order.
    val allocators = HashMap<String, HeadingIdAllocator>()

    rows.forEach { row ->
        val group = row[0]
        val text = row[2]
        val expected = Nfc.normalize(row[3]) // ids are always NFC; normalize the literal too
        test("[$group] \"$text\" -> $expected") {
            val allocator = allocators.getOrPut(group) { HeadingIdAllocator() }
            allocator.allocate(text) shouldBe expected
        }
    }
})
