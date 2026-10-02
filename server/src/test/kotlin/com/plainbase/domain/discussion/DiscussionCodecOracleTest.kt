package com.plainbase.domain.discussion

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.principal.SubjectKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DiscussionCodecOracleTest : FunSpec({
    test("snakeyaml agrees on every golden and seeded record") {
        val page = pageBytes("Ada")
        val quote = pageBytes("ci-bot").decodeToString()
            .replace("status: \"open\"", "status: \"resolved\"")
            .replace(
                "anchor_kind: \"page\"",
                "anchor_kind: \"quote\"\nanchor_quote: \"Run make.\\n\"\nanchor_prefix: \"\"\nanchor_suffix: \"\"\n" +
                "anchor_byte_start: 8\nanchor_byte_end: 18\nanchor_body_start: 0\nanchor_line: 3\nanchor_selection: \"snapped\"\nanchor_heading_path:\n  - \"1 Setup\"\n",
            )
            .toByteArray()
        val decodedQuote = (DiscussionCodec.decodeDiscussion(quote) as Decoded.Ok).value
        val reattachedAnchor = Anchor.Quote(
            "sha256:${"b".repeat(64)}",
            "a".repeat(40),
            QuoteCapture(
                "Shifted.\n", "left", "right", 20, 29, 5, 12, AnchorSelection.NARROWED,
                HeadingPath(listOf(HeadingPath.Entry(3, "Replacement"))),
            ),
        )
        val reattached = DiscussionCodec.encodeDiscussion(
            decodedQuote.copy(
                reattachment = Reattachment(
                    Actor(SubjectKey("builtin", "u-2"), "Bo"),
                    kotlin.time.Instant.parse("2026-09-23T11:30:00.250Z"),
                    reattachedAnchor,
                ),
            ),
        )
        val comment = """---
format: "plainbase-comment/1"
id: "01900000-0000-7000-8000-000000000002"
discussion_id: "01900000-0000-7000-8000-000000000001"
author_issuer: "builtin"
author_id: "u-1"
author_label: "Ada"
author_kind: "human"
created: "2026-09-23T10:00:00.000Z"
---
body
""".toByteArray()
        val retracted = comment.decodeToString().replace("author_issuer: \"builtin\"", "author_issuer: \"anonymous\"")
            .replace("author_id: \"u-1\"", "author_id: \"local\"")
            .replace("author_label: \"Ada\"", "author_label: \"anonymous\"")
            .replace(
                "author_kind: \"human\"",
                "author_kind: \"anonymous\"\nedited_at: \"2026-09-23T11:30:00.250Z\"\n" +
                "retracted_by_issuer: \"anonymous\"\nretracted_by_id: \"local\"\nretracted_by_label: \"anonymous\"\n" +
                "retracted_at: \"2026-09-23T11:30:00.250Z\"",
            )
            .replace("body", "retracted by anonymous")
            .toByteArray()

        listOf(page, quote, reattached).forEach(::assertDiscussionOracle)
        listOf(comment, retracted).forEach(::assertCommentOracle)
        DiscussionCodecSeedGenerator.generate().forEach { generated ->
            if (generated.isComment) assertCommentOracle(generated.bytes) else assertDiscussionOracle(generated.bytes)
        }
    }

    test("snakeyaml agrees on the extras goldens") {
        val extra = "x_future: \"kept\"\nx_list:\n  - \"a\"\n# note\nx_note: |\n  one\n\n  two\n"
        val bytes = pageBytes("Ada").decodeToString().replace("anchor_content_hash:", "$extra\nanchor_content_hash:").toByteArray()
        assertDiscussionOracle(bytes)
        val record = (DiscussionCodec.decodeDiscussion(bytes) as Decoded.Ok).value
        record.extras.trailing shouldBe "$extra\n"
    }

    test("snakeyaml agrees on raw line breaks inside known values") {
        listOf('\u0085', '\u2028').forEach { lineBreak ->
            val bytes = pageBytes("Ada${lineBreak}Grace")
            when (val decoded = DiscussionCodec.decodeDiscussion(bytes)) {
                is Decoded.Unreadable -> decoded.shouldBeInstanceOf<Decoded.Unreadable>()
                is Decoded.Ok -> {
                    val block = FrontmatterBlock.detect(bytes) as FrontmatterBlock.Detection.Present
                    val inner = bytes.copyOfRange(block.innerStart, block.innerEnd).toString(Charsets.UTF_8)
                    val options = LoaderOptions().apply {
                        isAllowDuplicateKeys = true
                        isWarnOnDuplicateKeys = false
                    }
                    val mapping = Yaml(options).load<Map<String, Any?>>(inner)
                    decoded.value.startedBy.actor.label shouldBe mapping["started_by_label"]
                }
            }
        }
    }
})

private fun pageBytes(label: String): ByteArray =
    """---
format: "plainbase-discussion/1"
id: "01900000-0000-7000-8000-000000000001"
page_id: "0190aaaa-0000-4000-8000-000000000003"
page_path: "guides/setup.md"
status: "open"
created: "2026-09-23T10:00:00.000Z"
started_by_issuer: "builtin"
started_by_id: "u-1"
started_by_label: "$label"
started_by_kind: "human"
anchor_kind: "page"
anchor_content_hash: "sha256:${"a".repeat(64)}"
---
""".toByteArray()

private fun assertDiscussionOracle(bytes: ByteArray) {
    val record = when (val decoded = DiscussionCodec.decodeDiscussion(bytes)) {
        is Decoded.Ok -> decoded.value
        is Decoded.Unreadable -> error("golden discussion was refused: ${decoded.reason.wire}")
    }
    val block = FrontmatterBlock.detect(bytes) as FrontmatterBlock.Detection.Present
    val actual = yaml(
        bytes.copyOfRange(block.innerStart, block.innerEnd).toString(Charsets.UTF_8),
        record.extras.leadingLength,
    )
    val expected = linkedMapOf<String, Any>(
        "format" to "plainbase-discussion/1",
        "id" to record.id.value,
        "page_id" to record.page.pageId.value,
        "page_path" to record.page.path.value,
        "status" to record.status.wire,
        "created" to formatOracleTime(record.created),
        "started_by_issuer" to record.startedBy.actor.subject.issuer,
        "started_by_id" to record.startedBy.actor.subject.id,
        "started_by_label" to record.startedBy.actor.label,
        "started_by_kind" to record.startedBy.kind.wire,
        "anchor_kind" to if (record.anchor is Anchor.Page) "page" else "quote",
        "anchor_content_hash" to record.anchor.contentHash,
    )
    record.statusChange?.let { change ->
        expected["status_changed_by_issuer"] = change.by.subject.issuer
        expected["status_changed_by_id"] = change.by.subject.id
        expected["status_changed_by_label"] = change.by.label
        expected["status_changed_at"] = formatOracleTime(change.at)
    }
    record.anchor.commit?.let { expected["anchor_commit"] = it }
    if (record.anchor is Anchor.Quote) {
        val capture = record.anchor.capture
        expected["anchor_quote"] = capture.quote
        expected["anchor_prefix"] = capture.prefix
        expected["anchor_suffix"] = capture.suffix
        expected["anchor_byte_start"] = capture.byteStart
        expected["anchor_byte_end"] = capture.byteEnd
        expected["anchor_body_start"] = capture.bodyStart
        expected["anchor_line"] = capture.line
        expected["anchor_selection"] = capture.selection.wire
        expected["anchor_heading_path"] = capture.headingPath.entries.map { "${it.level} ${it.text}" }
    }
    record.reattachment?.let { reattachment ->
        expected["reattached_by_issuer"] = reattachment.by.subject.issuer
        expected["reattached_by_id"] = reattachment.by.subject.id
        expected["reattached_by_label"] = reattachment.by.label
        expected["reattached_at"] = formatOracleTime(reattachment.at)
        expected["reattach_content_hash"] = reattachment.anchor.contentHash
        reattachment.anchor.commit?.let { expected["reattach_commit"] = it }
        val capture = reattachment.anchor.capture
        expected["reattach_quote"] = capture.quote
        expected["reattach_prefix"] = capture.prefix
        expected["reattach_suffix"] = capture.suffix
        expected["reattach_byte_start"] = capture.byteStart
        expected["reattach_byte_end"] = capture.byteEnd
        expected["reattach_body_start"] = capture.bodyStart
        expected["reattach_line"] = capture.line
        expected["reattach_selection"] = capture.selection.wire
        expected["reattach_heading_path"] = capture.headingPath.entries.map { "${it.level} ${it.text}" }
    }
    expected.forEach { (key, value) -> compareValue(actual[key], value) }
}

private fun assertCommentOracle(bytes: ByteArray) {
    val record = when (val decoded = DiscussionCodec.decodeComment(bytes)) {
        is Decoded.Ok -> decoded.value
        is Decoded.Unreadable -> error("golden comment was refused: ${decoded.reason.wire}")
    }
    val block = FrontmatterBlock.detect(bytes) as FrontmatterBlock.Detection.Present
    val actual = yaml(
        bytes.copyOfRange(block.innerStart, block.innerEnd).toString(Charsets.UTF_8),
        record.extras.leadingLength,
    )
    val expected = linkedMapOf<String, Any>(
        "format" to "plainbase-comment/1",
        "id" to record.id.value,
        "discussion_id" to record.discussionId.value,
        "author_issuer" to record.author.actor.subject.issuer,
        "author_id" to record.author.actor.subject.id,
        "author_label" to record.author.actor.label,
        "author_kind" to record.author.kind.wire,
        "created" to formatOracleTime(record.created),
    )
    record.editedAt?.let { expected["edited_at"] = formatOracleTime(it) }
    record.retraction?.let { retraction ->
        expected["retracted_by_issuer"] = retraction.by.subject.issuer
        expected["retracted_by_id"] = retraction.by.subject.id
        expected["retracted_by_label"] = retraction.by.label
        expected["retracted_at"] = formatOracleTime(retraction.at)
    }
    expected.forEach { (key, value) -> compareValue(actual[key], value) }
}

private fun yaml(text: String, leadingLength: Int = 0): Map<String, Any?> {
    val options = LoaderOptions().apply {
        isAllowDuplicateKeys = true
        isWarnOnDuplicateKeys = false
    }
    return Yaml(options).load(text.drop(leadingLength))
}

private val oracleTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun formatOracleTime(value: kotlin.time.Instant): String =
    oracleTimeFormatter.format(java.time.Instant.ofEpochMilli(value.toEpochMilliseconds()))

private fun compareValue(actual: Any?, expected: Any) {
    when (expected) {
        is Long -> (actual as Number).toLong() shouldBe expected
        else -> actual shouldBe expected
    }
}
