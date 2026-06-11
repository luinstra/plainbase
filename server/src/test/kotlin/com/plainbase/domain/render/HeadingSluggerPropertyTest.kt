package com.plainbase.domain.render

import com.plainbase.domain.content.Nfc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.merge
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * PB-SLUG-1 property tests (acceptance criterion 4): the slugger output is always NFC, never contains
 * U+0020, never empty, never splits a surrogate pair (the generator deliberately includes non-BMP
 * input); the allocator never emits a duplicate for any input sequence.
 *
 * Pure domain tests — zero flexmark imports.
 */
class HeadingSluggerPropertyTest : FunSpec({

    // A generator that mixes ASCII, whitespace/punctuation, non-BMP letters (surrogate pairs in
    // UTF-16), and combining marks — the categories PB-SLUG-1's steps must each handle. It now
    // deliberately exercises B1's White_Space boundary: U+0085 (NEL, White_Space the JDK predicates
    // miss) and a U+001C control (NOT White_Space but Character.isWhitespace returns true), plus a
    // ccc-220 mark (U+0323, below-attached) alongside U+0301 (ccc-230) so step 6's final NFC pass
    // sees a canonical-reordering seam.
    val spicyCodepoints: Arb<Codepoint> = Arb.of(
        // non-BMP letters (Lo) — surrogate pairs in UTF-16
        Codepoint(0x10437), // 𐐷 DESERET SMALL LETTER YEE
        Codepoint(0x2000B), // 𠀋 CJK Ext B
        Codepoint(0x1F680), // 🚀 emoji (symbol -> deleted)
        Codepoint(0x1F355), // 🍕 emoji
        // combining marks (Mn -> kept) — two different canonical combining classes
        Codepoint(0x0301), // COMBINING ACUTE (ccc 230, above)
        Codepoint(0x0303), // COMBINING TILDE (ccc 230, above)
        Codepoint(0x0323), // COMBINING DOT BELOW (ccc 220) — reordering seam vs the above-marks
        // whitespace / punctuation / structural
        Codepoint(' '.code),
        Codepoint('\t'.code),
        Codepoint('-'.code),
        Codepoint('_'.code),
        Codepoint('!'.code),
        Codepoint('#'.code),
        Codepoint(0x00A0), // NO-BREAK SPACE (White_Space)
        Codepoint(0x0085), // NEL — White_Space, but BOTH JDK predicates return false (B1)
        Codepoint(0x001C), // FILE SEPARATOR (Cc) — NOT White_Space, but isWhitespace returns true (B1)
        // some letters/digits
        Codepoint('A'.code),
        Codepoint('z'.code),
        Codepoint('5'.code),
        Codepoint(0x00E9), // é precomposed
    )

    // Merge in a broad Arb.codepoint so the invariants are exercised across the whole Unicode space,
    // not just the hand-picked alphabet (a full sweep over step 4's category classifier).
    val spicyStrings: Arb<String> =
        Arb.string(0..12, spicyCodepoints)
            .merge(Arb.string(0..16, Codepoint.az()))
            .merge(Arb.string(0..12, Codepoint.printableAscii()))
            .merge(Arb.string(0..10, Arb.codepoints()))

    test("slugify output is always NFC") {
        checkAll(spicyStrings) { input ->
            val slug = HeadingSlugger.headingId(input)
            Nfc.isNormalized(slug) shouldBe true
        }
    }

    test("slugify output never contains U+0020 SPACE") {
        checkAll(spicyStrings) { input ->
            HeadingSlugger.headingId(input) shouldNotContain " "
        }
    }

    test("slugify output is never empty (fallback guarantees a non-empty id)") {
        checkAll(spicyStrings) { input ->
            HeadingSlugger.headingId(input).isNotEmpty() shouldBe true
        }
    }

    test("slugify output contains only kept code points (no disallowed categories survive)") {
        checkAll(spicyStrings) { input ->
            val slug = HeadingSlugger.headingId(input)
            var i = 0
            while (i < slug.length) {
                val cp = slug.codePointAt(i)
                i += Character.charCount(cp)
                allowedInOutput(cp) shouldBe true
            }
        }
    }

    test("slugify never splits a surrogate pair (output is always valid UTF-16)") {
        checkAll(spicyStrings) { input ->
            val slug = HeadingSlugger.headingId(input)
            for (j in slug.indices) {
                val c = slug[j]
                when {
                    c.isHighSurrogate() ->
                        // must be followed by a low surrogate
                        (j + 1 < slug.length && slug[j + 1].isLowSurrogate()) shouldBe true
                    c.isLowSurrogate() ->
                        // must be preceded by a high surrogate
                        (j > 0 && slug[j - 1].isHighSurrogate()) shouldBe true
                }
            }
        }
    }

    test("allocator never emits a duplicate for any input sequence") {
        checkAll(Arb.list(spicyStrings, 0..20)) { sequence ->
            val allocator = HeadingIdAllocator()
            val emitted = sequence.map { allocator.allocate(it) }
            emitted.toSet().size shouldBe emitted.size
        }
    }

    // ---- B1: White_Space property pinned by data, not the JDK's tables --------------------------
    // Source-pinned with literal \u escapes (NOT golden bytes) so they cannot be lost to file-byte
    // mangling. They lock the two divergences the JDK predicates produced.

    test("U+0085 (NEL) is White_Space -> maps to space -> hyphen") {
        // Both Character.isWhitespace and isSpaceChar return false for U+0085; the data-pinned set
        // recognises it, so it becomes a hyphen between the letters rather than being deleted.
        HeadingSlugger.headingId("a\u0085b") shouldBe "a-b"
    }

    test("U+001C (FILE SEPARATOR) is NOT White_Space -> deleted, not hyphenated") {
        // Character.isWhitespace returns true for U+001C-U+001F, but they are Cc and NOT in the
        // Unicode White_Space property, so step 3 leaves them; step 4 deletes them (not in keep-set).
        HeadingSlugger.headingId("a\u001Cb") shouldBe "ab"
    }

    // ---- Step 6: NFD input -> NFC id, source-pinned (immune to file-byte NFC mangling) ----------

    test("an NFD heading slugs to an NFC id (re + U+0301 union -> NFC reunion)") {
        // \u escapes, not raw bytes: an editor cannot silently NFC-normalize these on save, so this
        // genuinely exercises the NFD->NFC path (the byte-fragility Fable flagged on golden row 19).
        HeadingSlugger.headingId("re\u0301union") shouldBe "r\u00E9union"
    }

    test("step-6 final NFC composes a deletion-stranded mark (A ! + U+0301 -> a-acute)") {
        HeadingSlugger.headingId("A!\u0301") shouldBe "\u00E1"
    }
})

/** Mirrors HeadingSlugger's keep-set, restricted to the post-transform alphabet (no SPACE survives). */
private fun allowedInOutput(cp: Int): Boolean {
    if (cp == '-'.code || cp == '_'.code) return true
    return when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        -> true

        else -> false
    }
}
