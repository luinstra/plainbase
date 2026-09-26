package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

class DiscussionCodecTest : FunSpec({
    test("marker goldens encode exactly") {
        DiscussionCodec.encodeDiscussion(pageRecord()).decodeToString() shouldBe PAGE
        DiscussionCodec.encodeDiscussion(quoteRecord()).decodeToString() shouldBe QUOTE
    }

    test("comment goldens encode exactly") {
        DiscussionCodec.encodeComment(commentRecord()).decodeToString() shouldBe COMMENT
        DiscussionCodec.encodeComment(retractedCommentRecord()).decodeToString() shouldBe RETRACTED_COMMENT
    }

    test("reattachment quote fields encode and decode completely") {
        val replacement = Anchor.Quote(
            "sha256:${"b".repeat(64)}",
            null,
            QuoteCapture(
                "Different.\n", "before", "after", 22, 33, 2, 9, AnchorSelection.NARROWED,
                HeadingPath(listOf(HeadingPath.Entry(3, "Replacement"))),
            ),
        )
        val expected = quoteRecord().copy(reattachment = Reattachment(actor("u-2", "Bo"), Instant.parse(T1), replacement))
        val bytes = DiscussionCodec.encodeDiscussion(expected)
        bytes.decodeToString().contains("reattach_content_hash: \"sha256:${"b".repeat(64)}\"") shouldBe true
        ok(DiscussionCodec.decodeDiscussion(bytes)) shouldBe expected
    }

    test("canonical bytes survive decode and encode unchanged") {
        listOf(PAGE, QUOTE, COMMENT, RETRACTED_COMMENT).forEach { text ->
            val bytes = text.toByteArray()
            val reencoded = when {
                text.contains("plainbase-comment") -> DiscussionCodec.encodeComment(ok(DiscussionCodec.decodeComment(bytes)))
                else -> DiscussionCodec.encodeDiscussion(ok(DiscussionCodec.decodeDiscussion(bytes)))
            }
            reencoded.decodeToString() shouldBe text
        }
    }

    test("decoded documents survive re-encoding") {
        DiscussionCodecSeedGenerator.generate().forEach { generated ->
            if (generated.isComment) {
                val first = ok(DiscussionCodec.decodeComment(generated.bytes))
                val encoded = DiscussionCodec.encodeComment(first)
                ok(DiscussionCodec.decodeComment(encoded)) shouldBe first
            } else {
                val first = ok(DiscussionCodec.decodeDiscussion(generated.bytes))
                val encoded = DiscussionCodec.encodeDiscussion(first)
                ok(DiscussionCodec.decodeDiscussion(encoded)) shouldBe first
            }
        }
    }

    test("round-trip seeds cover leading extras, quote variants, groups, and integers") {
        val seeds = DiscussionCodecSeedGenerator.generate()
        val discussions = seeds.filterNot { it.isComment }
            .map { ok(DiscussionCodec.decodeDiscussion(it.bytes)) }
        val comments = seeds.filter { it.isComment }
            .map { ok(DiscussionCodec.decodeComment(it.bytes)) }
        val quoteAnchors = discussions.mapNotNull { it.anchor as? Anchor.Quote }
        val reattachedAnchors = discussions.mapNotNull { it.reattachment?.anchor }

        assertSoftly {
            discussions.map { it.extras.leadingLength }.distinct().size shouldBeGreaterThan 1
            discussions.map { it.status }.toSet() shouldBe setOf(DiscussionStatus.OPEN, DiscussionStatus.RESOLVED)
            discussions.any { it.statusChange != null } shouldBe true
            discussions.any { it.anchor is Anchor.Page } shouldBe true
            quoteAnchors.any { it.capture.headingPath.entries.isEmpty() } shouldBe true
            quoteAnchors.any { it.capture.headingPath.entries.isNotEmpty() } shouldBe true
            quoteAnchors.map { it.capture.byteStart }.distinct().size shouldBeGreaterThan 1
            discussions.any { it.reattachment != null } shouldBe true
            reattachedAnchors.any { it.capture.headingPath.entries.isEmpty() } shouldBe true
            reattachedAnchors.any { it.capture.headingPath.entries.isNotEmpty() } shouldBe true
            comments.any { it.editedAt != null } shouldBe true
            comments.any { it.retraction != null } shouldBe true
        }
    }

    test("a leading continuation line is kept before format") {
        val record = pageRecord().copy(extras = FrontmatterExtras("  lead: 1\n", emptyList()))
        val encoded = DiscussionCodec.encodeDiscussion(record).decodeToString()
        encoded shouldBe PAGE.replace("---\nformat:", "---\n  lead: 1\nformat:")
        ok(DiscussionCodec.decodeDiscussion(encoded.toByteArray())) shouldBe record
    }

    test("all unknown blocks share one extras string and retain their blank lines") {
        val input = PAGE.replace("format:", "  lead: 1\nformat:")
            .replace("id: \"$D\"", "id: \"$D\"\nx_future: \"kept\"\nx_list:\n  - \"a\"")
            .replace("anchor_content_hash:", "# note\nx_note: |\n  one\n\n  two\nanchor_content_hash:")
        val decoded = ok(DiscussionCodec.decodeDiscussion(input.toByteArray()))
        decoded.extras.leading shouldBe "  lead: 1\n"
        decoded.extras.trailing shouldBe "x_future: \"kept\"\nx_list:\n  - \"a\"\n# note\nx_note: |\n  one\n\n  two\n"
    }

    test("a cap sized file without a final newline re-encodes one byte over") {
        val throughKeys = PAGE.substringBeforeLast("---\n")
        val paddingPrefix = "x_pad: \""
        val close = "---"
        val paddingSize = MAX_MARKER_BYTES - throughKeys.toByteArray().size - paddingPrefix.toByteArray().size - 2 - close.length
        val raw = (throughKeys + paddingPrefix + "a".repeat(paddingSize) + "\"\n" + close).toByteArray()
        raw.size shouldBe MAX_MARKER_BYTES
        val decoded = ok(DiscussionCodec.decodeDiscussion(raw))
        DiscussionCodec.encodeDiscussion(decoded).size shouldBe MAX_MARKER_BYTES + 1
    }

    test("crlf frontmatter decodes and rewrites as lf") {
        val input = PAGE.replace("\n", "\r\n")
        val decoded = ok(DiscussionCodec.decodeDiscussion(input.toByteArray()))
        DiscussionCodec.encodeDiscussion(decoded).decodeToString() shouldBe PAGE
    }

    test("a closer at eof without a newline decodes") {
        ok(DiscussionCodec.decodeDiscussion(PAGE.removeSuffix("\n").toByteArray())) shouldBe pageRecord()
    }

    test("a lone cr is bad value") {
        expectBad(PAGE.replace("status: \"open\"\n", "status: \"open\"\r").toByteArray())
    }

    test("status closed is bad value") { expectBad(PAGE.replace("status: \"open\"", "status: \"closed\"").toByteArray()) }
    test("anchor kind x is bad value") { expectBad(PAGE.replace("anchor_kind: \"page\"", "anchor_kind: \"x\"").toByteArray()) }
    test("unsupported discussion format is bad value") {
        expectBad(PAGE.replace("plainbase-discussion/1", "plainbase-discussion/2").toByteArray())
    }
    test("swapped format is bad value") {
        expectBad(PAGE.replace("plainbase-discussion/1", "plainbase-comment/1").toByteArray())
    }
    test("uppercase discussion id is bad value") {
        val id = "019aaaaa-0000-7000-8000-000000000001"
        expectBad(PAGE.replace(D, id).replace(id, id.uppercase()).toByteArray())
    }
    test("uppercase page id is bad value") { expectBad(PAGE.replace(P, P.uppercase()).toByteArray()) }
    test("decomposed page path is bad value") {
        expectBad(PAGE.replace("guides/setup.md", "guide\u0301s/setup.md").toByteArray())
    }
    test("created without milliseconds is bad value") {
        expectBad(PAGE.replace(T0, "2026-09-23T10:00:00Z").toByteArray())
    }
    test("created with numeric offset is bad value") {
        expectBad(PAGE.replace(T0, "2026-09-23T10:00:00.000+00:00").toByteArray())
    }
    test("created with hour twenty four is bad value") {
        expectBad(PAGE.replace(T0, "2026-09-23T24:00:00.000Z").toByteArray())
    }
    test("created with leap second is bad value") {
        expectBad(PAGE.replace(T0, "2026-09-23T10:00:60.000Z").toByteArray())
    }
    test("uppercase hash is bad value") {
        expectBad(PAGE.replace(HASH, "sha256:" + "a".repeat(63) + "A").toByteArray())
    }
    test("heading level seven is bad value") {
        expectBad(QUOTE.replace("1 Setup", "7 Setup").toByteArray())
    }
    test("heading without a separating space is bad value") {
        expectBad(QUOTE.replace("1 Setup", "1Setup").toByteArray())
    }
    test("partial status group is bad value") {
        expectBad(PAGE.replace("anchor_kind:", "status_changed_by_id: \"u-2\"\nanchor_kind:").toByteArray())
    }
    test("complete status and retraction groups report a blank actor id") {
        unreadable(
            DiscussionCodec.decodeDiscussion(
                QUOTE.replace("status_changed_by_id: \"u-1\"", "status_changed_by_id: \"\"").toByteArray(),
            ),
        )
            .detail shouldBe "status actor id is blank"
        unreadable(
            DiscussionCodec.decodeComment(
                RETRACTED_COMMENT.replace("retracted_by_id: \"local\"", "retracted_by_id: \"\"").toByteArray(),
            ),
        ).detail shouldBe "retraction actor id is blank"
    }
    test("reattachment group missing its time is bad value") {
        val record = quoteRecord().copy(
            reattachment = Reattachment(actor("u-2", "Bo"), Instant.parse(T1), quoteRecord().anchor as Anchor.Quote),
        )
        val text = DiscussionCodec.encodeDiscussion(record).decodeToString()
        expectBad(text.replace("reattached_at: \"$T1\"\n", "").toByteArray())
    }
    test("page kind with quote data is bad value") {
        expectBad(PAGE.replace("anchor_content_hash:", "anchor_quote: \"x\"\nanchor_content_hash:").toByteArray())
    }
    test("quote kind missing anchor line is bad value") {
        expectBad(QUOTE.replace("anchor_line: 3\n", "").toByteArray())
    }
    test("surrogate escape is bad value") {
        expectBad(PAGE.replace("started_by_label: \"Ada\"", "started_by_label: \"\\uD800\"").toByteArray())
    }
    test("raw unicode line separator is bad value") {
        expectBad(
            QUOTE.replace("Run make.", "Run\u2028make.")
                .replace("anchor_byte_end: 18", "anchor_byte_end: 20")
                .toByteArray(),
        )
    }
    test("raw tab is bad value") {
        expectBad(PAGE.replace("started_by_label: \"Ada\"", "started_by_label: \"A\tda\"").toByteArray())
    }
    test("integer with a leading zero is bad value") { expectBad(QUOTE.replace("anchor_line: 3", "anchor_line: 007").toByteArray()) }
    test("integer over ten digits is bad value") { expectBad(QUOTE.replace("anchor_line: 3", "anchor_line: 12345678901").toByteArray()) }
    test("two spaces after a known key are bad value") {
        expectBad(PAGE.replace("status: \"open\"", "status:  \"open\"").toByteArray())
    }
    test("empty scalar is bad value") { expectBad(PAGE.replace("status: \"open\"", "status: ").toByteArray()) }
    test("missing scalar separator is bad value") { expectBad(PAGE.replace("id: \"$D\"", "id:\"$D\"").toByteArray()) }
    test("trailing text after a scalar is bad value") {
        expectBad(PAGE.replace("started_by_label: \"Ada\"", "started_by_label: \"a\"b\"").toByteArray())
    }
    test("oversized quote is bad value") {
        val text = QUOTE.replace("anchor_quote: \"Run make.\\\\n\"", "anchor_quote: \"${"a".repeat(16_385)}\"")
            .replace("anchor_byte_end: 18", "anchor_byte_end: 16393")
        expectBad(text.toByteArray())
    }
    test("blank author identity is bad value") {
        expectBad(PAGE.replace("started_by_id: \"u-1\"", "started_by_id: \" \"").toByteArray())
    }
    test("comment over its cap is bad value") {
        unreadable(DiscussionCodec.decodeComment((COMMENT + "x".repeat(MAX_COMMENT_BYTES)).toByteArray()))
            .reason shouldBe UnreadableReason.BAD_VALUE
    }
    test("marker over its cap is bad value") {
        expectBad((PAGE + "x_pad: \"${"x".repeat(MAX_MARKER_BYTES)}\"\n").toByteArray())
    }

    test("wrong kinds are distinguished from bad values") {
        listOf(
            PAGE.replace("status: \"open\"", "status: 1"),
            PAGE.replace("status: \"open\"", "status: 007"),
            QUOTE.replace("anchor_line: 3", "anchor_line: \"3\""),
            QUOTE.replace("anchor_heading_path:\n", "anchor_heading_path: \"1 Setup\"\n"),
        ).forEach { text ->
            val unreadable = unreadable(DiscussionCodec.decodeDiscussion(text.toByteArray()))
            unreadable.reason shouldBe UnreadableReason.WRONG_KIND
        }
    }

    test("the first defective known line decides") {
        val text = PAGE.replace("status: \"open\"\n", "status: 1\nid: \"$D\"\n")
        unreadable(DiscussionCodec.decodeDiscussion(text.toByteArray())).reason shouldBe UnreadableReason.WRONG_KIND
    }

    test("missing and duplicate known keys are distinguished") {
        unreadable(DiscussionCodec.decodeDiscussion(PAGE.replace("page_path: \"guides/setup.md\"\n", "").toByteArray())).reason shouldBe
            UnreadableReason.MISSING_KEY
        val duplicate = PAGE.replace("status: \"open\"\n", "status: \"open\"\nstatus: \"open\"\n").toByteArray()
        unreadable(DiscussionCodec.decodeDiscussion(duplicate)).reason shouldBe UnreadableReason.DUPLICATE_KEY
    }

    test("unknown keys are ignored and kept") {
        val input = PAGE.replace("anchor_content_hash:", "x_future: \"kept\"\nanchor_content_hash:")
        val decoded = ok(DiscussionCodec.decodeDiscussion(input.toByteArray()))
        decoded.extras.trailing shouldBe "x_future: \"kept\"\n"
        DiscussionCodec.encodeDiscussion(decoded).decodeToString().contains("x_future: \"kept\"\n") shouldBe true
    }

    test("round trip preserves blank lines in extras and keys from other record types") {
        val markerInput = PAGE.replace(
            "anchor_content_hash:",
            "x_note: |+\n  one\n\nx_tail: 'quoted'\nanchor_content_hash:",
        )
        val marker = ok(DiscussionCodec.decodeDiscussion(markerInput.toByteArray()))
        val rewrittenMarker = DiscussionCodec.encodeDiscussion(marker)
        ok(DiscussionCodec.decodeDiscussion(rewrittenMarker)) shouldBe marker

        val commentInput = COMMENT.replace(
            "created:",
            "status: \"resolved\"\ncreated:",
        )
        val comment = ok(DiscussionCodec.decodeComment(commentInput.toByteArray()))
        val rewrittenComment = DiscussionCodec.encodeComment(comment)
        ok(DiscussionCodec.decodeComment(rewrittenComment)) shouldBe comment
        rewrittenComment.decodeToString().contains("status: \"resolved\"\n") shouldBe true
    }

    test("a terminal empty line in an unknown block survives rewriting") {
        val input = PAGE.replace(
            "anchor_content_hash: \"$HASH\"\n---\n",
            "anchor_content_hash: \"$HASH\"\nx_terminal: kept\n\n---\n",
        )
        val decoded = ok(DiscussionCodec.decodeDiscussion(input.toByteArray()))
        decoded.extras.trailing shouldBe "x_terminal: kept\n\n"
        val rewritten = DiscussionCodec.encodeDiscussion(decoded)
        rewritten.decodeToString().contains("x_terminal: kept\n\n---\n") shouldBe true
        ok(DiscussionCodec.decodeDiscussion(rewritten)) shouldBe decoded
    }

    test("the encoder refuses a lone surrogate") {
        shouldThrow<IllegalArgumentException> {
            DiscussionCodec.encodeComment(commentRecord().copy(author = Author(actor("u-1", "\uD800"), AuthorKind.HUMAN)))
        }
    }

    test("peek finds the page id after a malformed line") {
        val malformed = PAGE.replace("format:", "garbage \uFFFD\nformat:")
        DiscussionCodec.peekPageId(malformed.toByteArray())?.value shouldBe P
    }

    test("a 16384 byte quote round trips and 16385 is bad value") {
        val exact = quoteRecord().copy(anchor = Anchor.Quote(HASH, null, capture("a".repeat(16_384), 16_384)))
        val text = DiscussionCodec.encodeDiscussion(exact).decodeToString()
        ok(DiscussionCodec.decodeDiscussion(text.toByteArray())) shouldBe exact
        expectBad(
            text.replace("anchor_quote: \"${"a".repeat(16_384)}\"", "anchor_quote: \"${"a".repeat(16_385)}\"")
            .replace("anchor_byte_end: 16384", "anchor_byte_end: 16385").toByteArray(),
        )
    }

    test("lowercase unicode escapes decode") {
        val text = PAGE.replace("started_by_label: \"Ada\"", "started_by_label: \"A\\ufeffB\"")
        ok(DiscussionCodec.decodeDiscussion(text.toByteArray())).startedBy.actor.label shouldBe "A\uFEFFB"
    }

    test("unicode escapes accept noncanonical nonsurrogate scalars and require four hex digits") {
        val canonicalInput = PAGE.replace("started_by_label: \"Ada\"", "started_by_label: \"\\u0041da\"")
        ok(DiscussionCodec.decodeDiscussion(canonicalInput.toByteArray())).startedBy.actor.label shouldBe "Ada"
        listOf("\\u+001", "\\u001", "\\u00G1").forEach { escape ->
            val malformed = PAGE.replace("started_by_label: \"Ada\"", "started_by_label: \"$escape\"")
            expectBad(malformed.toByteArray())
        }
    }

    test("invalid UTF-8 inside frontmatter and a comment body has a specific detail") {
        val marker = PAGE.toByteArray()
        val labelStart = PAGE.indexOf("Ada")
        val malformedMarker = marker.copyOfRange(0, labelStart) + byteArrayOf(0xC3.toByte(), 0x28) +
            marker.copyOfRange(labelStart + 3, marker.size)
        val markerFailure = unreadable(DiscussionCodec.decodeDiscussion(malformedMarker))
        markerFailure.reason shouldBe UnreadableReason.BAD_VALUE
        markerFailure.detail shouldBe "frontmatter is not strict UTF-8"

        val comment = COMMENT.toByteArray()
        val bodyStart = COMMENT.indexOf("Looks good.")
        val malformedComment = comment.copyOfRange(0, bodyStart) + byteArrayOf(0xC3.toByte(), 0x28) +
            comment.copyOfRange(bodyStart + "Looks good.".length, comment.size)
        val commentFailure = unreadable(DiscussionCodec.decodeComment(malformedComment))
        commentFailure.reason shouldBe UnreadableReason.BAD_VALUE
        commentFailure.detail shouldBe "comment body is not strict UTF-8"
    }

    test("invalid body UTF-8 precedes a known-line kind error") {
        val bodyStart = COMMENT.indexOf("Looks good.")
        val beforeBody = COMMENT.substring(0, bodyStart)
        val malformed = beforeBody.replace("author_kind: \"human\"", "author_kind: 1").toByteArray() +
            byteArrayOf(0xC3.toByte(), 0x28)
        val failure = unreadable(DiscussionCodec.decodeComment(malformed))
        failure.reason shouldBe UnreadableReason.BAD_VALUE
        failure.detail shouldBe "comment body is not strict UTF-8"
    }

    test("invalid body UTF-8 precedes a duplicate known key") {
        val bodyStart = COMMENT.indexOf("Looks good.")
        val beforeBody = COMMENT.substring(0, bodyStart)
        val malformed = beforeBody.replace("author_id: \"u-1\"\n", "author_id: \"u-1\"\nauthor_id: \"u-1\"\n")
            .toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        val failure = unreadable(DiscussionCodec.decodeComment(malformed))
        failure.reason shouldBe UnreadableReason.BAD_VALUE
        failure.detail shouldBe "comment body is not strict UTF-8"
    }

    test("every string golden encodes to its pinned scalar and round trips") {
        val longQuote = "q".repeat(16_384)
        val goldens = listOf(
            "\u0000" to "\"\\u0000\"",
            "\u0085" to "\"\\u0085\"",
            "\uFEFF" to "\"\\uFEFF\"",
            "\u007F" to "\"\\u007F\"",
            "\r" to "\"\\r\"",
            "\r\n" to "\"\\r\\n\"",
            "\t" to "\"\\t\"",
            "\"" to "\"\\\"\"",
            "\\" to "\"\\\\\"",
            "😀" to "\"😀\"",
            "e\u0301" to "\"e\u0301\"",
            longQuote to "\"$longQuote\"",
        )
        goldens.forEach { (label, scalar) ->
            val record = commentRecord().copy(author = Author(actor("u-1", label), AuthorKind.HUMAN))
            val bytes = DiscussionCodec.encodeComment(record)
            bytes.decodeToString().lineSequence().single { it.startsWith("author_label: ") } shouldBe "author_label: $scalar"
            ok(DiscussionCodec.decodeComment(bytes)) shouldBe record
        }
    }
})

private const val D = "01900000-0000-7000-8000-000000000001"
private const val C = "01900000-0000-7000-8000-000000000002"
private const val P = "0190aaaa-0000-4000-8000-000000000003"
private const val T0 = "2026-09-23T10:00:00.000Z"
private const val T1 = "2026-09-23T11:30:00.250Z"
private val HASH = "sha256:" + "a".repeat(64)
private val COMMIT = "b".repeat(40)
private val PAGE = "---\nformat: \"plainbase-discussion/1\"\nid: \"$D\"\npage_id: \"$P\"\npage_path: \"guides/setup.md\"\n" +
    "status: \"open\"\ncreated: \"$T0\"\nstarted_by_issuer: \"builtin\"\nstarted_by_id: \"u-1\"\n" +
    "started_by_label: \"Ada\"\nstarted_by_kind: \"human\"\nanchor_kind: \"page\"\n" +
    "anchor_content_hash: \"$HASH\"\n---\n"
private val QUOTE = "---\nformat: \"plainbase-discussion/1\"\nid: \"$D\"\npage_id: \"$P\"\npage_path: \"guides/setup.md\"\n" +
    "status: \"resolved\"\ncreated: \"$T0\"\nstarted_by_issuer: \"agent\"\nstarted_by_id: \"tok-9\"\n" +
    "started_by_label: \"ci-bot\"\nstarted_by_kind: \"agent\"\nstatus_changed_by_issuer: \"builtin\"\n" +
    "status_changed_by_id: \"u-1\"\nstatus_changed_by_label: \"Ada\"\nstatus_changed_at: \"$T1\"\n" +
    "anchor_kind: \"quote\"\nanchor_content_hash: \"$HASH\"\nanchor_commit: \"$COMMIT\"\n" +
    "anchor_quote: \"Run make.\\n\"\nanchor_prefix: \"Build:\\n\\n\"\nanchor_suffix: \"\"\n" +
    "anchor_byte_start: 8\nanchor_byte_end: 18\nanchor_body_start: 0\nanchor_line: 3\n" +
    "anchor_selection: \"snapped\"\nanchor_heading_path:\n  - \"1 Setup\"\n  - \"2 Install\"\n---\n"
private val COMMENT = "---\nformat: \"plainbase-comment/1\"\nid: \"$C\"\ndiscussion_id: \"$D\"\n" +
    "author_issuer: \"builtin\"\nauthor_id: \"u-1\"\nauthor_label: \"Ada\"\nauthor_kind: \"human\"\n" +
    "created: \"$T0\"\n---\nLooks good.\n"
private val RETRACTED_COMMENT = "---\nformat: \"plainbase-comment/1\"\nid: \"$C\"\ndiscussion_id: \"$D\"\n" +
    "author_issuer: \"anonymous\"\nauthor_id: \"local\"\nauthor_label: \"anonymous\"\nauthor_kind: \"anonymous\"\n" +
    "created: \"$T0\"\nedited_at: \"$T1\"\nretracted_by_issuer: \"anonymous\"\nretracted_by_id: \"local\"\n" +
    "retracted_by_label: \"anonymous\"\nretracted_at: \"$T1\"\n---\nretracted by anonymous\n"

private fun pageRecord(): DiscussionRecord = DiscussionRecord(
    DiscussionId.require(D), PageRef(PageId.require(P), TreePath.require("guides/setup.md")),
    DiscussionStatus.OPEN, Instant.parse(T0), Author(actor("u-1", "Ada"), AuthorKind.HUMAN), null,
    Anchor.Page(HASH, null), null, FrontmatterExtras.NONE,
)

private fun quoteRecord(): DiscussionRecord = DiscussionRecord(
    DiscussionId.require(D), PageRef(PageId.require(P), TreePath.require("guides/setup.md")),
    DiscussionStatus.RESOLVED, Instant.parse(T0), Author(actor("tok-9", "ci-bot", "agent"), AuthorKind.AGENT),
    StatusChange(actor("u-1", "Ada"), Instant.parse(T1)),
    Anchor.Quote(
        HASH, COMMIT,
        capture(
            "Run make.\n", 18, start = 8, prefix = "Build:\n\n", selection = AnchorSelection.SNAPPED,
        headings = listOf(HeadingPath.Entry(1, "Setup"), HeadingPath.Entry(2, "Install")),
        ),
    ),
    null, FrontmatterExtras.NONE,
)

private fun commentRecord(): CommentRecord = CommentRecord(
    CommentId.require(C), DiscussionId.require(D), Author(actor("u-1", "Ada"), AuthorKind.HUMAN), Instant.parse(T0), null,
    null, "Looks good.\n", FrontmatterExtras.NONE,
)

private fun retractedCommentRecord(): CommentRecord = commentRecord().copy(
    author = Author(actor("local", "anonymous", "anonymous"), AuthorKind.ANONYMOUS),
    editedAt = Instant.parse(T1),
    retraction = Retraction(actor("local", "anonymous", "anonymous"), Instant.parse(T1)),
    body = "retracted by anonymous\n",
)

private fun actor(id: String, label: String, issuer: String = "builtin"): Actor =
    Actor(SubjectKey(issuer, id), label)

private fun capture(
    quote: String,
    end: Long,
    start: Long = 0,
    prefix: String = "",
    selection: AnchorSelection = AnchorSelection.NARROWED,
    headings: List<HeadingPath.Entry> = emptyList(),
): QuoteCapture = QuoteCapture(quote, prefix, "", start, end, 0, 3, selection, HeadingPath(headings))

private fun <T> ok(result: Decoded<T>): T = when (result) {
    is Decoded.Ok -> result.value
    is Decoded.Unreadable -> error("expected readable entry, got ${result.reason.wire}: ${result.detail}")
}

private fun unreadable(result: Decoded<*>): Decoded.Unreadable = result.shouldBeInstanceOf<Decoded.Unreadable>()

private fun expectBad(bytes: ByteArray) {
    unreadable(DiscussionCodec.decodeDiscussion(bytes)).reason shouldBe UnreadableReason.BAD_VALUE
}
