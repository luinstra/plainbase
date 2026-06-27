package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * The LIVE read seam `ProposalService` needs (P1a, B4) — all derivable from the published [com.plainbase.domain
 * .page.PageIndex] snapshot + the `ContentStore` the read facade already holds. Framework-free (`domain/`); the
 * impl is `com.plainbase.frameworks.ktor.IndexProposalBaseReader`. Reads only — propose-time base-hash validation
 * + drift + the `create`-collision flag; it performs NO content-tree write.
 *
 * The flags it derives ([occupied], and the hash compare the service runs over [currentBytes]) read the PUBLISHED
 * snapshot, NOT on-disk truth under the apply monitor — so `base_drifted` is a NON-AUTHORITATIVE pre-apply triage
 * datum. P1b's apply over on-disk truth is the real gate; a benign TOCTOU disagreement is acceptable.
 */
interface ProposalBaseReader {

    /** The published content-file path of [pageId] (null if no published page has that id) — resolves an edit's target. */
    fun pathOf(pageId: PageId): TreePath?

    /** The target's CURRENT published source bytes (null if no published page is there) — the base-hash / drift source. */
    fun currentBytes(path: TreePath): ByteArray?

    /** True iff a content FILE (page OR asset) currently occupies [path] in the published snapshot — the create-collision flag. */
    fun occupied(path: TreePath): Boolean
}
