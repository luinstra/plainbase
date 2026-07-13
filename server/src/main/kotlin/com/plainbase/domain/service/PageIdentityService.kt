package com.plainbase.domain.service

import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
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
 * `/p/{id}` permalink stays stable across rescans (the still-conflicting file keeps raising the issue).
 *
 * **Cross-root duplicate policy (ADR-0011 D2/D17):** when the two claimants live in DIFFERENT roots,
 * registry rank ([rootRank], the D7 origin-line order) decides - the earlier-declared root wins the
 * id REGARDLESS of which side held the prior id_map binding ("previously-bound keeps it" stays the
 * rule only WITHIN a root) - but ONLY between roots the pass actually SCANNED. An owner the pass
 * could not look at is NON-SUPERSEDABLE ([supersedable], the D16 rule): rank cannot settle a contest
 * one side did not turn up to, and the winner's key-complete bind would DELETE that root's durable
 * binding while its carried section still holds the page. So the pass's own page reassigns instead,
 * and the contest waits for a pass that can see both roots.
 *
 * The loser reassigns like the within-root loser, and BOTH reassign through ONE gate: a loser keeps its own
 * `mappedId` only when [ownerOf] says that binding is still ITS to keep (nobody else holds the id), else it
 * MINTS FRESH. The gate closes two cases, and the second is why it is a gate and not an id comparison:
 *  - the prior-owner case (two checkouts of one repo), where the loser's own binding IS the contested id:
 *    reusing it would either key-complete the winner's fresh row away (a silent cross-root steal) or trip the
 *    snapshot's byId uniqueness check (a rebuild crash);
 *  - and the case a `mappedId != contested id` check cannot see at all: the loser's binding names a DIFFERENT
 *    id, which another claimant of this same pass has already won. Reusing it hands one id to two live pages -
 *    the same crash, reached by a page that never contested that id.
 * Either way the mint is rescan-stable from the next pass on, since the fresh id becomes this path's binding.
 *
 * **A mapped id is contestable too, and for the same reason.** The cross-root loser above needed a
 * frontmatter id to lose the contest with; a page that carries NONE, but whose `id_map` row holds the
 * id an earlier claimant just won, loses it exactly as hard - so [ownerOf] gates EVERY reuse of a
 * `mappedId`, the no-frontmatter arm and both duplicate arms alike, and a taken id is reassigned (fresh
 * mint) with the issue recorded.
 *
 * **This arm is why BOTH passes now resolve the whole corpus before they bind ANY of it.** A pass that
 * bound INLINE could never reach the check: the winner's key-complete bind had already swept the loser's
 * row, so [mappedId] arrived null and the loser looked like a page that had never been seen before - a
 * silent fresh MINT, no duplicate detected, no issue recorded, and a durable permalink quietly moved to
 * another root. Reading the id_map as it stood BEFORE the pass touched it is what makes the beaten owner
 * visible as a beaten owner. Resolution therefore never depends on a side effect of the previous page's
 * bind - which is also what lets `AdoptionPass`'s read-only plan abort without a trace.
 *
 * Pure domain code: only chunk 1.5/4a/C1 domain types appear.
 */
class PageIdentityService(
    private val idProvider: IdProvider,
    private val rootRank: (RootName) -> Int,
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
     * @param ownerOf the previously-bound owner of a given id, or null if that id is not yet bound to
     *   another live path — the duplicate-detection seam. The caller threads its already-assigned ids
     *   through this lookup so a within-run duplicate is caught deterministically, and applies the
     *   D16 [BindingVisibility.isLive] rule to id_map rows so a detached binding is never treated as an owner.
     * @param supersedable whether the rank contest is even ALLOWED to take an id away from a given owner —
     *   the D16 [BindingVisibility.isSupersedable] rule (an owner in a root this pass did not scan is
     *   untouchable, so [path] reassigns however it ranks). Consulted ONLY on the cross-root arm.
     */
    fun resolve(
        path: RootedPath,
        rawFrontmatterId: String?,
        mappedId: PageId?,
        ownerOf: (PageId) -> RootedPath?,
        supersedable: (RootedPath) -> Boolean,
    ): Assignment {
        val frontmatterId = rawFrontmatterId?.let { PageId.of(it) }
        if (frontmatterId != null) {
            val owner = ownerOf(frontmatterId)
            if (owner != null && owner != path) {
                return duplicate(path, frontmatterId, mappedId, owner, supersedable(owner), ownerOf)
            }
            return Assignment(frontmatterId, Source.FRONTMATTER)
        }

        // No valid frontmatter id: keep the id_map entry if one exists, else mint a fresh UUIDv7.
        val mapped = mappedId ?: return Assignment(idProvider.next(), Source.MINTED)

        // ...unless the binding is no longer this page's to keep, because an earlier claimant in THIS pass
        // already took the id (the cross-root rank contest, lost by a page that had no frontmatter id of its
        // own to lose it with). A pass that binds INLINE never reaches this check - the winner's key-complete
        // bind has already deleted the row, so `mappedId` came back null and the mint below is the `null` arm
        // above. A pass that RESOLVES BEFORE IT BINDS (`AdoptionPass`'s read-only plan, which must be able to
        // abort without a trace) still reads the stale row, and honoring it would hand the winner's id to two
        // pages at once: a duplicate in the plan, and a byId uniqueness crash the moment it is indexed. So the
        // owner check gates EVERY reuse of a mappedId - this arm and the reassignments in [duplicate] - and
        // resolve() no longer depends on a side effect of the last bind.
        val owner = ownerOf(mapped)
        if (owner == null || owner == path) return Assignment(mapped, Source.ID_MAP)
        return Assignment(
            id = idProvider.next(),
            source = Source.MINTED,
            // Cross-root by construction - a within-root claimant can never get here, because the within-root
            // rule hands the id to the PREVIOUSLY-BOUND path, which is precisely this one. Classified rather
            // than asserted: an unreachable arm that reports honestly costs one line and cannot rot.
            issue = if (owner.root == path.root) {
                IdentityIssue.DuplicateId(id = mapped, root = path.root, keptPath = owner.path, reassignedPath = path.path)
            } else {
                IdentityIssue.CrossRootDuplicateId(id = mapped, kept = owner, reassigned = path)
            },
        )
    }

    /** The duplicate branch: within-root §5.2 verbatim, cross-root the D17 rank contest (class doc). */
    private fun duplicate(
        path: RootedPath,
        frontmatterId: PageId,
        mappedId: PageId?,
        owner: RootedPath,
        supersedable: Boolean,
        ownerOf: (PageId) -> RootedPath?,
    ): Assignment = when {
        // A valid frontmatter id already bound to ANOTHER path of the SAME root is a copied-file
        // duplicate: the previously-bound path keeps it; this path is reassigned. First detection
        // mints fresh, but a rescan reuses this path's own id_map binding so /p/{id} stays stable.
        owner.root == path.root -> reassign(
            path,
            mappedId,
            ownerOf,
            IdentityIssue.DuplicateId(id = frontmatterId, root = path.root, keptPath = owner.path, reassignedPath = path.path),
        )
        // Cross-root, this path outranks the owner AND the pass may take the id from it: it WINS
        // (D17 - rank beats previously-bound). The beaten owner sits in a root this pass SCANNED, so
        // it re-resolves later in this same pass (rank order) and records its own loser issue there.
        supersedable && rootRank(path.root) < rootRank(owner.root) -> Assignment(frontmatterId, Source.FRONTMATTER)
        // Cross-root loser - beaten on rank, or holding a rank it cannot cash because the owner's root
        // is one this pass never scanned (D16: rank cannot settle a contest one side did not turn up
        // to, and the bind would destroy that root's durable binding). Either way: reassign.
        else -> reassign(path, mappedId, ownerOf, IdentityIssue.CrossRootDuplicateId(id = frontmatterId, kept = owner, reassigned = path))
    }

    /**
     * The ONE way a duplicate loser gets an identity: its own `id_map` binding when [ownerOf] says that binding
     * is still its to keep, else a fresh mint - the SAME gate [resolve]'s id_map arm applies, for the same reason
     * (class doc). A `mappedId` some other claimant of this pass has already won is not a fallback, it is one id
     * on two live pages: a duplicate in the plan, and a `PageIndex` byId crash the moment it is indexed.
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
 * It has to run BEFORE the binds because a durable duplicate cannot be walked back: `id_map.bind` is key-complete,
 * so the second bind of an id DELETES the first page's row, and the only existing check ([PageIndex]'s `byId`) runs
 * AFTER the whole loop - it throws on a snapshot whose rows are already rewritten, and it throws again on every
 * boot that follows. Failing HERE aborts a pass that has changed nothing: the last-good snapshot stands, the rows
 * stand, and the fault is loud, named, and fixable.
 */
internal fun requireDistinctIds(plan: Map<RootedPath, PageId>) {
    val duplicates = plan.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
    check(duplicates.isEmpty()) {
        "identity resolution produced ONE id for SEVERAL pages, which no bind may make durable: " +
            duplicates.entries.joinToString("; ") { (id, paths) -> "${id.value} -> ${paths.joinToString { it.path.value }}" }
    }
}
