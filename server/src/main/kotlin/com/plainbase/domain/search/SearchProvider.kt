package com.plainbase.domain.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootedPageId

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
    fun delete(ids: Collection<RootedPageId>)

    /** Runs [query] against the engine; hits and total come from one engine snapshot (§B5). */
    fun search(query: SearchQuery): SearchResults

    /**
     * Full-corpus replacement under a generation/atomic swap; safe under concurrent [search].
     *
     * [retired] is the absence-authority rule at the engine boundary (C0): the ONLY pages a swap may drop are the
     * exact `(root, id)` rows an `AbsenceProof` just retired. Every other row rides into the new generation
     * UNCHANGED - superseded by a freshly inserted row where the snapshot has one, carried verbatim where it does not.
     *
     * It is not a nicety. A root unavailable since boot has no section in the snapshot; so does a root whose mount
     * failed under it; so does a page on a failed submount of a perfectly healthy root; so does every page of a
     * decoy tree's 997 missing siblings. An unrestricted swap reads all of that as a full-corpus delete and
     * destroys the index behind an unplugged disk - a mass delete an admin `reindex` performed on nobody's
     * instruction. Absence from the snapshot proves nothing; only the proof does. In C0 [retired] is always EMPTY,
     * so a swap deletes NOTHING.
     *
     * `null` is UNRESTRICTED (this corpus IS the engine): the shape the engine's own contract tests and the
     * single-root spike want, and the one no absence-authority caller may use.
     */
    fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>? = null)

    /**
     * The engine's OWN record of what it has indexed — the diff base for `SearchIndexer.sync`
     * (§B4 engine-truth diffing). Root rides the [RootedPageId] key; the value carries the
     * change-detection payload. Diffing against this instead of a separate checkpoint is what
     * makes the first sync after startup reconcile downtime drift and a deleted engine database
     * self-heal (empty state ⇒ full upsert).
     */
    fun indexedState(): Map<RootedPageId, PageSearchState>
}

/**
 * What the engine knows about one indexed page: enough to detect change. [contentHash] covers every
 * in-file change and [path] covers a within-root move; the key's root distinguishes roots.
 */
data class PageSearchState(
    val contentHash: String,
    val path: TreePath,
)
