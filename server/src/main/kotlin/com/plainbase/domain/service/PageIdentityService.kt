package com.plainbase.domain.service

import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath

/**
 * Pure precedence + duplicate-policy logic for assigning a page its identity (§5.2 made executable).
 * No I/O, no persistence — the caller (`AdoptionPass`, `IndexBuilder`) supplies the inputs and
 * persists the resulting assignments and issues.
 *
 * **Precedence (frozen):** a valid frontmatter `id` > an existing `id_map` entry > a freshly minted
 * UUIDv7. "Valid frontmatter `id`" means it matches the §A4 canonical shape (any version, owner
 * ruling) — anything else (e.g. `1-1-1-1-1`, which `UUID.fromString` would leniently accept) is
 * treated as **absent** and routed to the issues list, never silently honored.
 *
 * **Within-root duplicate policy (frozen, §5.2 - unchanged by C2):** when two paths under ONE root
 * carry the same frontmatter `id` (a copied file), the path already bound to that id keeps it; the
 * other path gets a fresh UUIDv7 (not materialized) plus an [IdentityIssue.DuplicateId] recording
 * both paths. "Older path keeps the id" is operationalized as "previously-bound path keeps it" —
 * deterministic without Git/mtime guesses.
 * **Rescan stability:** first detection mints fresh per §5.2, but a subsequent rescan of the same
 * conflict reuses this path's existing `id_map` binding rather than minting anew, so the copy's
 * `/p/{root}/{id}` permalink stays stable across rescans (the still-conflicting file keeps raising the issue).
 *
 * **Cross-root duplicates are legal (per-root identity, ADR-0012):** the same frontmatter `id:` in two DIFFERENT
 * roots is not a contest, and both pages keep it, each answering its own `/p/{root}/{id}`. The [ownerOf] seam is
 * root-scoped, so a returned owner always lives in the page's OWN root and rank decides SOURCE precedence only.
 *
 * A reassigning loser keeps its own `mappedId` only when [ownerOf] says that binding is still ITS to keep
 * (nobody else holds the id), else it MINTS FRESH. The gate closes two cases, and the second is why it is a
 * gate and not an id comparison:
 *  - the prior-owner case, where the loser's own binding IS the contested id because it held that id under this
 *    root before a same-root claimant won it this pass: reusing it would either key-complete the winner's fresh
 *    row away (a silent within-root steal) or trip the snapshot's byRootedId (root, id) uniqueness check (a
 *    rebuild crash);
 *  - and the case a `mappedId != contested id` check cannot see at all: the loser's binding names a DIFFERENT
 *    id, which another claimant of this same pass has already won. Reusing it hands one id to two live pages -
 *    the same crash, reached by a page that never contested that id.
 * Either way the mint is rescan-stable from the next pass on, since the fresh id becomes this path's binding.
 *
 * **A mapped id is contestable too, and for the same reason.** The within-root duplicate loser above needed
 * a frontmatter id to lose the contest with; a page that carries NONE, but whose `id_map` row holds the
 * id an earlier claimant just won, loses it exactly as hard - so [ownerOf] gates EVERY reuse of a
 * `mappedId`, the no-frontmatter arm and both duplicate arms alike, and a taken id is reassigned (fresh
 * mint) with the issue recorded.
 *
 * **This arm is why BOTH passes now resolve the whole corpus before they bind ANY of it.** A pass that
 * bound INLINE could never reach the check: the winner's key-complete bind had already swept the loser's
 * row, so [mappedId] arrived null and the loser looked like a page that had never been seen before - a
 * silent fresh MINT, no duplicate detected, no issue recorded, and a durable permalink quietly moved off the
 * page that owned it. Reading the id_map as it stood BEFORE the pass touched it is what makes the beaten owner
 * visible as a beaten owner. Resolution therefore never depends on a side effect of the previous page's
 * bind - which is also what lets `AdoptionPass`'s read-only plan abort without a trace.
 *
 * Pure domain code: only chunk 1.5/4a/C1 domain types appear.
 */
class PageIdentityService(
    private val idProvider: IdProvider,
) {

    /** How a page's resolved [id] was chosen — provenance the caller persists / surfaces in `adopt`. */
    enum class Source {
        /** A valid (canonical-shape, any version) frontmatter `id` was honored. */
        FRONTMATTER,

        /** No valid frontmatter id; the existing `id_map` entry for this path was kept. */
        ID_MAP,

        /** Neither source applied (or a duplicate was reassigned); a fresh UUIDv7 was minted. */
        MINTED,
    }

    /** A page's resolved identity plus any [issue] raised while resolving it. */
    data class Assignment(
        val id: PageId,
        val source: Source,
        val issue: IdentityIssue? = null,
    )

    /**
     * Resolves [path]'s identity under the frozen precedence.
     *
     * @param rawFrontmatterId the literal frontmatter `id` text as written, or null if absent. Parsed
     *   through the §A4 shape gate ([PageId.of]); a present-but-shape-invalid value is treated as
     *   absent (no issue raised here — the invalid-id warning is the reader's per-page concern, §C2).
     * @param mappedId the page's existing `id_map` entry, or null if unmapped.
     * @param ownerOf the previously-bound owner of a given id WITHIN this page's root (root-scoped since C5), or
     *   null if that id is not yet bound to another live path under this root — the duplicate-detection seam. The
     *   caller threads its already-assigned ids through this lookup so a within-run duplicate is caught
     *   deterministically, and applies the D16 [BindingVisibility.isLive] rule to id_map rows so a detached binding
     *   is never treated as an owner. Because it is root-scoped, a returned owner is always in [path]'s own root: a
     *   cross-root duplicate is no longer a contest (per-root identity), so both roots keep the id.
     */
    fun resolve(
        path: RootedPath,
        rawFrontmatterId: String?,
        mappedId: PageId?,
        ownerOf: (PageId) -> RootedPath?,
    ): Assignment {
        val frontmatterId = rawFrontmatterId?.let { PageId.of(it) }
        if (frontmatterId != null) {
            val owner = ownerOf(frontmatterId)
            if (owner != null && owner != path) {
                return duplicate(path, frontmatterId, mappedId, owner, ownerOf)
            }
            return Assignment(frontmatterId, Source.FRONTMATTER)
        }

        // No valid frontmatter id: keep the id_map entry if one exists, else mint a fresh UUIDv7.
        val mapped = mappedId ?: return Assignment(idProvider.next(), Source.MINTED)

        // ...unless the binding is no longer this page's to keep, because an earlier claimant in THIS pass
        // already took the id (a within-root contest, lost by a page that had no frontmatter id of its
        // own to lose it with). A pass that binds INLINE never reaches this check - the winner's key-complete
        // bind has already deleted the row, so `mappedId` came back null and the mint below is the `null` arm
        // above. A pass that RESOLVES BEFORE IT BINDS (`AdoptionPass`'s read-only plan, which must be able to
        // abort without a trace) still reads the stale row, and honoring it would hand the winner's id to two
        // pages at once: a duplicate in the plan, and a byRootedId uniqueness crash the moment it is indexed. So the
        // owner check gates EVERY reuse of a mappedId - this arm and the reassignments in [duplicate] - and
        // resolve() no longer depends on a side effect of the last bind.
        val owner = ownerOf(mapped)
        if (owner == null || owner == path) return Assignment(mapped, Source.ID_MAP)
        // owner is in path.root by construction (ownerOf is root-scoped, C5): a within-root claimant already took
        // the mapped id this pass, so this page reassigns and records the within-root duplicate.
        return Assignment(
            id = idProvider.next(),
            source = Source.MINTED,
            issue = IdentityIssue.DuplicateId(id = mapped, root = path.root, keptPath = owner.path, reassignedPath = path.path),
        )
    }

    /**
     * The duplicate branch: a valid frontmatter id already bound to ANOTHER path of the SAME root is a copied-file
     * duplicate (§5.2). Since [ownerOf] is root-scoped (C5) a returned owner is always in [path]'s own root - a
     * cross-root duplicate is no longer a contest, both roots keep the id - so this is the ONLY arm. The
     * previously-bound path keeps the id; this path reassigns. First detection mints fresh, but a rescan reuses this
     * path's own id_map binding so /p/{root}/{id} stays stable.
     */
    private fun duplicate(
        path: RootedPath,
        frontmatterId: PageId,
        mappedId: PageId?,
        owner: RootedPath,
        ownerOf: (PageId) -> RootedPath?,
    ): Assignment = reassign(
        path,
        mappedId,
        ownerOf,
        IdentityIssue.DuplicateId(id = frontmatterId, root = path.root, keptPath = owner.path, reassignedPath = path.path),
    )

    /**
     * The ONE way a duplicate loser gets an identity: its own `id_map` binding when [ownerOf] says that binding
     * is still its to keep, else a fresh mint - the SAME gate [resolve]'s id_map arm applies, for the same reason
     * (class doc). A `mappedId` some other claimant of this pass has already won is not a fallback, it is one id
     * on two live pages IN ONE ROOT: a duplicate in the plan, and a `PageIndex` byRootedId crash the moment it is indexed.
     */
    private fun reassign(
        path: RootedPath,
        mappedId: PageId?,
        ownerOf: (PageId) -> RootedPath?,
        issue: IdentityIssue,
    ): Assignment {
        val kept = mappedId?.takeIf { id ->
            val owner = ownerOf(id)
            owner == null || owner == path
        }
        return Assignment(
            id = kept ?: idProvider.next(),
            source = if (kept != null) Source.ID_MAP else Source.MINTED,
            issue = issue,
        )
    }
}

/**
 * The precondition BOTH resolve-then-bind passes (`IndexBuilder`, `AdoptionPass`) check immediately before they
 * make a plan DURABLE: one id, one page. Every rule above is meant to guarantee it, and the point of checking is
 * that a rule can be wrong - `duplicate()` reused a `mappedId` blind for a whole release, and nothing between
 * there and the disk would have noticed.
 *
 * It has to run BEFORE the binds because a durable duplicate cannot be walked back: `id_map.bind` is key-complete
 * within a root, so a second bind of the same id in ONE root DELETES the first page's row, and the only existing
 * check ([PageIndex]'s `byRootedId`) runs AFTER the whole loop - it throws on a snapshot whose rows are already
 * rewritten, and it throws again on every boot that follows. Failing HERE aborts a pass that has changed nothing:
 * the last-good snapshot stands, the rows stand, and the fault is loud, named, and fixable.
 *
 * The rule is PER ROOT (per-root identity, C5): the same id in two DIFFERENT roots is legal, so the check groups by
 * ([RootedPageId]) - one id per page WITHIN a root - and a cross-root duplicate passes.
 */
internal fun requireDistinctIds(plan: Map<RootedPath, PageId>) {
    val duplicates = plan.entries
        .groupBy({ RootedPageId(it.key.root, it.value) }, { it.key })
        .filterValues { it.size > 1 }
    check(duplicates.isEmpty()) {
        "identity resolution produced ONE id for SEVERAL pages IN ONE ROOT, which no bind may make durable: " +
            duplicates.entries.joinToString("; ") { (rooted, paths) ->
                "${rooted.id.value} in '${rooted.root}' -> ${paths.joinToString { it.path.value }}"
            }
    }
}
