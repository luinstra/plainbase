@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class UuidV7DiscussionIdProvider : DiscussionIdProvider {
    override fun nextDiscussion(): DiscussionId = DiscussionId.of(Uuid.generateV7())
    override fun nextComment(): CommentId = CommentId.of(Uuid.generateV7())
}
