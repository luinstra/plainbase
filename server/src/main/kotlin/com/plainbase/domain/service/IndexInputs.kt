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
 * One source's materialized scan result: drafts in path order, URLs assigned, last-commits batched — and the
 * path/URL-collision [issues] it raised, BUFFERED rather than persisted as they were found, so an abandoned
 * (root-loss) scan leaves no rows describing a tree it never finished walking. The coordinator records them only
 * after ALL source materializations return, so a materialized result with [complete] false still participates while a
 * null from [IndexSourceReader.read] means the whole source was skipped. The reader never records or binds these
 * values itself.
 */
internal data class SourceScan(
    val root: RootName,
    val drafts: List<Draft>,
    val folders: List<ContentFolder>,
    val assets: Set<TreePath>,
    val urls: CanonicalUrlBuilder.Result,
    val commits: Map<TreePath, Commit>,
    val issues: List<IdentityIssue>,
    /** Did the backend see the WHOLE tree ([com.plainbase.domain.content.ScanResult.complete])? */
    val complete: Boolean,
    /**
     * Read evidence only: did every selected case-sensitive Markdown candidate yield bytes? This is neither an
     * atomic filesystem snapshot nor an identity guarantee. The coordinator consults it only when minting an
     * OBJECT_LIST proof for this matching root, and [SourceScan.copy] retains it unchanged when drafts are filtered.
     */
    val pageReadsComplete: Boolean,
    /**
     * Paths the walk enumerated but the bound-only absence classifier returned [ContentRead.AbsenceUnknown] for.
     * Such a read race is not evidence of deletion: the coordinator keeps the binding in limbo rather than reaping
     * it. Confirmed absences clear [pageReadsComplete] but do not enter this set.
     */
    val unread: Set<TreePath>,
)

/** The resolved identity and whether the page carried its id in materialized frontmatter. */
internal class Identity(
    val id: PageId,
    val materialized: Boolean,
)
