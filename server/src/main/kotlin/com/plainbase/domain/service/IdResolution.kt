package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

/**
 * The outcome of resolving a bare [PageId] to the root that OWNS it (the Option B, id_map-first resolver;
 * [PageRootResolver.resolve]). Exactly one registered live claimant with no tombstone is [One]; two or more live
 * claimants, OR one live claimant ALONGSIDE a registered tombstone (the fail-closed MIXED case), is [Ambiguous]; no
 * registered live claimant - an unknown id, a binding under a detached root, OR a purely-retired id - is [None]. A
 * purely-retired id is NEVER [Ambiguous]: the surfaces split its 404/410/300 answer off the tombstone list.
 */
sealed interface IdResolution {

    /** Exactly one registered root holds the id LIVE, and no registered root holds a tombstone for it. */
    data class One(val root: RootName) : IdResolution

    /**
     * The bare id refuses to resolve: 2+ registered roots hold it live, OR a live root holds it alongside a
     * registered tombstone. [candidates] is the D7-ranked registered-root union. [hasRetiredCandidate] is a DOMAIN
     * flag only (never a serialized key): true when a candidate root holds a TOMBSTONE, so the mixed message can
     * warn that some candidates have retired the id.
     */
    data class Ambiguous(val candidates: List<RootName>, val hasRetiredCandidate: Boolean = false) : IdResolution

    /** No registered root holds the id LIVE - an unknown id, a detached-root binding, or a purely-retired id. */
    data object None : IdResolution
}

/**
 * The id refuses to resolve to one root and the caller gave no `root` to disambiguate. Mapped, exactly like
 * [RootUnavailable]/[AbsenceUnverified], in the `guarded {}` funnel (REST 409 `ambiguous_page_id` + the candidate
 * list) and the MCP catch funnels (an `ambiguousResult`); the permalink surfaces answer 300. [candidates] is the
 * ranked registered-root list, so every consumer emits the SAME order. [hasRetiredCandidate] is the same domain
 * flag as [IdResolution.Ambiguous] (no serialized key), so the mixed message can note a retired candidate.
 */
class AmbiguousPageId(
    val id: PageId,
    val candidates: List<RootName>,
    val hasRetiredCandidate: Boolean = false,
) : RuntimeException(
    "the page id '${id.value}' is claimed by more than one root (${candidates.joinToString(", ") { it.value }})" +
        (if (hasRetiredCandidate) ", some of which have retired it" else "") +
        "; name the root to disambiguate",
)
