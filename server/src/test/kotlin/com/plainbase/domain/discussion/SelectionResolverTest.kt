package com.plainbase.domain.discussion

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.render.SourceBlock
import com.plainbase.domain.render.SourceBlockKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class SelectionResolverTest : FunSpec({
    test("spa unique selection narrows") {
        val raw = "bar\n\nfoo bar\n".encodeToByteArray()
        val blocks = listOf(
            SourceBlock(0, 4, SourceBlockKind.PARAGRAPH),
            SourceBlock(5, 13, SourceBlockKind.PARAGRAPH),
        )
        val result = SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Spa(5, 13, "bar"))
        val capture = result.capture()
        capture.selection shouldBe AnchorSelection.NARROWED
        capture.byteStart shouldBe 9
        capture.byteEnd shouldBe 12
    }

    test("spa overlapping duplicate snaps") {
        val raw = "banana\n".encodeToByteArray()
        val result = SelectionResolver.resolve(
            raw,
            listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH)),
            emptyList(),
            SelectionRequest.Spa(0, raw.size.toLong(), "ana"),
        )
        result.capture().selection shouldBe AnchorSelection.SNAPPED
        result.capture().quote shouldBe "banana\n"
    }

    test("spa markup selection snaps") {
        val raw = "**bold** text\n".encodeToByteArray()
        val result = SelectionResolver.resolve(
            raw,
            listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH)),
            emptyList(),
            SelectionRequest.Spa(0, raw.size.toLong(), "bold text"),
        )
        result.capture().selection shouldBe AnchorSelection.SNAPPED
        result.capture().quote shouldBe "**bold** text\n"
    }

    test("spa verbatim cross block selection narrows") {
        val raw = "P1\n\nP2\n".encodeToByteArray()
        val blocks = listOf(
            SourceBlock(0, 3, SourceBlockKind.PARAGRAPH),
            SourceBlock(4, 7, SourceBlockKind.PARAGRAPH),
        )
        val result = SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Spa(0, 7, "P1\n\nP2"))
        result.capture().selection shouldBe AnchorSelection.NARROWED
        result.capture().byteStart shouldBe 0
        result.capture().byteEnd shouldBe 6
    }

    test("spa cross block selection with another separator snaps") {
        val raw = "P1\n\nP2\n".encodeToByteArray()
        val blocks = listOf(
            SourceBlock(0, 3, SourceBlockKind.PARAGRAPH),
            SourceBlock(4, 7, SourceBlockKind.PARAGRAPH),
        )
        val result = SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Spa(0, 7, "P1\nP2"))
        result.capture().selection shouldBe AnchorSelection.SNAPPED
        result.capture().byteStart shouldBe 0
        result.capture().byteEnd shouldBe 7
    }

    test("spa non boundary offsets are invalid") {
        val raw = "bar\n\nfoo bar\n".encodeToByteArray()
        val blocks = listOf(
            SourceBlock(0, 4, SourceBlockKind.PARAGRAPH),
            SourceBlock(5, 13, SourceBlockKind.PARAGRAPH),
        )
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Spa(1, 13, "foo"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Spa(5, 4, "foo"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Spa(99_999_999_999, 13, "foo"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("spa stale block past eof is invalid") {
        val raw = "page\n".encodeToByteArray()
        val block = SourceBlock(raw.size, raw.size + 1, SourceBlockKind.PARAGRAPH)
        SelectionResolver.resolve(raw, listOf(block), emptyList(), SelectionRequest.Spa(raw.size.toLong(), (raw.size + 1).toLong(), "x"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("spa stale block inside frontmatter is invalid") {
        val raw = "---\ntitle: New\n---\nbody\n".encodeToByteArray()
        val staleBlock = SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH)

        SelectionResolver.resolve(
            raw,
            listOf(staleBlock),
            emptyList(),
            SelectionRequest.Spa(0, raw.size.toLong(), "not present"),
        ).shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("spa shared container range is accepted") {
        val raw = "  code\n".encodeToByteArray()
        val shared = SourceBlock(0, raw.size, SourceBlockKind.LIST_ITEM)
        val child = SourceBlock(0, raw.size, SourceBlockKind.CODE)
        val result = SelectionResolver.resolve(
            raw,
            listOf(shared, child),
            emptyList(),
            SelectionRequest.Spa(0, raw.size.toLong(), "code"),
        )
        result.capture().selection shouldBe AnchorSelection.NARROWED
        result.capture().quote shouldBe "code"
    }

    test("spa oversized snap is refused") {
        val raw = "x".repeat(16_385).encodeToByteArray()
        val result = SelectionResolver.resolve(
            raw,
            listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH)),
            emptyList(),
            SelectionRequest.Spa(0, raw.size.toLong(), "missing"),
        )
        result.shouldRefuse(SelectionRefusal.ANCHOR_TOO_LARGE)
    }

    test("spa narrowed selection at the limit narrows") {
        val selected = "x" + "a".repeat(16_382) + "y"
        val raw = ("z".repeat(100) + selected + "z".repeat(100)).encodeToByteArray()
        val result = SelectionResolver.resolve(
            raw,
            listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH)),
            emptyList(),
            SelectionRequest.Spa(0, raw.size.toLong(), selected),
        )
        result.capture().quote shouldBe selected
        result.capture().selection shouldBe AnchorSelection.NARROWED
    }

    test("spa empty selection snaps") {
        val raw = "Intro.\n\n---\n".encodeToByteArray()
        val result = SelectionResolver.resolve(
            raw,
            listOf(SourceBlock(8, 11, SourceBlockKind.THEMATIC_BREAK)),
            emptyList(),
            SelectionRequest.Spa(8, 11, ""),
        )
        result.capture().selection shouldBe AnchorSelection.SNAPPED
        result.capture().quote shouldBe "---"
    }

    test("agent empty selection is invalid") {
        val raw = "body".encodeToByteArray()
        val blocks = listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH))
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent(""))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("agent quote counts") {
        val raw = "banana".encodeToByteArray()
        val blocks = listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH))
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("missing"))
            .shouldRefuse(SelectionRefusal.ANCHOR_NOT_FOUND)
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("a"))
            .shouldRefuse(SelectionRefusal.ANCHOR_NOT_UNIQUE)
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("ana"))
            .shouldRefuse(SelectionRefusal.ANCHOR_NOT_UNIQUE)
        val unique = SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("ban"))
        unique.capture().selection shouldBe AnchorSelection.NARROWED
        unique.capture().quote shouldBe "ban"
    }

    test("agent oversized selection is refused") {
        val selected = "q".repeat(16_385)
        val raw = selected.encodeToByteArray()
        val blocks = listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH))
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent(selected))
            .shouldRefuse(SelectionRefusal.ANCHOR_TOO_LARGE)
    }

    test("agent never searches frontmatter") {
        val raw = "---\nref: target\n---\ntarget\n".encodeToByteArray()
        val bodyStart = FrontmatterBlock.detect(raw).bodyStart
        val blocks = listOf(SourceBlock(bodyStart, raw.size, SourceBlockKind.PARAGRAPH))
        SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("ref: target"))
            .shouldRefuse(SelectionRefusal.ANCHOR_NOT_FOUND)
        val result = SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("target"))
        result.capture().byteStart shouldBe raw.decodeToString().lastIndexOf("target").toLong()
    }

    test("no blocks refuses both forms on a non strict body") {
        val raw = byteArrayOf(0xFF.toByte())
        SelectionResolver.resolve(raw, emptyList(), emptyList(), SelectionRequest.Agent("x"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
        SelectionResolver.resolve(raw, emptyList(), emptyList(), SelectionRequest.Spa(0, 1, "x"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("a strict blockless page accepts an agent quote") {
        val raw = "[id]: /target\n".encodeToByteArray()
        val result = SelectionResolver.resolve(raw, emptyList(), emptyList(), SelectionRequest.Agent("target"))
        result.capture().quote shouldBe "target"
        result.capture().selection shouldBe AnchorSelection.NARROWED
    }

    test("a strict blockless page still refuses a spa quote") {
        val raw = "[id]: /target\n".encodeToByteArray()
        SelectionResolver.resolve(raw, emptyList(), emptyList(), SelectionRequest.Spa(0, 1, "id"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("lone surrogate selection is refused") {
        val raw = "a ? b \uFFFD c\n".encodeToByteArray()
        val block = SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH)
        SelectionResolver.resolve(raw, listOf(block), emptyList(), SelectionRequest.Agent("\uD800"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
        SelectionResolver.resolve(raw, listOf(block), emptyList(), SelectionRequest.Spa(0, raw.size.toLong(), "\uD800"))
            .shouldRefuse(SelectionRefusal.INVALID_ANCHOR)
    }

    test("prefix never includes frontmatter") {
        val raw = "---\nt: 1\n---\nAll.".encodeToByteArray()
        val start = FrontmatterBlock.detect(raw).bodyStart
        val capture = QuoteCapture.at(raw, start, raw.size, emptyList(), AnchorSelection.NARROWED)
        capture.prefix shouldBe ""
    }

    test("context bounds clip at the body start and at eof") {
        val raw = "---\nt: 1\n---\nAll.".encodeToByteArray()
        val start = FrontmatterBlock.detect(raw).bodyStart
        val capture = QuoteCapture.at(raw, start, raw.size, emptyList(), AnchorSelection.NARROWED)
        capture.prefix shouldBe ""
        capture.suffix shouldBe ""
        capture.quote shouldBe "All."
    }

    test("context clipping keeps whole characters") {
        val alphabet = listOf("a", "é", "漢", "😀", "\n")
        val frontmatter = "---\ntitle: Context\n---\n".encodeToByteArray()
        for (seed in 0 until 1_000) {
            val random = Random(seed)
            val tokens = List(random.nextInt(1, 301)) { alphabet[random.nextInt(alphabet.size)] }
            val page = tokens.joinToString("")
            val body = page.encodeToByteArray()
            val raw = frontmatter + body
            val bodyStart = FrontmatterBlock.detect(raw).bodyStart
            val startToken = random.nextInt(tokens.size)
            val endToken = random.nextInt(startToken + 1, tokens.size + 1)
            val start = bodyStart + tokens.take(startToken).joinToString("").encodeToByteArray().size
            val end = bodyStart + tokens.take(endToken).joinToString("").encodeToByteArray().size
            val capture = QuoteCapture.at(raw, start, end, emptyList(), AnchorSelection.NARROWED)
            val prefix = capture.prefix.encodeToByteArray()
            val quote = capture.quote.encodeToByteArray()
            val suffix = capture.suffix.encodeToByteArray()
            val prefixStart = start - prefix.size
            val suffixEnd = end + suffix.size
            prefix.decodeToString(throwOnInvalidSequence = true) shouldBe capture.prefix
            suffix.decodeToString(throwOnInvalidSequence = true) shouldBe capture.suffix
            raw.copyOfRange(prefixStart, start).contentEquals(prefix) shouldBe true
            raw.copyOfRange(end, suffixEnd).contentEquals(suffix) shouldBe true
            prefix.size shouldBeLessThanOrEqual AnchorLimits.MAX_CONTEXT_BYTES
            suffix.size shouldBeLessThanOrEqual AnchorLimits.MAX_CONTEXT_BYTES
            if (prefixStart > bodyStart) {
                val previousCharacterSize = previousUtf8CharacterLength(raw, prefixStart, bodyStart)
                (
                    prefix.size + previousCharacterSize > AnchorLimits.MAX_CONTEXT_BYTES ||
                    prefixStart - previousCharacterSize < bodyStart
                    ) shouldBe true
            }
            if (suffixEnd < raw.size) {
                val nextCharacterSize = utf8CharacterLength(raw[suffixEnd])
                (suffix.size + nextCharacterSize > AnchorLimits.MAX_CONTEXT_BYTES || suffixEnd + nextCharacterSize > raw.size) shouldBe true
            }
            raw.copyOfRange(start, end).contentEquals(quote) shouldBe true
            quote.size shouldBe end - start
        }
    }
})

private fun previousUtf8CharacterLength(raw: ByteArray, end: Int, lowerBound: Int): Int =
    (1..4).first { length ->
        val start = end - length
        start >= lowerBound && utf8CharacterLengthOrNull(raw[start]) == length &&
            raw.decodeToString(startIndex = start, endIndex = end, throwOnInvalidSequence = true).isNotEmpty()
    }

private fun utf8CharacterLength(leadByte: Byte): Int = requireNotNull(utf8CharacterLengthOrNull(leadByte)) {
    "invalid UTF-8 leading byte: ${leadByte.toInt() and 0xFF}"
}

private fun utf8CharacterLengthOrNull(leadByte: Byte): Int? = when (val byte = leadByte.toInt() and 0xFF) {
    in 0x00..0x7F -> 1
    in 0xC2..0xDF -> 2
    in 0xE0..0xEF -> 3
    in 0xF0..0xF4 -> 4
    else -> null
}

private fun SelectionResult.capture(): QuoteCapture = when (this) {
    is SelectionResult.Resolved -> capture
    is SelectionResult.Refused -> error("expected a resolved quote, got ${refusal.code}")
}

private fun SelectionResult.shouldRefuse(expected: SelectionRefusal) {
    this shouldBe SelectionResult.Refused(expected)
}
