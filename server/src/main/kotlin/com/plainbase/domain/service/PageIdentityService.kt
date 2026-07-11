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
 * rule only WITHIN a root). The winner's assignment carries no issue but exposes the beaten owner
 * as [Assignment.supersededOwner], so a pass can record the loser-behalf issue when the loser sits
 * in a registered-but-unscanned root (the D16 outcome-two audit rule). The loser reassigns like the
 * within-root loser, with one GUARD: it reuses its own `mappedId` ONLY when that differs from the
 * contested id, else it MINTS FRESH. The guard closes the prior-owner case (two checkouts of one
 * repo): a stale read of the loser's own binding yields the contested id, and reusing it would
 * either key-complete the winner's fresh row away (a silent cross-root steal) or trip the
 * snapshot's byId uniqueness check (a rebuild crash). The mint is rescan-stable from the next pass
 * on. REACHABILITY: the guard's mappedId == contested-id case is unreachable in every C2 pass - on
 * the bind-inline builder path the winner's key-complete bind already deleted the loser's stale row
 * before the loser resolves, and in single-root adopt the cross-root arm only fires when a FOREIGN
 * row holds the contested id, so UNIQUE(id) precludes the loser's own binding equaling it. The
 * guard is a pure BELT protecting future batched-bind refactors and C4 multi-root
 * resolution-without-binding passes; keep guard, execution invariants, and the unit test that
 * drives the case synthetically - they are complementary, none redundant.
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

    /**
     * A page's resolved identity plus any [issue] raised while resolving it. [supersededOwner] is
     * non-null exactly on the cross-root WINNER arm: the beaten owner's rooted path, exposed so the
     * pass can record the loser-behalf [IdentityIssue.CrossRootDuplicateId] when that owner's root
     * is registered but unscanned (D16 outcome two - the winner's page itself never carries the issue).
     */
    data class Assignment(
        val id: PageId,
        val source: Source,
        val issue: IdentityIssue? = null,
        val supersededOwner: RootedPath? = null,
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
     *   D16 `BindingVisibility` rule to id_map rows so a detached binding is never treated as an owner.
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
                return duplicate(path, frontmatterId, mappedId, owner)
            }
            return Assignment(frontmatterId, Source.FRONTMATTER)
        }

        // No valid frontmatter id: keep the id_map entry if one exists, else mint a fresh UUIDv7.
        return when (mappedId) {
            null -> Assignment(idProvider.next(), Source.MINTED)
            else -> Assignment(mappedId, Source.ID_MAP)
        }
    }

    /** The duplicate branch: within-root §5.2 verbatim, cross-root the D17 rank contest (class doc). */
    private fun duplicate(path: RootedPath, frontmatterId: PageId, mappedId: PageId?, owner: RootedPath): Assignment = when {
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
        // Cross-root, this path outranks the owner: it WINS the id (D17 - rank beats previously-
        // bound). No issue on the winner; the beaten owner is exposed for loser-behalf recording.
        rootRank(path.root) < rootRank(owner.root) ->
            Assignment(frontmatterId, Source.FRONTMATTER, supersededOwner = owner)
        // Cross-root loser: reassign with the D17 mint guard - its own stale binding equaling the
        // contested id must never be reused (the prior-owner steal/crash case, class doc).
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
