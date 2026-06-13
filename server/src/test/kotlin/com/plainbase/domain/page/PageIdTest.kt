package com.plainbase.domain.page

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * PageId — the spec-owned canonical-shape gate (§A4), version-agnostic by owner ruling, and the
 * 16-byte BLOB round-trip the chunk-4b storage adapter relies on.
 *
 * The shape gate is OUR spec, not the JDK's: `UUID.fromString` leniently accepts `1-1-1-1-1`, which
 * must never leak into the 400-vs-404 boundary or the frontmatter `id` validity test.
 */
class PageIdTest : FunSpec({

    test("canonical-shape v7 id parses and round-trips lowercase") {
        val text = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val id = PageId.of(text).shouldNotBeNull()
        id.value shouldBe text
        id.toString() shouldBe text
    }

    test("uppercase input parses to the same id and emits lowercase (§A4)") {
        val lower = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val upper = lower.uppercase()
        val id = PageId.of(upper).shouldNotBeNull()
        id.value shouldBe lower
        id shouldBe PageId.require(lower)
    }

    test("a well-formed v4 id is accepted as valid identity (version-agnostic, owner ruling)") {
        val v4 = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val id = PageId.of(v4).shouldNotBeNull()
        // The version nibble is 4 — accepted regardless; uniqueness is the contract, version is provenance.
        id.value[14] shouldBe '4'
    }

    test("a value UUID.fromString accepts but the canonical shape rejects is treated as absent (§A4)") {
        // `1-1-1-1-1` is JDK-lenient-valid but NOT canonical 8-4-4-4-12 — our gate rejects it.
        UUID.fromString("1-1-1-1-1") // proves the JDK leniency exists
        PageId.of("1-1-1-1-1").shouldBeNull()
    }

    test("non-canonical shapes are rejected") {
        PageId.of("").shouldBeNull()
        PageId.of("not-a-uuid").shouldBeNull()
        PageId.of("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5").shouldBeNull() // 11-char tail
        PageId.of("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5az").shouldBeNull() // trailing junk
        PageId.of(" 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a").shouldBeNull() // leading space
    }

    test("16-byte BLOB round-trip is the identity, msb||lsb big-endian") {
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        val bytes = id.toByteArray()
        bytes.size shouldBe 16
        // First 8 bytes are the most-significant bits big-endian, last 8 the least-significant.
        bytes[0] shouldBe 0x01.toByte()
        bytes[1] shouldBe 0x97.toByte()
        bytes[15] shouldBe 0x5a.toByte()
        PageId.fromByteArray(bytes) shouldBe id
    }

    test("fromByteArray rejects a non-16-byte input") {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            PageId.fromByteArray(ByteArray(15))
        }
    }
})
