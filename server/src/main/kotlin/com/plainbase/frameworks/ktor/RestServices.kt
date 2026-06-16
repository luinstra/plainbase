@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.TreeBuilder
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.ktor.dto.ReindexResponse
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.TreeResponse
import com.plainbase.frameworks.ktor.dto.toDto
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The one dependency bundle the routing layer needs — built by Koin's `restModule` in production
 * and assembled directly by the route-test harnesses. Frameworks-side glue only; everything it
 * carries is domain.
 */
class RestServices(
    val indexBuilder: IndexBuilder,
    val pageService: PageService,
    val searchService: SearchService,
    val aliasRegistry: UrlAliasRegistry,
    val contentStore: ContentStore,
    val writePipeline: WritePipeline,
) {

    /** The per-snapshot memoized `/api/v1/tree` JSON (§C4). */
    val treeJson: TreeJsonCache = TreeJsonCache(indexBuilder)

    /**
     * §A5/R9 reindex single-flight: the route flips this with `compareAndSet(false, true)`, so a
     * concurrent `POST /api/v1/admin/reindex` returns 409 `reindex_in_flight`. It only rejects
     * concurrent *requests* — engine-write ordering is the IndexBuilder monitor's job, not this
     * flag's. Never `@Volatile`, never `java.util.concurrent.atomic` (kotlin.concurrent.atomics
     * house style; commit 9c78ca0).
     */
    val reindexInFlight: AtomicBoolean = AtomicBoolean(false)

    /**
     * Forces a full generation-swap rebuild of the search engine over the CURRENT published
     * snapshot (the §A5 reindex). It delegates to the atomic [IndexBuilder.rebuildSearchIndex],
     * which reads the snapshot AND rebuilds the engine under the rebuild monitor — NOT a separate
     * read-then-rebuild (the debate-caught stale-snapshot regression). Blocking JDBC: the route
     * hops to `Dispatchers.IO` before calling this.
     */
    fun reindex(): ReindexResponse = ReindexResponse(status = "ok", pages = indexBuilder.rebuildSearchIndex())
}

/**
 * Memoizes the tree JSON per published [PageIndex] snapshot (§C4: compute once per snapshot,
 * invalidate on swap). Snapshot identity (`===`) is the cache key — a rescan publishes a new
 * instance, so the next request recomputes; the established immutable-entry-in-AtomicReference
 * pattern, no locks. A concurrent miss may compute twice; both results are identical and either
 * publication is correct.
 */
class TreeJsonCache(private val indexBuilder: IndexBuilder) {

    private class Entry(val snapshot: PageIndex, val json: String)

    private val memo = AtomicReference<Entry?>(null)

    /** The tree JSON for the currently published snapshot. */
    fun current(): String {
        val snapshot = indexBuilder.current
        memo.load()?.takeIf { it.snapshot === snapshot }?.let { return it.json }
        val json = RestJson.encodeToString(TreeResponse.serializer(), TreeResponse(TreeBuilder.build(snapshot).toDto()))
        memo.store(Entry(snapshot, json))
        return json
    }
}
