package com.plainbase.domain.content

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Acceptance: NFC idempotence and NFD→NFC mapping.
 *
 * Pure logic — migrated to Kotest (JVM-only, NOT @Tag("native")).
 *
 * The NFD-side input is built with a \u escape, never a raw literal: an editor (or a code
 * formatter) can silently NFC a source-file literal on save, which would collapse the NFD-vs-NFC
 * distinction this suite exists to assert. "re" + U+0301 COMBINING ACUTE is the decomposed form;
 * U+00E9 'é' is the precomposed (NFC) form.
 */
class NfcTest : FunSpec({

    // "réunion" in NFD: r + e + U+0301 (combining acute) + union…
    val nfd = "re\u0301union"

    // "réunion" precomposed (NFC): U+00E9 = é
    val nfc = "r\u00e9union"

    test("NFD maps to NFC") {
        Nfc.normalize(nfd) shouldBe nfc
    }

    test("normalize is idempotent") {
        val once = Nfc.normalize(nfd)
        val twice = Nfc.normalize(once)
        twice shouldBe once
        twice shouldBe nfc
    }

    test("already-NFC text is unchanged") {
        Nfc.normalize(nfc) shouldBe nfc
    }

    test("isNormalized distinguishes NFC from NFD") {
        Nfc.isNormalized(nfc) shouldBe true
        Nfc.isNormalized(nfd) shouldBe false
    }

    test("combining mark with no precomposed form is NFC-stable") {
        // q + U+0303 COMBINING TILDE — no precomposed form exists (PB-SLUG-1 row 25).
        val qTilde = "q\u0303"
        Nfc.normalize(qTilde) shouldBe qTilde
        Nfc.isNormalized(qTilde) shouldBe true
    }

    test("ascii is untouched") {
        Nfc.normalize("guides/setup") shouldBe "guides/setup"
    }
})
