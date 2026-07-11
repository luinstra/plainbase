@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.TreeBuilder
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.RootTreeDto
import com.plainbase.frameworks.ktor.dto.TreeResponse
import com.plainbase.frameworks.ktor.dto.toDto
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Memoizes the tree JSON per published [PageIndex] snapshot (§C4: compute once per snapshot,
 * invalidate on swap). Snapshot identity (`===`) is the cache key — a rescan publishes a new
 * instance, so the next request recomputes; the established immutable-entry-in-AtomicReference
 * pattern, no locks. A concurrent miss may compute twice; both results are identical and either
 * publication is correct.
 *
 * A3: the flat `RestServices` raw-mutator bundle the choke-point synthesis abolished is GONE — routes receive
 * only the guarded [RouteContext]. This per-snapshot framework memo now lives inside [GuardedReadFacade]
 * (`tree()`), the only remaining consumer.
 */
class TreeJsonCache(
    private val indexBuilder: IndexBuilder,
) {

    private class Entry(val snapshot: PageIndex, val json: String)

    private val memo = AtomicReference<Entry?>(null)

    /**
     * The tree JSON for the currently published snapshot: one entry per snapshot section, in the
     * builder's registry (D7) order (C3; C4 moves entry enumeration to the registry and adds
     * `available`). The pre-first-build EMPTY snapshot yields `{"roots":[]}` - unreachable in
     * production (serve() rebuilds before listening) and handled by the SPA's pending/empty states.
     */
    fun current(): String {
        val snapshot = indexBuilder.current
        memo.load()?.takeIf { it.snapshot === snapshot }?.let { return it.json }
        val json = RestJson.encodeToString(
            TreeResponse.serializer(),
            TreeResponse(snapshot.sections.map { RootTreeDto(it.root.value, TreeBuilder.build(snapshot, it.root).toDto()) }),
        )
        memo.store(Entry(snapshot, json))
        return json
    }
}
