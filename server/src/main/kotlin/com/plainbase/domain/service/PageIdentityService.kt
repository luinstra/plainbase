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
 * The loser reassigns like the within-root loser, with one GUARD: it reuses its own `mappedId` ONLY
 * when that differs from the contested id, else it MINTS FRESH. The guard closes the prior-owner case
 * (two checkouts of one repo): a stale read of the loser's own binding yields the contested id, and
 * reusing it would either key-complete the winner's fresh row away (a silent cross-root steal) or trip
 * the snapshot's byId uniqueness check (a rebuild crash). The mint is rescan-stable from the next pass
 * on. REACHABILITY: the guard's mappedId == contested-id case stays unreachable in every pass - the
 * cross-root arm only fires when ANOTHER path owns the contested id, and UNIQUE(id) precludes the
 * loser's own binding equaling it. The guard is a pure BELT protecting future batched-bind refactors;
 * keep guard, execution invariants, and the unit test that drives the case synthetically.
 *
 * **A mapped id is contestable too, and for the same reason.** The cross-root loser above needed a
 * frontmatter id to lose the contest with; a page that carries NONE, but whose `id_map` row holds the
 * id an earlier claimant just won, loses it exactly as hard - so [ownerOf] is consulted on the id_map
 * arm as well, and a taken id is reassigned (fresh mint) with the issue recorded.
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
                return duplicate(path, frontmatterId, mappedId, owner, supersedable(owner))
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
        // owner check is on BOTH id sources, and resolve() no longer depends on a side effect of the last bind.
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
    ): Assignment = when {
        // A valid frontmatter id already bound to ANOTHER path of the SAME root is a copied-file
        // duplicate: the previously-bound path keeps it; this path is reassigned. First detection
        // mints fresh, but a rescan reuses this path's own id_map binding so /p/{id} stays stable.
        owner.root == path.root -> Assignment(
            id = mappedId ?: idProvider.next(),
            source = if (mappedId != null) Source.ID_MAP else Source.MINTED,
            issue = IdentityIssue.DuplicateId(
                id = frontmatterId,
                root = path.root,
                keptPath = owner.path,
                reassignedPath = path.path,
            ),
        )
        // Cross-root, this path outranks the owner AND the pass may take the id from it: it WINS
        // (D17 - rank beats previously-bound). The beaten owner sits in a root this pass SCANNED, so
        // it re-resolves later in this same pass (rank order) and records its own loser issue there.
        supersedable && rootRank(path.root) < rootRank(owner.root) -> Assignment(frontmatterId, Source.FRONTMATTER)
        // Cross-root loser - beaten on rank, or holding a rank it cannot cash because the owner's root
        // is one this pass never scanned (D16: rank cannot settle a contest one side did not turn up
        // to, and the bind would destroy that root's durable binding). Either way: reassign, with the
        // D17 mint guard - its own stale binding equaling the contested id must never be reused (the
        // prior-owner steal/crash case, class doc).
        else -> {
            val kept = mappedId?.takeIf { it != frontmatterId }
            Assignment(
                id = kept ?: idProvider.next(),
                source = if (kept != null) Source.ID_MAP else Source.MINTED,
                issue = IdentityIssue.CrossRootDuplicateId(id = frontmatterId, kept = owner, reassigned = path),
            )
        }
    }
}
