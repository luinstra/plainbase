@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.TreeBuilder
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.RootTreeDto
import com.plainbase.frameworks.ktor.dto.TreeResponse
import com.plainbase.frameworks.ktor.dto.toDto
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Memoizes the tree JSON per published snapshot (§C4: compute once per snapshot, invalidate on swap).
 *
 * The memo is keyed on BOTH identities - the [PageIndex] and the [RootAvailability.Snapshot] - and that second key
 * is load-bearing, not defensive: a watcher-failure flip marks a root unavailable WITHOUT publishing a new page
 * snapshot, so a single-key memo would keep serving a stale `available: true` for a root the server has already
 * given up on. Both are immutable objects published through an `AtomicReference`, so identity (`===`) is the key -
 * the established pattern, no locks. A concurrent miss may compute twice; both results are identical and either
 * publication is correct.
 *
 * Entries enumerate the REGISTRY (ADR-0011 D7 order), not the snapshot's sections: a root that is configured but not
 * serving must still APPEAR, flagged `available: false` with an EMPTY tree - saying "this root is here and it is not
 * serving" is the honest answer, where omitting it would look like it never existed and serving its carried listing
 * would be a stale serve. It also closes the client's divergence window: the SPA's known-root set now matches the
 * server's exactly, so it can never mistake a root segment for a page path.
 */
class TreeJsonCache(
    private val indexBuilder: IndexBuilder,
    private val registry: RootRegistry,
    private val availability: RootAvailability,
) {

    private class Entry(val snapshot: PageIndex, val availability: RootAvailability.Snapshot, val json: String)

    private val memo = AtomicReference<Entry?>(null)

    /**
     * The tree JSON for the currently published snapshot. The pre-first-build EMPTY snapshot yields every root with
     * an empty tree - unreachable in production (serve() rebuilds before listening) and handled by the SPA's
     * pending/empty states.
     */
    fun current(): String {
        val snapshot = indexBuilder.current
        val available = availability.current()
        memo.load()?.takeIf { it.snapshot === snapshot && it.availability === available }?.let { return it.json }
        val json = RestJson.encodeToString(
            TreeResponse.serializer(),
            TreeResponse(
                registry.roots.map { root ->
                    val serving = available.isAvailable(root.name)
                    // An unavailable root emits the bare synthetic root folder - never its carried-forward listing,
                    // which would be exactly the stale serve the availability rule exists to prevent.
                    val tree = TreeBuilder.build(if (serving) snapshot else PageIndex.EMPTY, root.name)
                    RootTreeDto(root = root.name.value, available = serving, tree = tree.toDto())
                },
            ),
        )
        memo.store(Entry(snapshot, available, json))
        return json
    }
}
