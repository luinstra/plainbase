package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

class DiscussionAssemblyTest : FunSpec({
    test("unknown entries leave a discussion ok") {
        val result = DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.Present(listOf(markerEntry(), commentEntry()), 1))
        val files = result.shouldBeInstanceOf<DiscussionRead.Ok>().files
        files.comments.map { it.name } shouldBe listOf(EntryName.Comment(COMMENT_ID))
    }

    test("only unknown entries is incomplete") {
        DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.Present(emptyList(), 0)) shouldBe
            DiscussionRead.Incomplete(emptyList())
    }

    test("cross-check failures carry the marker page id") {
        val otherId = DiscussionId.require("01900000-0000-7000-8000-000000000009")
        val wrongDiscussionId = DiscussionAssembly.assemble(otherId, EntriesRead.Present(listOf(markerEntry()), 0))
            .shouldBeInstanceOf<DiscussionRead.Unreadable>()
        wrongDiscussionId.reason shouldBe UnreadableReason.BAD_VALUE
        wrongDiscussionId.entry shouldBe "discussion.md"
        wrongDiscussionId.pageId shouldBe PAGE_ID

        val wrongComment = commentRecord(discussionId = otherId)
        val entries = EntriesRead.Present(
            listOf(markerEntry(), raw(EntryName.Comment(COMMENT_ID), DiscussionCodec.encodeComment(wrongComment))),
            1,
        )
        val read = DiscussionAssembly.assemble(DISCUSSION_ID, entries)
            .shouldBeInstanceOf<DiscussionRead.Unreadable>()
        read.reason shouldBe UnreadableReason.BAD_VALUE
        read.entry shouldBe "${COMMENT_ID.value}.md"
        read.pageId shouldBe PAGE_ID
    }

    test("the first malformed entry in name order decides") {
        val first = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000003"))
        val second = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000004"))
        val entries = listOf(
            markerEntry(),
            raw(second, byteArrayOf(0xff.toByte())),
            raw(first, byteArrayOf(0xfe.toByte())),
        )
        val result = DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.Present(entries, 2))
            .shouldBeInstanceOf<DiscussionRead.Unreadable>()
        result.entry shouldBe first.fileName
    }

    test("a failed read assembles to failed") {
        DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.Failed("disk fault")) shouldBe DiscussionRead.Failed("disk fault")
    }

    test("too many entries assemble unreadable with the page id") {
        val result = DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.TooMany(1_001, markerEntry()))
            .shouldBeInstanceOf<DiscussionRead.Unreadable>()
        result.reason shouldBe UnreadableReason.TOO_MANY_COMMENTS
        result.entry shouldBe "${DISCUSSION_ID.value}/"
        result.pageId shouldBe PAGE_ID
    }

    test("an escaped page id survives the over-limit path") {
        val markerBytes = DiscussionCodec.encodeDiscussion(markerRecord())
            .decodeToString()
            .replace(PAGE_TEXT, "\\u0030" + PAGE_TEXT.drop(1))
            .toByteArray()
        val marker = RawEntry(EntryName.Marker, markerBytes, EntryVersion("v1"), complete = true)

        val result = DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.TooMany(1_001, marker))
            .shouldBeInstanceOf<DiscussionRead.Unreadable>()

        result.reason shouldBe UnreadableReason.TOO_MANY_COMMENTS
        result.pageId shouldBe PAGE_ID
    }

    test("assembly releases each raw entry") {
        val entries = listOf(markerEntry(), commentEntry())
        DiscussionAssembly.assemble(DISCUSSION_ID, EntriesRead.Present(entries, 1))
        entries.forEach { entry -> shouldThrow<IllegalStateException> { entry.take() } }
    }

    test("retain keeps only the window and assembly precedence is unchanged") {
        val ids = listOf(
            CommentId.require("01900000-0000-7000-8000-000000000003"),
            CommentId.require("01900000-0000-7000-8000-000000000004"),
            CommentId.require("01900000-0000-7000-8000-000000000005"),
        )
        val entries = listOf(markerEntry()) + ids.map { id ->
            val record = commentRecord().copy(id = id)
            raw(EntryName.Comment(id), DiscussionCodec.encodeComment(record))
        }
        val seen = mutableListOf<CommentId>()
        val result = DiscussionAssembly.assemble(
            DISCUSSION_ID,
            EntriesRead.Present(entries, 3),
            retain = { false },
            onComment = { seen += (it.name as EntryName.Comment).id },
        ).shouldBeInstanceOf<DiscussionRead.Ok>()

        result.files.comments shouldBe emptyList()
        seen shouldBe ids
        entries.forEach { entry -> shouldThrow<IllegalStateException> { entry.take() } }

        val incompleteId = DiscussionId.require("01900000-0000-7000-8000-000000000006")
        val badComment = raw(
            EntryName.Comment(COMMENT_ID),
            DiscussionCodec.encodeComment(commentRecord()),
        )
        DiscussionAssembly.assemble(
            incompleteId,
            EntriesRead.Present(listOf(badComment), 1),
            retain = { false },
        ) shouldBe DiscussionRead.Incomplete(listOf(EntryName.Comment(COMMENT_ID).fileName))
        shouldThrow<IllegalStateException> { badComment.take() }
    }
})

private const val DISCUSSION_TEXT = "01900000-0000-7000-8000-000000000001"
private const val COMMENT_TEXT = "01900000-0000-7000-8000-000000000002"
private const val PAGE_TEXT = "0190aaaa-0000-4000-8000-000000000003"
private val DISCUSSION_ID = DiscussionId.require(DISCUSSION_TEXT)
private val COMMENT_ID = CommentId.require(COMMENT_TEXT)
private val PAGE_ID = PageId.require(PAGE_TEXT)
private val PAGE_HASH = "sha256:" + "a".repeat(64)
private val WHEN = Instant.parse("2026-09-23T10:00:00.000Z")

private fun markerRecord(id: DiscussionId = DISCUSSION_ID): DiscussionRecord = DiscussionRecord(
    id,
    PageRef(PAGE_ID, TreePath.require("guides/setup.md")),
    DiscussionStatus.OPEN,
    WHEN,
    Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN),
    null,
    Anchor.Page(PAGE_HASH, null),
    null,
    FrontmatterExtras.NONE,
)

private fun commentRecord(discussionId: DiscussionId = DISCUSSION_ID): CommentRecord = CommentRecord(
    COMMENT_ID,
    discussionId,
    Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN),
    WHEN,
    null,
    null,
    "Hi\n",
    FrontmatterExtras.NONE,
)

private fun markerEntry(): RawEntry = raw(EntryName.Marker, DiscussionCodec.encodeDiscussion(markerRecord()))

private fun commentEntry(): RawEntry = raw(EntryName.Comment(COMMENT_ID), DiscussionCodec.encodeComment(commentRecord()))

private fun raw(name: EntryName, bytes: ByteArray): RawEntry =
    RawEntry(name, bytes, EntryVersion("v1"), complete = true)
