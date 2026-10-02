package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SubjectKey
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("native")
class DiscussionCodecNativeTest {
    @Test
    fun principalKeysMatchPersistedIdentityDigests() {
        val cases = listOf(
            Principal.Human("builtin", "u-1") to SubjectKey("builtin", "u-1"),
            Principal.Agent("token-1") to SubjectKey("agent", "token-1"),
            Principal.Anonymous to SubjectKey("anonymous", "local"),
        )
        cases.forEach { (principal, persisted) ->
            assertEquals(persisted, SubjectKey.of(principal))
            assertEquals(IdentityDigest.of(persisted), IdentityDigest.of(SubjectKey.of(principal)))
        }
    }

    @Test
    fun escapedStringsRoundTripToPinnedBytes() {
        val id = CommentId.require("01900000-0000-7000-8000-000000000002")
        val discussionId = DiscussionId.require("01900000-0000-7000-8000-000000000001")
        val record = CommentRecord(
            id,
            discussionId,
            Author(Actor(SubjectKey("builtin", "u-1"), "Ada\u2028"), AuthorKind.HUMAN),
            Instant.parse("2026-09-25T12:34:56.789Z"),
            null,
            null,
            "body\n",
            FrontmatterExtras.NONE,
        )
        val expected = (
            "---\nformat: \"plainbase-comment/1\"\nid: \"01900000-0000-7000-8000-000000000002\"\n" +
                "discussion_id: \"01900000-0000-7000-8000-000000000001\"\nauthor_issuer: \"builtin\"\n" +
                "author_id: \"u-1\"\nauthor_label: \"Ada\\u2028\"\nauthor_kind: \"human\"\n" +
                "created: \"2026-09-25T12:34:56.789Z\"\n---\nbody\n"
            ).encodeToByteArray()

        val encoded = DiscussionCodec.encodeComment(record)
        assertContentEquals(expected, encoded)
        val decoded = DiscussionCodec.decodeComment(expected)
        assertTrue(decoded is Decoded.Ok, "expected a decoded comment")
        assertEquals(record, decoded.value)
        assertContentEquals(expected, DiscussionCodec.encodeComment(decoded.value))
    }

    @Test
    fun strictDecodingRefusesInvalidUtf8AndSurrogateEscapes() {
        val marker = DiscussionCodec.encodeDiscussion(
            DiscussionRecord(
                DiscussionId.require("01900000-0000-7000-8000-000000000001"),
                PageRef(PageId.require("01900000-0000-7000-8000-000000000010"), TreePath.require("guides/setup.md")),
                DiscussionStatus.OPEN,
                Instant.parse("2026-09-25T12:34:56.789Z"),
                Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN),
                null,
                Anchor.Page("sha256:" + "a".repeat(64), null),
                null,
                FrontmatterExtras.NONE,
            ),
        )
        val markerText = marker.decodeToString()
        val markerBytes = markerText.encodeToByteArray()
        val labelStart = markerText.indexOf("Ada")
        val badFrontmatter = markerBytes.copyOfRange(0, labelStart) + byteArrayOf(0xC3.toByte(), 0x28) +
            markerBytes.copyOfRange(labelStart + 3, markerBytes.size)
        val invalid = DiscussionCodec.decodeDiscussion(badFrontmatter)
        assertTrue(invalid is Decoded.Unreadable, "invalid frontmatter UTF-8 must be unreadable")
        assertEquals(UnreadableReason.BAD_VALUE, invalid.reason)
        assertEquals("frontmatter is not strict UTF-8", invalid.detail)

        val comment = CommentRecord(
            CommentId.require("01900000-0000-7000-8000-000000000002"),
            DiscussionId.require("01900000-0000-7000-8000-000000000001"),
            Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN),
            Instant.parse("2026-09-25T12:34:56.789Z"),
            null,
            null,
            "body\n",
            FrontmatterExtras.NONE,
        )
        val validCommentText = DiscussionCodec.encodeComment(comment).decodeToString()
        val validComment = validCommentText.encodeToByteArray()
        val bodyStart = validCommentText.indexOf("body\n")
        val badBody = validComment.copyOfRange(0, bodyStart) + byteArrayOf(0xC3.toByte(), 0x28) +
            validComment.copyOfRange(bodyStart + 5, validComment.size)
        val invalidBody = DiscussionCodec.decodeComment(badBody)
        assertTrue(invalidBody is Decoded.Unreadable, "invalid body UTF-8 must be unreadable")
        assertEquals(UnreadableReason.BAD_VALUE, invalidBody.reason)
        assertEquals("comment body is not strict UTF-8", invalidBody.detail)

        val surrogate = marker.decodeToString().replace("started_by_label: \"Ada\"", "started_by_label: \"\\uD800\"").encodeToByteArray()
        val escaped = DiscussionCodec.decodeDiscussion(surrogate)
        assertTrue(escaped is Decoded.Unreadable, "a surrogate escape must be unreadable")
        assertEquals(UnreadableReason.BAD_VALUE, escaped.reason)
    }
}
