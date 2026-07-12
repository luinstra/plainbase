package com.plainbase.domain.root

/**
 * The immutable topology snapshot: every configured root, built once at boot. Runtime AVAILABILITY
 * is deliberately NOT part of it - a root going unavailable mid-run is a separate atomic status
 * ([RootAvailability]), never a registry mutation.
 *
 * [roots] preserves the order [of] was given, which config parsing produces as origin-line-with-
 * name-tiebreak order (ADR-0011 D7) - the deterministic contract C2's cross-root duplicate-id winner
 * inherits. It is NOT raw declaration order in the two documented edge cases (same-line declarations,
 * per-file line numbers under includes), so never label it that.
 */
class RootRegistry private constructor(
    val roots: List<Root>,
    /**
     * The reserved primary root. A construction-time GUARANTEE, not a runtime search: [of] resolves and
     * validates it once, over the same snapshot [roots] holds. NOT necessarily `roots[0]` - D7 order is
     * preserved verbatim and main sits wherever config declared it, because [rank] (the cross-root
     * duplicate-id winner) reads that order.
     */
    val main: Root,
) {

    /**
     * Every root except [main], in D7 order. A partition of [roots], NOT a reordering: [rank] still reads
     * [roots], where main sits wherever config declared it. Exists so the per-root wiring folds over EXTRAS
     * and main's entry is constructed explicitly, instead of a fold re-selecting main by name (the C4
     * HistoryModule bug: main's arm short-circuited to a single that had drifted mode-blind).
     */
    val extras: List<Root> = roots.filter { it.name != main.name }

    private val rootsByName: Map<RootName, Root> = roots.associateBy { it.name }

    fun byName(name: RootName): Root? = rootsByName[name]

    /**
     * [name]'s D7 rank in [roots] (-1 when unregistered): the deterministic order the C2 cross-root
     * duplicate-id winner inherits (ADR-0011 D17). ONE definition, consumed by both the identity
     * service and the index builder - two inlined copies could drift and split the winner contract.
     */
    fun rank(name: RootName): Int = roots.indexOfFirst { it.name == name }

    companion object {

        /**
         * Builds a registry over a defensive copy of [roots], validated once: [main] is resolved HERE, so
         * every accessor downstream reads one canonical snapshot. The distinct-names check is defense for
         * programmatic construction only - HOCON merges duplicate keys field-wise at parse, so the
         * config path can never produce duplicates.
         */
        fun of(roots: List<Root>): RootRegistry {
            val snapshot = roots.toList()
            val duplicates = snapshot.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate root name(s): ${duplicates.joinToString(", ") { it.value }}" }
            val main = requireNotNull(snapshot.firstOrNull { it.name == RootName.MAIN }) {
                "a root named '${RootName.MAIN}' is required (the reserved primary)"
            }
            return RootRegistry(snapshot, main)
        }
    }
}
