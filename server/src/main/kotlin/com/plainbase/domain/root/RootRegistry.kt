package com.plainbase.domain.root

/**
 * The immutable topology snapshot: every configured root, built once at boot. Runtime AVAILABILITY
 * is deliberately NOT part of it - a root going unavailable mid-run is a separate atomic status
 * ([RootAvailability]), never a registry mutation.
 *
 * [roots] preserves the order [of] was given, which config parsing produces as origin-line-with-
 * name-tiebreak order (ADR-0011 D7) - the deterministic contract SOURCE precedence inherits. Since
 * per-root identity (ADR-0012) that is ALL rank decides: it never awards an id to a root.
 * It is NOT raw declaration order in the two documented edge cases (same-line declarations,
 * per-file line numbers under includes), so never label it that.
 */
class RootRegistry private constructor(
    val roots: List<Root>,
    /**
     * The reserved primary root. A construction-time GUARANTEE, not a runtime search: [of] resolves and
     * validates it once, over the same snapshot [roots] holds. NOT necessarily `roots[0]` - D7 order is
     * preserved verbatim and primary sits wherever config declared it, because [rank] (source precedence
     * across roots) reads that order.
     */
    val primary: Root,
) {

    /**
     * Every root except [primary], in D7 order. A partition of [roots], NOT a reordering: [rank] still reads
     * [roots], where primary sits wherever config declared it. Exists so the per-root wiring folds over EXTRAS
     * and primary's entry is constructed explicitly, instead of a fold re-selecting primary by name (the C4
     * HistoryModule bug: primary's arm short-circuited to a single that had drifted mode-blind).
     *
     * Partitions through [isPrimary] so this file compares primary's name in exactly ONE place. Safe from an
     * initializer: [isPrimary] reads only [primary], a constructor property.
     */
    val extras: List<Root> = roots.filterNot(::isPrimary)

    private val rootsByName: Map<RootName, Root> = roots.associateBy { it.name }

    fun byName(name: RootName): Root? = rootsByName[name]

    /**
     * Whether [root] is the primary, ANSWERED BY THE MODEL. Expects a member of [roots]: the check is by NAME,
     * so a `Root` built outside this registry and named like the primary answers true. Comparing names rather
     * than instances is deliberate, since [Root] is a data class and `===` would pin callers to the snapshot's
     * object identity for no gain.
     *
     * Exists for the wire projection (`TreeJsonCache` emits `RootTreeDto.primary`), which must report
     * primary-ness and cannot use the remedy `RootWiringArchitectureTest`'s Tier 1 prescribes: `listOf(primary)
     * + extras` reorders the roots and Tier 3 bans it. So the projection asks the model instead.
     *
     * **This does NOT make the Tier-1 ban self-enforcing, and an earlier draft of this KDoc claimed it did.**
     * The ban survives because those regexes require a QUALIFIED `x.primary.name` receiver, which the
     * unqualified comparison here (and in [extras], which predates it) never had. What DOES constrain this
     * method is that its call sites are ledgered by `RootWiringArchitectureTest`, because a per-root fold
     * branching on it would re-spell the C4 bug in a shape Tier 1 cannot see.
     */
    fun isPrimary(root: Root): Boolean = root.name == primary.name

    /**
     * [name]'s D7 rank in [roots] (-1 when unregistered): the deterministic order SOURCE precedence
     * inherits (ADR-0012). It settles which root's copy a pass reads first, NEVER which root owns an
     * id - the same id under two roots is two pages. ONE definition, consumed by `IndexBuilder`,
     * `AdoptionPass` and `PageRootResolver`'s candidate order (NOT by `PageIdentityService`, which
     * takes no rank) - two inlined copies could drift and split the ordering contract.
     */
    fun rank(name: RootName): Int = roots.indexOfFirst { it.name == name }

    companion object {

        /**
         * Builds a registry over a defensive copy of [roots], validated once: [primary] is resolved HERE, so
         * every accessor downstream reads one canonical snapshot. The distinct-names check is defense for
         * programmatic construction only - HOCON merges duplicate keys field-wise at parse, so the
         * config path can never produce duplicates.
         */
        fun of(roots: List<Root>): RootRegistry {
            val snapshot = roots.toList()
            val duplicates = snapshot.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate root name(s): ${duplicates.joinToString(", ") { it.value }}" }
            val primary = requireNotNull(snapshot.firstOrNull { it.name == RootName.PRIMARY }) {
                "a root named '${RootName.PRIMARY}' is required (the reserved primary)"
            }
            return RootRegistry(snapshot, primary)
        }
    }
}
