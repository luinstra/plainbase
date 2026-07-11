package com.plainbase.domain.service

import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.search.SearchProvider
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Engine-truth diff sync (§B4): [sync] reconciles a published [PageIndex] snapshot against
 * [SearchProvider.indexedState] — the engine's OWN record of what it holds — upserting pages the
 * engine lacks or holds stale (`contentHash` covers every in-file change, root+path cover moves,
 * cross-root included) and deleting pages the snapshot no longer has. An unchanged corpus makes
 * ZERO engine calls beyond the state read (the no-op fast path). The diff stays pageId-keyed:
 * ids are global across roots under the D17 winner policy, so one engine row per page still holds.
 *
 * Diffing against engine truth instead of a previous in-memory snapshot is what makes the FIRST
 * sync after startup reconcile everything that changed while down, and a deleted engine database
 * self-healing (empty state ⇒ full upsert). It is also why a sync that fails mid-way needs no
 * cleanup: the next sync re-diffs and repairs for free (§B4 listener exception policy).
 *
 * Registered as an `IndexBuilder.PublicationListener` (Koin wiring, chunk S2), so it runs inside
 * the serialized rebuild against exactly the snapshot that was just published.
 */
class SearchIndexer(
    private val provider: SearchProvider,
    private val splitter: SectionSplitter,
) {

    fun sync(snapshot: PageIndex) {
        val engineState = provider.indexedState()
        val stale = engineState.keys - snapshot.byId.keys
        val changed = snapshot.pages.filter { page ->
            val state = engineState[page.id]
            // The root check keeps the engine's root column live: a cross-root move with an
            // unchanged relative path and hash must still re-upsert.
            state == null || state.contentHash != page.contentHash || state.path != page.path || state.root != page.root
        }
        if (stale.isEmpty() && changed.isEmpty()) {
            logger.debug { "search sync: engine matches the snapshot, nothing to do" }
            return
        }
        if (stale.isNotEmpty()) provider.delete(stale)
        // Cold start (empty engine) makes `changed` the WHOLE corpus, so stream the split documents
        // through the provider in bounded batches rather than materialize every page's at once — the
        // same working-set guard `rebuild` gets from its Sequence param. Each page stays its own
        // transaction inside the provider (§B4 per-page atomicity is untouched). One behavior shift vs
        // the old eager `.map` (which threw before any write): a page that throws mid-run — in `split`
        // or the provider index write — now leaves the earlier chunks already committed (partial, not
        // zero, progress), benign because the next sync's engine-truth diff re-detects and repairs the
        // rest, exactly as the §B4 self-heal already promises.
        if (changed.isNotEmpty()) {
            changed.asSequence().map(splitter::split).chunked(INDEX_BATCH).forEach(provider::index)
        }
        logger.info { "search sync: ${changed.size} page(s) upserted, ${stale.size} deleted" }
    }

    /**
     * The full-corpus counterpart to [sync] (the S8 reindex path): a single generation-swap
     * [SearchProvider.rebuild] of the engine from [snapshot] — NOT a per-page diff. Where [sync]
     * incrementally reconciles against engine truth, [rebuild] discards the engine's current
     * generation and re-derives the whole index from the snapshot, which is what an explicit
     * `reindex` asks for. It is driven only from `IndexBuilder.rebuildSearchIndex()`, which reads
     * the snapshot and calls this under the same monitor a watcher [sync] runs under — so the two
     * can never interleave to regress the engine to a stale generation (§B4 / the S8 atomicity fix).
     */
    fun rebuild(snapshot: PageIndex) {
        provider.rebuild(snapshot.pages.asSequence().map(splitter::split))
        logger.info { "search reindex: rebuilt the engine for ${snapshot.pages.size} page(s)" }
    }

    /**
     * Single-page upsert (the PB-WRITE-1 targeted-reindex path): one
     * [com.plainbase.domain.search.PageDocuments] through the provider's already-per-page-transactional
     * [SearchProvider.index] — genuine O(1), NOT the corpus-wide [indexedState] diff [sync] makes. Used
     * only from `IndexBuilder.reindex`, under the rebuild monitor.
     */
    fun syncPage(page: IndexedPage) {
        provider.index(listOf(splitter.split(page)))
        logger.debug { "search syncPage: upserted ${page.id} (root ${page.root})" }
    }

    companion object {
        /** Caps the split-document working set of a full-corpus [sync]; a cold start upserts every page. */
        internal const val INDEX_BATCH = 256

        private val logger = KotlinLogging.logger {}
    }
}
