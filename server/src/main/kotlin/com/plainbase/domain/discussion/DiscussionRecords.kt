package com.plainbase.domain.discussion

import kotlin.time.Instant

data class DiscussionRecord(
    val id: DiscussionId,
    val page: PageRef,
    val status: DiscussionStatus,
    val created: Instant,
    val startedBy: Author,
    val statusChange: StatusChange?,
    val anchor: Anchor,
    val reattachment: Reattachment?,
    val extras: FrontmatterExtras,
) {
    init {
        require(anchor !is Anchor.Page || reattachment == null)
    }
}

data class StatusChange(val by: Actor, val at: Instant)

data class CommentRecord(
    val id: CommentId,
    val discussionId: DiscussionId,
    val author: Author,
    val created: Instant,
    val editedAt: Instant?,
    val retraction: Retraction?,
    val body: String,
    val extras: FrontmatterExtras,
)

data class Retraction(val by: Actor, val at: Instant)

/** Unknown frontmatter retained in one backing string; [leadingLength] locates its leading continuation prefix. */
data class FrontmatterExtras(val content: String, val leadingLength: Int) {
    val leading: String? get() = content.substring(0, leadingLength).takeIf(String::isNotEmpty)
    val trailing: String get() = content.substring(leadingLength)

    constructor(leading: String?, trailing: List<String>) : this(
        content = (leading ?: "") + trailing.joinToString(separator = "") { block ->
            block.removeSuffix("\n") + "\n"
        },
        leadingLength = leading?.length ?: 0,
    )

    init {
        require(leadingLength in 0..content.length)
    }

    companion object {
        val NONE = FrontmatterExtras(content = "", leadingLength = 0)
    }
}
