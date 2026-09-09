package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

/** One page's in-flight state: read once, frontmatter parsed once, bytes kept for the single render. */
internal class Draft(
    val file: ContentFile,
    val bytes: ByteArray,
    val frontmatter: Frontmatter,
)

/**
 * One source's materialized result. Scan and URL issues stay buffered until all sources return, so an abandoned scan
 * leaves no persisted issues. Incomplete results participate; null from [IndexSourceReader.read] means the source was skipped.
 */
internal data class SourceScan(
    val root: RootName,
    val drafts: List<Draft>,
    val folders: List<ContentFolder>,
    val assets: Set<TreePath>,
    val urls: CanonicalUrlBuilder.Result,
    val commits: Map<TreePath, Commit>,
    val issues: List<IdentityIssue>,
    /** Whether the backend saw the whole tree ([com.plainbase.domain.content.ScanResult.complete]). */
    val complete: Boolean,
    /** True only when every selected Markdown candidate yielded bytes; used for a matching-root OBJECT_LIST proof. */
    val pageReadsComplete: Boolean,
    /**
     * Enumerated paths with [ContentRead.AbsenceUnknown], retained in limbo.
     * Confirmed absences clear [pageReadsComplete] but do not enter this set.
     */
    val unread: Set<TreePath>,
)

/** The resolved identity and whether the page carried its id in materialized frontmatter. */
internal class Identity(
    val id: PageId,
    val materialized: Boolean,
)
