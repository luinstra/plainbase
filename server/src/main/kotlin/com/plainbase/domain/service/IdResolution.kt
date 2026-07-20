package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

/**
 * The outcome of resolving a bare [PageId] to the root that OWNS it (the Option B, id_map-first resolver;
 * [PageRootResolver.resolve]). One durable claimant is [One]; more than one is [Ambiguous] (FAKE-only under the
 * `UNIQUE(id)` invariant - no real row can produce it until C5 drops that constraint); none is [None].
 *
 * The full One/Ambiguous/None + candidate-list contract is built AHEAD of its real C5 trigger by design: the
 * resolving route, the 300/409 disambiguation shapes and the candidate ranking all land here so C5 need only flip
 * the schema, never re-open the wire.
 */
sealed interface IdResolution {

    /** Exactly one registered root holds the id - the durable claimant selected id_map-first. */
    data class One(val root: RootName) : IdResolution

    /** More than one registered root holds the id, ranked in D7 winner order (FAKE-only in C4). */
    data class Ambiguous(val candidates: List<RootName>) : IdResolution

    /** No registered root holds the id - an unknown id, or a binding under a detached root. */
    data object None : IdResolution
}

/**
 * The id names more than one root and the caller gave no `root` to disambiguate. Mapped, exactly like
 * [RootUnavailable]/[AbsenceUnverified], in the `guarded {}` funnel (REST 409 `ambiguous_page_id` + the candidate
 * list) and the MCP catch funnels (an `ambiguousResult`); the permalink surfaces answer 300. [candidates] is the
 * ranked registered-root list, so every consumer emits the SAME order.
 */
class AmbiguousPageId(val id: PageId, val candidates: List<RootName>) : RuntimeException(
    "the page id '${id.value}' is held by more than one root (${candidates.joinToString(", ") { it.value }}); " +
        "name the root to disambiguate",
)
