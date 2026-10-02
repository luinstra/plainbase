package com.plainbase.domain.service

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.SelectionRequest
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName

sealed interface DiscussionAnchorRequest {
    val contentHash: String

    data class Page(override val contentHash: String) : DiscussionAnchorRequest
    data class Quote(override val contentHash: String, val selection: SelectionRequest) : DiscussionAnchorRequest
}

interface DiscussionFacade {
    fun start(principal: Principal, pageId: PageId, root: RootName?, anchor: DiscussionAnchorRequest, body: String): DiscussionWriteOutcome
    fun comment(principal: Principal, id: DiscussionId, root: RootName?, body: String): DiscussionWriteOutcome
    fun edit(principal: Principal, id: DiscussionId, root: RootName?, commentId: CommentId, body: String): DiscussionWriteOutcome
    fun retract(principal: Principal, id: DiscussionId, root: RootName?, commentId: CommentId): DiscussionWriteOutcome
    fun resolve(principal: Principal, id: DiscussionId, root: RootName?): DiscussionWriteOutcome
    fun reopen(principal: Principal, id: DiscussionId, root: RootName?): DiscussionWriteOutcome
    fun reattach(principal: Principal, id: DiscussionId, root: RootName?, anchor: DiscussionAnchorRequest.Quote): DiscussionWriteOutcome
    fun purge(principal: Principal, id: DiscussionId, root: RootName?, commentId: CommentId): DiscussionWriteOutcome
}
