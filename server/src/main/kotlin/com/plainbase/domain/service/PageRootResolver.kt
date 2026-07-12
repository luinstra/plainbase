package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry

/**
 * The ONE owner of the two root questions every gated surface asks: *who owns this page id?* and *is
 * that root serving?* They are the same trust boundary seen from two sides, and both need the registry,
 * so they live together - a second implementation behind a raw `IdMapRepository`/`RootRegistry` in some
 * facade is exactly the drift this service exists to prevent.
 *
 * Injected into all three guarded facades (the write gates, the propose gates, the id-addressed reads +
 * the permalink) and reached by `ProposalService` through a narrow `(RootName) -> RootStatus` lambda.
 *
 * **Both snapshots arrive as PARAMETERS, and that is load-bearing.** `IndexBuilder.current` and
 * `RootAvailability.current()` are fresh reads of a published `AtomicReference` every time they are
 * touched. If the gate resolved a root from read #1 and the write re-resolved the page from read #2, a
 * rebuild landing between them that REASSIGNS the id's owning root (the ADR-0011 D17 cross-root
 * duplicate-id rank contest is precisely such a reassignment) would authorize root A and write into root
 * B. So each request reads each holder exactly ONCE into a local and threads that immutable object
 * through resolution, the availability check, the page resolve and the write intent - the race is
 * unreachable by construction rather than detected. That is also what keeps this service cheap,
 * stateless and holder-free, which is what makes it safe to reach from the domain proposal service.
 */
class PageRootResolver(
    private val idMap: IdMapRepository,
    private val registry: RootRegistry,
) {

    /**
     * The REGISTERED root that owns [id] IN [snapshot], or null when none does - an unknown id, or a
     * binding under a DETACHED root. The `id_map` fallback fires only on a snapshot MISS (an unknown id,
     * or a page in a root that was never scanned this process because it was unavailable at boot), so the
     * hot read/edit path never touches the DB.
     */
    fun rootOf(snapshot: PageIndex, id: PageId): RootName? =
        (snapshot.byId[id]?.root ?: idMap.pathOf(id)?.root)?.takeIf { registry.byName(it) != null }

    /**
     * [root]'s serving status. DETACHED is checked FIRST: [RootAvailability] only ever tracks REGISTERED
     * roots, so it answers a vacuous "available" for a name the registry does not know (ADR-0011 D15) -
     * and a name read back off a durable row is exactly such a name.
     */
    fun statusOf(root: RootName, availability: RootAvailability.Snapshot): RootStatus = when {
        registry.byName(root) == null -> RootStatus.DETACHED
        !availability.isAvailable(root) -> RootStatus.UNAVAILABLE
        else -> RootStatus.AVAILABLE
    }
}

/**
 * A root's serving status. DETACHED is treated as permanently UNAVAILABLE everywhere it is consulted:
 * the row stays as it is, is never applied, never rewritten, never deleted, and re-adding the root's
 * name revives it (ADR-0011 D2/D15).
 */
enum class RootStatus { AVAILABLE, UNAVAILABLE, DETACHED }
