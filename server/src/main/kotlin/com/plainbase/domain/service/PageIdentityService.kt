package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.UuidV7

/**
 * Pure precedence + duplicate-policy logic for assigning a page its identity (§5.2 made executable).
 * No I/O, no persistence — the caller (chunk 4b's `AdoptionPass`) supplies the inputs and persists
 * the resulting assignments and issues.
 *
 * **Precedence (frozen):** a valid frontmatter `id` > an existing `id_map` entry > a freshly minted
 * UUIDv7. "Valid frontmatter `id`" means it matches the §A4 canonical shape (any version, owner
 * ruling) — anything else (e.g. `1-1-1-1-1`, which `UUID.fromString` would leniently accept) is
 * treated as **absent** and routed to the issues list, never silently honored.
 *
 * **Duplicate policy (frozen, §5.2):** when two paths carry the same frontmatter `id` (a copied
 * file), the path already bound to that id keeps it; the other path gets a fresh UUIDv7 (not
 * materialized) plus an [IdentityIssue.DuplicateId] recording both paths. "Older path keeps the id"
 * is operationalized as "previously-bound path keeps it" — deterministic without Git/mtime guesses.
 * **Rescan stability:** first detection mints fresh per §5.2, but a subsequent rescan of the same
 * conflict reuses this path's existing `id_map` binding rather than minting anew, so the copy's
 * `/p/{id}` permalink stays stable across rescans (the still-conflicting file keeps raising the issue).
 *
 * Pure domain code: only chunk 1.5/4a domain types appear.
 */
class PageIdentityService(private val uuidV7: UuidV7) {

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
    data class Assignment(val id: PageId, val source: Source, val issue: IdentityIssue? = null)

    /**
     * Resolves [path]'s identity under the frozen precedence.
     *
     * @param rawFrontmatterId the literal frontmatter `id` text as written, or null if absent. Parsed
     *   through the §A4 shape gate ([PageId.of]); a present-but-shape-invalid value is treated as
     *   absent (no issue raised here — the invalid-id warning is the reader's per-page concern, §C2).
     * @param mappedId the page's existing `id_map` entry, or null if unmapped.
     * @param ownerOf the previously-bound owner of a given id, or null if that id is not yet bound to
     *   another path — the duplicate-detection seam. The caller threads its already-assigned ids
     *   through this lookup so a within-run duplicate is caught deterministically.
     */
    fun resolve(
        path: TreePath,
        rawFrontmatterId: String?,
        mappedId: PageId?,
        ownerOf: (PageId) -> TreePath?,
    ): Assignment {
        val frontmatterId = rawFrontmatterId?.let { PageId.of(it) }
        if (frontmatterId != null) {
            val owner = ownerOf(frontmatterId)
            // A valid frontmatter id already bound to ANOTHER path is a copied-file duplicate: the
            // previously-bound path keeps it; this path is reassigned. First detection mints fresh,
            // but a rescan reuses this path's own id_map binding so the copy's /p/{id} stays stable.
            if (owner != null && owner != path) {
                return Assignment(
                    id = mappedId ?: uuidV7.next(),
                    source = if (mappedId != null) Source.ID_MAP else Source.MINTED,
                    issue = IdentityIssue.DuplicateId(id = frontmatterId, keptPath = owner, reassignedPath = path),
                )
            }
            return Assignment(frontmatterId, Source.FRONTMATTER)
        }

        // No valid frontmatter id: keep the id_map entry if one exists, else mint a fresh UUIDv7.
        return when (mappedId) {
            null -> Assignment(uuidV7.next(), Source.MINTED)
            else -> Assignment(mappedId, Source.ID_MAP)
        }
    }
}
