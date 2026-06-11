package com.plainbase.domain.content

import com.plainbase.domain.content.PercentCoding.DecodeError
import com.plainbase.domain.content.PercentCoding.DecodeResult
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Frozen decode-once rules (PB-LINK-1 §A2 step 2) pinned at the path-semantics layer.
 * Rows 25-29 of the PB-LINK-1 golden table have their decode-LEVEL expectations here;
 * full resolution outcomes are re-verified in chunk 2.
 *
 * Pure logic — migrated to Kotest (JVM-only, NOT @Tag("native")).
 *
 * Non-ASCII expectations use \u escapes rather than raw literals: a source-file string
 * literal cannot be relied on to stay un-normalized on disk (editors silently NFC it),
 * which would defeat the NFD-vs-NFC ordering assertion below. Likewise the NFD-side INPUT
 * is fed as %CC%81 (the UTF-8 of U+0301), which is plain ASCII on disk and therefore immune
 * to editor normalization — that is what makes the decode-then-NFC ordering test robust.
 */
class PercentCodingTest : FunSpec({

    fun decoded(input: String): String = when (val r = PercentCoding.decodeOnce(input)) {
        is DecodeResult.Success -> r.value
        is DecodeResult.Failure -> fail("expected success, got failure: ${r.error} for '$input'")
    }

    fun failure(input: String): DecodeError = when (val r = PercentCoding.decodeOnce(input)) {
        is DecodeResult.Success -> fail("expected failure, got success: '${r.value}' for '$input'")
        is DecodeResult.Failure -> r.error
    }

    // ---- decode-once / double-encoding ----

    test("decode happens exactly once - double-encoded dot stays literal") {
        // %252e: %25 -> '%', then literal '2e' => the three characters "%2e", NOT re-decoded to '.'.
        decoded("%252e") shouldBe "%2e"
    }

    test("row 26 - double-encoded traversal decodes to a literal directory name, never re-decoded") {
        // %252e%252e/secret.md -> literal "%2e%2e/secret.md"; cannot become "../secret.md".
        decoded("%252e%252e/secret.md") shouldBe "%2e%2e/secret.md"
    }

    test("row 27 - single-encoded traversal decodes to literal dot-dot segments") {
        // %2e%2e/%2e%2e/outside.txt -> "../../outside.txt" (containment is ContentRoot's job).
        decoded("%2e%2e/%2e%2e/outside.txt") shouldBe "../../outside.txt"
    }

    // ---- encoded slash rejection (row 25) ----

    test("row 25 - encoded slash uppercase is rejected") {
        failure("setup%2Fextra.md") shouldBe DecodeError.ENCODED_SLASH
    }

    test("encoded slash lowercase is rejected") {
        failure("setup%2fextra.md") shouldBe DecodeError.ENCODED_SLASH
    }

    // ---- invalid UTF-8 (row 29) ----

    test("row 29 - lone high byte is invalid strict UTF-8") {
        // caf%E9-notes.md: 0xE9 is a lone continuation-expecting lead byte -> invalid.
        failure("caf%E9-notes.md") shouldBe DecodeError.INVALID_UTF8
    }

    test("valid two-byte UTF-8 decodes") {
        // The correct encoding of U+00E9 'e-acute' is %C3%A9.
        decoded("caf%C3%A9") shouldBe "caf\u00e9"
    }

    test("truncated multibyte sequence is invalid UTF-8") {
        // %C3 with no continuation byte.
        failure("%C3") shouldBe DecodeError.INVALID_UTF8
    }

    // ---- malformed escapes ----

    test("non-hex escape is malformed") {
        failure("%G1") shouldBe DecodeError.MALFORMED_ESCAPE
    }

    test("trailing percent is malformed") {
        failure("abc%") shouldBe DecodeError.MALFORMED_ESCAPE
    }

    test("one-char-after-percent is malformed") {
        failure("%A") shouldBe DecodeError.MALFORMED_ESCAPE
    }

    // ---- no plus-to-space (URLDecoder divergence) ----

    test("plus is a literal byte, never a space") {
        decoded("a+b") shouldBe "a+b"
    }

    test("encoded space decodes to a space") {
        // row 7: "release%20notes%202026.md"
        decoded("release%20notes%202026.md") shouldBe "release notes 2026.md"
    }

    // ---- fragment-as-data (row 28, decode level) ----

    test("row 28 - encoded hash decodes to a literal hash in the path") {
        // The resolver splits the fragment on the RAW '#' before decoding; an ENCODED %23
        // therefore stays path data. At the decode layer, %23 -> '#'.
        decoded("setup.md%23prerequisites") shouldBe "setup.md#prerequisites"
    }

    // ---- decode-then-NFC ordering ----

    test("decode strictly precedes NFC normalization") {
        // %CC%81 is the UTF-8 encoding of U+0301 COMBINING ACUTE. Decoding "re%CC%81union"
        // yields NFD code points (r, e, U+0301, ...); only AFTER decoding does NFC fold
        // "e + U+0301" into precomposed U+00E9.
        val nfd = "re\u0301union" // r, e, U+0301 COMBINING ACUTE — decomposed
        val nfc = "r\u00e9union" // r, U+00E9 LATIN SMALL E WITH ACUTE — precomposed

        val decodedNfd = decoded("re%CC%81union")
        decodedNfd shouldBe nfd // still NFD right after decode (decode does NOT normalize)
        Nfc.normalize(decodedNfd) shouldBe nfc // NFC applied as a distinct second step
        // Proving the ORDER matters: NFC-ing the encoded string first leaves %CC%81 untouched
        // (it is plain ASCII), so normalization-before-decode could never fold the accent.
        Nfc.normalize("re%CC%81union") shouldBe "re%CC%81union"
    }

    // ---- encode round-trip ----

    test("encodeSegment percent-encodes unicode and reserved bytes") {
        PercentCoding.encodeSegment("r\u00e9union") shouldBe "r%C3%A9union"
    }

    test("encodeSegment leaves unreserved characters alone") {
        PercentCoding.encodeSegment("a-b_c.d~e") shouldBe "a-b_c.d~e"
    }

    test("encodeSegment encodes a slash") {
        PercentCoding.encodeSegment("a/b") shouldBe "a%2Fb"
    }

    test("encodePath preserves separators and encodes segments") {
        PercentCoding.encodePath("notes/r\u00e9union") shouldBe "notes/r%C3%A9union"
    }

    test("encode then decode round-trips a unicode segment") {
        val encoded = PercentCoding.encodeSegment("r\u00e9union")
        decoded(encoded) shouldBe "r\u00e9union"
    }

    // ---- supplementary (astral) characters: a surrogate pair must survive intact ----
    // U+1F355 SLICE OF PIZZA, written as its explicit UTF-16 surrogate pair (\ud83c\udf55, UTF-8
    // F0 9F 8D 95) so no editor can re-encode it. A per-Char decoder would split the pair into two
    // lone surrogates, each mangled to U+FFFD.

    test("a raw supplementary character decodes intact - surrogate pair is not split") {
        decoded("a\ud83c\udf55b") shouldBe "a\ud83c\udf55b"
    }

    test("percent-encoded and raw forms of a supplementary character decode identically") {
        decoded("%F0%9F%8D%95") shouldBe "\ud83c\udf55"
        decoded("\ud83c\udf55") shouldBe "\ud83c\udf55"
    }

    test("encodeSegment encodes a supplementary character as its four UTF-8 bytes") {
        PercentCoding.encodeSegment("\ud83c\udf55") shouldBe "%F0%9F%8D%95"
    }

    test("encode then decode round-trips a supplementary character") {
        decoded(PercentCoding.encodeSegment("\ud83c\udf55")) shouldBe "\ud83c\udf55"
    }

    test("an unpaired surrogate in literal input is rejected as invalid UTF-8") {
        // A lone high surrogate (no following low surrogate) is not valid Unicode; strict encoding
        // rejects it rather than silently emitting U+FFFD \u2014 symmetric with %-escaped strict decode.
        failure("a\ud83cb") shouldBe DecodeError.INVALID_UTF8
    }
})
