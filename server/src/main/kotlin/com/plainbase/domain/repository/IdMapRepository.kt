package com.plainbase.domain.repository

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId

/**
 * Persistence port for the page-identity map and the identity issues list (§5.2, chunk 4b).
 *
 * The id_map binds each content path to its [PageId] plus the materialization state — whether the
 * id also lives in the file's frontmatter. Pre-materialization identity is path-keyed by accepted
 * design (§5.2): durability is only promised for materialized ids.
 *
 * One binding per path AND per id. The §5.2 duplicate policy guarantees an id is never claimed by
 * two live paths, and [bind] supersedes a stale binding when an id moves with its file — so the
 * adapter may enforce id-uniqueness structurally.
 *
 * Pure domain port: only chunk 1.5/4a domain types appear; the at-rest representation (16-byte
 * BLOBs, decision log #6) is invisible here — that is the storage adapter's single concern.
 */
interface IdMapRepository {

    /** The binding for [path], or null when the path is unmapped. */
    fun find(path: TreePath): IdBinding?

    /** The path currently bound to [id] (the 4a `ownerOf` seam), or null when the id is unbound. */
    fun pathOf(id: PageId): TreePath?

    /**
     * Binds [path] to [id], replacing the path's previous binding and superseding any stale binding
     * of the same id under another path (a moved file). The caller's duplicate policy guarantees a
     * live owner is never superseded this way.
     */
    fun bind(path: TreePath, id: PageId, materialized: Boolean)

    /** Marks [path]'s binding materialized — called after the patched file write lands (§5.2). */
    fun markMaterialized(path: TreePath)

    /** Every binding, for reporting and tests. */
    fun bindings(): List<IdBinding>

    /** Records [issue] for the admin issues list. Recording the same issue again is a no-op. */
    fun record(issue: IdentityIssue)

    /** Every recorded issue. */
    fun issues(): List<IdentityIssue>
}

/** One id_map binding: the page at [path] is [id]; [materialized] iff the id also lives in the file. */
data class IdBinding(
    val path: TreePath,
    val id: PageId,
    val materialized: Boolean,
)
