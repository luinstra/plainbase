package com.plainbase.domain.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * The search engine port (§5.6/§B4 — master vocabulary: index, delete, search, rebuild, plus the
 * one reconciliation addition [indexedState]). Phase 2 ships one implementation
 * (`Fts5SearchProvider`); everything above the port — section splitting, document shaping, the
 * engine-truth diff sync — is engine-agnostic domain code and stays put when an engine changes.
 *
 * Atomicity tiers (frozen behavior, engine-agnostic — §B4): [index] replaces per page atomically;
 * [rebuild] is generation/atomic-swapped — concurrent searches never error and always see one
 * complete corpus, old or new.
 */
interface SearchProvider {

    /** Replaces each page's document set (atomic per page — old or new, never half). */
    fun index(pages: List<PageDocuments>)

    /** Removes every document of each page in [ids]. */
    fun delete(ids: Collection<PageId>)

    /** Runs [query] against the engine; hits and total come from one engine snapshot (§B5). */
    fun search(query: SearchQuery): SearchResults

    /** Full-corpus replacement under a generation/atomic swap; safe under concurrent [search]. */
    fun rebuild(pages: Sequence<PageDocuments>)

    /**
     * The engine's OWN record of what it has indexed — the diff base for `SearchIndexer.sync`
     * (§B4 engine-truth diffing). Diffing against this instead of a separate checkpoint is what
     * makes the first sync after startup reconcile downtime drift and a deleted engine database
     * self-heal (empty state ⇒ full upsert).
     */
    fun indexedState(): Map<PageId, PageSearchState>
}

/** What the engine knows about one indexed page: enough to detect change ([contentHash]) and moves ([path]). */
data class PageSearchState(
    val contentHash: String,
    val path: TreePath,
)
