package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import kotlin.time.Instant

internal object DiscussionCodecSeedGenerator {
    data class Input(val isComment: Boolean, val bytes: ByteArray)

    fun generate(): List<Input> = List(1_000) { index ->
        val label = labels[index % labels.size] + " $index"
        val actor = Actor(SubjectKey("builtin", "seed-$index"), label)
        val author = Author(actor, if (index % 3 == 0) AuthorKind.AGENT else AuthorKind.HUMAN)
        val leading = when (index % 5) {
            0 -> "  x_lead: $index\n"
            1 -> "   x_lead: $index\n"
            else -> ""
        }
        val extraType = if (index % 2 == 0) "author_kind: \"agent\"\n" else "status: \"open\"\n"
        val extras = FrontmatterExtras(
            content = leading + "x_note: |+\n  note with quotes, backslash, emoji 😀, and e\u0301 ($index)\n\n" + extraType,
            leadingLength = leading.length,
        )
        val at = instant(index)
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000001")
        if (index % 2 == 0) {
            val anchor: Anchor = if (index % 4 == 0) {
                Anchor.Page(contentHash(index), if (index % 3 == 0) "a".repeat(40) else null)
            } else {
                Anchor.Quote(
                    contentHash(index),
                    if (index % 3 == 0) "b".repeat(40) else null,
                    quoteCapture(index, emptyHeading = index % 8 == 2),
                )
            }
            val reattachment = (anchor as? Anchor.Quote)
                ?.takeIf { index % 8 == 2 || index % 8 == 6 }
                ?.let {
                    Reattachment(
                        actor,
                        instant(index + 1_000),
                        Anchor.Quote(
                            contentHash(index + 1_000),
                            if (index % 2 == 0) "c".repeat(40) else null,
                            quoteCapture(index + 1_000, emptyHeading = index % 8 == 6),
                        ),
                    )
                }
            val status = if (index % 6 == 0) DiscussionStatus.RESOLVED else DiscussionStatus.OPEN
            val record = DiscussionRecord(
                id = id,
                page = PageRef(
                    PageId.require("0190aaaa-0000-4000-8000-000000000003"),
                    TreePath.require("guides/setup.md"),
                ),
                status = status,
                created = at,
                startedBy = author,
                statusChange = if (status == DiscussionStatus.RESOLVED) StatusChange(actor, at) else null,
                anchor = anchor,
                reattachment = reattachment,
                extras = extras,
            )
            Input(isComment = false, DiscussionCodec.encodeDiscussion(record))
        } else {
            val record = CommentRecord(
                id = CommentId.require("01900000-0000-7000-8000-000000000002"),
                discussionId = id,
                author = author,
                created = at,
                editedAt = if (index % 4 == 1) instant(index + 2_000) else null,
                retraction = if (index % 6 == 1) Retraction(actor, instant(index + 3_000)) else null,
                body = "body ${labels[index % labels.size]} $index\n",
                extras = extras,
            )
            Input(isComment = true, DiscussionCodec.encodeComment(record))
        }
    }

    private fun quoteCapture(seed: Int, emptyHeading: Boolean): QuoteCapture {
        val quote = "needle-$seed 😀"
        val byteStart = 32L + seed
        return QuoteCapture(
            quote = quote,
            prefix = "left-$seed",
            suffix = "right-$seed",
            byteStart = byteStart,
            byteEnd = byteStart + quote.encodeToByteArray().size,
            bodyStart = 8,
            line = (seed % 1_000 + 1).toLong(),
            selection = if (seed % 2 == 0) AnchorSelection.NARROWED else AnchorSelection.SNAPPED,
            headingPath = if (emptyHeading) {
                HeadingPath.EMPTY
            } else {
                HeadingPath(listOf(HeadingPath.Entry(1, "Setup $seed"), HeadingPath.Entry(2, "Details $seed")))
            },
        )
    }

    private fun contentHash(seed: Int): String = "sha256:" + seed.toString(16).padStart(64, 'a').takeLast(64)

    private fun instant(seed: Int): Instant =
        Instant.parse(if (seed % 2 == 0) "2026-09-23T10:00:00.000Z" else "2026-09-23T10:00:01.234Z")

    private val labels = listOf(
        "Ada",
        "NUL\u0000",
        "NEL\u0085",
        "BOM\uFEFF",
        "DEL\u007F",
        "CR\rLF\nTAB\t",
        "quote \" and slash \\",
        "non-BMP 😀",
        "decomposed e\u0301",
    )
}
