package com.plainbase.frameworks.protocol

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.DiscussionAnchorRequest
import com.plainbase.domain.service.DiscussionFacade

/** The guarded discussion surface shared by transport adapters. */
interface DiscussionTransportFacade : DiscussionFacade {
    fun pageList(principal: Principal, pageId: PageId, pin: RootName?, after: DiscussionId?, limit: Int): DiscussionListDto
    fun rootList(principal: Principal, root: RootName, after: DiscussionId?, limit: Int, state: String?): DiscussionListDto
    fun detail(principal: Principal, id: DiscussionId, pin: RootName?, after: CommentId?, limit: Int): DiscussionDetailDto
    fun preview(principal: Principal, pageId: PageId, pin: RootName?, request: DiscussionAnchorRequest.Quote): DiscussionPreviewDto
}
