package com.plainbase.domain.service

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId

interface DiscussionIdProvider {
    fun nextDiscussion(): DiscussionId
    fun nextComment(): CommentId
}
