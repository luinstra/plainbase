package com.plainbase.domain.page

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Byte-level unit tests for the shared §A3 frontmatter-block detector — the SINGLE grammar the
 * renderer (chunk 3) and the surgical patcher (chunk 4a) both use. These pin the detection rules
 * independent of flexmark: the patcher's byte-fidelity guarantee depends on exact offsets, so the
 * grammar is verified here against raw bytes, not through the renderer's M2 bridging.
 *
 * Acceptances frozen at chunk level (the renderer's agreement test rides on the same rules).
 */
class FrontmatterBlockTest : FunSpec({

    fun detect(s: String) = FrontmatterBlock.detect(s.toByteArray())

    test("valid block: opener, body region, and `---` closer are located by byte offset") {
        val source = "---\ntitle: x\n---\nbody\n"
        val d = detect(source).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        d.bomLength shouldBe 0
        // inner region is exactly `title: x\n`
        source.substring(d.innerStart, d.innerEnd) shouldBe "title: x\n"
        // body begins right after the closing `---\n`
        source.substring(d.bodyStart) shouldBe "body\n"
    }

    test("`...` closer is accepted (§A3)") {
        val source = "---\ntitle: x\n...\nbody\n"
        val d = detect(source).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        source.substring(d.bodyStart) shouldBe "body\n"
    }

    test("trailing-space opener (`--- `) is NOT a frontmatter opener") {
        val d = detect("--- \ntitle: x\n---\nbody\n").shouldBeInstanceOf<FrontmatterBlock.Detection.Absent>()
        // The whole input (after a zero-length BOM) is body.
        d.bodyStart shouldBe 0
    }

    test("no closing delimiter ⇒ no frontmatter (the `---` is a thematic break)") {
        val d = detect("---\ntitle: x\nbody with no close\n").shouldBeInstanceOf<FrontmatterBlock.Detection.Absent>()
        d.bodyStart shouldBe 0
    }

    test("BOM-prefixed opener is detected after byte 3") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val d = FrontmatterBlock.detect(bom + "---\ntitle: x\n---\nbody\n".toByteArray())
            .shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        d.bomLength shouldBe 3
        // body offset accounts for the BOM + block.
        String(bom + "---\ntitle: x\n---\nbody\n".toByteArray(), d.bodyStart, "body\n".length) shouldBe "body\n"
    }

    test("bare `---` at EOF (no terminator) is not an opener") {
        detect("---").shouldBeInstanceOf<FrontmatterBlock.Detection.Absent>()
    }

    test("empty block (`---` immediately followed by `---`) is a present, empty block") {
        val source = "---\n---\nbody\n"
        val d = detect(source).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        d.innerStart shouldBe d.innerEnd // empty inner region
        source.substring(d.bodyStart) shouldBe "body\n"
    }

    test("CRLF terminators are handled (opener and closer recognized)") {
        val source = "---\r\ntitle: x\r\n---\r\nbody\r\n"
        val d = detect(source).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        source.substring(d.bodyStart) shouldBe "body\r\n"
    }

    // ---- Patcher-critical EOF edges (chunk 4a inherits this grammar; the patcher's byte-fidelity
    // guarantee turns on these exact offsets) ------------------------------------------------------

    test("empty file (0 bytes) is Absent with bodyStart 0") {
        val d = FrontmatterBlock.detect(ByteArray(0)).shouldBeInstanceOf<FrontmatterBlock.Detection.Absent>()
        d.bomLength shouldBe 0
        d.bodyStart shouldBe 0
    }

    test("only-frontmatter, closer at EOF with NO trailing newline → present, bodyStart == size") {
        // `---\na: b\n---` — the closer is the final line and there is no terminator after it, so the
        // body is empty and bodyStart sits exactly at the end of input (the patcher relies on this).
        val source = "---\na: b\n---"
        val bytes = source.toByteArray()
        val d = FrontmatterBlock.detect(bytes).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        source.substring(d.innerStart, d.innerEnd) shouldBe "a: b\n"
        d.bodyStart shouldBe bytes.size
        source.substring(d.bodyStart) shouldBe ""
    }

    test("lone-CR line endings: opener and closer recognized, body after the CR closer") {
        val source = "---\ra: b\r---\rbody\r"
        val d = detect(source).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
        source.substring(d.innerStart, d.innerEnd) shouldBe "a: b\r"
        source.substring(d.bodyStart) shouldBe "body\r"
    }
})
