package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath

/**
 * The ONE owner of the two root questions every gated surface asks: *who owns this page id?* and *is
 * that root serving?* They are the same trust boundary seen from two sides, and both need the registry,
 * so they live together - a second implementation behind a raw `IdMapRepository`/`RootRegistry` in some
 * facade is exactly the drift this service exists to prevent.
 *
 * Injected into all three guarded facades (the write gates, the propose gates, the id-addressed reads +
 * the permalink) and reached by `ProposalService` through a narrow `(RootName) -> RootStatus` lambda.
 *
 * **[resolve] is id_map-FIRST (Option B), and that is the durable-authority choice.** The bare `/p/{id}` and bare
 * REST id read resolve the owning root from the DURABLE `id_map`, never from a snapshot - so a [IdResolution.One]
 * always names a durable claimant (the property `resolve(One).root in rootsHoldingId(id)` holds unconditionally),
 * and a cross-root move in flight fails CLOSED (503/404/410) rather than serving the displaced root. This retires
 * the old "the hot path never touches the DB" promise for the BARE path: every bare resolution pays ONE indexed
 * point-SELECT. The CANONICAL `/p/{root}/{id}` and `?root=`-present read paths stay snapshot-first-hot - a
 * pinned READ is coherent-stale on a hit, no durable check - so the canonical-hot property is preserved there.
 * A rooted-miss 410 also pays a `rootsHoldingId` point-SELECT to find live alternate holders. That cost is limited
 * to the cold retired path because a present read returns before the tombstone arm.
 * [statusOf] takes a `RootAvailability.Snapshot` PARAMETER rather than reading the holder itself: a caller threads
 * ONE availability read through ONE decision, and a service that reached for the holder mid-decision would
 * silently mint a second read that could disagree with the first. [resolvePinned] takes no snapshot at all - the
 * only status it needs is DETACHED, which is registry-derived.
 *
 * **A recorded known, flagged for measurement before C5 widens the fan-out (not a task, and deliberately not a
 * gate):** every BARE id read now pays that durable point-SELECT, and `DatabaseFactory` hands SQLDelight a plain
 * `JdbcSqliteDriver` - a connection per statement, outside any transaction - so the per-read cost is connection
 * acquisition plus the indexed lookup, not the lookup alone.
 */
class PageRootResolver(
    private val idMap: IdMapRepository,
    private val registry: RootRegistry,
) {

    /**
     * The Option B resolution of [id] to its durable owning root: [IdResolution.One] when exactly one REGISTERED
     * root holds a live `id_map` binding, [IdResolution.Ambiguous] when more than one does (FAKE-only under
     * `UNIQUE(id)`), [IdResolution.None] for an unknown id or a binding under a detached root. The claimant COUNT
     * selects BOTH ambiguity AND the single winner (Rule A end-state); the winner is the top of the D7 rank order.
     */
    fun resolve(id: PageId): IdResolution = classify(registeredRanked(idMap.rootsHoldingId(id)))

    /** The tombstone twin of [resolve] over the retired-claimant list - the permalink 410 arm consults it after a live None. */
    fun resolveRetired(id: PageId): IdResolution = classify(registeredRanked(idMap.retiredRootsHoldingId(id)))

    /** True iff [root] holds a LIVE durable binding for [id] - the pinned WRITE / pinned-read-miss fresh-validate. */
    fun bindsLive(root: RootName, id: PageId): Boolean = root in idMap.rootsHoldingId(id)

    /**
     * **The pinned-WRITE validation, in the SIGNATURE rather than in four call sites.** A caller-supplied [pin] is
     * never trusted blind: it is [IdResolution.One] only when the registry still knows the root (not DETACHED) AND
     * the root still holds a LIVE binding for [id]; anything else is [IdResolution.None], which each entry maps to
     * its own not-found vocabulary. Fail-CLOSED is the whole point - a pin that stopped binding the id reads as gone
     * from the pinned root rather than walking the write into whichever root now holds it (ADR-0011 D17).
     *
     * This exact triple used to be copy-pasted into `save`, `directSave`, `writeAsset` and `proposeEdit`. It is a
     * security-relevant rule, and a security-relevant rule in four places is a security-relevant rule that drifts.
     *
     * DETACHED is asked of the REGISTRY directly, and that is the whole question here: [statusOf]'s DETACHED arm IS
     * `registry.byName(root) == null`, so an availability snapshot could never change this answer - it only obliged
     * all four call sites to mint a holder read the pin never consulted. UNAVAILABLE is deliberately NOT the pin's
     * business: each entry runs its own availability gate afterwards and answers the 503 there.
     */
    fun resolvePinned(pin: RootName, id: PageId): IdResolution =
        if (registry.byName(pin) != null && bindsLive(pin, id)) IdResolution.One(pin) else IdResolution.None

    /** The last-known rooted path of ([root], [id])'s tombstone, or null when it was never retired there. */
    fun retirementAt(root: RootName, id: PageId): RootedPath? = idMap.retiredAt(root, id)?.path

    /** REGISTERED-filtered, de-duplicated, D7-then-name-ranked - the winner order every candidate list emits. */
    private fun registeredRanked(roots: List<RootName>): List<RootName> =
        roots.filter { registry.byName(it) != null }.distinct().sortedWith(compareBy({ registry.rank(it) }, { it.value }))

    private fun classify(ranked: List<RootName>): IdResolution = when (ranked.size) {
        0 -> IdResolution.None
        1 -> IdResolution.One(ranked.single())
        else -> IdResolution.Ambiguous(ranked)
    }

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
