package com.plainbase.domain.service

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("native")
class UuidV7DiscussionIdProviderNativeTest {

    @Test
    fun `mints v7 ids with the RFC variant that parse back`() {
        val provider = UuidV7DiscussionIdProvider()
        val discussion = provider.nextDiscussion()
        val comment = provider.nextComment()

        assertEquals('7', discussion.value[14])
        assertTrue(discussion.value[19] in "89ab")
        assertEquals(discussion, DiscussionId.of(discussion.value))

        assertEquals('7', comment.value[14])
        assertTrue(comment.value[19] in "89ab")
        assertEquals(comment, CommentId.of(comment.value))
    }
}
