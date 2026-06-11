package com.plainbase.domain.render

import com.plainbase.domain.content.Nfc

/**
 * PB-SLUG-1 (§A1) — the frozen heading-ID algorithm, steps 1–6 (the per-page dedup, step 7,
 * lives in [HeadingIdAllocator]).
 *
 * The slugger maps a heading's *text content* to a candidate anchor id. Steps 1–6 are also reused
 * verbatim as the segment slugifier for canonical-URL construction (§A4) — same code, with caller-
 * chosen empty-result fallbacks ([slugify] takes the fallback as a parameter; [headingId] hard-codes
 * the `section` fallback for headings).
 *
 * **Iteration unit (frozen):** every transform operates on Unicode **code points**
 * ([String.codePoints]) — never UTF-16 [Char]s — so surrogate pairs are never split and step 4's
 * category classification applies to the whole code point.
 *
 * Pure domain code: imports only chunk 1.5's [Nfc] (the single NFC call site). No framework or
 * Markdown-library type appears here.
 */
object HeadingSlugger {

    /** The frozen empty-result fallback for HEADING ids (§A1 step 6). */
    const val HEADING_FALLBACK: String = "section"

    /** The frozen empty-result fallback for page slugs (§A4). */
    const val PAGE_FALLBACK: String = "page"

    /** The frozen empty-result fallback for directory segments (§A4). */
    const val FOLDER_FALLBACK: String = "folder"

    /**
     * Runs PB-SLUG-1 steps 1–6 over [text], returning the candidate id (NO dedup — that is the
     * allocator's job). On an empty result, returns [HEADING_FALLBACK] (`section`).
     */
    fun headingId(text: String): String = slugify(text, HEADING_FALLBACK)

    /**
     * PB-SLUG-1 steps 1–6 as the shared slugifier (also §A4's URL-segment slugifier). [emptyFallback]
     * is returned when the transform yields an empty string (`section` for headings, `page`/`folder`
     * for URLs). Output is always NFC and never contains U+0020; the algorithm and the final-NFC proof
     * live in §A1.
     */
    fun slugify(text: String, emptyFallback: String): String {
        val slug = buildString {
            Nfc.normalize(text.lowercase()).codePoints().forEach { cp ->
                when {
                    isWhiteSpace(cp) -> append('-') // White_Space folds straight to a hyphen (no collapsing)
                    isKept(cp) -> appendCodePoint(cp)
                }
            }
        }
        // Final NFC: deletion can strand a combining mark that must recompose; keeps ids NFC (see A1).
        return Nfc.normalize(slug.ifEmpty { emptyFallback })
    }

    /**
     * Step 3 predicate: the Unicode `White_Space` property, **pinned by data, not by the JDK's
     * tables** (§A1's "behavior pinned by data" stance). Neither [Character.isWhitespace] nor
     * [Character.isSpaceChar] matches the property exactly:
     *  - U+0085 (NEL) IS White_Space but BOTH JDK predicates return false — they would delete it;
     *  - U+001C–U+001F (Cc) are NOT White_Space but [Character.isWhitespace] returns true — it would
     *    map them to space, then hyphenate them, where the spec deletes them (not in step 4's keep-set).
     * The explicit frozen set below is the Unicode `White_Space=Yes` code points and nothing else.
     */
    private fun isWhiteSpace(cp: Int): Boolean = cp in WHITE_SPACE

    /**
     * The frozen Unicode `White_Space` property set (Unicode `PropList.txt`): the 25 code points
     * `White_Space=Yes`. Data-pinned so JDK Unicode-table drift can never move the boundary.
     */
    private val WHITE_SPACE: Set<Int> =
        setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680) +
            (0x2000..0x200A) +
            setOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)

    /**
     * Step 4 keep-set: Unicode general category Letter (Lu/Ll/Lt/Lm/Lo), Mark (Mn/Mc/Me), Decimal
     * Number (Nd), or HYPHEN-MINUS / LOW LINE. (Mirrors Ruby `[[:word:]]` = `\p{L}\p{M}\p{Nd}\p{Pc}`
     * plus hyphen — GitHub's effective keep-set, but `\p{Pc}` is narrowed here to exactly LOW LINE
     * per the frozen spec text.) SPACE is in the spec keep-set but unreachable: White_Space folds to
     * a hyphen before this predicate runs.
     */
    private fun isKept(cp: Int): Boolean {
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
}

/**
 * PB-SLUG-1 step 7 — per-page dedup via linear probe. Stateful, single-page-scoped: construct one
 * [HeadingIdAllocator] per page and feed it heading text in document order. All heading levels h1–h6
 * share one namespace per page.
 *
 * The probe is provably collision-free: each emitted id is recorded; a clashing candidate gets
 * `{candidate}-1`, `{candidate}-2`, … until an unused id is found (and the probed suffix is itself
 * recorded). Deterministic and order-dependent.
 */
class HeadingIdAllocator {

    private val used = HashSet<String>()

    /** Slugs [text] via [HeadingSlugger.headingId], then linear-probes to a unique id for this page. */
    fun allocate(text: String): String = allocateCandidate(HeadingSlugger.headingId(text))

    /** Linear-probes an already-slugged [candidate] to a unique id for this page, recording the winner. */
    fun allocateCandidate(candidate: String): String {
        if (used.add(candidate)) return candidate
        var n = 1
        while (true) {
            val probe = "$candidate-$n"
            if (used.add(probe)) return probe
            n++
        }
    }
}
