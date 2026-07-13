package com.plainbase.domain.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

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

    /**
     * Full-corpus replacement under a generation/atomic swap; safe under concurrent [search].
     *
     * [deleteAuthority] is the D5 rule at the engine boundary (ADR-0011): a row whose root lies OUTSIDE the set
     * rides the swap into the new generation UNCHANGED, because a root this pass never walked is a root it has no
     * authority to purge. It is not a nicety - a root unavailable since boot has no section in the snapshot, so it
     * contributes no [PageDocuments] here, and an unrestricted swap would read that absence as a full-corpus
     * delete and destroy its whole index behind an unplugged disk. `null` is UNRESTRICTED (this corpus IS the
     * engine): the shape the engine's own contract tests and the single-root spike want, and the one no D5 caller
     * may use.
     */
    fun rebuild(pages: Sequence<PageDocuments>, deleteAuthority: Set<RootName>? = null)

    /**
     * The engine's OWN record of what it has indexed — the diff base for `SearchIndexer.sync`
     * (§B4 engine-truth diffing). Diffing against this instead of a separate checkpoint is what
     * makes the first sync after startup reconcile downtime drift and a deleted engine database
     * self-heal (empty state ⇒ full upsert).
     */
    fun indexedState(): Map<PageId, PageSearchState>
}

/** What the engine knows about one indexed page: enough to detect change ([contentHash]) and root+path cover moves. */
data class PageSearchState(
    val contentHash: String,
    val root: RootName,
    val path: TreePath,
)
