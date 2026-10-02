package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Instant

class RowDerivationTest : FunSpec({
    test("updated is the latest of every timestamp") {
        val baseAnchor = quoteAnchor()
        val latest = instant(6)
        val marker = markerRecord().copy(
            anchor = baseAnchor,
            created = instant(1),
            statusChange = StatusChange(actor(), instant(2)),
            reattachment = Reattachment(actor(), instant(3), baseAnchor),
        )
        val comment = commentRecord().copy(
            created = instant(4),
            editedAt = instant(5),
            retraction = Retraction(actor(), instant(1)),
        )
        val cases = listOf(
            marker.copy(created = latest) to comment,
            marker.copy(statusChange = StatusChange(actor(), latest)) to comment,
            marker.copy(reattachment = Reattachment(actor(), latest, baseAnchor)) to comment,
            marker to comment.copy(created = latest),
            marker to comment.copy(editedAt = latest),
            marker to comment.copy(retraction = Retraction(actor(), latest)),
        )

        cases.forEach { (caseMarker, caseComment) ->
            val result = RowDerivation.derive(
                DISCUSSION_ID,
                EntriesRead.Present(listOf(markerEntry(caseMarker), commentEntry(caseComment)), 1),
            ).update.shouldBeInstanceOf<RowUpdate.Upsert>()

            result.row.updated shouldBe latest.toEpochMilliseconds()
        }
    }

    test("near cap comments fold timestamps and entry metadata without retaining bodies") {
        val comments = (1..8).map { index ->
            commentRecord().copy(
                id = CommentId.require("01900000-0000-7000-8000-${index.toString().padStart(12, '0')}"),
                created = instant(index + 2),
                body = "body-$index\n${"x".repeat(65_000)}",
            )
        }
        val entries = buildList {
            add(markerEntry())
            comments.forEachIndexed { index, comment ->
                val bytes = DiscussionCodec.encodeComment(comment)
                add(
                    RawEntry(
                        EntryName.Comment(comment.id),
                        bytes,
                        EntryVersion("sha256:" + index.toString(16).padStart(64, '0')),
                        complete = true,
                    ),
                )
            }
        }

        val result = RowDerivation.derive(DISCUSSION_ID, EntriesRead.Present(entries, comments.size))
            .update.shouldBeInstanceOf<RowUpdate.Upsert>()

        result.row.commentCount shouldBe comments.size
        result.row.updated shouldBe comments.last().created.toEpochMilliseconds()
        result.entries shouldHaveSize comments.size + 1
        result.toString().contains("body-1") shouldBe false
    }

    test("a failed read is unknown and never a delete") {
        RowDerivation.derive(DISCUSSION_ID, EntriesRead.Failed("disk fault")).update shouldBe
            RowUpdate.Unknown("disk fault")
    }

    test("an unreadable marker keeps the peeked page id and reason") {
        val bytes = DiscussionCodec.encodeDiscussion(markerRecord()).decodeToString()
            .replace("status: \"open\"", "status: \"closed\"")
            .encodeToByteArray()
        val update = RowDerivation.derive(
            DISCUSSION_ID,
            EntriesRead.Present(listOf(raw(EntryName.Marker, bytes)), 0),
        ).update.shouldBeInstanceOf<RowUpdate.Upsert>()

        update.row.state shouldBe "unreadable"
        update.row.reason shouldBe "bad_value:discussion.md"
        update.row.pageId shouldBe PAGE_ID
    }

    test("too many comments is unreadable with the listing count") {
        val update = RowDerivation.derive(
            DISCUSSION_ID,
            EntriesRead.TooMany(1_001, markerEntry()),
        ).update.shouldBeInstanceOf<RowUpdate.Upsert>()

        update.row.state shouldBe "unreadable"
        update.row.reason shouldBe "too_many_comments:${DISCUSSION_ID.value}/"
        update.row.pageId shouldBe PAGE_ID
        update.row.commentCount shouldBe 1_001
    }

    test("a comment mismatch before the marker keeps the marker page id") {
        val otherDiscussion = DiscussionId.require("01900000-0000-7000-8000-000000000009")
        val comment = commentRecord().copy(discussionId = otherDiscussion)
        val update = RowDerivation.derive(
            DISCUSSION_ID,
            EntriesRead.Present(listOf(commentEntry(comment), markerEntry()), 1),
        ).update.shouldBeInstanceOf<RowUpdate.Upsert>()

        update.row.reason shouldBe "bad_value:${COMMENT_ID.value}.md"
        update.row.pageId shouldBe PAGE_ID
    }

    test("identity digest encodings are unambiguous") {
        val first = IdentityDigest.of(SubjectKey("a\u0000b", "c"))
        val second = IdentityDigest.of(SubjectKey("a", "b\u0000c"))

        first.startsWith("sha256:") shouldBe true
        first.length shouldBe 71
        (first == second) shouldBe false
    }

    test("identity is stored as a digest and an overlong page path as null") {
        val starter = SubjectKey("issuer-start", "user-start")
        val commenter = SubjectKey("issuer-comment", "user-comment")
        val marker = markerRecord().copy(
            page = PageRef(PAGE_ID, TreePath.require("a".repeat(4_100))),
            startedBy = Author(Actor(starter, "Starter"), AuthorKind.HUMAN),
        )
        val comment = commentRecord().copy(author = Author(Actor(commenter, "Commenter"), AuthorKind.HUMAN))
        val update = RowDerivation.derive(
            DISCUSSION_ID,
            EntriesRead.Present(listOf(markerEntry(marker), commentEntry(comment)), 1),
        ).update.shouldBeInstanceOf<RowUpdate.Upsert>()

        update.row.pagePath shouldBe null
        update.row.anchorHash shouldBe markerEntry(marker).version.token

        val directory = Files.createTempDirectory("pb-row-derivation")
        try {
            DiscussionDb(directory.resolve("discussions.db")).use { db ->
                val rows = JdbcDiscussionRows(db)
                rows.writing {
                    apply(RootName.PRIMARY, DISCUSSION_ID, update, Stamp("fixture"), dropMatch = false)
                }

                rows.row(RootName.PRIMARY, DISCUSSION_ID)?.starterKey shouldBe IdentityDigest.of(starter)
                rows.entry(RootName.PRIMARY, DISCUSSION_ID, EntryName.Marker)?.authorKey shouldBe IdentityDigest.of(starter)
                rows.entry(RootName.PRIMARY, DISCUSSION_ID, EntryName.Comment(COMMENT_ID))?.authorKey shouldBe
                    IdentityDigest.of(commenter)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})

private const val DISCUSSION_TEXT = "01900000-0000-7000-8000-000000000001"
private const val COMMENT_TEXT = "01900000-0000-7000-8000-000000000002"
private const val PAGE_TEXT = "0190aaaa-0000-4000-8000-000000000003"
private val DISCUSSION_ID = DiscussionId.require(DISCUSSION_TEXT)
private val COMMENT_ID = CommentId.require(COMMENT_TEXT)
private val PAGE_ID = PageId.require(PAGE_TEXT)
private val PAGE_HASH = "sha256:" + "a".repeat(64)
private fun instant(hour: Int): Instant = Instant.parse("2026-09-23T${hour.toString().padStart(2, '0')}:00:00.000Z")
private fun actor() = Actor(SubjectKey("builtin", "user"), "Ada")

private fun markerRecord(): DiscussionRecord = DiscussionRecord(
    DISCUSSION_ID,
    PageRef(PAGE_ID, TreePath.require("guides/setup.md")),
    DiscussionStatus.OPEN,
    instant(1),
    Author(actor(), AuthorKind.HUMAN),
    null,
    Anchor.Page(PAGE_HASH, null),
    null,
    FrontmatterExtras.NONE,
)

private fun quoteAnchor(): Anchor.Quote {
    val capture = QuoteCapture("q", "", "", 0, 1, 0, 1, AnchorSelection.NARROWED, HeadingPath.EMPTY)
    return Anchor.Quote(PAGE_HASH, null, capture)
}

private fun commentRecord(): CommentRecord = CommentRecord(
    COMMENT_ID,
    DISCUSSION_ID,
    Author(actor(), AuthorKind.HUMAN),
    instant(1),
    null,
    null,
    "Hi\n",
    FrontmatterExtras.NONE,
)

private fun markerEntry(record: DiscussionRecord = markerRecord()): RawEntry = raw(
    EntryName.Marker,
    DiscussionCodec.encodeDiscussion(record),
)

private fun commentEntry(record: CommentRecord = commentRecord()): RawEntry = raw(
    EntryName.Comment(record.id),
    DiscussionCodec.encodeComment(record),
)

private fun raw(name: EntryName, bytes: ByteArray): RawEntry = RawEntry(
    name,
    bytes,
    EntryVersion("sha256:" + "b".repeat(64)),
    complete = true,
)
