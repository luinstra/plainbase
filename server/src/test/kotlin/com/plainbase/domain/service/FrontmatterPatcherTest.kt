package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * PB-PATCH-1 behavior tests beyond the byte-pair goldens: the case-10 / case-11 refusals that need a
 * tuned bound or an injected corrupt output, the rule-naming message requirement, and the property
 * that every `Patched` output is a pure single-point insertion satisfying the post-extraction
 * invariant (§A3 byte-fidelity guarantee + acceptance criterion).
 */
class FrontmatterPatcherTest : FunSpec({

    val patcher = FrontmatterPatcher()
    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    test("case 10 — a block over the sanity bound is Refused(block_too_large)") {
        // A tiny bound makes the case testable without a giant committed fixture.
        val tightPatcher = FrontmatterPatcher(maxBlockBytes = 8)
        val input = "---\ntitle: a long enough value\n---\nbody\n".toByteArray()
        val result = tightPatcher.patch(input, id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Refused>()
        result.reason shouldBe FrontmatterPatcher.RefusalReason.BLOCK_TOO_LARGE
    }

    test("case 10 — a block over the DEFAULT 64 KiB bound is Refused(block_too_large)") {
        // Exercise the default-constructed patcher's own bound (no committed >64 KiB fixture): build an
        // in-memory block whose inner region exceeds DEFAULT_MAX_BLOCK_BYTES with a single long value.
        val oversized = "x".repeat(64 * 1024 + 1)
        val input = "---\ntitle: $oversized\n---\nbody\n".toByteArray()
        val result = patcher.patch(input, id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Refused>()
        result.reason shouldBe FrontmatterPatcher.RefusalReason.BLOCK_TOO_LARGE
    }

    test("case 9 — the refusal message names the violated rule (§A3 actionable output)") {
        val input = "---\n'quoted': value\n---\nbody\n".toByteArray()
        val result = patcher.patch(input, id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Refused>()
        result.reason shouldBe FrontmatterPatcher.RefusalReason.NOT_A_MAPPING
        result.message shouldContain "plain unquoted scalars"
    }

    test("case 11 — an injected faulty insertion is caught by the post-extraction backstop") {
        // The "injected faulty insertion" the spec mandates: a correct patcher never produces an
        // output that fails the post-check, so we hand postCheckHolds a deliberately damaged output.
        val input = "---\ntitle: x\n---\nbody\n".toByteArray()

        // (a) the inserted id text does not match -> fail.
        val wrongId = "---\nid: ffffffff-ffff-7fff-bfff-ffffffffffff\ntitle: x\n---\nbody\n".toByteArray()
        patcher.postCheckHolds(input, wrongId, id) shouldBe false

        // (b) the body was mutated -> fail.
        val mutatedBody = "---\nid: ${id.value}\ntitle: x\n---\nMUTATED body\n".toByteArray()
        patcher.postCheckHolds(input, mutatedBody, id) shouldBe false

        // (c) the block was destroyed -> fail.
        patcher.postCheckHolds(input, "no frontmatter at all\n".toByteArray(), id) shouldBe false

        // The honest correct output passes the same backstop.
        val good = "---\nid: ${id.value}\ntitle: x\n---\nbody\n".toByteArray()
        patcher.postCheckHolds(input, good, id) shouldBe true
    }

    test("idempotence — patch(patch(x)) inserts exactly once") {
        val input = "---\ntitle: x\n---\nbody\n".toByteArray()
        val once = patcher.patch(input, id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Patched>()
        // Re-patching the already-id'd output is a no-op.
        patcher.patch(once.bytes, id) shouldBe FrontmatterPatcher.PatchResult.AlreadyPresent
    }

    test("property — every Patched output is a pure single-point insertion satisfying the post-check") {
        // Generate accepted blocks: a simple-key line + an arbitrary body, never carrying an id key.
        checkAll(200, Arb.string(0, 20), Arb.string(0, 40)) { keyVal, body ->
            // Exclude "id" so the generated key never collides with the id key (which would flip the
            // expected result to AlreadyPresent and spuriously fail the Patched assertion below).
            val safeKey = keyVal.filter { it.isLetterOrDigit() }.ifEmpty { "title" }.let { if (it == "id") "title" else it }
            val safeBody = body.filter { it != '-' && it != '\r' }
            val input = "---\n$safeKey: value\n---\n$safeBody".toByteArray()

            val r = patcher.patch(input, id).shouldBeInstanceOf<FrontmatterPatcher.PatchResult.Patched>()
            // Single-point insertion: there is exactly one offset k where the output equals the input
            // with the id line spliced in. Locate it by the unique-prefix/suffix split and confirm the
            // removed slice is exactly the id line — i.e. output minus the insertion equals the input.
            val output = r.bytes
            val k = commonPrefixLength(input, output)
            val tail = output.size - input.size // == insertion length
            output.copyOfRange(0, k).toList() shouldBe input.copyOfRange(0, k).toList()
            output.copyOfRange(k + tail, output.size).toList() shouldBe input.copyOfRange(k, input.size).toList()
            String(output.copyOfRange(k, k + tail)) shouldContain id.value
            // The post-extraction invariant holds on every accepted output (§A3 backstop).
            patcher.postCheckHolds(input, output, id) shouldBe true
        }
    }

    // ---- readIdValue — the case-5 grammar exposed for adoption (chunk 4b) ------------------------

    test("readIdValue reads the column-0 id key's raw value") {
        patcher.readIdValue("---\nid: ${id.value}\ntitle: x\n---\nbody\n".toByteArray()) shouldBe id.value
        // Raw means raw: validity is the 4a resolver's concern, not the reader's.
        patcher.readIdValue("---\nid: not-a-uuid\n---\n".toByteArray()) shouldBe "not-a-uuid"
        patcher.readIdValue("---\r\nid: ${id.value}\r\n---\r\n".toByteArray()) shouldBe id.value
    }

    test("readIdValue never honors id:value (a plain scalar no YAML parser reads an id from)") {
        patcher.readIdValue("---\nid:${id.value}\n---\nbody\n".toByteArray()) shouldBe null
    }

    test("readIdValue is null without a block, without an id key, or on a non-UTF-8 block") {
        patcher.readIdValue("just a body\n".toByteArray()) shouldBe null
        patcher.readIdValue("---\ntitle: x\n---\nbody\n".toByteArray()) shouldBe null
        patcher.readIdValue("---\nno closer".toByteArray()) shouldBe null
        val invalidUtf8 = "---\n".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '\n'.code.toByte()) +
            "---\n".toByteArray()
        patcher.readIdValue(invalidUtf8) shouldBe null
    }
})

/** The length of the longest common byte prefix of [a] and [b] — the insertion offset `k`. */
private fun commonPrefixLength(a: ByteArray, b: ByteArray): Int {
    var i = 0
    while (i < a.size && i < b.size && a[i] == b[i]) i++
    return i
}
