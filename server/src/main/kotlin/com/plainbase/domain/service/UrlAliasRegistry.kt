@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.UrlAlias
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.domain.root.RootedPath
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The in-memory view over the persisted url_alias registry (§A4): old canonical URL path → page id,
 * keyed per root since multi-root C2. Lookups ([find]) are memory-only; writes go through to the
 * [UrlAliasRepository] and the view follows (write-through), so the chunk-6 alias route never pays
 * a query per request.
 *
 * **One hop, by construction:** an alias maps a rooted path to a [PageId], never to another alias —
 * after any number of moves every recorded old path resolves in one hop (§A4 chain collapse).
 * Re-registering a path re-points it (one row per rooted path).
 *
 * The view is an immutable map swapped through an [AtomicReference] (copy-on-write): a reader
 * always sees a complete map, with no locks and no `@Volatile`. Loading happens once at
 * construction — the registry is app-state in DATA_DIR, NOT rebuildable from the tree alone (§A4
 * documented exception); rebuilds only append through [register]/[dropShadowed].
 */
class UrlAliasRegistry(private val repository: UrlAliasRepository) {

    private val aliases = AtomicReference(repository.aliases().associate { it.path to it.id })

    /** The page aliased at [path], or null when no alias claims it. */
    fun find(path: RootedPath): PageId? = aliases.load()[path]

    /** Every registered alias, as the current immutable view. */
    fun all(): Map<RootedPath, PageId> = aliases.load()

    /** Registers [path] as an alias of the page [id], replacing any alias previously at that rooted path. */
    fun register(path: RootedPath, id: PageId) {
        repository.register(path, id)
        aliases.store(aliases.load() + (path to id))
    }

    /**
     * Drops the alias shadowed by the live canonical [canonicalPath] (§A4: a live canonical path
     * always wins, within its root) and returns it for issue recording, or null when nothing was
     * shadowed. The memory probe makes the per-canonical sweep free; the repository is touched only
     * on a hit.
     */
    fun dropShadowed(canonicalPath: RootedPath): UrlAlias? {
        if (canonicalPath !in aliases.load()) return null
        val dropped = repository.dropShadowed(canonicalPath) ?: return null
        aliases.store(aliases.load() - canonicalPath)
        return dropped
    }
}
