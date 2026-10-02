package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey

enum class AuthorKind(val wire: String) {
    HUMAN("human"),
    AGENT("agent"),
    ANONYMOUS("anonymous"),
}

/** An identified discussion participant and the label shown to readers. */
data class Actor(val subject: SubjectKey, val label: String)

/** An actor paired with the kind of authorship they represent. */
data class Author(val actor: Actor, val kind: AuthorKind)

enum class DiscussionStatus(val wire: String) {
    OPEN("open"),
    RESOLVED("resolved"),
}

/** A page identity paired with its content-tree path. */
data class PageRef(val pageId: PageId, val path: TreePath)
