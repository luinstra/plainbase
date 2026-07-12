package com.plainbase.domain.repository

import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * Persistence port for the page-identity map and the identity issues list (§5.2, chunk 4b;
 * (root, path)-keyed since multi-root C2, ADR-0011).
 *
 * The id_map binds each rooted content path to its [PageId] plus the materialization state — whether
 * the id also lives in the file's frontmatter. Pre-materialization identity is path-keyed by accepted
 * design (§5.2): durability is only promised for materialized ids.
 *
 * One binding per (root, path) AND per id, so the adapter may enforce id-uniqueness structurally.
 * The adapter's stale-unbind in [bind] is key-complete: it removes ANY other (root, path) holding
 * the id, which is what lets a live bind supersede a DETACHED row (a binding under a root absent
 * from the registry - the ADR-0011 D2 conditional-restore consequence) and sweep a moved file's own
 * stale row. The caller's duplicate policy is what bounds it: a LIVE owner's row is only ever
 * removed as the deterministic D17 rank-contest outcome, and only when the caller SCANNED that
 * owner's root - so the loser is a page the same pass re-resolves and reassigns. A binding under a
 * root the caller could not look at is never superseded at all (D16 [BindingVisibility]
 * [com.plainbase.domain.service.BindingVisibility.isSupersedable]), which is what keeps an outage
 * from costing a page its durable identity.
 *
 * Pure domain port: only chunk 1.5/4a/C1 domain types appear; the at-rest representation (16-byte
 * BLOBs, decision log #6) is invisible here — that is the storage adapter's single concern.
 */
interface IdMapRepository {

    /** The binding for [path], or null when the rooted path is unmapped. */
    fun find(path: RootedPath): IdBinding?

    /** The rooted path currently bound to [id] (the 4a `ownerOf` seam), or null when the id is unbound. */
    fun pathOf(id: PageId): RootedPath?

    /**
     * Binds [path] to [id], replacing the key's previous binding and superseding any stale binding
     * of the same id under another (root, path) - see the class doc for the key-complete SQL vs
     * the caller's supersede POLICY split (ADR-0011 D2/D16/D17).
     */
    fun bind(path: RootedPath, id: PageId, materialized: Boolean)

    /** Marks [path]'s binding materialized — called after the patched file write lands (§5.2). */
    fun markMaterialized(path: RootedPath)

    /** Every binding, for reporting and tests. */
    fun bindings(): List<IdBinding>

    /** Every root name holding at least one binding - the boot detached-root guard + C4 health seam. */
    fun roots(): Set<RootName>

    /** Records [issue] for the admin issues list. Recording the same issue again is a no-op. */
    fun record(issue: IdentityIssue)

    /** Every recorded issue. */
    fun issues(): List<IdentityIssue>
}

/** One id_map binding: the page at [path] is [id]; [materialized] iff the id also lives in the file. */
data class IdBinding(
    val path: RootedPath,
    val id: PageId,
    val materialized: Boolean,
)
