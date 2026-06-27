package com.plainbase.domain.page

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * ProposalId — a DISTINCT identity type from [PageId] (so the two can never be confused at a call site), sharing
 * the canonical-text + 16-byte BLOB round-trip the storage adapter relies on.
 */
class ProposalIdTest : FunSpec({

    test("canonical v7 id parses and round-trips lowercase") {
        val text = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val id = ProposalId.of(text).shouldNotBeNull()
        id.value shouldBe text
        id.toString() shouldBe text
        ProposalId.require(text) shouldBe id
    }

    test("uppercase input parses to the same id and emits lowercase") {
        val lower = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        ProposalId.of(lower.uppercase()).shouldNotBeNull().value shouldBe lower
    }

    test("non-UUID text is null / require throws") {
        ProposalId.of("").shouldBeNull()
        ProposalId.of("not-a-uuid").shouldBeNull()
        shouldThrow<IllegalArgumentException> { ProposalId.require("nope") }
    }

    test("16-byte BLOB round-trip is the identity, msb||lsb big-endian") {
        val id = ProposalId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        val bytes = id.toByteArray()
        bytes.size shouldBe 16
        bytes[0] shouldBe 0x01.toByte()
        bytes[15] shouldBe 0x5a.toByte()
        ProposalId.fromByteArray(bytes) shouldBe id
    }

    test("fromByteArray rejects a non-16-byte input") {
        shouldThrow<IllegalArgumentException> { ProposalId.fromByteArray(ByteArray(15)) }
    }
})
