package com.plainbase.domain.discussion

interface DiscussionWatchSink {
    fun discussionChanged(id: DiscussionId)
    fun collectionChanged()
}
