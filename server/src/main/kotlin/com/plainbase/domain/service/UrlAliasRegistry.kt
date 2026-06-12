package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.UrlAlias
import com.plainbase.domain.repository.UrlAliasRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * The in-memory view over the persisted url_alias registry (§A4): old canonical URL path → page id.
 * Lookups ([find]) are memory-only; writes go through to the [UrlAliasRepository] and the view
 * follows (write-through), so the chunk-6 alias route never pays a query per request.
 *
 * **One hop, by construction:** an alias maps a path to a [PageId], never to another alias —
 * after any number of moves every recorded old path resolves in one hop (§A4 chain collapse).
 * Re-registering a path re-points it (one row per path).
 *
 * The view is an immutable map swapped through an [AtomicReference] (copy-on-write): a reader
 * always sees a complete map, with no locks and no `@Volatile`. Loading happens once at
 * construction — the registry is app-state in DATA_DIR, NOT rebuildable from the tree alone (§A4
 * documented exception); rebuilds only append through [register]/[dropShadowed].
 */
class UrlAliasRegistry(private val repository: UrlAliasRepository) {

    private val aliases = AtomicReference(repository.aliases().associate { it.path to it.id })

    /** The page aliased at [path], or null when no alias claims it. */
    fun find(path: TreePath): PageId? = aliases.get()[path]

    /** Every registered alias, as the current immutable view. */
    fun all(): Map<TreePath, PageId> = aliases.get()

    /** Registers [path] as an alias of the page [id], replacing any alias previously at that path. */
    fun register(path: TreePath, id: PageId) {
        repository.register(path, id)
        aliases.updateAndGet { it + (path to id) }
    }

    /**
     * Drops the alias shadowed by the live canonical [canonicalPath] (§A4: a live canonical path
     * always wins) and returns it for issue recording, or null when nothing was shadowed. The
     * memory probe makes the per-canonical sweep free; the repository is touched only on a hit.
     */
    fun dropShadowed(canonicalPath: TreePath): UrlAlias? {
        if (canonicalPath !in aliases.get()) return null
        val dropped = repository.dropShadowed(canonicalPath) ?: return null
        aliases.updateAndGet { it - canonicalPath }
        return dropped
    }
}
