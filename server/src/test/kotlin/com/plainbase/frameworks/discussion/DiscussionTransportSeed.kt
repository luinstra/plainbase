package com.plainbase.frameworks.discussion

import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.principal.SubjectKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

/** Seed authoritative files without going through a policy that deliberately refuses the configured root. */
internal fun seedTransportDiscussion(root: Path, page: IndexedPage, id: DiscussionId, comment: CommentId) {
    val directory = Files.createDirectories(root.resolve(".plainbase/discussions/${id.value}"))
    val author = Author(Actor(SubjectKey("anonymous", "local"), "Local"), AuthorKind.ANONYMOUS)
    val time = Instant.parse("2026-09-28T00:00:00Z")
    Files.write(
        directory.resolve(EntryName.Marker.fileName),
        DiscussionCodec.encodeDiscussion(
            DiscussionRecord(
                id, PageRef(page.id, page.path), DiscussionStatus.OPEN, time, author,
                null, Anchor.Page(page.contentHash, null), null, FrontmatterExtras.NONE,
            ),
        ),
    )
    Files.write(
        directory.resolve(EntryName.Comment(comment).fileName),
        DiscussionCodec.encodeComment(
            CommentRecord(comment, id, author, time, null, null, "Preserved comment\n", FrontmatterExtras.NONE),
        ),
    )
}
