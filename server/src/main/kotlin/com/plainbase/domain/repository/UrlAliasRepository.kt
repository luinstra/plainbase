package com.plainbase.domain.repository

import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath

/**
 * Persistence port for the URL alias registry (§A4, chunk 4b; (root, path)-keyed since multi-root C2).
 *
 * An alias maps an old canonical URL path - the NFC-slugified, ROOT-relative path (the tail after
 * `/docs/{root}/` since C3), carried as a [RootedPath] whose segments are URL slugs rather than
 * filenames - to the [RootedPageId] that used to live there. URL space is per root, so the same old path may alias different pages under different
 * roots, and the target may live in a DIFFERENT root than the URL path does (its [RootedPageId] carries the
 * target's own root). Alias URLs 301 to the page's current canonical URL (chunk 6).
 *
 * **Chains collapse on write, by construction:** [register] accepts only a [RootedPageId], so an alias
 * can never point at another alias — after any number of moves, every recorded old path resolves to
 * the page in one hop.
 *
 * **Population is chunk 5's job:** rows are written by the `IndexBuilder` (move/rename/slug-change
 * detection) and `redirect_from` registration; this chunk delivers the persistence machinery only.
 *
 * Documented derived-state exception (§A4): the registry is app-state in DATA_DIR and NOT
 * rebuildable from the tree alone — losing DATA_DIR loses old-URL continuity; canonical URLs and
 * `/p/{root}/{id}` permalinks are unaffected.
 */
interface UrlAliasRepository {

    /** Registers [path] as an alias of the page [target], replacing any alias previously at that rooted path. */
    fun register(path: RootedPath, target: RootedPageId)

    /** The rooted page aliased at [path], or null when no alias claims it. */
    fun find(path: RootedPath): RootedPageId?

    /** Every registered alias, for the chunk-5 in-memory registry load and tests. */
    fun aliases(): List<UrlAlias>

    /**
     * Drops the alias shadowed by a live canonical page at [canonicalPath] (§A4: a live canonical
     * path always wins over an alias claiming the same path, within its root) and returns it, or
     * null when no alias was shadowed. The caller records the corresponding `redirect_conflict`
     * issue — that pairing is pinned by the repository tests and exercised by chunk 5's IndexBuilder.
     */
    fun dropShadowed(canonicalPath: RootedPath): UrlAlias?
}

/** One url_alias row: the old canonical URL [path] belongs to the page [target] (its own root and id). */
data class UrlAlias(
    val path: RootedPath,
    val target: RootedPageId,
)
